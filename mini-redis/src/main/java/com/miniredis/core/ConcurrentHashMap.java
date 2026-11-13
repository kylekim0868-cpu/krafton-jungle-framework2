package com.miniredis.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Segment Locking(분할 잠금) 기반 Thread-Safe 해시 맵 래퍼.
 *
 * <p><b>설계</b> (JDK 7 의 ConcurrentHashMap 과 같은 방식)</p>
 * <pre>
 *  ConcurrentHashMap
 *   ├─ segment[0]  : ReentrantReadWriteLock + HashTable(버킷 0..c0-1)
 *   ├─ segment[1]  : ReentrantReadWriteLock + HashTable(버킷 0..c1-1)
 *   ├─ ...
 *   └─ segment[15] : ReentrantReadWriteLock + HashTable
 * </pre>
 * <ul>
 *   <li>전체 버킷 공간을 N 개의 세그먼트로 분할하고, 세그먼트마다 독립된 HashTable 과 락을 둔다.</li>
 *   <li>키 → 세그먼트 → (세그먼트 내부 HashTable 의 해시 함수) → 버킷 순으로 위치가 결정된다.</li>
 *   <li>get 은 Read Lock(공유), put/remove 는 Write Lock(배타).</li>
 *   <li>서로 다른 세그먼트의 키는 서로 다른 락을 사용하므로 대기 없이 병렬 처리된다.</li>
 *   <li>리사이징도 세그먼트 단위로 일어나므로, 한 세그먼트가 재해싱 중이어도 다른 세그먼트는 영향이 없다.</li>
 * </ul>
 *
 * <p>※ 이름이 java.util.concurrent.ConcurrentHashMap 과 같으므로 import 시 주의.</p>
 */
public class ConcurrentHashMap {

    public static final int DEFAULT_SEGMENTS = 16;

    /** 세그먼트: 락 1개 + 해시 테이블 1개 */
    private static final class Segment {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        final HashTable table;

        Segment(int initialCapacity) {
            this.table = new HashTable(initialCapacity);
        }
    }

    private final Segment[] segments;

    public ConcurrentHashMap() {
        this(DEFAULT_SEGMENTS, HashTable.DEFAULT_CAPACITY);
    }

    public ConcurrentHashMap(int segmentCount, int initialCapacityPerSegment) {
        if (segmentCount <= 0) {
            throw new IllegalArgumentException("segmentCount must be > 0");
        }
        this.segments = new Segment[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new Segment(initialCapacityPerSegment);
        }
    }

    // =====================================================================
    // 세그먼트 선택
    // =====================================================================

    /**
     * 키가 속할 세그먼트 번호를 계산한다.
     *
     * <p>세그먼트 내부 HashTable 은 hashCode 의 "하위 비트"(% capacity)로 버킷을 정한다.
     * 세그먼트까지 하위 비트로 고르면, 같은 세그먼트에 모인 키들이 모두 같은 하위 비트를 가지게 되어
     * 세그먼트 내부에서 특정 버킷에만 몰리는 현상이 생긴다.
     * 그래서 hashCode 를 한 번 섞은 뒤(spread) "상위 비트"를 사용해 세그먼트를 고른다.</p>
     */
    private int segmentIndex(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        int h = key.hashCode();
        h ^= (h >>> 16);
        h *= 0x85EBCA6B;       // MurmurHash3 finalizer 상수로 비트 확산
        h ^= (h >>> 13);
        return ((h >>> 16) & 0x7FFF) % segments.length;
    }

    private Segment segmentFor(String key) {
        return segments[segmentIndex(key)];
    }

    // =====================================================================
    // 쓰기 연산 (Write Lock)
    // =====================================================================

    public void put(String key, Object value, int ttlSeconds) {
        Segment seg = segmentFor(key);
        // 해당 세그먼트의 Write Lock 획득 → 같은 세그먼트의 다른 읽기/쓰기는 대기, 다른 세그먼트는 무관
        seg.lock.writeLock().lock();
        try {
            seg.table.put(key, value, ttlSeconds);
        } finally {
            // 예외가 나도 반드시 해제 (데드락 방지)
            seg.lock.writeLock().unlock();
        }
    }

