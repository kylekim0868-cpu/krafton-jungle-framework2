package miniredis.product;

import miniredis.http.MiniRedisHttpServer;
import miniredis.store.KeyValueStore;
import miniredis.support.MutableClock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ProductCacheTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MutableClock clock = new MutableClock(1000);
    private final KeyValueStore store = new KeyValueStore(1, clock);
    private final InMemoryProductOrigin origin = new InMemoryProductOrigin(List.of(new Product(42, "사과")), 0);
    private MiniRedisHttpServer redis;
    private HttpKeyCache cache;
    private ProductService service;
    private ProductHttpServer products;
    private HttpClient client;
    private URI base;

    @BeforeEach void start() throws Exception {
        redis = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", 0), store);
        redis.start();
        cache = new HttpKeyCache(URI.create("http://127.0.0.1:" + redis.port()), Duration.ofMillis(500));
        service = new ProductService(origin, cache, 10);
        products = new ProductHttpServer(new InetSocketAddress("127.0.0.1", 0), service, origin);
        products.start();
        base = URI.create("http://127.0.0.1:" + products.port());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }
    @AfterEach void stop() {
        if (client != null) client.close();
        if (products != null) products.close();
        if (cache != null) cache.close();
        if (redis != null) redis.close();
    }
    @Test void missThenHitAvoidsSecondOriginRead() throws Exception {
        var first = send("GET", "/products/42", null);
        var second = send("GET", "/products/42", null);
        assertEquals(200, first.statusCode());
        assertEquals(first.body(), second.body());
        assertEquals("사과", json.readTree(second.body()).get("name").stringValue());
        assertEquals(1, origin.readCount());
        assertEquals(1, json.readTree(send("GET", "/stats", null).body()).get("originReads").longValue());
    }
    @Test void expirationAndDeleteForceOriginRead() throws Exception {
        send("GET", "/products/42", null);
        clock.set(1009);
        send("GET", "/products/42", null);
        assertEquals(1, origin.readCount());
        clock.set(1010);
        assertEquals(200, send("GET", "/products/42", null).statusCode());
        assertEquals(2, origin.readCount());
        cache.delete(42);
        send("GET", "/products/42", null);
        assertEquals(3, origin.readCount());
    }
    @Test void successfulUpdateInvalidatesCachedResponse() throws Exception {
        send("GET", "/products/42", null);
        assertEquals(204, send("PUT", "/products/42", "{\"name\":\"배\"}").statusCode());
        assertTrue(store.get("product:42").isEmpty());
        assertEquals("배", json.readTree(send("GET", "/products/42", null).body()).get("name").stringValue());
        assertEquals(2, origin.readCount());
    }
    @Test void outageFallsBackAndDoesNotUndoSuccessfulOriginUpdate() throws Exception {
        send("GET", "/products/42", null);
        redis.close();
        assertEquals(200, send("GET", "/products/42", null).statusCode());
        assertEquals(2, origin.readCount());
        assertEquals(204, send("PUT", "/products/42", "{\"name\":\"변경\"}").statusCode());
        assertEquals("변경", json.readTree(send("GET", "/products/42", null).body()).get("name").stringValue());
        assertTrue(service.cacheFailureCount() >= 3);
    }
    @Test void absentProductsAreNotCachedAndFailedMutationPreservesCache() throws Exception {
        send("GET", "/products/42", null);
        assertEquals(404, send("PUT", "/products/99", "{\"name\":\"new\"}").statusCode());
        assertTrue(store.get("product:42").isPresent());
        assertEquals(404, send("GET", "/products/99", null).statusCode());
        assertEquals(404, send("GET", "/products/99", null).statusCode());
        assertTrue(store.get("product:99").isEmpty());
        assertEquals(3, origin.readCount());
    }
    @ParameterizedTest @ValueSource(strings = {"{", "{}", "null", "{\"name\":null}", "{\"name\":1}",
            "{\"name\":\"x\",\"extra\":1}", "{\"name\":\"x\",\"name\":\"y\"}", "{\"name\":\"x\"} {}"})
    void invalidUpdatesDoNotChangeOriginOrCache(String body) throws Exception {
        send("GET", "/products/42", null);
        String cached = store.get("product:42").orElseThrow();
        assertEquals(400, send("PUT", "/products/42", body).statusCode());
        assertEquals(cached, store.get("product:42").orElseThrow());
        assertEquals("사과", origin.get(42).orElseThrow().name());
    }
    @Test void malformedCachedProductFallsBackAndRefills() throws Exception {
        store.put("product:42", "not JSON");
        assertEquals(200, send("GET", "/products/42", null).statusCode());
        assertEquals(1, origin.readCount());
        assertEquals(1, service.cacheFailureCount());
        send("GET", "/products/42", null);
        assertEquals(1, origin.readCount());
    }
    @Test void disabledCacheReadsSameOriginEveryTime() throws Exception {
        var uncached = new ProductService(origin, null, 10);
        assertEquals(uncached.get(42), uncached.get(42));
        assertEquals(2, origin.readCount());
        assertEquals(0, store.size());
    }
    @Test void originFailureIsNotHiddenByFallback() {
        var unavailable = new ProductOrigin() {
            @Override public java.util.Optional<Product> get(int id) throws java.io.IOException { throw new java.io.IOException("offline"); }
            @Override public java.util.Optional<Product> update(int id, String name) throws java.io.IOException { throw new java.io.IOException("offline"); }
        };
        var failed = new ProductService(unavailable, cache, 10);
        assertThrows(java.io.IOException.class, () -> failed.get(42));
        assertThrows(java.io.IOException.class, () -> failed.update(42, "x"));
        assertEquals(0, store.size());
    }
    @Test void bodyStallAfterHeadersHasEndToEndDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var slow = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }
        });
        slow.start();
        try (var deadlineCache = new HttpKeyCache(URI.create("http://127.0.0.1:" + slow.getAddress().getPort()), Duration.ofMillis(100))) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(java.io.IOException.class, () -> deadlineCache.get(42)));
        } finally { release.countDown(); slow.stop(0); }
    }
    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3));
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
