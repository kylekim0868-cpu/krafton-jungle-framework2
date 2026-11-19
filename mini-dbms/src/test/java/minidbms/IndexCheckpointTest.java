package minidbms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.*;

class IndexCheckpointTest {
    @TempDir Path directory;

    @Test void restoresCheckpointWithoutRepublishingAnUnchangedIndex() throws IOException {
        seed(160);
        Path index = directory.resolve("products.index");
        Files.setLastModifiedTime(index, FileTime.fromMillis(86_400_000));
        FileTime original = Files.getLastModifiedTime(index);
        try (var db = new Database(directory)) {
            assertEquals(160, db.revision());
            assertEquals(160, db.validateIndex().entries());
            compareWithScan(db, "SELECT * FROM products WHERE id BETWEEN 31 AND 98;");
        }
        assertEquals(original, Files.getLastModifiedTime(index));
    }

    @Test void replaysAnUncheckpointedTailAndRestoresTheNextId() throws IOException {
        seed(63); // The replay crosses a leaf split boundary.
        append("{\"id\":64,\"name\":\"뒤🍎\",\"price\":64}\n{\"id\":65,\"name\":\"tail\",\"price\":65}\n");
        try (var db = new Database(directory)) {
            assertEquals(65, db.revision());
            compareWithScan(db, "SELECT * FROM products WHERE id BETWEEN 31 AND 65;");
            assertEquals(66L, db.execute(new SqlStatement.Insert("next", 66)).insertedId());
        }
        try (var db = new Database(directory)) {
            assertEquals(66, db.validateIndex().entries());
            compareWithScan(db, "SELECT * FROM products WHERE id = 66;");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "truncated", "checksum", "version"})
    void missingOrInvalidCheckpointFallsBackToTheTable(String damage) throws IOException {
        seed(80);
        Path index = directory.resolve("products.index");
        byte[] original = Files.readAllBytes(index);
        switch (damage) {
            case "missing" -> Files.delete(index);
            case "truncated" -> Files.write(index, java.util.Arrays.copyOf(original, original.length - 1));
            case "checksum" -> {
                byte[] damaged = original.clone();
                damaged[damaged.length - 1] ^= 1;
                Files.write(index, damaged);
            }
            case "version" -> {
                byte[] damaged = original.clone();
                damaged[7]++;
                Files.write(index, damaged);
            }
        }
        try (var db = new Database(directory)) {
            assertEquals(80, db.revision());
            compareWithScan(db, "SELECT * FROM products WHERE id BETWEEN 20 AND 78;");
        }
        assertArrayEquals(original, Files.readAllBytes(index));
    }

    @Test void sameLengthInvalidTableReplacementCannotBeHiddenByTheCheckpoint() throws IOException {
        seed(2);
        Path table = directory.resolve("products.jsonl");
        String rows = Files.readString(table);
        Files.writeString(table, rows.replace("\"id\":1", "\"id\":9"));
        assertThrows(IOException.class, () -> new Database(directory));
        Files.writeString(table, rows.replace("사과", "바나"));
        try (var db = new Database(directory)) {
            assertEquals(2, db.revision());
            assertEquals("바나🍎", new SqlEngine(db).execute("SELECT name FROM products WHERE id = 1;")
                    .getFirst().rows().getFirst().get("name"));
        }
        assertEquals(2, new IndexCheckpoint(table).load().orElseThrow().tree().size());
    }

    @Test void aShorterValidTableRebuildsAndContinuesFromItsLastRow() throws IOException {
        seed(10);
        Path table = directory.resolve("products.jsonl");
        Files.writeString(table, Files.readAllLines(table).getFirst() + "\n");
        try (var db = new Database(directory)) {
            assertEquals(1, db.revision());
            assertEquals(2L, db.execute(new SqlStatement.Insert("next", 2)).insertedId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":2", "{\"id\":3,\"name\":\"gap\",\"price\":1}\n"})
    void invalidTailStillPreventsStartup(String tail) throws IOException {
        seed(1);
        byte[] original = Files.readAllBytes(directory.resolve("products.index"));
        append(tail);
        assertThrows(IOException.class, () -> new Database(directory));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("products.index")));
    }

    @Test void failedCheckpointPublishLeavesTheJsonlRecoverable() throws IOException {
        Path index = Files.createDirectory(directory.resolve("products.index"));
        Files.writeString(index.resolve("keep"), "block replace");
        seed(1);
        try (var db = new Database(directory)) {
            compareWithScan(db, "SELECT * FROM products WHERE id = 1;");
        }
        assertEquals("block replace", Files.readString(index.resolve("keep")));
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private void seed(int count) throws IOException {
        try (var db = new Database(directory)) {
            for (int i = 1; i <= count; i++) db.execute(new SqlStatement.Insert("사과🍎", i));
        }
    }

    private void append(String rows) throws IOException {
        Files.writeString(directory.resolve("products.jsonl"), rows, APPEND);
    }

    private static void compareWithScan(Database db, String sql) throws IOException {
        var scanned = new SqlEngine(db, false).execute(sql).getFirst().rows();
        var indexed = new SqlEngine(db).execute(sql).getFirst().rows();
        assertFalse(scanned.isEmpty());
        assertEquals(scanned, indexed);
    }
}
