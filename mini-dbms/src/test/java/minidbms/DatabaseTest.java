package minidbms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DatabaseTest {
    @TempDir Path directory;
    @Test void insertsProjectsFiltersLimitsAndReopensWithoutLosingIds() throws Exception {
        try (var db = new Database(directory)) {
            var engine = new SqlEngine(db);
            var results = engine.execute("INSERT INTO products(name,price) VALUES('사과',1200); INSERT INTO products(name,price) VALUES('',0); SELECT * FROM products;");
            assertEquals(1, results.getFirst().insertedId());
            assertEquals(2, results.get(1).insertedId());
            assertEquals(List.of(Map.of("id",1L,"name","사과","price",1200L), Map.of("id",2L,"name","","price",0L)), results.get(2).rows());
            assertEquals(List.of(Map.of("name","사과")), engine.execute("SELECT name FROM products WHERE price=1200 LIMIT 1;").getFirst().rows());
            assertTrue(engine.execute("SELECT * FROM products WHERE id=999;").getFirst().rows().isEmpty());
        }
        try (var db = new Database(directory)) {
            var engine = new SqlEngine(db);
            assertEquals(3, engine.execute("INSERT INTO products(name,price) VALUES('배',5);").getFirst().insertedId());
            assertEquals("배", engine.execute("SELECT * FROM products WHERE id=3;").getFirst().rows().getFirst().get("name"));
        }
        assertEquals(3, Files.readAllLines(directory.resolve("products.jsonl")).size());
    }
    @Test void escapesEmbeddedNewlinesAndSemicolonsWithoutBreakingRowsOrOffsets() throws Exception {
        try (var db = new Database(directory)) {
            var engine = new SqlEngine(db);
            engine.execute("INSERT INTO products(name,price) VALUES('첫줄\n둘째줄; 🍎 O''Brien',1);");
            engine.execute("INSERT INTO products(name,price) VALUES('next',2);");
            assertEquals("첫줄\n둘째줄; 🍎 O'Brien", engine.execute("SELECT name FROM products WHERE id=1;").getFirst().rows().getFirst().get("name"));
        }
        assertEquals(2, Files.readAllLines(directory.resolve("products.jsonl")).size());
        try (var db = new Database(directory)) { assertEquals(2, new SqlEngine(db).execute("SELECT * FROM products;").getFirst().rows().size()); }
    }
    @Test void validatesTheWholeScriptBeforeAnyInsert() throws Exception {
        try (var db = new Database(directory)) {
            var engine = new SqlEngine(db);
            assertThrows(IllegalArgumentException.class, () -> engine.execute("INSERT INTO products(name,price) VALUES('must not persist',1); SELECT bad FROM products;"));
            assertTrue(engine.execute("SELECT * FROM products;").getFirst().rows().isEmpty());
        }
        assertEquals(0, Files.size(directory.resolve("products.jsonl")));
    }
    @Test void rejectsOversizedRowWithoutConsumingAnId() throws Exception {
        try (var db = new Database(directory)) {
            assertThrows(IllegalArgumentException.class, () -> db.execute(new SqlStatement.Insert("x".repeat(1_048_576),1)));
            assertEquals(1, db.execute(new SqlStatement.Insert("valid",1)).insertedId());
        }
    }
    @Test void rejectsCorruptTruncatedDuplicateAndNonsequentialRowsWithoutRewriting() throws Exception {
        Path path = directory.resolve("products.jsonl");
        for (String text : List.of("{\n", "{\"id\":1,\"name\":\"a\",\"price\":1}", "{\"id\":2,\"name\":\"a\",\"price\":1}\n",
                "{\"id\":1,\"name\":\"a\",\"price\":1}\n{\"id\":1,\"name\":\"b\",\"price\":2}\n",
                "{\"id\":1,\"name\":\"a\",\"price\":-1}\n", "{\"id\":1,\"name\":null,\"price\":1}\n",
                "{\"id\":1,\"name\":\"a\",\"price\":1,\"extra\":1}\n", "{\"id\":1,\"id\":2,\"name\":\"a\",\"price\":1}\n")) {
            Files.writeString(path,text);
            assertThrows(IOException.class, () -> new Database(directory));
            assertEquals(text, Files.readString(path));
        }
        Files.writeString(path, "");
        try (var db = new Database(directory)) { assertEquals(1, db.execute(new SqlStatement.Insert("ok",1)).insertedId()); }
    }
    @Test void rejectsNonUtf8InsteadOfAutodetectingAndMixingEncodings() throws Exception {
        Path path = directory.resolve("products.jsonl");
        String json = "{\"id\":1,\"name\":\"a\",\"price\":1}";
        for (var charset : List.of(java.nio.charset.StandardCharsets.UTF_16BE, java.nio.charset.StandardCharsets.UTF_16LE)) {
            byte[] payload = json.getBytes(charset);
            byte[] bytes = Arrays.copyOf(payload, payload.length + 1); bytes[bytes.length - 1] = '\n';
            Files.write(path, bytes);
            assertThrows(IOException.class, () -> new Database(directory));
            assertArrayEquals(bytes, Files.readAllBytes(path));
        }
        byte[] prefix = "{\"id\":1,\"name\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suffix = "\",\"price\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix,0,bytes,0,prefix.length); bytes[prefix.length]=(byte)0xFF;
        System.arraycopy(suffix,0,bytes,prefix.length+1,suffix.length);
        Files.write(path,bytes);
        assertThrows(IOException.class, () -> new Database(directory));
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void ownershipCloseIsIdempotentAndRejectsSameJvmAliases() throws Exception {
        var first = new Database(directory);
        try {
            assertThrows(IOException.class, () -> new Database(directory));
            Path alias = directory.resolveSibling(directory.getFileName()+"-link");
            Files.createSymbolicLink(alias,directory);
            try { assertThrows(IOException.class, () -> new Database(alias)); }
            finally { Files.delete(alias); }
        } finally { first.close(); first.close(); }
        assertThrows(IllegalStateException.class, () -> first.execute(new SqlStatement.Insert("closed",1)));
        try (var reopened = new Database(directory)) { assertEquals(1,reopened.execute(new SqlStatement.Insert("ok",1)).insertedId()); }
    }
    @Test void bytePointersRoundTripMultibyteRows() throws Exception {
        Path path = directory.resolve("pointer.jsonl");
        try (var table = new TableFile(path)) {
            var first = new Product(1,"한글🍎",1); var second = new Product(2,"next",2);
            var p1=table.append(first); var p2=table.append(second);
            assertEquals(p1.offset()+p1.length()+1,p2.offset());
            assertEquals(first,table.read(p1)); assertEquals(second,table.read(p2));
        }
    }
}
