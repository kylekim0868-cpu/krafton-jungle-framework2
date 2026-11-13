package miniredis.persistence;

import miniredis.store.KeyValueStore;
import miniredis.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PersistentStoreTest {
    @TempDir Path directory;

    @Test void replayPreservesDeleteOverwriteEmptyCollisionResizeAndAbsoluteTtl() throws Exception {
        Path path = directory.resolve("data.aof");
        var clock = new MutableClock(1000);
        try (var store = KeyValueStore.openPersistent(0, clock, path)) {
            store.put("", ""); store.put("Aa", "collision-a"); store.put("BB", "collision-b");
            store.put("expire", "old", 10);
            store.put("noTtl", "old", 10); store.put("noTtl", "new");
            store.put("replaceTtl", "old", 10); store.put("replaceTtl", "fresh", 100);
            store.put("deleted", "value"); store.delete("deleted"); store.delete("missing");
            for (int i = 0; i < 100; i++) store.put("grow" + i, "v" + i);
        }
        clock.set(1010);
        try (var store = KeyValueStore.openPersistent(0, clock, path)) {
            assertEquals(Optional.of(""), store.get(""));
            assertEquals(Optional.of("collision-a"), store.get("Aa"));
            assertEquals(Optional.of("collision-b"), store.get("BB"));
            assertTrue(store.get("expire").isEmpty()); assertTrue(store.get("deleted").isEmpty());
            assertEquals(Optional.of("new"), store.get("noTtl"));
            assertEquals(Optional.of("fresh"), store.get("replaceTtl"));
            for (int i = 0; i < 100; i++) assertEquals(Optional.of("v" + i), store.get("grow" + i));
            assertEquals(105, store.size());
            clock.set(1100);
            assertTrue(store.get("replaceTtl").isEmpty());
        }
    }

    @Test void storeChangesMemoryOnlyAfterTheDurableAppendReturns() throws Exception {
        var reference = new AtomicReference<KeyValueStore>();
        var calls = new AtomicInteger();
        var records = new ArrayList<Mutation>();
        MutationLog log = new MutationLog() {
            @Override public void append(Mutation mutation) {
                assertEquals(calls.get() == 0 ? Optional.empty() : Optional.of("old"), reference.get().get("key"));
                records.add(mutation); calls.incrementAndGet();
            }
            @Override public void close() { }
        };
        try (var store = new KeyValueStore(1, new MutableClock(1000), log)) {
            reference.set(store);
            store.put("key", "old", 10);
            store.delete("key");
            assertTrue(store.get("key").isEmpty());
            assertEquals(1010L, records.getFirst().expiresAt());
        }
    }

    @Test void failedDeleteDoesNotRemoveMemoryAndInvalidInputsDoNotReachLog() throws Exception {
        var failing = new AtomicBoolean();
        var calls = new AtomicInteger();
        MutationLog log = new MutationLog() {
            @Override public void append(Mutation mutation) throws IOException {
                calls.incrementAndGet(); if (failing.get()) throw new IOException("delete failed");
            }
            @Override public void close() { }
        };
        try (var store = new KeyValueStore(1, new MutableClock(1000), log)) {
            store.put("key", "old");
            assertThrows(IllegalArgumentException.class, () -> store.put(null, "new"));
            assertThrows(IllegalArgumentException.class, () -> store.put("key", null));
            assertThrows(IllegalArgumentException.class, () -> store.put("key", "new", 0));
            assertThrows(IllegalArgumentException.class, () -> store.put("key", "new", Long.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> store.delete(null));
            assertEquals(1, calls.get());
            failing.set(true);
            assertThrows(StorageUnavailableException.class, () -> store.delete("key"));
            assertEquals(Optional.of("old"), store.get("key"));
            assertThrows(StorageUnavailableException.class, () -> store.put("key", "new"));
            assertEquals(2, calls.get());
        }
    }

    @Test void concurrentMemoryAndReplayOrdersAgree() throws Exception {
        Path path = directory.resolve("parallel.aof");
        var expected = new HashMap<String, Optional<String>>();
        try (var store = KeyValueStore.openPersistent(0, Clock.systemUTC(), path)) {
            try (var workers = Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<Future<?>>();
                for (int w = 0; w < 4; w++) {
                    int worker = w;
                    futures.add(workers.submit(() -> {
                        for (int i = 0; i < 30; i++) {
                            store.put(worker + ":" + i, "v" + i);
                            store.put("shared", "w" + worker + ":" + i);
                            if (i % 2 == 0) store.delete(worker + ":" + i);
                        }
                    }));
                }
                for (var future : futures) future.get(20, TimeUnit.SECONDS);
            }
            for (int w = 0; w < 4; w++) for (int i = 0; i < 30; i++) expected.put(w + ":" + i, store.get(w + ":" + i));
            expected.put("shared", store.get("shared"));
        }
        try (var recovered = KeyValueStore.openPersistent(0, Clock.systemUTC(), path)) {
            expected.forEach((key, value) -> assertEquals(value, recovered.get(key)));
            assertEquals(61, recovered.size());
        }
    }
}
