package miniredis.store;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KeyValueStoreTest {
    @Test
    void storesOverwritesAndDeletesThroughTheStoreBoundary() {
        var store = new KeyValueStore(3);
        assertEquals(0, store.size());
        assertEquals(Optional.empty(), store.get("product:1"));
        store.put("product:1", "사과");
        assertEquals(Optional.of("사과"), store.get("product:1"));

        store.put("product:1", "배");
        assertEquals(Optional.of("배"), store.get("product:1"));
        assertEquals(1, store.size());
        assertTrue(store.delete("product:1"));
        assertEquals(Optional.empty(), store.get("product:1"));
        assertFalse(store.delete("product:1"));
        assertEquals(0, store.size());
    }

    @Test
    void distinguishesEmptyStringsFromMissingKeys() {
        var store = new KeyValueStore(1);
        store.put("", "");

        assertEquals(Optional.of(""), store.get(""));
        assertEquals(Optional.empty(), store.get("missing"));
        assertTrue(store.delete(""));
        assertEquals(Optional.empty(), store.get(""));
    }

    @Test
    void preservesCollidingKeysAndGrowsPastTheEstimate() {
        var store = new KeyValueStore(0);
        String[] keys = {"AaAa", "AaBB", "BBAa", "BBBB"};
        for (String key : keys) {
            store.put(key, "value:" + key);
        }

        store.put("AaBB", "updated");
        assertTrue(store.delete("BBAa"));
        assertEquals(3, store.size());
        assertEquals(Optional.of("value:AaAa"), store.get("AaAa"));
        assertEquals(Optional.of("updated"), store.get("AaBB"));
        assertEquals(Optional.of("value:BBBB"), store.get("BBBB"));
        assertEquals(Optional.empty(), store.get("BBAa"));
    }

    @Test
    void rejectsNullWithoutChangingTheStore() {
        var store = new KeyValueStore(1);
        store.put("present", "value");

        assertThrows(IllegalArgumentException.class, () -> store.put(null, "value"));
        assertThrows(IllegalArgumentException.class, () -> store.put("present", null));
        assertThrows(IllegalArgumentException.class, () -> store.get(null));
        assertThrows(IllegalArgumentException.class, () -> store.delete(null));
        assertEquals(1, store.size());
        assertEquals(Optional.of("value"), store.get("present"));
    }

    @Test
    void rejectsInvalidExpectedSize() {
        assertThrows(IllegalArgumentException.class, () -> new KeyValueStore(-1));
        assertThrows(IllegalArgumentException.class, () -> new KeyValueStore(Integer.MAX_VALUE));
    }
}
