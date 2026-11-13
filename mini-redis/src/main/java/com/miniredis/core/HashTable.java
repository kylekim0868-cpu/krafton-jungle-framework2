package com.miniredis.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 직접 구현한 Custom 해시 테이블.
 *
 * <p>java.util.HashMap / ConcurrentHashMap 을 내부 저장소로 사용하지 않고,
 * "버킷 배열 + 단방향 연결 리스트(Separate Chaining)" 구조를 직접 구현한다.
 * (반환용 DTO 를 만들 때에만 List / Map 을 사용하며, 저장소 자체는 오직 {@link #buckets} 배열이다.)</p>
 *
 * <p><b>이 클래스는 Thread-Safe 하지 않다.</b> 동시성 제어는 {@link ConcurrentHashMap} 래퍼가
 * 세그먼트 락으로 담당한다.</p>
 */
public class HashTable {

    /** 기본 초기 버킷 수 */
    public static final int DEFAULT_CAPACITY = 16;

    /** 적재율 임계값: size / capacity 가 이 값을 "초과"하면 2배로 리사이징 */
    public static final double LOAD_FACTOR_THRESHOLD = 0.75;

    /** 만료 없음을 의미하는 expireAt 값 */
    public static final long NO_EXPIRY = -1L;

    /**
     * 체이닝용 노드.
     * 하나의 버킷에 여러 엔트리가 들어오면 next 참조로 단방향 연결 리스트를 구성한다.
     */
    static final class Entry<K, V> {
        final K key;
        V value;
        /** 만료 시각(epoch ms). {@link #NO_EXPIRY}(-1) 이면 만료 없음 */
        long expireAt;
        /** 같은 버킷에 체이닝된 다음 노드 */
        Entry<K, V> next;

        Entry(K key, V value, long expireAt, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.expireAt = expireAt;
            this.next = next;
        }

        /** now 기준으로 만료되었는지 검사 */
        boolean isExpired(long now) {
            return expireAt != NO_EXPIRY && now > expireAt;
        }
    }

    /**
     * 외부(래퍼/영속성 모듈)에 노출하는 읽기 전용 스냅샷.
     * 내부 Entry 노드를 그대로 노출하면 락 밖에서 next 포인터가 변경될 수 있으므로 복사본을 반환한다.
     */
    public record EntryView(String key, Object value, long expireAt, int bucketIndex) {
        public boolean isExpired(long now) {
            return expireAt != NO_EXPIRY && now > expireAt;
        }

        /** 남은 TTL(초). 만료 없음이면 -1, 이미 만료면 0 */
        public long remainingTtlSeconds(long now) {
            if (expireAt == NO_EXPIRY) {
                return -1;
            }
            long remainingMs = expireAt - now;
            if (remainingMs <= 0) {
                return 0;
            }
            // 1ms 라도 남아 있으면 1초로 올림 (Redis 의 TTL 명령과 유사한 체감)
            return (remainingMs + 999) / 1000;
        }
    }

    /** 버킷 배열. 각 칸은 연결 리스트의 head 노드 */
    private Entry<String, Object>[] buckets;

    /** 현재 저장된 엔트리 수 (아직 정리되지 않은 만료 엔트리 포함) */
    private int size;

    /** 현재 버킷 배열 크기 */
    private int capacity;

    /** 지금까지 수행된 리사이징 횟수 (시각화/통계용) */
    private int resizeCount;

    public HashTable() {
        this(DEFAULT_CAPACITY);
    }

    public HashTable(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be > 0");
        }
        this.capacity = initialCapacity;
        this.buckets = newBucketArray(initialCapacity);
        this.size = 0;
    }

    @SuppressWarnings("unchecked")
    private static Entry<String, Object>[] newBucketArray(int capacity) {
        // 제네릭 배열은 직접 생성할 수 없으므로 raw 배열 생성 후 캐스팅
        return (Entry<String, Object>[]) new Entry[capacity];
    }

    // =====================================================================
    // 해시 함수
    // =====================================================================

    /**
     * 키를 버킷 인덱스로 변환하는 해시 함수.
     *
     * <ol>
     *   <li>key.hashCode() 는 음수일 수 있다.</li>
     *   <li>0x7FFFFFFF 와 AND 연산하여 부호 비트(최상위 비트)를 제거 → 항상 0 이상의 양수.
     *       (Math.abs 는 Integer.MIN_VALUE 에서 음수를 반환하므로 사용하지 않는다.)</li>
     *   <li>capacity 로 나눈 나머지 → 0 ~ capacity-1 범위의 버킷 인덱스.</li>
     * </ol>
     */
    private int hash(String key, int cap) {
        return (key.hashCode() & 0x7FFFFFFF) % cap;
    }

    private int hash(String key) {
        return hash(key, capacity);
    }

    // =====================================================================
    // 기본 연산
    // =====================================================================

    /**
     * 삽입/갱신(Upsert).
     *
     * @param ttlSeconds 0 이하이면 만료 없음
     */
    public void put(String key, Object value, int ttlSeconds) {
        long expireAt = ttlSeconds > 0
                ? System.currentTimeMillis() + ttlSeconds * 1000L
                : NO_EXPIRY;
        putWithExpireAt(key, value, expireAt);
    }

    /**
     * 절대 만료 시각(epoch ms)을 직접 지정하여 삽입/갱신한다.
     * AOF/RDB 복구 시 원래의 만료 시각을 그대로 복원하기 위해 사용한다.
     */
    public void putWithExpireAt(String key, Object value, long expireAt) {
        validateKey(key);
        int index = hash(key);

        // 1) 체인을 따라가며 동일 키가 있으면 값/만료시각만 갱신 (Upsert)
        Entry<String, Object> node = buckets[index];
        while (node != null) {
            if (node.key.equals(key)) {
                node.value = value;
                node.expireAt = expireAt;
                return;
            }
            node = node.next;
        }

        // 2) 동일 키가 없으면 새 노드를 체인의 head 에 삽입 (O(1))
        //    새 노드.next = 기존 head → buckets[index] = 새 노드
        buckets[index] = new Entry<>(key, value, expireAt, buckets[index]);
        size++;

        // 3) 적재율이 0.75 를 "초과"하면 버킷 배열을 2배로 확장
        if ((double) size / capacity > LOAD_FACTOR_THRESHOLD) {
            resize(capacity * 2);
        }
    }

    /** 조회. 키가 없으면 null */
    public Object get(String key) {
        validateKey(key);
        int index = hash(key);
        for (Entry<String, Object> node = buckets[index]; node != null; node = node.next) {
            if (node.key.equals(key)) {
                return node.value;
            }
        }
        return null;
    }

    /**
     * 구조를 변경하지 않는 조회(만료 엔트리도 그대로 반환).
     *
     * <p>{@link ConcurrentHashMap} 이 Read Lock 만 잡은 상태에서 호출한다.
     * Read Lock 하에서는 여러 스레드가 동시에 들어오므로 체인을 수정(삭제)하면 안 된다.
     * 만료 여부 판단과 실제 삭제는 래퍼가 Write Lock 으로 승격한 뒤 처리한다.</p>
     */
    public EntryView peek(String key) {
        validateKey(key);
        int index = hash(key);
        Entry<String, Object> node = buckets[index];
        while (node != null) {
            if (node.key.equals(key)) {
                return new EntryView(node.key, node.value, node.expireAt, index);
            }
            node = node.next;
        }
        return null;
    }

    /**
     * 삭제.
     *
     * @return 키를 삭제했으면 true
     */
    public boolean remove(String key) {
        validateKey(key);
        int index = hash(key);

        Entry<String, Object> prev = null;
        Entry<String, Object> node = buckets[index];
        while (node != null) {
            if (node.key.equals(key)) {
                unlink(index, prev, node);
                return true;
            }
            prev = node;
            node = node.next;
        }
        return false;
    }

    /**
     * 단방향 연결 리스트에서 node 를 제거한다.
     * prev 가 null 이면 node 가 head 이므로 버킷의 head 를 node.next 로 교체한다.
     */
    private void unlink(int index, Entry<String, Object> prev, Entry<String, Object> node) {
        if (prev == null) {
            buckets[index] = node.next;
        } else {
            prev.next = node.next;
        }
        node.next = null; // GC 친화적으로 참조 끊기
        size--;
    }

    /** 전체 초기화. 버킷 배열 크기(capacity)는 유지한다. */
    public void clear() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = null;
        }
        size = 0;
    }

    /** 현재 "유효한"(만료되지 않은) 엔트리 수. 전체 체인을 순회하여 계산한다. */
    public int size() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Entry<String, Object> head : buckets) {
            for (Entry<String, Object> n = head; n != null; n = n.next) {
                if (!n.isExpired(now)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 만료 여부와 관계없이 물리적으로 저장된 엔트리 수 (size 필드 값) */
    public int rawSize() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public int resizeCount() {
        return resizeCount;
    }

    // =====================================================================
    // 동적 리사이징 (Rehashing)
    // =====================================================================

    /**
     * 버킷 배열을 newCapacity 크기로 확장하고 모든 엔트리를 재해싱한다.
     *
     * <p>capacity 가 바뀌면 hash(key) = (hashCode & 0x7FFFFFFF) % capacity 결과도 바뀌므로
     * 기존 노드를 전부 새 배열의 올바른 위치로 옮겨야 한다.
     * 새 Entry 객체를 만들지 않고 기존 노드의 next 포인터만 재연결(relink)하여 메모리 할당을 줄인다.
     * 재해싱 도중 이미 만료된 엔트리는 옮기지 않고 버린다(덤으로 정리).</p>
     */
    private void resize(int newCapacity) {
        Entry<String, Object>[] oldBuckets = buckets;
        Entry<String, Object>[] newBuckets = newBucketArray(newCapacity);
        long now = System.currentTimeMillis();
        int newSize = 0;

        for (Entry<String, Object> head : oldBuckets) {
            Entry<String, Object> node = head;
            while (node != null) {
                Entry<String, Object> next = node.next; // 재연결 전에 다음 노드 보관
                if (!node.isExpired(now)) {
                    int newIndex = hash(node.key, newCapacity);
                    // 새 버킷의 head 로 삽입
                    node.next = newBuckets[newIndex];
                    newBuckets[newIndex] = node;
                    newSize++;
                } else {
                    node.next = null;
                }
                node = next;
            }
        }

        this.buckets = newBuckets;
        this.capacity = newCapacity;
        this.size = newSize;
        this.resizeCount++;
    }

    // =====================================================================
    // 디버깅 / 시각화용
    // =====================================================================

    /** 유효한 전체 엔트리의 스냅샷 (영속성 모듈용) */
    public List<EntryView> snapshot() {
        long now = System.currentTimeMillis();
        List<EntryView> result = new ArrayList<>(size);
        for (int i = 0; i < buckets.length; i++) {
            for (Entry<String, Object> n = buckets[i]; n != null; n = n.next) {
                if (!n.isExpired(now)) {
                    result.add(new EntryView(n.key, n.value, n.expireAt, i));
                }
            }
        }
        return result;
    }

    /**
     * 웹 UI 렌더링용 전체 엔트리 목록.
     * 각 원소: key, value, remainingTTL(초, 만료 없음 -1), expireAt, bucketIndex
     */
    public List<Map<String, Object>> getAllEntries() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> result = new ArrayList<>(size);
        for (EntryView v : snapshot()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", v.key());
            row.put("value", v.value());
            row.put("remainingTTL", v.remainingTtlSeconds(now));
            row.put("expireAt", v.expireAt());
            row.put("bucketIndex", v.bucketIndex());
            result.add(row);
        }
        return result;
    }

    /**
     * 버킷 번호 → 체이닝된 키 목록 (head → tail 순서).
     * 빈 버킷도 빈 리스트로 포함하여 UI 가 전체 버킷 배열을 그릴 수 있게 한다.
     */
    public Map<Integer, List<String>> getBucketDistribution() {
        long now = System.currentTimeMillis();
        Map<Integer, List<String>> dist = new TreeMap<>();
        for (int i = 0; i < buckets.length; i++) {
            List<String> chain = new ArrayList<>();
            for (Entry<String, Object> n = buckets[i]; n != null; n = n.next) {
                if (!n.isExpired(now)) {
                    chain.add(n.key);
                }
            }
            dist.put(i, chain);
        }
        return dist;
    }

    private static void validateKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
