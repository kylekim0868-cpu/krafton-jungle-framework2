package minidbms.index;

import java.util.*;

/** 직접 만든 삽입·동등·범위 조회 B+ 트리. 값은 leaf에만 있고 내부 키는 오른쪽 자식의 최소 키다. */
public final class BPlusTree<V> {
    private abstract static class Node {
        final long[] keys;
        int count;
        // 노드에 저장할 수 있는 최대 개수+1를 keys배열 개수로 지정한 이유: 64번째 Key를 임시로 삽입한 다음 Split을 수행하기 때문.
        Node(int capacity) { keys = new long[capacity + 1]; }
    }
    private static final class Leaf extends Node {
        final Object[] values;
        Leaf next;
        Leaf(int capacity) { super(capacity); values = new Object[capacity + 1]; }
    }
    private static final class Branch extends Node {
        final Node[] children;
        Branch(int capacity) { super(capacity); children = new Node[capacity + 2]; }
    }
    private record Split(long separator, Node right) { }
    public record Structure(int height, long entries, int leaves) { }
    private final int maxKeys;
    private Node root;
    private long size;

    public BPlusTree(int maxKeys) {
        if (maxKeys < 3 || maxKeys > 4096) throw new IllegalArgumentException("maxKeys must be 3..4096");
        this.maxKeys = maxKeys; root = new Leaf(maxKeys);
    }

    public Optional<V> get(long key) {
        var leaf = findLeaf(key);
        int index = lowerBound(leaf, key);
        return index < leaf.count && leaf.keys[index] == key ? Optional.of(value(leaf, index)) : Optional.empty();
    }

    @FunctionalInterface
    public interface RangeVisitor<V, E extends Exception> {
        boolean visit(long key, V value) throws E;
    }

    /** YJ의 범위 순회 방식으로 시작 리프를 한 번 찾고 리프의 next 연결을 따른다. */
    public <E extends Exception> void rangeScan(long low, long high, RangeVisitor<V, E> visitor) throws E {
        if (low > high) return;
        Leaf leaf = findLeaf(low);
        int index = lowerBound(leaf, low);
        while (leaf != null) {
            for (; index < leaf.count; index++) {
                long key = leaf.keys[index];
                if (key > high || !visitor.visit(key, value(leaf, index))) return;
            }
            leaf = leaf.next;
            index = 0;
        }
    }

    private Leaf findLeaf(long key) {
        Node node = root;
        while (node instanceof Branch branch) node = branch.children[upperBound(node, key)];
        return (Leaf) node;
    }

    @SuppressWarnings("unchecked") private V value(Leaf leaf, int index) { return (V) leaf.values[index]; }

    public void put(long key, V value) {
        if (value == null) throw new IllegalArgumentException("null index value");
        Split split = insert(root, key, value);
        if (split != null) {
            var expanded = new Branch(maxKeys);
            expanded.keys[0] = split.separator; expanded.children[0] = root; expanded.children[1] = split.right; expanded.count = 1;
            root = expanded;
        }
    }

