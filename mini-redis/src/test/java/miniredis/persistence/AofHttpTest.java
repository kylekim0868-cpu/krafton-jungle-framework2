package miniredis.persistence;

import miniredis.http.MiniRedisHttpServer;
import miniredis.store.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AofHttpTest {
    @Test void http503OnWriteFailureKeepsReadsAndPermanentlyBlocksLaterWrites() throws Exception {
        var fail = new AtomicBoolean();
        var count = new AtomicInteger();
        MutationLog log = new MutationLog() {
            @Override public void append(Mutation mutation) throws IOException {
                count.incrementAndGet(); if (fail.get()) throw new IOException("injected persistence failure");
            }
            @Override public void close() { }
        };
        try (var store = new KeyValueStore(1, Clock.systemUTC(), log);
             var server = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", 0), store);
             var client = HttpClient.newHttpClient()) {
            server.start();
            URI key = URI.create("http://127.0.0.1:" + server.port() + "/keys/key");
            assertEquals(204, send(client, key, "PUT", "{\"value\":\"old\"}").statusCode());
            fail.set(true);
            var failed = send(client, key, "PUT", "{\"value\":\"new\"}");
            assertEquals(503, failed.statusCode());
            assertEquals("{\"error\":\"STORAGE_UNAVAILABLE\"}", failed.body());
            assertEquals(503, send(client, key, "DELETE", null).statusCode());
            fail.set(false);
            assertEquals(503, send(client, key, "PUT", "{\"value\":\"blocked\"}").statusCode());
            assertEquals("{\"value\":\"old\"}", send(client, key, "GET", null).body());
            assertEquals(2, count.get());
        }
    }

    @Test void successfulResponseWaitsForDurableAppendCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        MutationLog log = new MutationLog() {
            @Override public void append(Mutation mutation) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("append not released");
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            }
            @Override public void close() { }
        };
        try (var store = new KeyValueStore(1, Clock.systemUTC(), log);
             var server = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", 0), store);
             var client = HttpClient.newHttpClient()) {
            server.start();
            URI key = URI.create("http://127.0.0.1:" + server.port() + "/keys/key");
            var request = HttpRequest.newBuilder(key).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"v\"}")).build();
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(pending.isDone(), "204 must not precede durable append completion");
            } finally { release.countDown(); }
            assertEquals(204, pending.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals("{\"value\":\"v\"}", send(client, key, "GET", null).body());
        } finally { release.countDown(); }
    }

    private static HttpResponse<String> send(HttpClient client, URI key, String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(key).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
