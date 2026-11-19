package miniredis.http;

import miniredis.store.KeyValueStore;
import miniredis.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DashboardTest {
    @Test void servesDashboardAndPeriodicallyRemovesUnvisitedKeys() throws Exception {
        var clock = new MutableClock(1000);
        try (var store = new KeyValueStore(3, clock);
             var server = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", 0), store);
             var client = HttpClient.newHttpClient()) {
            store.put("orphan", "no GET", 20);
            store.put("Aa", "first");
            store.put("BB", "second");
            server.start();
            var origin = URI.create("http://127.0.0.1:" + server.port());
            var page = get(client, origin.resolve("/dashboard"));
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("사용 중인 버킷 분포"));
            clock.set(1020);
            var stats = store.stats();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (stats.activelyExpired() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
                stats = store.stats();
            }
            assertEquals(1, stats.activelyExpired());
            var response = get(client, origin.resolve("/stats"));
            assertEquals(200, response.statusCode());
            var snapshot = JsonMapper.builder().build().readTree(response.body());
            assertEquals(2, snapshot.get("liveKeys").intValue());
            assertEquals(2, snapshot.get("maxChainLength").intValue());
            assertEquals(1, snapshot.get("activelyExpired").longValue());
            assertEquals(404, get(client, origin.resolve("/dashboard-extra")).statusCode());
        }
    }

    @Test void concurrentSweepAndOverwritePreserveTheNewValue() throws Exception {
        var clock = new MutableClock(1000);
        try (var store = new KeyValueStore(3, clock);
             var tasks = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 100; i++) {
                store.put("same", "expired", 1);
                clock.set(clock.millis() + 1);
                var sweep = tasks.submit(store::expireEntries);
                var write = tasks.submit(() -> store.put("same", "fresh"));
                sweep.get(2, TimeUnit.SECONDS);
                write.get(2, TimeUnit.SECONDS);
                assertEquals("fresh", store.get("same").orElseThrow());
            }
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
