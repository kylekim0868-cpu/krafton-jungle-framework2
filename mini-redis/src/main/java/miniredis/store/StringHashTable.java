package miniredis.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 버킷 배열과 체이닝을 사용하는 문자열 해시 테이블. 스레드 안전하지 않다. */
final class StringHashTable {
    private Node[] buckets;
    private int size;
    private int resizeCount;

    StringHashTable(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        buckets = new Node[initialCapacity];
    }

    static StringHashTable withExpectedSize(int expectedEntries) {
        if (expectedEntries < 0) {
            throw new IllegalArgumentException("expectedEntries must not be negative");
        }
        // ceil(expectedEntries / 0.75). long으로 계산해 정수 곱셈의 오버플로를 막는다.
        long capacity = Math.max(1L, (expectedEntries * 4L + 2) / 3);
        if (capacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("expectedEntries requires too many buckets");
        }
        return new StringHashTable((int) capacity);
    }

    void put(String key, String value) {
        put(key, value, null);
    }

    void put(String key, String value, Long expiresAt) {
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        int index = bucketIndex(key, buckets.length);
        for (Node node = buckets[index]; node != null; node = node.next) {
            if (node.key.equals(key)) {
                node.entry = new Entry(value, expiresAt);
                return;
            }
        }

        // 기존 키는 위에서 처리했으므로 신규 키만 크기 증가와 확장 대상이다.
        if ((size + 1L) * 4 > buckets.length * 3L) { // 실수 나눗셈 대신 정수의 곱셈만으로 Load Factor를 비교한 코드. (size+1)/buckets.length > 0.75 (원래 식)
            resize();
            index = bucketIndex(key, buckets.length);
        }
        buckets[index] = new Node(key, new Entry(value, expiresAt), buckets[index]); // 세 번째 인자인 buckets[index]는 새 노드의 next가 기존 Head를 가리키도록
        size++;
    }

    Optional<String> get(String key) {
        return entry(key).map(Entry::value);
    }

    Optional<Entry> entry(String key) {
        validateKey(key);
        int index = bucketIndex(key, buckets.length);
        for (Node node = buckets[index]; node != null; node = node.next) {
            if (node.key.equals(key)) {
                // 해당 Key를 찾아서 Value 반환
                return Optional.of(node.entry);
            }
        }
        // 해당 Key를 찾지 못했을 경우 emptyp()호출
        return Optional.empty();
    }

    int removeExpired(long now) {
        int before = size;
        for (int i = 0; i < buckets.length; i++) {
            Node previous = null;
            Node node = buckets[i];
            while (node != null) {
                Node next = node.next;
                if (node.entry.expired(now)) {
                    if (previous == null) {
                        buckets[i] = next;
                    } else {
                        previous.next = next;
                    }
                    size--;
                } else {
                    previous = node;
                }
                node = next;
            }
        }
        return before - size;
    }

    KeyValueStore.Stats stats(long now, long activelyExpired) {
        var keys = new ArrayList<KeyValueStore.KeyView>();
        var occupied = new ArrayList<KeyValueStore.BucketView>();
        int liveKeys = 0;
        int usedBuckets = 0;
        int maxChainLength = 0;
        for (int index = 0; index < buckets.length; index++) {
            int chainLength = 0;
            for (Node node = buckets[index]; node != null; node = node.next) {
                if (node.entry.expired(now)) continue;
                chainLength++;
                if (keys.size() < 80) {
                    keys.add(new KeyValueStore.KeyView(preview(node.key), preview(node.entry.value()),
                            node.entry.expiresAt(), index));
                }
            }
            if (chainLength == 0) continue;
            liveKeys += chainLength;
            usedBuckets++;
            maxChainLength = Math.max(maxChainLength, chainLength);
            if (occupied.size() < 32) occupied.add(new KeyValueStore.BucketView(index, chainLength));
        }
        return new KeyValueStore.Stats(now, liveKeys, buckets.length, usedBuckets, maxChainLength,
                resizeCount, activelyExpired, List.copyOf(keys), List.copyOf(occupied));
    }

    private static String preview(String text) {
        if (text.length() <= 120) return text;
        int end = Character.isHighSurrogate(text.charAt(119)) ? 119 : 120;
        return text.substring(0, end) + "…";
    }

    record Entry(String value, Long expiresAt) {
        boolean expired(long now) {
            return expiresAt != null && now >= expiresAt;
        }
    }

    boolean delete(String key) {
        validateKey(key);
        int index = bucketIndex(key, buckets.length);
        Node previous = null;
        for (Node node = buckets[index]; node != null; node = node.next) {
            if (node.key.equals(key)) {
                if (previous == null) {
                    buckets[index] = node.next;
                } else {
                    previous.next = node.next;
                }
                size--;
                return true;
            }
            previous = node;
        }
        return false;
    }

    int size() {
        return size;
    }

    int capacity() {
        return buckets.length;
    }

    private void resize() {
        if (buckets.length > Integer.MAX_VALUE / 2) {
            throw new IllegalStateException("bucket capacity cannot be doubled");
        }
        Node[] expanded = new Node[buckets.length * 2]; // 2배 크기의 빈 배열 생성
        // 기존 버킷 순회
        for (Node head : buckets) { 
            Node node = head;
            while (node != null) {
                Node next = node.next; // 원래 다음 노드 참조 보관
                int index = bucketIndex(node.key, expanded.length); // 새 버킷 Index 계산
                node.next = expanded[index]; // 새 버킷의 기존 Head에 연결
                expanded[index] = node; // 현재 노드를 새 Head로 설정
                node = next; // 다음 기존 노드로 이동
            }
        }
        // 노드를 재사용해 연결만 바꾼다. 재배치는 신규 삽입이 아니므로 size는 유지한다.
        buckets = expanded;
        resizeCount++;
    }

    private static int bucketIndex(String key, int capacity) {
        return Math.floorMod(key.hashCode(), capacity);
    }

    private static void validateKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }

    private static final class Node {
        private final String key;
        private Entry entry;
        private Node next;

        private Node(String key, Entry entry, Node next) {
            this.key = key;
            this.entry = entry;
            this.next = next;
        }
    }
}
