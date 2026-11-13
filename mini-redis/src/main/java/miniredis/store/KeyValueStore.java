package miniredis.store;

import miniredis.persistence.AofLog;
import miniredis.persistence.Mutation;
import miniredis.persistence.MutationLog;
import miniredis.persistence.StorageUnavailableException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** 만료 확인과 변경을 같은 모니터로 보호하는 단일 프로세스 저장소. */
public final class KeyValueStore implements AutoCloseable {
    private final StringHashTable table;
    private final Clock clock;
    private MutationLog log;
    private boolean writesFailed;
    private boolean closed;

    public KeyValueStore(int expectedEntries) {
        this(expectedEntries, Clock.systemUTC());
    }

    /** 시계는 TTL 경계 테스트와 재시작 시 절대 만료 시각 판정에 사용한다. */
    public KeyValueStore(int expectedEntries, Clock clock) {
        table = StringHashTable.withExpectedSize(expectedEntries);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 로그를 모두 검증·순서 재생한 뒤 만료를 정리하고 반환한다. HTTP 시작 전에 호출한다. */
    public static KeyValueStore openPersistent(int expectedEntries, Clock clock, Path path) throws IOException {
        var store = new KeyValueStore(expectedEntries, clock);
        store.log = AofLog.open(path, store::replay);
        try {
            store.table.removeExpired(clock.millis());
            return store;
        } catch (RuntimeException | Error failure) {
            try { store.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** 테스트·대체 로그 구현용 경계. 전달한 로그의 생명주기는 저장소가 소유한다. */
    public KeyValueStore(int expectedEntries, Clock clock, MutationLog log) {
        this(expectedEntries, clock);
        this.log = Objects.requireNonNull(log, "log");
    }

    private void replay(Mutation mutation) {
        if (mutation.op().equals("PUT")) table.put(mutation.key(), mutation.value(), mutation.expiresAt());
        else table.delete(mutation.key());
    }

    public synchronized void put(String key, String value) {
        applyPut(key, value, null); // 만료 없는 덮어쓰기는 이전 만료 설정도 제거한다.
    }

    public synchronized void put(String key, String value, long ttlMs) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        long expiresAt;
        try {
            expiresAt = Math.addExact(clock.millis(), ttlMs);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("expiration is out of range", overflow);
        }
        applyPut(key, value, expiresAt);
    }

    private void applyPut(String key, String value, Long expiresAt) {
        ensureWritable();
        // validation precedes logging; failed validation never changes the AOF or memory.
        var mutation = new Mutation("PUT", key, value, expiresAt);
        append(mutation);
        boolean applied = false;
        try {
            table.put(key, value, expiresAt);
            applied = true;
        } catch (RuntimeException failure) {
            throw new StorageUnavailableException("durable write could not be applied; restart required", failure);
        } finally {
            // Even allocation Errors after a durable append must not allow further writes.
            if (!applied) writesFailed = true;
        }
    }

    private void append(Mutation mutation) {
        if (log == null) return;
        try {
            log.append(mutation);
        } catch (IOException failure) {
            writesFailed = true;
            throw new StorageUnavailableException("AOF write failed; writes disabled until restart", failure);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("store is closed");
    }

    private void ensureWritable() {
        ensureOpen();
        if (writesFailed) throw new StorageUnavailableException("writes disabled until restart", null);
    }

    /** 조회·만료 판정·삭제가 원자적이므로 이전 만료가 새 값을 지우지 않는다. */
    public synchronized Optional<String> get(String key) {
        ensureOpen();
        var entry = table.entry(key);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (entry.orElseThrow().expired(clock.millis())) {
            table.delete(key);
            return Optional.empty();
        }
        return Optional.of(entry.orElseThrow().value());
    }

    public synchronized boolean delete(String key) {
        ensureWritable();
        var mutation = new Mutation("DELETE", key, null, null);
        var entry = table.entry(key);
        boolean present = entry.isPresent() && !entry.orElseThrow().expired(clock.millis());
        append(mutation); // 없는 키 삭제도 로그 동기화 후204를 보장한다.
        table.delete(key);
        return present;
    }

    /** 유효 키 수. 만료 정리는 O(버킷 수 + 키 수)이며 백그라운드 스캐너는 없다. */
    public synchronized int size() {
        ensureOpen();
        table.removeExpired(clock.millis());
        return table.size();
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (log != null) log.close();
    }
}
