package miniredis.http;

import miniredis.store.KeyValueStore;
import miniredis.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class KeyHttpApiTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private MiniRedisHttpServer server;
    private HttpClient client;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        server = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", 0), new KeyValueStore(3));
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.port());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void stopServer() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    @Test
    void putGetDeleteGetUsesTheActualHttpTransport() throws Exception {
        var put = send("PUT", "/keys/product:42", "{\"value\":\"상품 응답\"}");
        assertEmptyResponse(204, put);

        var get = send("GET", "/keys/product:42", null);
        assertEquals(200, get.statusCode());
        assertEquals("application/json; charset=utf-8", get.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(JSON.readTree("{\"value\":\"상품 응답\"}"), JSON.readTree(get.body()));

        assertEmptyResponse(204, send("DELETE", "/keys/product:42", null));
        assertEmptyResponse(404, send("GET", "/keys/product:42", null));
    }

    @Test
    void overwritesAnExistingValue() throws Exception {
        assertEmptyResponse(204, send("PUT", "/keys/key", "{\"value\":\"old\"}"));
        assertEmptyResponse(204, send("PUT", "/keys/key", "{\"value\":\"new\"}"));
        assertValue("/keys/key", "new");
    }

    @Test
    void returnsEmptyStringAsAHitRatherThanAMiss() throws Exception {
        assertEmptyResponse(204, send("PUT", "/keys/empty", "{\"value\":\"\"}"));
        assertValue("/keys/empty", "");
        assertEmptyResponse(404, send("GET", "/keys/missing", null));
    }

    @Test
    void deletingAnAbsentKeyIsIdempotent() throws Exception {
        assertEmptyResponse(204, send("DELETE", "/keys/missing", null));
        assertEmptyResponse(204, send("DELETE", "/keys/missing", null));
        assertEmptyResponse(404, send("GET", "/keys/missing", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "{\"value\":}", "{}", "null", "[]", "42",
            "{\"value\":null}", "{\"value\":1}", "{\"value\":true}", "{\"value\":[]}",
            "{\"value\":{}}", "{\"value\":\"new\"} {}", "{\"value\":\"a\",\"value\":\"b\"}",
            "{\"value\":\"new\",\"other\":1}"})
    void rejectsInvalidJsonOrSchemaWithoutChangingTheStore(String body) throws Exception {
        assertEquals(204, send("PUT", "/keys/existing", "{\"value\":\"old\"}").statusCode());

        assertError(400, "INVALID_REQUEST", send("PUT", "/keys/existing", body));
        assertValue("/keys/existing", "old");
        assertError(400, "INVALID_REQUEST", send("PUT", "/keys/new", body));
        assertEmptyResponse(404, send("GET", "/keys/new", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775807", "9223372036854775808", "1.0", "0", "-1", "1.5", "\"30000\"", "null", "true", "{}"})
    void rejectsInvalidTtlWithoutStoringOrOverwriting(String ttl) throws Exception {
        assertEquals(204, send("PUT", "/keys/existing", "{\"value\":\"old\"}").statusCode());
        String body = "{\"value\":\"new\",\"ttlMs\":" + ttl + "}";

        assertError(400, "INVALID_TTL", send("PUT", "/keys/existing", body));
        assertValue("/keys/existing", "old");
        assertError(400, "INVALID_TTL", send("PUT", "/keys/new", body));
        assertEmptyResponse(404, send("GET", "/keys/new", null));
    }

    @Test
    void ttlWorksAtTheExactBoundaryAndOmittingItRemovesTheOldExpiration() throws Exception {
        server.close();
        var clock = new MutableClock(1000);
        server = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", 0), new KeyValueStore(1, clock));
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.port());
        assertEmptyResponse(204, send("PUT", "/keys/key", "{\"value\":\"\",\"ttlMs\":10}"));
        clock.set(1009);
        assertValue("/keys/key", "");
        clock.set(1010);
        assertEmptyResponse(404, send("GET", "/keys/key", null));
        assertEmptyResponse(204, send("PUT", "/keys/key", "{\"value\":\"old\",\"ttlMs\":1}"));
        assertEmptyResponse(204, send("PUT", "/keys/key", "{\"value\":\"permanent\"}"));
        clock.set(2000);
        assertValue("/keys/key", "permanent");
    }

    @Test
    void roundTripsUnicodeAndJsonEscapes() throws Exception {
        String value = "사과 \"특가\"\\경로\n다음 줄\t🍎";
        String body = JSON.writeValueAsString(JSON.createObjectNode().put("value", value));

        assertEmptyResponse(204, send("PUT", "/keys/unicode", body));
        assertValue("/keys/unicode", value);
    }

    @Test
    void decodesKeyOnceAndPreservesPlus() throws Exception {
        assertEquals(204, send("PUT", "/keys/%EC%82%AC%EA%B3%BC%2B42", "{\"value\":\"한글\"}").statusCode());
        assertValue("/keys/%EC%82%AC%EA%B3%BC+42", "한글");

        assertEquals(204, send("PUT", "/keys/a%252Fb", "{\"value\":\"literal\"}").statusCode());
        assertValue("/keys/a%252Fb", "literal");
        assertEmptyResponse(404, send("GET", "/keys/a%25252Fb", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/keys/", "/keys/a/b", "/keys/a%2Fb", "/keys/%00", "/keys/%0A", "/keys/%FF", "/keys/%C0%AF", "/keys/key?ttlMs=30000"})
    void rejectsInvalidKeysAndQueryParameters(String path) throws Exception {
        assertEquals(400, send("PUT", path, "{\"value\":\"not stored\"}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/unknown/key", "/keys", "/keysExtra/key", "/KEYS/key", "/k%65ys/key"})
    void doesNotTreatContextPrefixAsAnExactRoute(String path) throws Exception {
        assertEquals(404, send("GET", path, null).statusCode());
    }

    @Test
    void rejectsUnsupportedMethodsAndKeepsExistingValue() throws Exception {
        assertEquals(204, send("PUT", "/keys/key", "{\"value\":\"old\"}").statusCode());
        var response = send("POST", "/keys/key", "{\"value\":\"new\"}");

        assertError(405, "METHOD_NOT_ALLOWED", response);
        assertEquals("PUT, GET, DELETE", response.headers().firstValue("Allow").orElseThrow());
        assertValue("/keys/key", "old");
    }

    @Test
    void requiresJsonContentType() throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve("/keys/key"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"new\"}", UTF_8)).build();

        assertError(415, "UNSUPPORTED_MEDIA_TYPE", client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8)));
        assertEmptyResponse(404, send("GET", "/keys/key", null));
    }

    @Test
    void rejectsOversizedBodyBeforeParsingOrWriting() throws Exception {
        assertEquals(204, send("PUT", "/keys/key", "{\"value\":\"old\"}").statusCode());
        String body = "{\"value\":\"" + "x".repeat(1_048_576) + "\"}";

        assertError(413, "REQUEST_TOO_LARGE", send("PUT", "/keys/key", body));
        assertValue("/keys/key", "old");
    }

    @Test
    void requestsFromMultipleConnectionsShareTheLockedStore() throws Exception {
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            var request = HttpRequest.newBuilder(baseUri.resolve("/keys/key" + i))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"" + i + "\"}")).build();
            requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString(UTF_8)));
        }
        for (var request : requests) {
            assertEmptyResponse(204, request.get(6, java.util.concurrent.TimeUnit.SECONDS));
        }
        for (int i = 0; i < 40; i++) {
            assertValue("/keys/key" + i, Integer.toString(i));
        }
    }

    @Test
    void closesIdempotentlyAndReleasesThePort() throws Exception {
        int port = server.port();
        assertEquals(404, send("GET", "/keys/missing", null).statusCode());
        server.close();
        server.close();
        assertTrue(server.isTerminated());

        try (var replacement = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", port), new KeyValueStore(0))) {
            replacement.start();
            assertEquals(port, replacement.port());
            assertEquals(404, send("GET", "/keys/missing", null).statusCode());
        }
    }

    @Test
    void closingBeforeStartDoesNotBindOrLeakAWorker() throws Exception {
        // 이미 사용 중인 포트여도 start 전에는 바인딩하지 않아야 한다.
        var unstarted = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", server.port()), new KeyValueStore(0));
        try {
            assertThrows(IllegalStateException.class, unstarted::port);
            unstarted.close();
            assertTrue(unstarted.isTerminated());
            assertThrows(IllegalStateException.class, unstarted::start);
            assertEquals(404, send("GET", "/keys/missing", null).statusCode());
        } finally {
            unstarted.close();
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(5));
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, UTF_8);
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8");
        }
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    private void assertValue(String path, String expected) throws Exception {
        var response = send("GET", path, null);
        assertEquals(200, response.statusCode());
        assertEquals(JSON.createObjectNode().put("value", expected), JSON.readTree(response.body()));
    }

    private static void assertEmptyResponse(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode());
        assertEquals("", response.body());
    }

    private static void assertError(int status, String error, HttpResponse<String> response) {
        assertEquals(status, response.statusCode());
        assertEquals(JSON.createObjectNode().put("error", error), JSON.readTree(response.body()));
    }
}
