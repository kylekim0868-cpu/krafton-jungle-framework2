package miniredis.store;

import java.util.Optional;

/** 메모리 전용 문자열 저장소. null은 허용하지 않으며 TTL과 동시성 제어는 제공하지 않는다. */
public final class KeyValueStore {
    private final StringHashTable table;

    /** 예상 최대 고유 키 수에 맞춰 적재율 0.75 기준으로 버킷을 사전 할당한다. */
    public KeyValueStore(int expectedEntries) {
        table = StringHashTable.withExpectedSize(expectedEntries);
    }

    public void put(String key, String value) {
        table.put(key, value);
    }

    /** 빈 문자열은 존재하는 값이며, 없는 키만 Optional.empty()를 반환한다. */
    public Optional<String> get(String key) {
        return table.get(key);
    }

    public boolean delete(String key) {
        return table.delete(key);
    }

    public int size() {
        return table.size();
    }
}
