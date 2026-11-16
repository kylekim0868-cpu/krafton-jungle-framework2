package minidbms.index;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class BPlusTreeTest {
    @ParameterizedTest @ValueSource(ints={3,4,7,63})
    void ascendingDescendingAndRandomInsertionsMatchTreeMapAndMaintainAllStructure(int capacity) {
        for(int order=0;order<3;order++) {
            var tree=new BPlusTree<String>(capacity); var oracle=new TreeMap<Long,String>();
            var keys=new ArrayList<Long>(); for(long i=0;i<10_000;i++) keys.add(i);
            if(order==1) Collections.reverse(keys); if(order==2) Collections.shuffle(keys,new Random(8317));
            for(int i=0;i<keys.size();i++) {
                long key=keys.get(i); tree.put(key,"v"+key); oracle.put(key,"v"+key);
                if(i%197==0) assertEquals(oracle.size(),tree.validate().entries());
            }
            assertTrue(tree.validate().height()>1);
            oracle.forEach((key,value)->assertEquals(Optional.of(value),tree.get(key)));
            for(long i=0;i<10_000;i+=3) { tree.put(i,"new"+i); oracle.put(i,"new"+i); }
            assertEquals(oracle.size(),tree.size()); tree.validate();
            oracle.forEach((key,value)->assertEquals(Optional.of(value),tree.get(key)));
            assertTrue(tree.get(-1).isEmpty()); assertTrue(tree.get(10_000).isEmpty());
        }
    }
    @Test void handlesLongExtremesOverwriteAndEmptyRootWithoutSentinelConfusion() {
        var tree=new BPlusTree<String>(3); assertEquals(0,tree.validate().entries()); assertTrue(tree.get(0).isEmpty());
        for(long key:new long[]{Long.MAX_VALUE,Long.MIN_VALUE,0,-1,1,Long.MAX_VALUE-1,Long.MIN_VALUE+1}) tree.put(key,"v"+key);
        tree.put(Long.MIN_VALUE,"min"); tree.put(Long.MAX_VALUE,"max");
        assertEquals(7,tree.size()); tree.validate();
        assertEquals(Optional.of("min"),tree.get(Long.MIN_VALUE)); assertEquals(Optional.of("max"),tree.get(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->tree.put(1,null));
        assertEquals(Optional.of("v1"),tree.get(1));
    }
    @Test void rejectsUnsupportedFanout() {
        assertThrows(IllegalArgumentException.class,()->new BPlusTree<>(2));
        assertThrows(IllegalArgumentException.class,()->new BPlusTree<>(4097));
    }
}
