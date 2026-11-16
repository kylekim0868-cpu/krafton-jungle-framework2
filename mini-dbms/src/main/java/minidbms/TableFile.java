package minidbms;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;

/** products.jsonl 전용. 행 위치는 UTF-8 바이트 기준이며 LF는 길이에서 제외한다. */
final class TableFile implements AutoCloseable {
    record Pointer(long offset, int length) { }
    @FunctionalInterface interface Visitor { boolean visit(Product product, Pointer pointer) throws IOException; }
    private static final int MAX_ROW = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
    private final Path path;
    private final FileChannel channel;
    private boolean failed;

    TableFile(Path path) throws IOException { this.path = path; channel = FileChannel.open(path, CREATE, READ, WRITE); }

    Pointer append(Product product) throws IOException {
        if (failed) throw new IOException("table writes disabled after I/O failure");
        byte[] payload = JSON.writeValueAsBytes(product);
        if (payload.length > MAX_ROW) throw new IllegalArgumentException("row exceeds 1 MiB");
        long offset = channel.size();
        var bytes = ByteBuffer.allocate(payload.length + 1).put(payload).put((byte) '\n').flip();
        try {
            channel.position(offset);
            while (bytes.hasRemaining()) if (channel.write(bytes) <= 0) throw new IOException("table write made no progress");
        } catch (IOException failure) { failed = true; throw failure; }
        return new Pointer(offset, payload.length);
    }

    void scan(Visitor visitor) throws IOException {
        try (var input = new BufferedInputStream(Files.newInputStream(path), 65_536)) {
            var row = new ByteArrayOutputStream();
            byte[] buffer = new byte[65_536];
            long offset = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == '\n') {
                        byte[] payload = row.toByteArray();
                        if (!visitor.visit(decode(payload), new Pointer(offset, payload.length))) return;
                        offset += payload.length + 1L; row.reset();
                    } else {
                        if (row.size() == MAX_ROW) throw new IOException("oversized row at " + offset);
                        row.write(buffer[i]);
                    }
                }
            }
            if (row.size() != 0) throw new IOException("unterminated final row at " + offset);
        }
    }

    Product read(Pointer pointer) throws IOException {
        var bytes = ByteBuffer.allocate(pointer.length());
        long offset = pointer.offset();
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, offset);
            if (read <= 0) throw new IOException("invalid row pointer");
            offset += read;
        }
        return decode(bytes.array());
    }

    private static Product decode(byte[] bytes) throws IOException {
        try {
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            var root = JSON.readTree(text);
            if (root == null || !root.isObject() || root.size() != 3 || !root.has("id") || !root.has("name") || !root.has("price")
                    || !root.get("id").isIntegralNumber() || !root.get("id").canConvertToLong()
                    || !root.get("name").isString() || !root.get("price").isIntegralNumber() || !root.get("price").canConvertToLong()) {
                throw new IOException("invalid products row schema");
            }
            return new Product(root.get("id").longValue(), root.get("name").stringValue(), root.get("price").longValue());
        } catch (JacksonException | IllegalArgumentException failure) { throw new IOException("corrupt products row", failure); }
    }

    @Override public void close() throws IOException {
        try { if (!failed) channel.force(true); } finally { channel.close(); }
    }
}
