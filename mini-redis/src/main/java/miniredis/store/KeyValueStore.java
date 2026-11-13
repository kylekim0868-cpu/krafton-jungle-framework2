package miniredis.store;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** 만료 확인과 변경을 같은 모니터로 보호하는 단일 프로세스 저장소. */
public final class KeyValueStore {
    private final StringHashTable table;
    private final Clock clock;

    public KeyValueStore(int expectedEntries) {
        this(expectedEntries, Clock.systemUTC());
    }

    /** 시계는 TTL 경계 테스트와 재시작 시 절대 만료 시각 판정에 사용한다. */
    public KeyValueStore(int expectedEntries, Clock clock) {
        table = StringHashTable.withExpectedSize(expectedEntries);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void put(String key, String value) {
        table.put(key, value); // 만료 없는 덮어쓰기는 이전 만료 설정도 제거한다.
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
        table.put(key, value, expiresAt);
    }

    /** 조회·만료 판정·삭제가 원자적이므로 이전 만료가 새 값을 지우지 않는다. */
    public synchronized Optional<String> get(String key) {
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
        // 만료된 키의 삭제는 없는 키와 같다.
        boolean present = get(key).isPresent();
        table.delete(key);
        return present;
    }

    /** 유효 키 수. 만료 정리는 O(버킷 수 + 키 수)이며 백그라운드 스캐너는 없다. */
    public synchronized int size() {
        table.removeExpired(clock.millis());
        return table.size();
    }
}
