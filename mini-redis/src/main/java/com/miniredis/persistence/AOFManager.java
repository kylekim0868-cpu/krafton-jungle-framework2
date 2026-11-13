package com.miniredis.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniredis.core.ConcurrentHashMap;
import com.miniredis.core.HashTable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * AOF(Append Only File) 관리자.
 *
 * <p><b>파일 포맷</b> (한 줄 = 한 커맨드, 공백 구분)</p>
 * <pre>
 *   SET &lt;key&gt; &lt;value&gt; &lt;ttlSeconds&gt; &lt;timestamp&gt;
 *   DEL &lt;key&gt; &lt;timestamp&gt;
 *   FLUSH &lt;timestamp&gt;
 * </pre>
 * <ul>
 *   <li>key / value 에 공백·개행이 들어가도 한 줄 포맷이 깨지지 않도록 URL 인코딩(UTF-8)한다.</li>
 *   <li>value 는 문자열뿐 아니라 숫자/객체도 저장할 수 있도록 먼저 JSON 으로 직렬화한 뒤 인코딩한다.</li>
 *   <li>timestamp 는 커맨드가 실행된 시각(epoch ms). 복구 시 만료 시각 = timestamp + ttlSeconds*1000.</li>
 * </ul>
 */
public class AOFManager {

    private static final Logger log = LoggerFactory.getLogger(AOFManager.class);

    public static final String AOF_FILE_NAME = "appendonly.aof";

    private final ConcurrentHashMap store;
    private final Path dataDir;
    private final Path aofFile;
    private final ObjectMapper objectMapper;

    private BufferedWriter writer;
    private long commandsWritten = 0;

    public AOFManager(ConcurrentHashMap store, Path dataDir, ObjectMapper objectMapper) {
        this.store = store;
        this.dataDir = dataDir;
        this.aofFile = dataDir.resolve(AOF_FILE_NAME);
        this.objectMapper = objectMapper;
    }

    /**
     * 서버 시작 시 호출.
     * <ol>
     *   <li>AOF 파일에 내용이 있으면 → 한 줄씩 Replay 하여 메모리 상태 복원.</li>
     *   <li>AOF 가 없거나 비어 있는데 메모리에 이미 데이터가 있으면(= RDB 에서 복구된 경우)
     *       → 현재 상태를 AOF 로 재작성(rewrite). 이렇게 하지 않으면 다음 재시작 때 AOF 에는
     *       RDB 이후의 변경분만 남아 RDB 데이터가 유실된다.</li>
     *   <li>기록용 BufferedWriter 를 append 모드로 연다.</li>
     * </ol>
     */
    @PostConstruct
    public synchronized void init() {
        try {
            Files.createDirectories(dataDir);
            if (hasData()) {
                int applied = replay();
                log.info("[AOF] Replayed {} commands from {}", applied, aofFile.toAbsolutePath());
            } else if (store.rawSize() > 0) {
                rewriteInternal();
                log.info("[AOF] Rewrote AOF from current in-memory state ({} keys)", store.size());
            }
            openWriter();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize AOF", e);
        }
    }

