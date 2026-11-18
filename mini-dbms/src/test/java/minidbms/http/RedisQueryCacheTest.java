package minidbms.http;

import com.sun.net.httpserver.HttpServer;
import minidbms.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class RedisQueryCacheTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
    private static final String SELECT = "SELECT id FROM products WHERE name = '사과';";
    private static final String INSERT = "INSERT INTO products(name,price) VALUES('사과',1);";

    @TempDir Path directory;

    @Test void cachesScansAndIsolatesWritesAndRestartsWithoutChangingTheResultContract() throws Exception {
        try (var redis = new RedisEndpoint(); var client = HttpClient.newHttpClient()) {
            try (var db = new Database(directory); var server = new QueryHttpServer(LOCAL, db, redis.origin())) {
                server.start();
                assertEquals("BYPASS", cacheHeader(post(client, server, INSERT)));
                var miss = post(client, server, SELECT);
                assertEquals("MISS", cacheHeader(miss));
                assertEquals("HIT", cacheHeader(post(client, server, "select id from products where name='사과'")));
                assertEquals(miss.body(), post(client, server, SELECT).body());
                assertEquals("BYPASS", cacheHeader(post(client, server, "SELECT id FROM products WHERE id = 1;")));
                assertEquals("BYPASS", cacheHeader(post(client, server, "SELECT id FROM products WHERE id BETWEEN 1 AND 10;")));
                assertEquals(1, redis.values.size());

                post(client, server, INSERT);
                var updated = post(client, server, SELECT);
                assertEquals("MISS", cacheHeader(updated));
                assertEquals(2, JSON.readTree(updated.body()).get("rows").size());
                assertEquals(2, redis.values.size(), "stale entry remains only under its old revision");
            }
            try (var db = new Database(directory); var restarted = new QueryHttpServer(LOCAL, db, redis.origin())) {
                restarted.start();
                assertEquals("MISS", cacheHeader(post(client, restarted, SELECT)));
                assertEquals(3, redis.values.size(), "restart uses a new key even at the same revision");
                assertEquals("HIT", cacheHeader(post(client, restarted, SELECT)));

                redis.values.replaceAll((key, value) -> "invalid cached JSON");
                assertEquals("BYPASS", cacheHeader(post(client, restarted, SELECT)));
                redis.close();
                var fallback = post(client, restarted, SELECT);
                assertEquals("BYPASS", cacheHeader(fallback));
                assertEquals(2, JSON.readTree(fallback.body()).get("rows").size());
            }
        }
    }

    @Test void inFlightCacheHitIsDiscardedWhenAnInsertAdvancesTheRevision() throws Exception {
        try (var redis = new RedisEndpoint(); var client = HttpClient.newHttpClient();
             var db = new Database(directory); var server = new QueryHttpServer(LOCAL, db, redis.origin())) {
            server.start();
            post(client, server, INSERT);
            assertEquals("MISS", cacheHeader(post(client, server, SELECT)));
            redis.pauseHit.set(true);
            var pending = client.sendAsync(query(server, SELECT), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(redis.hitReceived.await(2, TimeUnit.SECONDS));
                // Bypass the cache so the INSERT can finish even while it is responding to another request.
                post(client, server, INSERT);
            } finally {
                redis.releaseHit.countDown();
            }
            var updated = pending.get(3, TimeUnit.SECONDS);
            assertEquals(200, updated.statusCode());
            assertEquals("MISS", cacheHeader(updated));
            assertEquals(2, JSON.readTree(updated.body()).get("rows").size());
        }
    }

    private static String cacheHeader(HttpResponse<String> response) {
        return response.headers().firstValue("X-Query-Cache").orElseThrow();
    }

    private static HttpResponse<String> post(HttpClient client, QueryHttpServer server, String sql) throws Exception {
        var response = client.send(query(server, sql), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }

    private static HttpRequest query(QueryHttpServer server, String sql) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/query"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(4))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(JSON.createObjectNode().put("sql", sql))))
                .build();
    }

    // Test the HTTP contract without starting the sibling Maven project.
    private static final class RedisEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
        private final AtomicBoolean pauseHit = new AtomicBoolean();
        private final CountDownLatch hitReceived = new CountDownLatch(1);
        private final CountDownLatch releaseHit = new CountDownLatch(1);
        private boolean stopped;

        RedisEndpoint() throws IOException {
            server = HttpServer.create(LOCAL, 0);
            server.createContext("/keys/", exchange -> {
                try (exchange) {
                    String key = exchange.getRequestURI().getPath();
                    if (exchange.getRequestMethod().equals("PUT")) {
                        var body = JSON.readTree(exchange.getRequestBody());
                        if (body.path("ttlMs").asLong() != 30_000 || !body.path("value").isString()) {
                            exchange.sendResponseHeaders(400, -1);
                            return;
                        }
                        values.put(key, body.get("value").stringValue());
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    var value = values.get(key);
                    if (value == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    if (pauseHit.compareAndSet(true, false)) {
                        hitReceived.countDown();
                        try {
                            releaseHit.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    var body = JSON.writeValueAsString(JSON.createObjectNode().put("value", value)).getBytes(UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        URI origin() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override public void close() {
            if (!stopped) server.stop(0);
            stopped = true;
        }
    }
}
