package miniredis.store;

import miniredis.persistence.AofLog;
import miniredis.persistence.Mutation;
import miniredis.persistence.MutationLog;
import miniredis.persistence.StorageUnavailableException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 만료 확인과 변경을 같은 모니터로 보호하는 단일 프로세스 저장소. */
public final class KeyValueStore implements AutoCloseable {
    private final StringHashTable table;
    private final Clock clock;
    private MutationLog log;
    private boolean writesFailed;
    private boolean closed;
    private long activelyExpired;

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
        // AOF에 기록된 변경 연산과 만료 시각을 재생
        store.log = AofLog.open(path, store::replay);
        try {
            // 현재 시각 기준 이미 만료된 항목을 제거한다
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
        // 저장소에 쓰기 가능한 상태인지 확인
        ensureWritable();
        // validation precedes logging; failed validation never changes the AOF or memory.
        // 디스크 로그를 먼저 기록하는 이유
        // 해시 테이블을 먼저 변경한 다음 AOF기록에 실패했다면 메모리에는 저장이 되었지만, 재시작 후 해당 변경을 복구할 기록이 없을 수 있기 때문이다.
        var mutation = new Mutation("PUT", key, value, expiresAt);
        append(mutation);
        boolean applied = false; // 해시 테이블 변경 작업이 정상적으로 완료되었는지 추적하는 변수
        try {
            table.put(key, value, expiresAt);
            applied = true;
        } catch (RuntimeException failure) {
            throw new StorageUnavailableException("durable write could not be applied; restart required", failure);
        } finally {
            // Even allocation Errors after a durable append must not allow further writes.
            // AOF에는 변경 기록이 존재하지만 메모리에는 반영되지 않았으므로, 서버가 계속 쓰기를 허용한다면 로그와 실제 메모리 상태의 차이가 더 복잡해질 수 있다.
            // ★ 데이터 일관성을 보호하기 위한 장애 대응 정책!
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
        
        // 해시 테이블에서 key에 해당하는 항목을 찾는다.
        // 항목이 없으면 빈 결과를 반환.
        var entry = table.entry(key);
        if (entry.isEmpty()) {
            return Optional.empty();
        }

        // Lazy Expiration(지연 만료): 조회 시에 만료를 확인하는 것이 포인트!
        // 항목이 있다면 현재 시각과 만료 시각을 비교
        // 이미 만료됐다면 그때 헤시 테이블엣허 삭제하고 빈 결과를 반환
        // 만료되지 않았다면 저장된 값 반환
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

    /** 절대 만료 시각으로 재계산할 수 있는 삭제이므로 AOF에 추가하지 않는다. */
    public synchronized void expireEntries() {
        ensureOpen();
        activelyExpired += table.removeExpired(clock.millis());
    }

    public synchronized Stats stats() {
        ensureOpen();
        return table.stats(clock.millis(), activelyExpired);
    }

    public record KeyView(String keyPreview, String valuePreview, Long expiresAt, int bucket) { }
    public record BucketView(int index, int chainLength) { }
    public record Stats(long observedAt, int liveKeys, int capacity, int usedBuckets, int maxChainLength,
                        int resizeCount, long activelyExpired, List<KeyView> keys, List<BucketView> buckets) { }

    /** 유효 키 수. 만료 정리는 O(버킷 수 + 키 수)다. */
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