    /** AOF 파일이 존재하고 비어 있지 않은지 */
    public boolean hasData() {
        try {
            return Files.exists(aofFile) && Files.size(aofFile) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void openWriter() throws IOException {
        // CREATE + APPEND: 파일이 없으면 만들고, 있으면 끝에 이어서 쓴다.
        writer = Files.newBufferedWriter(aofFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    // =====================================================================
    // 기록 (Append)
    // =====================================================================

    /** SET 커맨드 기록. put() 성공 직후 호출한다. */
    public void appendSet(String key, Object value, int ttlSeconds) {
        appendSet(key, value, ttlSeconds, System.currentTimeMillis());
    }

    void appendSet(String key, Object value, int ttlSeconds, long timestamp) {
        String line = "SET " + encode(key) + " " + encode(toJson(value)) + " "
                + Math.max(ttlSeconds, 0) + " " + timestamp;
        appendLine(line);
    }

    /** DEL 커맨드 기록. remove() 성공 직후 호출한다. */
    public void appendDel(String key) {
        appendLine("DEL " + encode(key) + " " + System.currentTimeMillis());
    }

    /** FLUSH 커맨드 기록. clear() 직후 호출한다. (Replay 시 이전 데이터를 모두 지우기 위해 필요) */
    public void appendFlush() {
        appendLine("FLUSH " + System.currentTimeMillis());
    }

    /**
     * 한 줄을 추가하고 즉시 flush() 하여 OS 로 내려보낸다.
     * synchronized: 여러 요청 스레드가 동시에 써도 줄이 서로 섞이지 않도록 직렬화한다.
     */
    private synchronized void appendLine(String line) {
        try {
            if (writer == null) {
                openWriter();
            }
            writer.write(line);
            writer.newLine();
            writer.flush(); // 버퍼에 쌓아두지 않고 매 커맨드마다 즉시 디스크로 반영
            commandsWritten++;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append AOF: " + line, e);
        }
    }

    // =====================================================================
    // 복구 (Replay)
    // =====================================================================

    /**
     * AOF 파일을 처음부터 한 줄씩 읽어 커맨드를 store 에 재실행한다.
     * Replay 는 store 에 직접 적용하므로 AOF 에 다시 기록되지 않는다.
     *
     * @return 적용된 커맨드 수 (만료되어 건너뛴 SET, 파싱 실패 줄 제외)
     */
    public synchronized int replay() {
        if (!Files.exists(aofFile)) {
            return 0;
        }
        int applied = 0;
        int lineNo = 0;
        long now = System.currentTimeMillis();

        try (BufferedReader reader = Files.newBufferedReader(aofFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    if (applyLine(line, now)) {
                        applied++;
                    }
                } catch (Exception e) {
                    // 비정상 종료로 마지막 줄이 잘린 경우 등: 해당 줄만 건너뛰고 계속 진행
                    log.warn("[AOF] Skipping malformed line {}: {} ({})", lineNo, line, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to replay AOF", e);
        }
        return applied;
    }

    /** 한 줄 커맨드를 해석해 적용. 실제 적용되었으면 true. */
    private boolean applyLine(String line, long now) throws IOException {
        String[] parts = line.trim().split(" ");
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "SET" -> {
                if (parts.length != 5) {
                    throw new IllegalArgumentException("SET requires 4 arguments");
                }
                String key = decode(parts[1]);
                Object value = objectMapper.readValue(decode(parts[2]), Object.class);
                int ttl = Integer.parseInt(parts[3]);
                long timestamp = Long.parseLong(parts[4]);

                if (ttl <= 0) {
                    store.putWithExpireAt(key, value, HashTable.NO_EXPIRY);
                    return true;
                }
                // 원래 만료 시각 = 기록 시각 + TTL. 상대 TTL 을 다시 적용하면 재시작할 때마다 수명이 늘어나므로 절대 시각으로 복원.
                long expireAt = timestamp + ttl * 1000L;
                if (expireAt <= now) {
                    // 이미 만료된 SET 은 건너뛴다.
                    // 단, 앞선 줄에서 같은 키가 (만료 없이) 저장되었을 수 있으므로 그 값은 제거해야 최종 상태가 정확하다.
                    store.remove(key);
                    return false;
                }
                store.putWithExpireAt(key, value, expireAt);
                return true;
            }
            case "DEL" -> {
                if (parts.length != 3) {
                    throw new IllegalArgumentException("DEL requires 2 arguments");
                }
                store.remove(decode(parts[1]));
                return true;
            }
            case "FLUSH" -> {
                store.clear();
                return true;
            }
            default -> throw new IllegalArgumentException("Unknown command: " + cmd);
        }
    }

    // =====================================================================
    // AOF Rewrite (현재 상태로 파일 재작성)
    // =====================================================================

    /** 현재 메모리 상태를 최소한의 SET 커맨드로 다시 써서 AOF 를 압축한다. */
    public synchronized void rewrite() {
        try {
            rewriteInternal();
            openWriter();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rewrite AOF", e);
        }
    }

    private void rewriteInternal() throws IOException {
        closeWriterQuietly();
        Path tmp = dataDir.resolve(AOF_FILE_NAME + ".tmp");
        List<HashTable.EntryView> entries = store.snapshot();
        long now = System.currentTimeMillis();

        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (HashTable.EntryView e : entries) {
                int ttl = 0;
                long timestamp = now;
                if (e.expireAt() != HashTable.NO_EXPIRY) {
                    // 남은 시간을 초 단위로 올림하고, timestamp 를 역산하여 timestamp + ttl*1000 == expireAt 이 정확히 성립하게 한다.
                    ttl = (int) Math.max(1, (e.expireAt() - now + 999) / 1000);
                    timestamp = e.expireAt() - ttl * 1000L;
                }
                w.write("SET " + encode(e.key()) + " " + encode(toJson(e.value())) + " " + ttl + " " + timestamp);
                w.newLine();
            }
        }
        moveAtomically(tmp, aofFile);
        commandsWritten = entries.size();
    }

    // =====================================================================
    // 종료 / 유틸
    // =====================================================================

    @PreDestroy
    public synchronized void close() {
        closeWriterQuietly();
        log.info("[AOF] Closed");
    }

    private void closeWriterQuietly() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // 종료 중 오류는 무시
            }
            writer = null;
        }
    }

    public Path getAofFile() {
        return aofFile;
    }

    public long getFileSize() {
        try {
            return Files.exists(aofFile) ? Files.size(aofFile) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    public synchronized long getCommandsWritten() {
        return commandsWritten;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Value is not JSON serializable", e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
