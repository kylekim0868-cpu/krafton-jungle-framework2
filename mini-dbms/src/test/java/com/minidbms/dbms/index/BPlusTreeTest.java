package com.minidbms.dbms.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BPlusTreeTest {

    private static List<Long> keysInRange(BPlusTree tree, long low, long high) {
        List<Long> keys = new ArrayList<>();
        tree.rangeScan(low, high, (k, v) -> keys.add(k));
        return keys;
    }

    @Test
    @DisplayName("빈 트리: 검색은 NOT_FOUND, 범위 검색은 결과 없음")
    void emptyTree() {
        BPlusTree tree = new BPlusTree(4);
        assertEquals(BPlusTree.NOT_FOUND, tree.search(1));
        assertTrue(keysInRange(tree, Long.MIN_VALUE, Long.MAX_VALUE).isEmpty());
        assertEquals(0, tree.size());
        assertEquals(1, tree.height());
    }

    @Test
    @DisplayName("차수는 3 이상이어야 한다")
    void invalidOrder() {
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(2));
    }

    @Test
    @DisplayName("순차 삽입(자동 증가 id 와 같은 패턴): 분할이 여러 층에서 일어나도 모두 찾는다")
    void sequentialInsert() {
        BPlusTree tree = new BPlusTree(4); // 차수를 작게 잡아 분할을 자주 일으킨다
        int n = 100_000;
        for (long id = 1; id <= n; id++) {
            tree.insert(id, id * 10);
        }
        assertEquals(n, tree.size());
        assertTrue(tree.height() > 5, "small order must produce a tall tree, height=" + tree.height());
        for (long id = 1; id <= n; id++) {
            assertEquals(id * 10, tree.search(id));
        }
        assertEquals(BPlusTree.NOT_FOUND, tree.search(0));
        assertEquals(BPlusTree.NOT_FOUND, tree.search(n + 1));
    }

    @Test
    @DisplayName("무작위/역순 삽입: java.util.TreeMap 과 결과가 완전히 같다")
    void randomInsertMatchesTreeMap() {
        for (int order : new int[]{3, 4, 5, 16, 128}) {
            BPlusTree tree = new BPlusTree(order);
            TreeMap<Long, Long> expected = new TreeMap<>();
            Random random = new Random(order);

            for (int i = 0; i < 20_000; i++) {
                long key = random.nextInt(50_000) - 25_000; // 음수 키와 중복 키 포함
                long value = random.nextInt(1_000_000);
                tree.insert(key, value);
                expected.put(key, value);
            }
            for (long key = 30_000; key > 29_000; key--) { // 역순
                tree.insert(key, key);
                expected.put(key, key);
            }

            assertEquals(expected.size(), tree.size(), "order=" + order);
            for (Map.Entry<Long, Long> e : expected.entrySet()) {
                assertEquals(e.getValue(), tree.search(e.getKey()), "order=" + order + " key=" + e.getKey());
            }
            // 리프 연결을 따라간 전체 순회가 정렬 순서와 같아야 한다
            assertEquals(new ArrayList<>(expected.keySet()),
                    keysInRange(tree, Long.MIN_VALUE, Long.MAX_VALUE), "order=" + order);
            // 임의의 범위
            for (int i = 0; i < 200; i++) {
                long low = random.nextInt(60_000) - 30_000;
                long high = low + random.nextInt(2_000);
                assertEquals(new ArrayList<>(expected.subMap(low, true, high, true).keySet()),
                        keysInRange(tree, low, high), "order=" + order + " range=" + low + ".." + high);
            }
        }
    }

    @Test
    @DisplayName("같은 키를 다시 넣으면 값만 바뀌고 크기는 그대로")
    void duplicateKeyOverwrites() {
        BPlusTree tree = new BPlusTree(4);
        tree.insert(7, 100);
        tree.insert(7, 200);
        assertEquals(1, tree.size());
        assertEquals(200, tree.search(7));
        assertTrue(tree.contains(7));
        assertFalse(tree.contains(8));
    }

    @Test
    @DisplayName("범위 검색: 경계 포함, 뒤집힌 범위는 빈 결과, visitor 가 false 를 주면 중단")
    void rangeScan() {
        BPlusTree tree = new BPlusTree(4);
        for (long k = 10; k <= 100; k += 10) {
            tree.insert(k, k);
        }
        assertEquals(List.of(30L, 40L, 50L), keysInRange(tree, 30, 50));
        assertEquals(List.of(30L, 40L, 50L), keysInRange(tree, 25, 55));
        assertEquals(List.of(100L), keysInRange(tree, 100, Long.MAX_VALUE));
        assertTrue(keysInRange(tree, 101, 200).isEmpty());
        assertTrue(keysInRange(tree, 50, 30).isEmpty());

        List<Long> firstThree = new ArrayList<>();
        tree.rangeScan(Long.MIN_VALUE, Long.MAX_VALUE, (k, v) -> {
            firstThree.add(k);
            return firstThree.size() < 3;
        });
        assertEquals(List.of(10L, 20L, 30L), firstThree);
    }

    @Test
    @DisplayName("100만 건: 높이는 log 수준으로 낮게 유지된다")
    void millionKeysStayShallow() {
        BPlusTree tree = new BPlusTree();
        int n = 1_000_000;
        for (long id = 1; id <= n; id++) {
            tree.insert(id, id);
        }
        assertEquals(n, tree.size());
        assertTrue(tree.height() <= 4, "height=" + tree.height());
        assertEquals(777_777L, tree.search(777_777L));
    }
}
