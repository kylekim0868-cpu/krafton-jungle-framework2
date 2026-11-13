package com.miniredis.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.miniredis.core.ConcurrentHashMap;
import com.miniredis.core.HashTable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RDB 스냅샷 관리자.
 *
 * <p>주기적으로(기본 30초) 또는 수동 호출 시 현재 유효한 모든 엔트리를
 * {@code data/dump.rdb.json} 에 JSON 으로 통째로 저장한다.</p>
 *
 * <pre>
 * {
 *   "version": 1,
 *   "savedAt": 1731480000000,
 *   "count": 2,
 *   "entries": [
 *     {"key":"k1","value":"v1","expireAt":-1},
 *     {"key":"k2","value":{"a":1},"expireAt":1731480060000}
 *   ]
 * }
 * </pre>
 *
 * <p>저장은 임시 파일에 먼저 쓰고 원자적으로 교체(rename)하므로, 저장 도중 서버가 죽어도
 * 기존 스냅샷이 반쯤 덮어써진 상태로 깨지지 않는다.</p>
 */
public class RDBManager {

    private static final Logger log = LoggerFactory.getLogger(RDBManager.class);

    public static final String RDB_FILE_NAME = "dump.rdb.json";

    private final ConcurrentHashMap store;
    private final Path dataDir;
    private final Path rdbFile;
    private final Path aofFile;
    private final ObjectMapper objectMapper;
    private final long intervalSeconds;

    private ScheduledExecutorService scheduler;
    private volatile long lastSaveAt = 0L;
    private volatile int lastSaveCount = 0;

    public RDBManager(ConcurrentHashMap store, Path dataDir, ObjectMapper objectMapper) {
        this(store, dataDir, objectMapper, 30L);
    }

    public RDBManager(ConcurrentHashMap store, Path dataDir, ObjectMapper objectMapper, long intervalSeconds) {
        this.store = store;
        this.dataDir = dataDir;
        this.rdbFile = dataDir.resolve(RDB_FILE_NAME);
        this.aofFile = dataDir.resolve(AOFManager.AOF_FILE_NAME);
        // Spring 의 ObjectMapper 설정을 건드리지 않도록 복사본에 pretty print 적용
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * 서버 시작 시:
     * <ol>
     *   <li>AOF 파일이 없거나 비어 있고 RDB 파일이 존재하면 → RDB 스냅샷에서 복구.
     *       (AOF 가 있으면 AOF 가 더 최신의 전체 이력이므로 AOFManager 가 복구한다.)</li>
     *   <li>주기적 스냅샷 스케줄러 시작.</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create data dir", e);
        }

        boolean aofHasData;
        try {
            aofHasData = Files.exists(aofFile) && Files.size(aofFile) > 0;
        } catch (IOException e) {
            aofHasData = false;
        }
        if (!aofHasData && Files.exists(rdbFile)) {
            int restored = restore();
            log.info("[RDB] Restored {} keys from {}", restored, rdbFile.toAbsolutePath());
        }
        startScheduler();
    }

    public synchronized void startScheduler() {
        if (intervalSeconds <= 0 || (scheduler != null && !scheduler.isShutdown())) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rdb-snapshot");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                save();
            } catch (Throwable t) {
                log.error("[RDB] Periodic snapshot failed", t);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("[RDB] Snapshot scheduler started (interval={}s)", intervalSeconds);
    }

    /**
     * 현재 상태를 스냅샷으로 저장한다.
     *
     * @return 저장된 엔트리 수
     */
    public synchronized int save() {
        List<HashTable.EntryView> entries = store.snapshot(); // 세그먼트별 Read Lock 으로 수집

        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (HashTable.EntryView e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", e.key());
            row.put("value", e.value());
            row.put("expireAt", e.expireAt());
            rows.add(row);
        }
        long now = System.currentTimeMillis();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("savedAt", now);
        root.put("count", rows.size());
        root.put("entries", rows);

        try {
            Files.createDirectories(dataDir);
            Path tmp = dataDir.resolve(RDB_FILE_NAME + ".tmp");
            objectMapper.writeValue(tmp.toFile(), root);
            AOFManager.moveAtomically(tmp, rdbFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save RDB snapshot", e);
        }
        lastSaveAt = now;
        lastSaveCount = rows.size();
        log.debug("[RDB] Saved {} keys", rows.size());
        return rows.size();
    }

    /**
     * RDB 파일에서 복구한다. 이미 만료된 엔트리는 건너뛴다.
     *
     * @return 복구된 엔트리 수
     */
    public synchronized int restore() {
        if (!Files.exists(rdbFile)) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(rdbFile.toFile());
            JsonNode entries = root.path("entries");
            long now = System.currentTimeMillis();
            int restored = 0;
            for (JsonNode node : entries) {
                String key = node.path("key").asText(null);
                if (key == null) {
                    continue;
                }
                long expireAt = node.path("expireAt").asLong(HashTable.NO_EXPIRY);
                if (expireAt != HashTable.NO_EXPIRY && expireAt <= now) {
                    continue; // 스냅샷 이후 이미 만료됨
                }
                Object value = objectMapper.treeToValue(node.get("value"), Object.class);
                store.putWithExpireAt(key, value, expireAt);
                restored++;
            }
            return restored;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to restore RDB snapshot", e);
        }
    }

    /** 종료 시 스케줄러를 멈추고 마지막 스냅샷을 저장한다 (Redis 의 shutdown save 와 유사). */
    @PreDestroy
    public synchronized void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            save();
            log.info("[RDB] Final snapshot saved on shutdown");
        } catch (Exception e) {
            log.warn("[RDB] Final snapshot failed: {}", e.getMessage());
        }
    }

    public Path getRdbFile() {
        return rdbFile;
    }

    public long getLastSaveAt() {
        return lastSaveAt;
    }

    public int getLastSaveCount() {
        return lastSaveCount;
    }
}
