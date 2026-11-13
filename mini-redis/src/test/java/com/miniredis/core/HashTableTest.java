package com.miniredis.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HashTableTest {

    @Test
    @DisplayName("1. 기본 put / get / remove 정상 동작")
    void basicPutGetRemove() {
        HashTable table = new HashTable();
        table.put("k1", "v1", 0);
        table.put("k2", 42, 0);

        assertThat(table.get("k1")).isEqualTo("v1");
        assertThat(table.get("k2")).isEqualTo(42);
        assertThat(table.size()).isEqualTo(2);

        assertThat(table.remove("k1")).isTrue();
        assertThat(table.get("k1")).isNull();
        assertThat(table.remove("k1")).isFalse(); // 이미 삭제됨
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("2. 동일 Key 덮어쓰기(Upsert) 시 값 갱신, size 증가 없음")
    void upsertOverwritesValue() {
        HashTable table = new HashTable();
        table.put("k", "old", 0);
        table.put("k", "new", 0);

        assertThat(table.get("k")).isEqualTo("new");
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. 존재하지 않는 Key 조회 시 null")
    void missingKeyReturnsNull() {
        HashTable table = new HashTable();
        assertThat(table.get("nope")).isNull();
    }

    @Test
    @DisplayName("4. 해시 충돌: 같은 hashCode 를 가진 서로 다른 키가 한 버킷에 체이닝되어도 모두 정상 동작")
    void separateChainingOnCollision() {
        // "Aa" 와 "BB" 는 hashCode 가 2112 로 동일 → 조합한 문자열들도 모두 같은 hashCode
        String[] colliding = {"Aa", "BB", "AaAa", "BBBB", "AaBB", "BBAa"};
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        assertThat("AaAa".hashCode()).isEqualTo("BBBB".hashCode()).isEqualTo("AaBB".hashCode()).isEqualTo("BBAa".hashCode());

        HashTable table = new HashTable(64); // 리사이징이 일어나지 않을 만큼 넉넉하게
        for (String k : colliding) {
            table.put(k, "val-" + k, 0);
        }

        // 모두 개별적으로 조회 가능
        for (String k : colliding) {
            assertThat(table.get(k)).isEqualTo("val-" + k);
        }

        // 버킷 분포에서 같은 해시를 가진 키들이 하나의 체인에 모여 있는지 확인
        Map<Integer, List<String>> dist = table.getBucketDistribution();
        int idx2 = ("Aa".hashCode() & 0x7FFFFFFF) % 64;
        int idx4 = ("AaAa".hashCode() & 0x7FFFFFFF) % 64;
        assertThat(dist.get(idx2)).contains("Aa", "BB");
        assertThat(dist.get(idx4)).contains("AaAa", "BBBB", "AaBB", "BBAa");

        // 체인 중간 노드 삭제 후에도 앞/뒤 노드가 끊기지 않아야 함
        assertThat(table.remove("BBBB")).isTrue();
        assertThat(table.get("AaAa")).isEqualTo("val-AaAa");
        assertThat(table.get("AaBB")).isEqualTo("val-AaBB");
        assertThat(table.get("BBAa")).isEqualTo("val-BBAa");
        assertThat(table.get("BBBB")).isNull();
    }

    @Test
    @DisplayName("4-1. 충돌 체인은 head 삽입 순서(최근 → 과거)로 연결 + getAllEntries 에 버킷 인덱스 포함")
    void chainOrderAndEntryMetadata() {
        HashTable table = new HashTable(8);
        table.put("Aa", 1, 0);
        table.put("BB", 2, 0);   // 같은 버킷의 head 로 삽입됨
        int idx = ("Aa".hashCode() & 0x7FFFFFFF) % 8;
        assertThat(table.getBucketDistribution().get(idx)).containsExactly("BB", "Aa");

        List<Map<String, Object>> all = table.getAllEntries();
        assertThat(all).hasSize(2);
        assertThat(all.get(0)).containsEntry("key", "BB").containsEntry("bucketIndex", idx).containsEntry("remainingTTL", -1L);
    }

    @Test
    @DisplayName("6. clear() 후 size() == 0")
    void clearEmptiesTable() {
        HashTable table = new HashTable();
        for (int i = 0; i < 100; i++) {
            table.put("k" + i, i, 0);
        }
        table.clear();
        assertThat(table.size()).isZero();
        assertThat(table.get("k1")).isNull();
        assertThat(table.getAllEntries()).isEmpty();
    }
}
