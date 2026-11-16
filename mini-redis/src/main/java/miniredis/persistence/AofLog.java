package miniredis.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

import static java.nio.file.StandardOpenOption.*;

/** 16바이트 검증 헤더와 JSON payload. 한 소유 채널에서 복구·append·force·파일 잠금을 관리한다. */
public final class AofLog implements MutationLog {
    static final int HEADER_BYTES = 16;
    static final int MAGIC = 0x4D524131; // MRA1: Mini Redis AOF format version 1
    static final int MAX_PAYLOAD_BYTES = 8_388_608;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
    // Closing any second channel may release this process's original POSIX file lock.
    // Reject same-file aliases before opening a channel, not merely before tryLock().
    private static final Set<AofLog> OWNERS = new HashSet<>();
    private final Path ownedPath;
    private final Object fileKey;
    private final FileChannel channel;
    private final FileLock lock;
    private final ChannelIo io;
    private boolean failed;
    private boolean closed;

    /** package-private OS I/O 경계: 부분쓰기·force 오류를 실제 파일에 주입해 검증한다. */
    interface ChannelIo {
        default int write(FileChannel channel, ByteBuffer bytes) throws IOException { return channel.write(bytes); }
        default void force(FileChannel channel) throws IOException { channel.force(true); }
    }

    private AofLog(Path ownedPath, Object fileKey, FileChannel channel, FileLock lock, ChannelIo io) {
        this.ownedPath = ownedPath;
        this.fileKey = fileKey;
        this.channel = channel;
        this.lock = lock;
        this.io = io;
    }

    public static AofLog open(Path path, Consumer<Mutation> replay) throws IOException {
        return open(path, replay, new ChannelIo() { });
    }

    static synchronized AofLog open(Path path, Consumer<Mutation> replay, ChannelIo io) throws IOException {
        if (Files.notExists(path)) {
            try { Files.createFile(path); } catch (FileAlreadyExistsException racedCreation) {
                // Another process created it; identity and OS ownership are still checked below.
            }
        }
        Path realPath = path.toRealPath();
        Object key = Files.readAttributes(realPath, BasicFileAttributes.class).fileKey();
        for (AofLog owner : OWNERS) {
            if (realPath.equals(owner.ownedPath) || (key != null && key.equals(owner.fileKey))
                    || Files.isSameFile(realPath, owner.ownedPath)) {
                throw new IOException("AOF already owned in this JVM");
            }
        }
        var channel = FileChannel.open(realPath, READ, WRITE);
        AofLog log = null;
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("AOF already owned", failure);
            }
            if (lock == null) throw new IOException("AOF already owned");
            log = new AofLog(realPath, key, channel, lock, io);
            OWNERS.add(log);
            log.recover(replay);
            return log;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (log != null) log.close(); else channel.close();
            } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void recover(Consumer<Mutation> replay) throws IOException {
        long length = channel.size();
        long validEnd = 0;
        while (validEnd < length) {
            if (length - validEnd < HEADER_BYTES) break; // incomplete final header only
            var header = ByteBuffer.allocate(HEADER_BYTES);
            readFully(header);
            byte[] raw = header.array();
            header.flip();
            int magic = header.getInt();
            int payloadSize = header.getInt();
            int payloadCrc = header.getInt();
            int headerCrc = header.getInt();
            if (magic != MAGIC || headerCrc != crc(raw, 12)
                    || payloadSize <= 0 || payloadSize > MAX_PAYLOAD_BYTES) {
                throw new IOException("invalid AOF header at " + validEnd);
            }
            if (length - validEnd - HEADER_BYTES < payloadSize) break;
            var payload = ByteBuffer.allocate(payloadSize);
            readFully(payload);
            if (payloadCrc != crc(payload.array(), payloadSize)) {
                throw new IOException("AOF checksum mismatch at " + validEnd);
            }
            replay.accept(decode(payload.array(), validEnd));
            validEnd += HEADER_BYTES + (long) payloadSize;
        }
        if (validEnd != length) {
            channel.truncate(validEnd);
            channel.force(true); // repaired tail must be durable before accepting new writes
        }
        channel.position(validEnd);
    }

    private void readFully(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes);
            if (read <= 0) throw new IOException("AOF changed or read made no progress");
        }
    }

    private static Mutation decode(byte[] bytes, long position) throws IOException {
        try {
            var root = JSON.readTree(bytes);
            if (root == null || !root.isObject() || root.size() != 4
                    || !root.has("op") || !root.get("op").isString()
                    || !root.has("key") || !root.get("key").isString()
                    || !root.has("value") || !root.has("expiresAt")) {
                throw new IOException("invalid AOF record at " + position);
            }
            var value = root.get("value");
            var expiration = root.get("expiresAt");
            if ((!value.isNull() && !value.isString())
                    || (!expiration.isNull() && (!expiration.isIntegralNumber() || !expiration.canConvertToLong()))) {
                throw new IOException("invalid AOF field at " + position);
            }
            return new Mutation(root.get("op").stringValue(), root.get("key").stringValue(),
                    value.isNull() ? null : value.stringValue(), expiration.isNull() ? null : expiration.longValue());
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new IOException("invalid AOF payload at " + position, failure);
        }
    }

    @Override public synchronized void append(Mutation mutation) throws IOException {
        if (closed || failed) throw new IOException("AOF writes disabled; restart and validate log");
        if (mutation == null) throw new IllegalArgumentException("mutation must not be null");
        byte[] payload = JSON.writeValueAsBytes(mutation);
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("AOF record exceeds 8 MiB");
        var frame = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        frame.putInt(MAGIC).putInt(payload.length).putInt(crc(payload, payload.length));
        frame.putInt(crc(frame.array(), 12)).put(payload).flip();
        try {
            while (frame.hasRemaining()) {
                if (io.write(channel, frame) <= 0) throw new IOException("AOF write made no progress");
            }
            io.force(channel);
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
    }

    private static int crc(byte[] bytes, int length) {
        var crc = new CRC32C();
        crc.update(bytes, 0, length);
        return (int) crc.getValue();
    }

    @Override public void close() throws IOException {
        // Same lock order as open: JVM registry first, then this log's append monitor.
        synchronized (AofLog.class) {
            synchronized (this) {
                if (closed) return;
                closed = true;
                try { lock.release(); } finally {
                    try { channel.close(); } finally {
                        if (!channel.isOpen()) OWNERS.remove(this);
                    }
                }
            }
        }
    }
}
