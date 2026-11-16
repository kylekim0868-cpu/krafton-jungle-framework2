package miniredis.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class StringHashTableTest {
    @Test
    void startsEmpty() {
        var table = new StringHashTable(4);

        assertEquals(0, table.size());
        assertEquals(Optional.empty(), table.get("missing"));
        assertFalse(table.delete("missing"));
        assertEquals(4, table.capacity());
    }

    @Test
    void storesAndRetrievesValues() {
        var table = new StringHashTable(4);

        table.put("product:1", "사과");
        table.put("product:2", "배");

        assertEquals(Optional.of("사과"), table.get("product:1"));
        assertEquals(Optional.of("배"), table.get("product:2"));
        assertEquals(2, table.size());
    }

    @Test
    void comparesKeyContentsWhenOverwritingAndDeleting() {
        var table = new StringHashTable(4);
        table.put(new String("product:1"), "사과");

        table.put(new String("product:1"), "배");

        assertEquals(Optional.of("배"), table.get(new String("product:1")));
        assertEquals(1, table.size());
        assertTrue(table.delete(new String("product:1")));
        assertEquals(0, table.size());
    }

    @Test
    void distinguishesEmptyValueFromMissingKey() {
        var table = new StringHashTable(4);
        table.put("empty", "");

        assertEquals(Optional.of(""), table.get("empty"));
        assertEquals(Optional.empty(), table.get("missing"));
        assertTrue(table.delete("empty"));
        assertEquals(Optional.empty(), table.get("empty"));
    }

    @Test
    void acceptsEmptyKey() {
        var table = new StringHashTable(4);
        table.put("", "value");

        assertEquals(Optional.of("value"), table.get(""));
        assertTrue(table.delete(""));
        assertEquals(Optional.empty(), table.get(""));
    }

    @Test
    void deletingMissingKeyDoesNotChangeExistingEntry() {
        var table = new StringHashTable(4);
        table.put("present", "value");

        assertFalse(table.delete("missing"));
        assertEquals(1, table.size());
        assertEquals(Optional.of("value"), table.get("present"));
    }

    @Test
    void storesAndOverwritesKeysWithIdenticalHashesIndependently() {
        var table = new StringHashTable(4);
        assertEquals("Aa".hashCode(), "BB".hashCode());

        table.put("Aa", "first");
        table.put("BB", "second");
        table.put("Aa", "updated");

        assertEquals(Optional.of("updated"), table.get("Aa"));
        assertEquals(Optional.of("second"), table.get("BB"));
        assertEquals(2, table.size());
        assertEquals(Optional.empty(), table.get("missing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AaAa", "AaBB", "BBAa"})
    void deletingHeadMiddleOrTailPreservesOtherCollidingKeys(String deletedKey) {
        var table = new StringHashTable(8);
        String[] keys = {"AaAa", "AaBB", "BBAa"};
        for (String key : keys) {
            assertEquals(keys[0].hashCode(), key.hashCode());
            table.put(key, "value:" + key);
        }
        assertEquals(keys[0].hashCode(), "BBBB".hashCode());
        assertEquals(Optional.empty(), table.get("BBBB"));
        assertFalse(table.delete("BBBB"));
        assertEquals(3, table.size());

        assertTrue(table.delete(deletedKey));
        assertFalse(table.delete(deletedKey));
        assertEquals(2, table.size());
        for (String key : keys) {
            assertEquals(key.equals(deletedKey) ? Optional.empty() : Optional.of("value:" + key),
                    table.get(key));
        }
    }

    @Test
    void distinguishesDifferentHashesInTheSameBucket() {
        var table = new StringHashTable(4);
        assertNotEquals("a".hashCode(), "e".hashCode());
        assertEquals(Math.floorMod("a".hashCode(), 4), Math.floorMod("e".hashCode(), 4));
        table.put("a", "first");
        table.put("e", "second");

        assertTrue(table.delete("a"));
        assertEquals(Optional.of("second"), table.get("e"));
        assertEquals(1, table.size());
    }

    @Test
    void growsOnlyWhenANewKeyExceedsThreeQuarters() {
        var table = new StringHashTable(4);
        table.put("a", "1");
        table.put("b", "2");
        table.put("c", "3");

        assertEquals(3, table.size());
        assertEquals(4, table.capacity());
        table.put("b", "updated");
        assertEquals(3, table.size());
        assertEquals(4, table.capacity());

        table.put("d", "4");
        assertEquals(4, table.size());
        assertEquals(8, table.capacity());
        assertEquals(Optional.of("updated"), table.get("b"));

        table.put("e", "5");
        table.put("f", "6");
        assertEquals(8, table.capacity());
        table.put("g", "7");
        assertEquals(7, table.size());
        assertEquals(16, table.capacity());
    }

    @Test
    void rehashesExistingEntriesWhoseBucketChanges() {
        var table = new StringHashTable(4);
        String[] keys = {"a", "e", "b", "c"};
        assertNotEquals(Math.floorMod("e".hashCode(), 4), Math.floorMod("e".hashCode(), 8));
        for (String key : keys) {
            table.put(key, "value:" + key);
        }

        assertEquals(8, table.capacity());
        assertEquals(keys.length, table.size());
        for (String key : keys) {
            assertEquals(Optional.of("value:" + key), table.get(key));
        }
        assertTrue(table.delete("e"));
        assertEquals(Optional.of("value:a"), table.get("a"));
    }

    @Test
    void preservesCollisionChainsAcrossResize() {
        var table = new StringHashTable(4);
        String[] keys = {"AaAa", "AaBB", "BBAa", "BBBB"};
        for (String key : keys) {
            assertEquals(keys[0].hashCode(), key.hashCode());
            table.put(key, key);
        }

        assertEquals(8, table.capacity());
        for (String key : keys) {
            assertEquals(Optional.of(key), table.get(key));
        }
        table.put("AaBB", "updated");
        assertTrue(table.delete("BBAa"));
        assertEquals(Optional.of("updated"), table.get("AaBB"));
        assertEquals(Optional.of("AaAa"), table.get("AaAa"));
        assertEquals(Optional.of("BBBB"), table.get("BBBB"));
        assertEquals(3, table.size());
    }

    @Test
    void keepsAllEntriesUsableAfterRepeatedGrowth() {
        var table = new StringHashTable(1);
        for (int i = 0; i < 1_000; i++) {
            table.put("key:" + i, "value:" + i);
        }
        assertEquals(1_000, table.size());
        assertEquals(2_048, table.capacity());

        for (int i = 0; i < 1_000; i++) {
            assertEquals(Optional.of("value:" + i), table.get("key:" + i));
            table.put("key:" + i, "updated:" + i);
        }
        assertEquals(1_000, table.size());
        for (int i = 0; i < 1_000; i++) {
            assertEquals(Optional.of("updated:" + i), table.get("key:" + i));
            assertTrue(table.delete("key:" + i));
            assertEquals(Optional.empty(), table.get("key:" + i));
        }
        assertEquals(0, table.size());
        assertEquals(2_048, table.capacity());
    }

    @Test
    void handlesMinimumIntegerHashWithoutNegativeBucketIndex() {
        var table = new StringHashTable(3);
        String key = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, key.hashCode());

        table.put(key, "value");
        assertEquals(Optional.of("value"), table.get(key));
        assertTrue(table.delete(key));
        assertEquals(Optional.empty(), table.get(key));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBucketCapacity(int capacity) {
        assertThrows(IllegalArgumentException.class, () -> new StringHashTable(capacity));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1,2", "2,3", "3,4", "4,6", "100,134", "100000,133334"})
    void preallocatesForExpectedEntriesWithoutRoundingToPowerOfTwo(int expectedEntries, int capacity) {
        var table = StringHashTable.withExpectedSize(expectedEntries);
        assertEquals(capacity, table.capacity());

        for (int i = 0; i < expectedEntries; i++) {
            table.put("key:" + i, "value:" + i);
        }

        assertEquals(expectedEntries, table.size());
        assertEquals(capacity, table.capacity());
        for (int i = 0; i < expectedEntries; i++) {
            assertEquals(Optional.of("value:" + i), table.get("key:" + i));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MAX_VALUE})
    void rejectsInvalidExpectedSizesWithoutOverflow(int expectedEntries) {
        assertThrows(IllegalArgumentException.class, () -> StringHashTable.withExpectedSize(expectedEntries));
    }

    @Test
    void rejectsNullWithoutChangingEntriesOrCapacity() {
        var table = new StringHashTable(4);
        table.put("a", "1");
        table.put("b", "2");
        table.put("c", "3");

        assertThrows(IllegalArgumentException.class, () -> table.put(null, "value"));
        assertThrows(IllegalArgumentException.class, () -> table.put("a", null));
        assertThrows(IllegalArgumentException.class, () -> table.put("new", null));
        assertThrows(IllegalArgumentException.class, () -> table.get(null));
        assertThrows(IllegalArgumentException.class, () -> table.delete(null));
        assertEquals(3, table.size());
        assertEquals(4, table.capacity());
        assertEquals(Optional.of("1"), table.get("a"));
    }
}