    private Split insert(Node node, long key, V value) {
        if (node instanceof Leaf leaf) {
            int index = lowerBound(leaf, key);
            if (index < leaf.count && leaf.keys[index] == key) { leaf.values[index] = value; return null; }
            System.arraycopy(leaf.keys,index,leaf.keys,index+1,leaf.count-index);
            System.arraycopy(leaf.values,index,leaf.values,index+1,leaf.count-index);
            leaf.keys[index]=key; leaf.values[index]=value; leaf.count++; size++;
            if (leaf.count <= maxKeys) return null;
            int middle=(leaf.count+1)/2;
            var right=new Leaf(maxKeys); right.count=leaf.count-middle;
            System.arraycopy(leaf.keys,middle,right.keys,0,right.count);
            System.arraycopy(leaf.values,middle,right.values,0,right.count);
            Arrays.fill(leaf.values,middle,leaf.count,null);
            leaf.count=middle; right.next=leaf.next; leaf.next=right;
            return new Split(right.keys[0],right);
        }
        var branch=(Branch)node;
        int index=upperBound(branch,key);
        Split split=insert(branch.children[index],key,value);
        if (split==null) return null;
        System.arraycopy(branch.keys,index,branch.keys,index+1,branch.count-index);
        System.arraycopy(branch.children,index+1,branch.children,index+2,branch.count-index);
        branch.keys[index]=split.separator; branch.children[index+1]=split.right; branch.count++;
        if (branch.count<=maxKeys) return null;
        int middle=branch.count/2;
        long promoted=branch.keys[middle];
        var right=new Branch(maxKeys); right.count=branch.count-middle-1;
        System.arraycopy(branch.keys,middle+1,right.keys,0,right.count);
        System.arraycopy(branch.children,middle+1,right.children,0,right.count+1);
        Arrays.fill(branch.children,middle+1,branch.count+1,null);
        branch.count=middle;
        return new Split(promoted,right);
    }

    private static int lowerBound(Node node,long key) {
        int low=0, high=node.count;
        while(low<high) { int middle=(low+high)>>>1; if(node.keys[middle]<key) low=middle+1; else high=middle; }
        return low;
    }
    private static int upperBound(Node node,long key) {
        int low=0, high=node.count;
        while(low<high) { int middle=(low+high)>>>1; if(node.keys[middle]<=key) low=middle+1; else high=middle; }
        return low;
    }
    public long size() { return size; }

    /** 테스트/실험 진단. 전체 키 범위·점유율·동일 leaf 깊이·구분 키·leaf 연결을 검증한다. */
    public Structure validate() {
        var leaves=new ArrayList<Leaf>();
        int height=validate(root,null,null,true,leaves);
        long entries=0;
        for(int i=0;i<leaves.size();i++) {
            var leaf=leaves.get(i); entries+=leaf.count;
            if(leaf.next!=(i+1<leaves.size()?leaves.get(i+1):null)) throw new IllegalStateException("broken leaf chain");
        }
        if(entries!=size) throw new IllegalStateException("incorrect entry count");
        return new Structure(height,entries,leaves.size());
    }
    private int validate(Node node,Long lower,Long upper,boolean isRoot,List<Leaf> leaves) {
        int minimum=node instanceof Leaf?(maxKeys+1)/2:maxKeys/2;
        if(node.count>maxKeys || (!isRoot && node.count<minimum) || (isRoot && node instanceof Branch && node.count<1)) {
            throw new IllegalStateException("invalid occupancy");
        }
        for(int i=0;i<node.count;i++) {
            if((i>0 && node.keys[i-1]>=node.keys[i]) || (lower!=null && node.keys[i]<lower) || (upper!=null && node.keys[i]>=upper)) {
                throw new IllegalStateException("incorrect key order/range");
            }
        }
        if(node instanceof Leaf leaf) {
            for(int i=0;i<leaf.count;i++) if(leaf.values[i]==null) throw new IllegalStateException("missing value");
            leaves.add(leaf); return 1;
        }
        var branch=(Branch)node;
        int childHeight=-1;
        for(int i=0;i<=branch.count;i++) {
            if(branch.children[i]==null) throw new IllegalStateException("missing child");
            if(i>0 && minimum(branch.children[i])!=branch.keys[i-1]) throw new IllegalStateException("incorrect separator");
            Long childLower=lower, childUpper=upper;
            if(i>0) childLower=branch.keys[i-1];
            if(i<branch.count) childUpper=branch.keys[i];
            int depth=validate(branch.children[i],childLower,childUpper,false,leaves);
            if(childHeight!=-1 && childHeight!=depth) throw new IllegalStateException("unequal leaf depth");
            childHeight=depth;
        }
        return childHeight+1;
    }
    private static long minimum(Node node) {
        while(node instanceof Branch branch) node=branch.children[0];
        return node.keys[0];
    }
}
