package miniredis.store;

import miniredis.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TtlConcurrencyTest {
    @Test
    void checksBeforeAtAndAfterExpiration() {
        var clock = new MutableClock(1000);
        var store = new KeyValueStore(2, clock);
        store.put("empty", "", 10);
        store.put("other", "v", 10);
        clock.set(1009);
        assertEquals(Optional.of(""), store.get("empty"));
        assertEquals(2, store.size());
        clock.set(1010);
        assertEquals(Optional.empty(), store.get("empty"));
        assertFalse(store.delete("other"));
        assertEquals(0, store.size());
        clock.set(1011);
        assertEquals(Optional.empty(), store.get("empty"));
    }

    @Test
    void overwritesReplaceOrRemoveThePreviousTtl() {
        var clock = new MutableClock(1000);
        var store = new KeyValueStore(1, clock);
        store.put("key", "old", 10);
        clock.set(1009);
        store.put("key", "new", 20);
        clock.set(1010);
        assertEquals(Optional.of("new"), store.get("key"));
        store.put("key", "permanent");
        clock.set(Long.MAX_VALUE);
        assertEquals(Optional.of("permanent"), store.get("key"));
        assertEquals(1, store.size());
    }

    @Test
    void invalidTtlOverflowAndNullDoNotChangeExistingData() {
        var clock = new MutableClock(1000);
        var store = new KeyValueStore(1, clock);
        store.put("key", "old");
        for (long ttl : new long[]{0, -1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> store.put("key", "new", ttl));
        }
        assertThrows(IllegalArgumentException.class, () -> store.put(null, "v", 1));
        assertThrows(IllegalArgumentException.class, () -> store.put("key", null, 1));
        assertEquals(Optional.of("old"), store.get("key"));
        assertEquals(1, store.size());
    }

    @Test
    void purgesExpiredNodesWithoutBreakingCollidingChainsAcrossResize() {
        var clock = new MutableClock(0);
        var store = new KeyValueStore(0, clock);
        String[] keys = {"AaAa", "AaBB", "BBAa", "BBBB"};
        for (String key : keys) store.put(key, key, 10);
        store.put("AaBB", "keep");
        for (int i = 0; i < 100; i++) store.put("key" + i, "expired", 10);
        clock.set(10);
        assertEquals(1, store.size());
        assertEquals(Optional.of("keep"), store.get("AaBB"));
        for (String key : new String[]{"AaAa", "BBAa", "BBBB"}) assertTrue(store.get(key).isEmpty());
    }

    @Test
    void expiredReadCannotDeleteAConcurrentReplacement() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
            @Override public long millis() {
                if (armed.getAndSet(false)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return 1000;
            }
        };
        var initialWrite = new AtomicBoolean(true);
        Clock combined = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
            @Override public long millis() { return initialWrite.getAndSet(false) ? 999 : clock.millis(); }
        };
        var raced = new KeyValueStore(1, combined);
        raced.put("key", "old", 1);
        armed.set(true);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var reader = workers.submit(() -> raced.get("key"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var writer = workers.submit(() -> { writerStarted.countDown(); raced.put("key", "new"); });
            try {
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                assertFalse(writer.isDone(), "overwrite must wait for expiration critical section");
            } finally {
                release.countDown();
            }
            assertTrue(reader.get(5, TimeUnit.SECONDS).isEmpty());
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertEquals(Optional.of("new"), raced.get("key"));
    }

    @Test
    void parallelWritersReadersAndDeletesPreserveAllOtherKeysAndResize() throws Exception {
        var store = new KeyValueStore(0);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int w = 0; w < 8; w++) {
                int worker = w;
                tasks.add(workers.submit(() -> {
                    for (int i = 0; i < 300; i++) {
                        String key = worker + ":" + i;
                        store.put(key, "first", 60000);
                        assertEquals(Optional.of("first"), store.get(key));
                        store.put(key, "last");
                        if (i % 2 == 0) assertTrue(store.delete(key));
                    }
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1200, store.size());
        for (int w = 0; w < 8; w++) for (int i = 1; i < 300; i += 2)
            assertEquals(Optional.of("last"), store.get(w + ":" + i));
    }
}
