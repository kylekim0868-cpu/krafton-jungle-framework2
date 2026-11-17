package com.minidbms.dbms.index;

/**
 * 메모리 기반 B+ 트리. 키(long: 행의 id) → 값(long: 데이터 파일에서 그 행이 시작하는 바이트 위치).
 *
 * <pre>
 *                     [ 30 | 60 ]                 ← 내부 노드: 길 안내용 키만 가진다
 *                    /     |     \
 *        [10|20] → [30|40|50] → [60|70]           ← 리프 노드: 실제 (키, 값) 보관,
 *                                                    next 포인터로 옆 리프와 연결
 * </pre>
 *
 * <ul>
 *   <li><b>검색</b>: 루트에서 리프까지 한 길로만 내려간다 → O(log N).</li>
 *   <li><b>삽입</b>: 리프에 넣고, 꽉 차면 반으로 쪼갠 뒤 가운데 키를 부모로 올린다.
 *       부모도 꽉 차면 같은 일을 위로 반복한다. 루트가 쪼개질 때만 트리 높이가 1 늘어난다.</li>
 *   <li><b>범위 검색</b>: 시작 키가 있는 리프를 찾은 뒤 next 를 따라 옆으로 이동한다.
 *       (값이 리프에만 있고 리프끼리 연결된 것이 B 트리와 다른 B+ 트리의 특징)</li>
 * </ul>
 *
 * <p>규칙: 내부 노드의 i 번째 자식에는 {@code keys[i-1] <= k < keys[i]} 인 키 k 만 들어 있다.</p>
 *
 * <p><b>Thread-Safe 하지 않다.</b> 동시성은 이 트리를 가진 Table 의 읽기-쓰기 락이 담당한다.</p>
 */
public final class BPlusTree {

    /** {@link #search(long)} 가 키를 찾지 못했을 때 돌려주는 값 (파일 위치는 음수가 될 수 없다) */
    public static final long NOT_FOUND = -1L;

    public static final int DEFAULT_ORDER = 128;

    /** 범위 검색에서 (키, 값) 한 쌍마다 호출된다. false 를 돌려주면 검색을 멈춘다. */
    @FunctionalInterface
    public interface EntryVisitor {
        boolean visit(long key, long value);
    }

    private abstract static class Node {
        /** 정렬된 키. 분할 직전에 한 칸이 더 필요하므로 길이는 order */
        final long[] keys;
        /** 현재 들어 있는 키 개수 */
        int count;

        Node(int order) {
            this.keys = new long[order];
        }
    }

    private static final class Leaf extends Node {
        final long[] values;
        Leaf next;

        Leaf(int order) {
            super(order);
            this.values = new long[order];
        }
    }

    private static final class Internal extends Node {
        /** 자식은 키보다 하나 많다: children[0..count] */
        final Node[] children;

        Internal(int order) {
            super(order);
            this.children = new Node[order + 1];
        }
    }

    /** 노드가 쪼개졌을 때 부모에게 전달하는 정보: 올라갈 키 + 새로 생긴 오른쪽 노드 */
    private record Split(long key, Node right) {
    }

