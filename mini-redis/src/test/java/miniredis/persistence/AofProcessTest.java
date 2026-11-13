package miniredis.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AofProcessTest {
    @TempDir Path directory;

    @Test void acknowledgedWritesSurviveForcedJvmTerminationAndTtlDoesNotRestart() throws Exception {
        Path path = directory.resolve("crash.aof");
        try (var client = HttpClient.newHttpClient()) {
            try (var first = Child.start(path)) {
                assertEquals(204, send(client, first.base, "keep", "PUT", "{\"value\":\"사과🍎\"}").statusCode());
                assertEquals(204, send(client, first.base, "deleted", "PUT", "{\"value\":\"gone\"}").statusCode());
                assertEquals(204, send(client, first.base, "deleted", "DELETE", null).statusCode());
                assertEquals(204, send(client, first.base, "expire", "PUT", "{\"value\":\"short\",\"ttlMs\":2000}").statusCode());
                assertEquals(200, send(client, first.base, "expire", "GET", null).statusCode());
                first.process.destroyForcibly(); // SIGKILL on supported Unix platforms; no shutdown hook
                assertTrue(first.process.waitFor(5, TimeUnit.SECONDS));
            }
            Thread.sleep(2100); // test real process downtime; exact TTL boundary is separately clock-tested
            try (var second = Child.start(path)) {
                var value = send(client, second.base, "keep", "GET", null);
                assertEquals(200, value.statusCode());
                assertTrue(value.body().contains("사과🍎"));
                assertEquals(404, send(client, second.base, "deleted", "GET", null).statusCode());
                assertEquals(404, send(client, second.base, "expire", "GET", null).statusCode());
                assertEquals(204, send(client, second.base, "after", "PUT", "{\"value\":\"restart\"}").statusCode());
                second.process.destroyForcibly();
                assertTrue(second.process.waitFor(5, TimeUnit.SECONDS));
            }
            try (var third = Child.start(path)) {
                assertEquals(200, send(client, third.base, "after", "GET", null).statusCode());
                assertEquals(200, send(client, third.base, "keep", "GET", null).statusCode());
            }
        }
    }

    @Test void anotherJvmCannotStartOnAnOwnedLog() throws Exception {
        Path path = directory.resolve("owned.aof");
        try (var first = Child.start(path)) {
            Process second = Child.launch(path);
            try {
                assertTrue(second.waitFor(5, TimeUnit.SECONDS));
                assertNotEquals(0, second.exitValue());
                assertTrue(new String(second.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("AOF already owned"));
            } finally { second.destroyForcibly(); second.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    @Test void rejectedSameJvmAliasesDoNotReleaseTheOriginalCrossProcessLock() throws Exception {
        Path path = directory.resolve("protected.aof");
        try (var first = miniredis.store.KeyValueStore.openPersistent(1, java.time.Clock.systemUTC(), path)) {
            Path symlink = java.nio.file.Files.createSymbolicLink(directory.resolve("symlink.aof"), path);
            Path hardlink = java.nio.file.Files.createLink(directory.resolve("hardlink.aof"), path);
            for (Path alias : java.util.List.of(path, symlink, hardlink)) {
                assertThrows(java.io.IOException.class, () ->
                        miniredis.store.KeyValueStore.openPersistent(1, java.time.Clock.systemUTC(), alias));
                Process other = Child.launch(path);
                try {
                    assertTrue(other.waitFor(5, TimeUnit.SECONDS));
                    assertNotEquals(0, other.exitValue(), "duplicate rejection must not unlock original owner");
                    assertTrue(new String(other.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .contains("AOF already owned"));
                } finally { other.destroyForcibly(); other.waitFor(5, TimeUnit.SECONDS); }
            }
            first.put("key", "original still owns the file");
        }
        try (var recovered = miniredis.store.KeyValueStore.openPersistent(1, java.time.Clock.systemUTC(), path)) {
            assertEquals(java.util.Optional.of("original still owns the file"), recovered.get("key"));
        }
        try (var anotherProcess = Child.start(path)) {
            assertTrue(anotherProcess.process.isAlive(), "close must release OS and JVM ownership");
        }
    }

    private static HttpResponse<String> send(HttpClient client, URI base, String key, String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve("/keys/" + key)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final URI base;
        private Child(Process process, URI base) { this.process = process; this.base = base; }
        static Process launch(Path path) throws java.io.IOException {
            return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), "miniredis.Main", "0", "10", path.toString()).start();
        }
        static Child start(Path path) throws Exception {
            var process = launch(path);
            var line = CompletableFuture.supplyAsync(() -> {
                try { return process.inputReader().readLine(); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            try {
                String ready = line.get(8, TimeUnit.SECONDS);
                if (ready == null || !ready.startsWith("Mini Redis listening on http://127.0.0.1:")) {
                    throw new AssertionError("child did not become ready: " + ready);
                }
                return new Child(process, URI.create(ready.substring("Mini Redis listening on ".length())));
            } catch (Exception | Error failure) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); line.cancel(true);
                throw failure;
            }
        }
        @Override public void close() throws Exception {
            if (process.isAlive()) process.destroy();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
