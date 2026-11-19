package minidbms;

import minidbms.index.BPlusTree;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.Optional;

import static java.nio.file.StandardOpenOption.WRITE;

/** On-disk snapshot of the B+ tree's ordered leaf entries for the append-only products table. */
final class IndexCheckpoint {
    private static final int MAGIC = 0x4d444258; // MDBX
    private static final int VERSION = 1;
    private static final int DIGEST_SIZE = 32;
    private static final int FIXED_SIZE = 2 * Integer.BYTES + 2 * Long.BYTES + 2 * DIGEST_SIZE;
    private static final int MAX_KEYS = 63;

    record Snapshot(BPlusTree<TableFile.Pointer> tree, long offset) { }

    private final Path table;
    private final Path index;

    IndexCheckpoint(Path table) {
        this.table = table;
        this.index = table.resolveSibling("products.index");
    }

    static BPlusTree<TableFile.Pointer> emptyTree() {
        return new BPlusTree<>(MAX_KEYS);
    }

    Optional<Snapshot> load() {
        if (!Files.isRegularFile(index)) return Optional.empty();
        var checksum = digest();
        try (var checked = new DigestInputStream(new BufferedInputStream(Files.newInputStream(index)), checksum);
             var input = new DataInputStream(checked)) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return Optional.empty();
            long end = input.readLong();
            long count = input.readLong();
            long indexSize = Files.size(index);
            if (end < 0 || end > Files.size(table) || count < 0 || count > end / 2
                    || indexSize < FIXED_SIZE || (indexSize - FIXED_SIZE) % Integer.BYTES != 0
                    || count != (indexSize - FIXED_SIZE) / Integer.BYTES) {
                return Optional.empty();
            }
            byte[] expectedPrefix = input.readNBytes(DIGEST_SIZE);
            var tree = emptyTree();
            long offset = 0;
            for (long id = 1; id <= count; id++) {
                int length = input.readInt();
                if (length <= 0 || length > TableFile.MAX_ROW || length >= end - offset) return Optional.empty();
                tree.put(id, new TableFile.Pointer(offset, length));
                offset += length + 1L;
            }
            checked.on(false);
            if (offset != end || !MessageDigest.isEqual(input.readNBytes(DIGEST_SIZE), checksum.digest())
                    || input.read() != -1 || !MessageDigest.isEqual(expectedPrefix, prefixDigest(end))) {
                return Optional.empty();
            }
            tree.validate();
            return Optional.of(new Snapshot(tree, end));
        } catch (IOException | IllegalStateException invalidCheckpoint) {
            return Optional.empty(); // The JSONL scan remains authoritative.
        }
    }

    void save(BPlusTree<TableFile.Pointer> tree) throws IOException {
        long tableSize = Files.size(table);
        byte[] prefix = prefixDigest(tableSize);
        Path temporary = Files.createTempFile(index.getParent(), "products.index.", ".tmp");
        try {
            var checksum = digest();
            try (var channel = FileChannel.open(temporary, WRITE);
                 var checked = new DigestOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)), checksum);
                 var output = new DataOutputStream(checked)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeLong(tableSize);
                output.writeLong(tree.size());
                output.write(prefix);
                // IDs are contiguous starting at 1. Each offset is the sum of the preceding row lengths and LFs.
                long[] nextOffset = {0};
                tree.rangeScan(1, Long.MAX_VALUE, (id, pointer) -> {
                    if (pointer.offset() != nextOffset[0]) throw new IOException("noncontiguous index pointer");
                    output.writeInt(pointer.length());
                    nextOffset[0] += pointer.length() + 1L;
                    return true;
                });
                if (nextOffset[0] != tableSize) throw new IOException("index/table size mismatch");
                checked.on(false);
                output.write(checksum.digest());
                output.flush();
                channel.force(true);
            }
            Files.move(temporary, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] prefixDigest(long length) throws IOException {
        var hash = digest();
        byte[] buffer = new byte[65_536];
        try (var input = Files.newInputStream(table)) {
            while (length > 0) {
                int count = input.read(buffer, 0, (int) Math.min(length, buffer.length));
                if (count <= 0) throw new EOFException("table shorter than checkpoint");
                hash.update(buffer, 0, count);
                length -= count;
            }
        }
        return hash.digest();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