    /** 절대 만료 시각으로 저장 (AOF/RDB 복구용) */
    public void putWithExpireAt(String key, Object value, long expireAt) {
        Segment seg = segmentFor(key);
        seg.lock.writeLock().lock();
        try {
            seg.table.putWithExpireAt(key, value, expireAt);
        } finally {
            seg.lock.writeLock().unlock();
        }
    }

    public boolean remove(String key) {
        Segment seg = segmentFor(key);
        seg.lock.writeLock().lock();
        try {
            return seg.table.remove(key);
        } finally {
            seg.lock.writeLock().unlock();
        }
    }

    /**
     * 전체 초기화.
     * 모든 세그먼트의 Write Lock 을 "항상 0번부터 오름차순"으로 획득한다.
     * 락 획득 순서를 고정하면 여러 스레드가 동시에 clear 를 호출해도 교착상태(Deadlock)가 생기지 않는다.
     */
    public void clear() {
        for (Segment seg : segments) {
            seg.lock.writeLock().lock();
        }
        try {
            for (Segment seg : segments) {
                seg.table.clear();
            }
        } finally {
            // 획득의 역순으로 해제
            for (int i = segments.length - 1; i >= 0; i--) {
                segments[i].lock.writeLock().unlock();
            }
        }
    }

    // =====================================================================
    // 읽기 연산 (Read Lock)
    // =====================================================================

    /**
     * 조회. Read Lock 으로 동시 읽기를 허용하면서 Lazy Expiration 을 안전하게 처리한다.
     *
     * <ol>
     *   <li>Read Lock 획득 후 peek(구조 변경 없는 조회).</li>
     *   <li>만료되지 않았으면 그대로 반환 → 대부분의 요청은 여기서 끝(읽기끼리 병렬).</li>
     *   <li>만료되었으면 Read Lock 해제 → Write Lock 획득 후 HashTable.get() 호출.
     *       HashTable.get() 내부의 Lazy Expiration 로직이 체인에서 엔트리를 제거한다.
     *       (ReentrantReadWriteLock 은 Read→Write 직접 승격을 지원하지 않으므로 해제 후 재획득.
     *        그 사이 다른 스레드가 값을 갱신했을 수 있는데, HashTable.get() 이 다시 검사하므로 안전하다.)</li>
     * </ol>
     */
    public Object get(String key) {
        Segment seg = segmentFor(key);
        long now = System.currentTimeMillis();

        seg.lock.readLock().lock();
        try {
            HashTable.EntryView view = seg.table.peek(key);
            if (view == null) {
                return null;
            }
            if (!view.isExpired(now)) {
                return view.value();
            }
        } finally {
            seg.lock.readLock().unlock();
        }

        // 만료 엔트리 발견 → Write Lock 으로 승격하여 Lazy 삭제 수행
        seg.lock.writeLock().lock();
        try {
            return seg.table.get(key);
        } finally {
            seg.lock.writeLock().unlock();
        }
    }

    /**
     * 남은 TTL 정보까지 함께 조회. 존재하지 않거나 만료되었으면 null.
     * (만료 엔트리는 get() 과 동일하게 Lazy 삭제된다.)
     */
    public HashTable.EntryView getEntry(String key) {
        Segment seg = segmentFor(key);
        long now = System.currentTimeMillis();

        seg.lock.readLock().lock();
        try {
            HashTable.EntryView view = seg.table.peek(key);
            if (view == null) {
                return null;
            }
            if (!view.isExpired(now)) {
                return view;
            }
        } finally {
            seg.lock.readLock().unlock();
        }

        seg.lock.writeLock().lock();
        try {
            // HashTable.get() 이 만료를 재확인하고 삭제한다. 그 사이 갱신되었다면 새 값을 돌려준다.
            if (seg.table.get(key) == null) {
                return null;
            }
            return seg.table.peek(key);
        } finally {
            seg.lock.writeLock().unlock();
        }
    }

    public boolean containsKey(String key) {
        return getEntry(key) != null;
    }

    /** 유효한 엔트리 총합. 각 세그먼트를 Read Lock 으로 순차 집계(약한 일관성). */
    public int size() {
        int total = 0;
        for (Segment seg : segments) {
            seg.lock.readLock().lock();
            try {
                total += seg.table.size();
            } finally {
                seg.lock.readLock().unlock();
            }
        }
        return total;
    }

