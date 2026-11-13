package miniredis.persistence;

import miniredis.store.KeyValueStore;
import miniredis.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.CRC32C;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AofLogTest {
    @TempDir Path directory;
    private Path path() { return directory.resolve("store.aof"); }

    @Test void roundTripsUnicodeEmptyValuesAndAbsoluteExpiryInVersionedCrcFrames() throws Exception {
        var mutations = List.of(new Mutation("PUT", "사과\n", "\"🍎\"", 1234L),
                new Mutation("PUT", "", "", null), new Mutation("DELETE", "gone", null, null));
        try (var log = AofLog.open(path(), unused -> fail("new log must be empty"))) {
            for (var mutation : mutations) log.append(mutation);
        }
        var replayed = new ArrayList<Mutation>();
        try (var log = AofLog.open(path(), replayed::add)) { assertEquals(mutations, replayed); }
        byte[] bytes = Files.readAllBytes(path());
        var header = ByteBuffer.wrap(bytes);
        assertEquals(AofLog.MAGIC, header.getInt());
        int length = header.getInt();
        int payloadCrc = header.getInt();
        int headerCrc = header.getInt();
        assertEquals(crc(Arrays.copyOfRange(bytes, 16, 16 + length)), payloadCrc);
        assertEquals(crc(Arrays.copyOf(bytes, 12)), headerCrc);
    }

    @Test void loopsOverPartialWritesAndForcesOnlyAfterTheCompleteFrame() throws Exception {
        var writes = new AtomicInteger();
        var forces = new AtomicInteger();
        var io = new AofLog.ChannelIo() {
            @Override public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
                writes.incrementAndGet();
                int limit = bytes.limit();
                bytes.limit(Math.min(limit, bytes.position() + 3));
                try { return channel.write(bytes); } finally { bytes.limit(limit); }
            }
            @Override public void force(FileChannel channel) throws IOException {
                assertTrue(channel.position() > AofLog.HEADER_BYTES);
                assertEquals(channel.size(), channel.position());
                forces.incrementAndGet();
                channel.force(true);
            }
        };
        try (var log = AofLog.open(path(), unused -> { }, io)) {
            log.append(new Mutation("PUT", "key", "value", null));
            assertTrue(writes.get() > 1);
            assertEquals(1, forces.get());
        }
        try (var recovered = KeyValueStore.openPersistent(0, Clock.systemUTC(), path())) {
            assertEquals(Optional.of("value"), recovered.get("key"));
        }
    }

    @Test void partialWriteFailureLeavesMemoryUnchangedBlocksWritesAndRepairsTailOnRestart() throws Exception {
        var failing = new AtomicBoolean();
        var io = new AofLog.ChannelIo() {
            @Override public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
                if (failing.get()) {
                    bytes.limit(bytes.position() + 3);
                    channel.write(bytes);
                    throw new IOException("injected disk full after partial write");
                }
                return channel.write(bytes);
            }
        };
        var log = AofLog.open(path(), unused -> { }, io);
        try (var store = new KeyValueStore(1, new MutableClock(1000), log)) {
            store.put("key", "old");
            failing.set(true);
            assertThrows(StorageUnavailableException.class, () -> store.put("key", "new"));
            assertEquals(Optional.of("old"), store.get("key"));
            assertThrows(StorageUnavailableException.class, () -> store.delete("key"));
            assertThrows(IOException.class, () -> log.append(new Mutation("PUT", "other", "v", null)));
        }
        try (var recovered = KeyValueStore.openPersistent(0, Clock.systemUTC(), path())) {
            assertEquals(Optional.of("old"), recovered.get("key"));
            recovered.put("after", "repair");
        }
        try (var recovered = KeyValueStore.openPersistent(0, Clock.systemUTC(), path())) {
            assertEquals(Optional.of("repair"), recovered.get("after"));
        }
    }

    @Test void forceFailureReturnsUncertainFailureAndBlocksSubsequentPutAndDelete() throws Exception {
        var failing = new AtomicBoolean();
        var forces = new AtomicInteger();
        var io = new AofLog.ChannelIo() {
            @Override public void force(FileChannel channel) throws IOException {
                forces.incrementAndGet();
                if (failing.get()) throw new IOException("injected force failure");
                channel.force(true);
            }
        };
        try (var store = new KeyValueStore(1, Clock.systemUTC(), AofLog.open(path(), unused -> { }, io))) {
            store.put("key", "old");
            failing.set(true);
            assertThrows(StorageUnavailableException.class, () -> store.put("key", "uncertain"));
            assertEquals(Optional.of("old"), store.get("key"));
            assertThrows(StorageUnavailableException.class, () -> store.put("key", "blocked"));
            assertThrows(StorageUnavailableException.class, () -> store.delete("missing"));
            assertEquals(2, forces.get());
        }
        // No acknowledgement was sent; a fully written but unforced record may nevertheless survive.
        try (var recovered = KeyValueStore.openPersistent(0, Clock.systemUTC(), path())) {
            assertEquals(Optional.of("uncertain"), recovered.get("key"));
        }
    }

    @Test void zeroProgressWriteFailsClosedInsteadOfSpinning() throws Exception {
        var io = new AofLog.ChannelIo() {
            @Override public int write(FileChannel channel, ByteBuffer bytes) { return 0; }
        };
        try (var log = AofLog.open(path(), unused -> { }, io)) {
            assertThrows(IOException.class, () -> log.append(new Mutation("PUT", "key", "v", null)));
            assertThrows(IOException.class, () -> log.append(new Mutation("DELETE", "key", null, null)));
        }
    }

    @Test void everyTruncatedFinalHeaderOrPayloadRestoresLastValidRecordAndCanAppend() throws Exception {
        try (var store = KeyValueStore.openPersistent(1, Clock.systemUTC(), path())) {
            store.put("key", "old");
            store.put("key", "new");
        }
        byte[] bytes = Files.readAllBytes(path());
        int endFirst = 16 + ByteBuffer.wrap(bytes).getInt(4);
        for (int cut = endFirst; cut < bytes.length; cut++) {
            Path cutPath = directory.resolve("cut-" + cut + ".aof");
            Files.write(cutPath, Arrays.copyOf(bytes, cut));
            try (var store = KeyValueStore.openPersistent(1, Clock.systemUTC(), cutPath)) {
                assertEquals(Optional.of("old"), store.get("key"), "cut=" + cut);
                store.put("after", "valid");
            }
            try (var store = KeyValueStore.openPersistent(1, Clock.systemUTC(), cutPath)) {
                assertEquals(Optional.of("old"), store.get("key"));
                assertEquals(Optional.of("valid"), store.get("after"));
            }
        }
    }

    @Test void completeCorruptHeaderOrPayloadAbortsStartupAndDoesNotTruncate() throws Exception {
        try (var store = KeyValueStore.openPersistent(1, Clock.systemUTC(), path())) {
            store.put("key", "first"); store.put("key", "last");
        }
        byte[] original = Files.readAllBytes(path());
        int last = 16 + ByteBuffer.wrap(original).getInt(4);
        for (int offset : new int[]{0, 4, 8, 12, 16, last, last + 4, last + 12, original.length - 1}) {
            byte[] corrupt = original.clone(); corrupt[offset] ^= 1;
            Files.write(path(), corrupt);
            assertThrows(IOException.class, () -> KeyValueStore.openPersistent(1, Clock.systemUTC(), path()));
            assertArrayEquals(corrupt, Files.readAllBytes(path()));
        }
        Files.write(path(), original);
        try (var store = KeyValueStore.openPersistent(1, Clock.systemUTC(), path())) {
            assertEquals(Optional.of("last"), store.get("key"));
        }
    }

    @Test void crcValidButInvalidSchemaIsRejectedAndLockReleasedAfterFailure() throws Exception {
        for (String payload : List.of("{}", "null", "{", "{\"op\":\"DELETE\",\"key\":\"k\",\"value\":\"v\",\"expiresAt\":null}",
                "{\"op\":\"PUT\",\"key\":\"k\",\"value\":\"v\",\"expiresAt\":1.5}",
                "{\"op\":\"PUT\",\"key\":\"k\",\"value\":\"v\",\"expiresAt\":null} {}")) {
            Files.write(path(), frame(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThrows(IOException.class, () -> KeyValueStore.openPersistent(1, Clock.systemUTC(), path()));
        }
        Files.write(path(), new byte[0]);
        try (var store = KeyValueStore.openPersistent(1, Clock.systemUTC(), path())) { store.put("valid", "v"); }
    }

    @Test void twoOwnersCannotOpenTheSameLogAndCloseReleasesOwnership() throws Exception {
        var first = KeyValueStore.openPersistent(1, Clock.systemUTC(), path());
        try {
            assertThrows(IOException.class, () -> KeyValueStore.openPersistent(1, Clock.systemUTC(), path()));
            first.put("key", "v");
        } finally { first.close(); first.close(); }
        try (var second = KeyValueStore.openPersistent(1, Clock.systemUTC(), path())) {
            assertEquals(Optional.of("v"), second.get("key"));
        }
    }

    @Test void crcValidButImpossibleLengthIsCorruptionNotATruncatedTail() throws Exception {
        for (int length : new int[]{0, -1, AofLog.MAX_PAYLOAD_BYTES + 1}) {
            var header = ByteBuffer.allocate(16);
            header.putInt(AofLog.MAGIC).putInt(length).putInt(0);
            header.putInt(crc(Arrays.copyOf(header.array(), 12)));
            Files.write(path(), header.array());
            assertThrows(IOException.class, () -> KeyValueStore.openPersistent(1, Clock.systemUTC(), path()));
            assertArrayEquals(header.array(), Files.readAllBytes(path()));
        }
    }

    @Test void validatesSizeBeforeWritingAndKeepsTheLogUsable() throws Exception {
        try (var log = AofLog.open(path(), unused -> { })) {
            assertThrows(IllegalArgumentException.class, () -> log.append(null));
            assertThrows(IllegalArgumentException.class, () -> log.append(new Mutation("PUT", "key", "x".repeat(AofLog.MAX_PAYLOAD_BYTES), null)));
            log.append(new Mutation("PUT", "key", "small", null));
        }
        try (var store = KeyValueStore.openPersistent(1, Clock.systemUTC(), path())) { assertEquals(Optional.of("small"), store.get("key")); }
    }

    static byte[] frame(byte[] payload) {
        var frame = ByteBuffer.allocate(16 + payload.length);
        frame.putInt(AofLog.MAGIC).putInt(payload.length).putInt(crc(payload));
        frame.putInt(crc(Arrays.copyOf(frame.array(), 12))).put(payload);
        return frame.array();
    }
    private static int crc(byte[] bytes) { var crc = new CRC32C(); crc.update(bytes); return (int) crc.getValue(); }
}
