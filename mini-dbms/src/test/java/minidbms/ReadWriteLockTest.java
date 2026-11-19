package minidbms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ReadWriteLockTest {
    @TempDir Path directory;

    @Test void indexedSelectAndRevisionCanFinishWhileScanIsStillExecuting() throws Exception {
        try (var db = new Database(directory); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            db.execute(new SqlStatement.Insert("first", 1));
            var paused = new PausedScan();
            var first = workers.submit(() -> db.execute(paused.statement()));
            try {
                paused.awaitEntry();
                var overlapping = workers.submit(() -> {
                    assertEquals(1, db.revision());
                    return select(db, "SELECT id FROM products WHERE id = 1;");
                });
                assertEquals(1L, overlapping.get(5, SECONDS).rows().getFirst().get("id"));
                assertFalse(first.isDone());
            } finally {
                paused.release.countDown();
            }
            assertEquals(1L, first.get(5, SECONDS).rows().getFirst().get("id"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeSelectExcludesInsertAndClose(boolean close) throws Exception {
        try (var db = new Database(directory); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            db.execute(new SqlStatement.Insert("first", 1));
            var paused = new PausedScan();
            var reader = workers.submit(() -> db.execute(paused.statement()));
            var started = new CountDownLatch(1);
            Future<?> writer;
            try {
                paused.awaitEntry();
                writer = workers.submit(() -> {
                    started.countDown();
                    if (close) db.close();
                    else assertEquals(2L, db.execute(new SqlStatement.Insert("second", 2)).insertedId());
                    return null;
                });
                assertTrue(started.await(5, SECONDS));
                assertThrows(TimeoutException.class, () -> writer.get(100, TimeUnit.MILLISECONDS));
            } finally {
                paused.release.countDown();
            }
            assertEquals(1L, reader.get(5, SECONDS).rows().getFirst().get("id"));
            writer.get(5, SECONDS);
            if (close) assertThrows(IllegalStateException.class, db::revision);
            else assertEquals(2, db.revision());
        }
    }

    @Test void insertExcludesSelectUntilFileIndexAndRevisionAreUpdated() throws Exception {
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var db = new Database(directory, (channel, bytes) -> {
            writing.countDown();
            await(release);
            return channel.write(bytes);
        }); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = workers.submit(() -> db.execute(new SqlStatement.Insert("first", 1)));
            var reading = new CountDownLatch(1);
            Future<QueryResult> reader;
            try {
                assertTrue(writing.await(5, SECONDS));
                reader = workers.submit(() -> {
                    reading.countDown();
                    var result = select(db, "SELECT id FROM products WHERE id = 1;");
                    assertEquals(1, db.revision());
                    return result;
                });
                assertTrue(reading.await(5, SECONDS));
                assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(1L, writer.get(5, SECONDS).insertedId());
            assertEquals(1L, reader.get(5, SECONDS).rows().getFirst().get("id"));
        }
    }

    @Test void storageFailureFromOneReaderIsVisibleToOtherReadersAndLaterWrites() throws Exception {
        var table = new AtomicReference<FileChannel>();
        try (var db = new Database(directory, (channel, bytes) -> {
            table.set(channel);
            return channel.write(bytes);
        }); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            db.execute(new SqlStatement.Insert("first", 1));
            var paused = new PausedScan();
            var first = workers.submit(() -> db.execute(paused.statement()));
            try {
                paused.awaitEntry();
                table.get().close();
                assertThrows(IOException.class, () -> select(db, "SELECT id FROM products WHERE id = 1;"));
                assertThrows(IOException.class, db::revision);
            } finally {
                paused.release.countDown();
            }
            var failure = assertThrows(ExecutionException.class, () -> first.get(5, SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertThrows(IOException.class, () -> db.execute(new SqlStatement.Insert("blocked", 2)));
            assertThrows(IOException.class, db::close); // the injected closed channel cannot be forced
        }
    }

    @Test void mixedReadersAndWritersObserveConsistentIdsAndPersistedRows() throws Exception {
        try (var db = new Database(directory); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CyclicBarrier(6);
            var results = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 3; worker++) {
                int batch = worker;
                results.add(workers.submit(() -> {
                    start.await(5, SECONDS);
                    for (int i = 0; i < 80; i++) {
                        db.execute(new SqlStatement.Insert("batch-" + batch + "-" + i, i));
                    }
                    return null;
                }));
            }
            for (int worker = 0; worker < 3; worker++) {
                results.add(workers.submit(() -> {
                    start.await(5, SECONDS);
                    for (int i = 0; i < 40; i++) {
                        long before = db.revision();
                        var rows = select(db, "SELECT id FROM products WHERE id BETWEEN 1 AND 240;").rows();
                        assertTrue(rows.size() >= before && rows.size() <= db.revision());
                        for (int row = 0; row < rows.size(); row++) {
                            assertEquals(row + 1L, rows.get(row).get("id"));
                        }
                    }
                    return null;
                }));
            }
            for (var result : results) result.get(10, SECONDS);
            assertEquals(240, db.validateIndex().entries());
        }
        try (var db = new Database(directory)) {
            String sql = "SELECT * FROM products WHERE id BETWEEN 1 AND 240;";
            var indexed = select(db, sql).rows();
            assertEquals(240, indexed.size());
            assertEquals(new SqlEngine(db, false).execute(sql).getFirst().rows(), indexed);
            assertEquals(240, indexed.stream().map(row -> row.get("name")).distinct().count());
        }
    }

    private static QueryResult select(Database db, String sql) throws IOException {
        return db.execute(SqlEngine.parseOne(sql));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, SECONDS)) throw new IOException("test timed out waiting to resume");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("test interrupted", interrupted);
        }
    }

    private static final class PausedScan {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void awaitEntry() throws InterruptedException {
            assertTrue(entered.await(5, SECONDS));
        }

        SqlStatement.Select statement() {
            // Pause inside a real table scan without adding a production test hook.
            Object filterValue = new Object() {
                @Override public boolean equals(Object name) {
                    entered.countDown();
                    try {
                        await(release);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                    return "first".equals(name);
                }

                @Override public int hashCode() {
                    return "first".hashCode();
                }
            };
            return new SqlStatement.Select(List.of("id"), new SqlStatement.Filter("name", filterValue), 1);
        }
    }
}