    /** 만료 여부와 무관하게 물리적으로 저장된 엔트리 총합 (Active Expiration 검증용) */
    public int rawSize() {
        int total = 0;
        for (Segment seg : segments) {
            seg.lock.readLock().lock();
            try {
                total += seg.table.rawSize();
            } finally {
                seg.lock.readLock().unlock();
            }
        }
        return total;
    }

    /** 전체 버킷 수 (모든 세그먼트 capacity 합) */
    public int totalCapacity() {
        int total = 0;
        for (Segment seg : segments) {
            seg.lock.readLock().lock();
            try {
                total += seg.table.capacity();
            } finally {
                seg.lock.readLock().unlock();
            }
        }
        return total;
    }

    public int segmentCount() {
        return segments.length;
    }

    // =====================================================================
    // Active Expiration (TTLManager 에서 호출)
    // =====================================================================

    /**
     * 세그먼트를 하나씩 Write Lock 으로 잠그고 만료 엔트리를 제거한다.
     * 전역 락이 아니라 세그먼트 단위로 잠그므로 정리 중에도 다른 세그먼트는 정상 서비스된다.
     */
    public int removeExpired() {
        int removed = 0;
        for (Segment seg : segments) {
            seg.lock.writeLock().lock();
            try {
                removed += seg.table.removeExpired(System.currentTimeMillis());
            } finally {
                seg.lock.writeLock().unlock();
            }
        }
        return removed;
    }

    // =====================================================================
    // 스냅샷 / 시각화
    // =====================================================================

    /** 모든 유효 엔트리 스냅샷 (RDB/AOF rewrite 용). bucketIndex 는 전역 버킷 번호로 변환된다. */
    public List<HashTable.EntryView> snapshot() {
        List<HashTable.EntryView> result = new ArrayList<>();
        int offset = 0;
        for (Segment seg : segments) {
            seg.lock.readLock().lock();
            try {
                for (HashTable.EntryView v : seg.table.snapshot()) {
                    result.add(new HashTable.EntryView(v.key(), v.value(), v.expireAt(), offset + v.bucketIndex()));
                }
                offset += seg.table.capacity();
            } finally {
                seg.lock.readLock().unlock();
            }
        }
        return result;
    }

    /**
     * 전체 엔트리 목록. 세그먼트 내부 버킷 번호를 "전역 버킷 번호"로 변환한다.
     * 전역 번호 = (앞선 세그먼트들의 capacity 합) + 세그먼트 내부 버킷 번호
     */
    public List<Map<String, Object>> getAllEntries() {
        List<Map<String, Object>> result = new ArrayList<>();
        int offset = 0;
        for (int s = 0; s < segments.length; s++) {
            Segment seg = segments[s];
            seg.lock.readLock().lock();
            try {
                for (Map<String, Object> row : seg.table.getAllEntries()) {
                    int local = (Integer) row.get("bucketIndex");
                    row.put("bucketIndex", offset + local);
                    row.put("segment", s);
                    result.add(row);
                }
                offset += seg.table.capacity();
            } finally {
                seg.lock.readLock().unlock();
            }
        }
        return result;
    }

    /** 전역 버킷 번호 → 체이닝된 키 목록 (빈 버킷 포함) */
    public Map<Integer, List<String>> getBucketDistribution() {
        Map<Integer, List<String>> dist = new TreeMap<>();
        int offset = 0;
        for (Segment seg : segments) {
            seg.lock.readLock().lock();
            try {
                for (Map.Entry<Integer, List<String>> e : seg.table.getBucketDistribution().entrySet()) {
                    dist.put(offset + e.getKey(), e.getValue());
                }
                offset += seg.table.capacity();
            } finally {
                seg.lock.readLock().unlock();
            }
        }
        return dist;
    }

    /** 세그먼트별 통계 (시각화용): index, bucketOffset, capacity, size, resizeCount */
    public List<Map<String, Object>> getSegmentStats() {
        List<Map<String, Object>> stats = new ArrayList<>();
        int offset = 0;
        for (int s = 0; s < segments.length; s++) {
            Segment seg = segments[s];
            seg.lock.readLock().lock();
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", s);
                m.put("bucketOffset", offset);
                m.put("capacity", seg.table.capacity());
                m.put("size", seg.table.size());
                m.put("resizeCount", seg.table.resizeCount());
                stats.add(m);
                offset += seg.table.capacity();
            } finally {
                seg.lock.readLock().unlock();
            }
        }
        return stats;
    }
}