    /** 차수: 내부 노드의 최대 자식 수. 노드 하나의 최대 키 수는 order - 1 */
    private final int order;
    private Node root;
    private final Leaf firstLeaf;
    private long size;
    private int height = 1;

    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("order must be >= 3");
        }
        this.order = order;
        this.firstLeaf = new Leaf(order);
        this.root = firstLeaf;
    }

    // ------------------------------------------------------------------
    // 검색
    // ------------------------------------------------------------------

    /** @return 키에 대응하는 값. 없으면 {@link #NOT_FOUND} */
    public long search(long key) {
        Leaf leaf = findLeaf(key);
        int pos = lowerBound(leaf.keys, leaf.count, key);
        if (pos < leaf.count && leaf.keys[pos] == key) {
            return leaf.values[pos];
        }
        return NOT_FOUND;
    }

    public boolean contains(long key) {
        return search(key) != NOT_FOUND;
    }

    /** low <= key <= high 인 항목을 키 오름차순으로 방문한다. */
    public void rangeScan(long low, long high, EntryVisitor visitor) {
        if (low > high) {
            return;
        }
        Leaf leaf = findLeaf(low);
        int pos = lowerBound(leaf.keys, leaf.count, low);
        while (leaf != null) {
            for (; pos < leaf.count; pos++) {
                long key = leaf.keys[pos];
                if (key > high || !visitor.visit(key, leaf.values[pos])) {
                    return;
                }
            }
            leaf = leaf.next; // 옆 리프로 이동
            pos = 0;
        }
    }

    /** 루트에서 시작해 key 가 있어야 할 리프까지 내려간다. */
    private Leaf findLeaf(long key) {
        Node node = root;
        while (node instanceof Internal internal) {
            node = internal.children[upperBound(internal.keys, internal.count, key)];
        }
        return (Leaf) node;
    }

    // ------------------------------------------------------------------
    // 삽입
    // ------------------------------------------------------------------

    /** 삽입. 이미 있는 키면 값만 바꾼다. */
    public void insert(long key, long value) {
        Split split = insert(root, key, value);
        if (split != null) {
            // 루트가 쪼개졌다 → 새 루트를 만들고 트리 높이가 1 증가
            Internal newRoot = new Internal(order);
            newRoot.keys[0] = split.key();
            newRoot.children[0] = root;
            newRoot.children[1] = split.right();
            newRoot.count = 1;
            root = newRoot;
            height++;
        }
    }

    /** @return node 가 쪼개졌으면 부모가 처리할 Split, 아니면 null */
    private Split insert(Node node, long key, long value) {
        if (node instanceof Leaf leaf) {
            return insertIntoLeaf(leaf, key, value);
        }

        Internal internal = (Internal) node;
        int idx = upperBound(internal.keys, internal.count, key);
        Split childSplit = insert(internal.children[idx], key, value);
        if (childSplit == null) {
            return null;
        }

        // 자식이 쪼개졌다 → 올라온 키를 idx 자리에, 새 오른쪽 자식을 idx+1 자리에 끼워 넣는다
        System.arraycopy(internal.keys, idx, internal.keys, idx + 1, internal.count - idx);
        System.arraycopy(internal.children, idx + 1, internal.children, idx + 2, internal.count - idx);
        internal.keys[idx] = childSplit.key();
        internal.children[idx + 1] = childSplit.right();
        internal.count++;

        if (internal.count < order) {
            return null;
        }
        return splitInternal(internal);
    }

    private Split insertIntoLeaf(Leaf leaf, long key, long value) {
        int pos = lowerBound(leaf.keys, leaf.count, key);
        if (pos < leaf.count && leaf.keys[pos] == key) {
            leaf.values[pos] = value; // 같은 키 → 덮어쓰기
            return null;
        }

        System.arraycopy(leaf.keys, pos, leaf.keys, pos + 1, leaf.count - pos);
        System.arraycopy(leaf.values, pos, leaf.values, pos + 1, leaf.count - pos);
        leaf.keys[pos] = key;
        leaf.values[pos] = value;
        leaf.count++;
        size++;

        if (leaf.count < order) {
            return null;
        }
        return splitLeaf(leaf);
    }

    /**
     * 리프 분할: 뒤쪽 절반을 새 리프로 옮긴다.
     * 리프의 키는 실제 데이터이므로 사라지면 안 된다 → 오른쪽 리프의 첫 키를 "복사"해서 부모로 올린다.
     */
    private Split splitLeaf(Leaf leaf) {
        int mid = leaf.count / 2;
        Leaf right = new Leaf(order);
        right.count = leaf.count - mid;
        System.arraycopy(leaf.keys, mid, right.keys, 0, right.count);
        System.arraycopy(leaf.values, mid, right.values, 0, right.count);
        leaf.count = mid;

        right.next = leaf.next;
        leaf.next = right;
        return new Split(right.keys[0], right);
    }

    /**
     * 내부 노드 분할: 가운데 키는 양쪽 어디에도 남기지 않고 부모로 "이동"시킨다.
     * (내부 노드의 키는 길 안내용이라 한 곳에만 있으면 된다)
     */
    private Split splitInternal(Internal node) {
        int mid = node.count / 2;
        long upKey = node.keys[mid];

        Internal right = new Internal(order);
        right.count = node.count - mid - 1;
        System.arraycopy(node.keys, mid + 1, right.keys, 0, right.count);
        System.arraycopy(node.children, mid + 1, right.children, 0, right.count + 1);

        // 옮겨 간 자식 참조를 지워 GC 가 헷갈리지 않게 한다
        for (int i = mid + 1; i <= node.count; i++) {
            node.children[i] = null;
        }
        node.count = mid;
        return new Split(upKey, right);
    }

    // ------------------------------------------------------------------
    // 이진 탐색
    // ------------------------------------------------------------------

    /** keys[i] >= key 인 첫 위치 (없으면 count) */
    private static int lowerBound(long[] keys, int count, long key) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** keys[i] > key 인 첫 위치 (없으면 count). 내부 노드에서 내려갈 자식 번호가 된다. */
    private static int upperBound(long[] keys, int count, long key) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid] <= key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ------------------------------------------------------------------
    // 통계
    // ------------------------------------------------------------------

    public long size() {
        return size;
    }

    /** 루트에서 리프까지의 층 수 (리프만 있으면 1) */
    public int height() {
        return height;
    }

    public int order() {
        return order;
    }
}
