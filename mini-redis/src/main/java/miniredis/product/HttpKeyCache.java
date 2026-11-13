package miniredis.product;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** 상품 API가 별도 프로세스의 Mini Redis에 접근하는 HTTP 경계. */
public final class HttpKeyCache implements AutoCloseable {
    private final URI baseUri;
    private final HttpClient client;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();

    public HttpKeyCache(URI baseUri, Duration timeout) {
        if (!"http".equals(baseUri.getScheme()) || baseUri.getHost() == null
                || baseUri.getRawUserInfo() != null || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null || !(baseUri.getPath().isEmpty() || baseUri.getPath().equals("/"))
                || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("cache requires an HTTP origin and a positive timeout");
        }
        this.baseUri = baseUri;
        this.timeout = timeout;
        client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Optional<String> get(int id) throws IOException {
        var response = send(request(id).GET().build());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IOException("cache GET status " + response.statusCode());
        try {
            var root = json.readTree(response.body());
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("value")
                    || !root.get("value").isString()) throw new IOException("invalid cache response");
            return Optional.of(root.get("value").stringValue());
        } catch (tools.jackson.core.JacksonException failure) {
            throw new IOException("invalid cache JSON", failure);
        }
    }

    public void put(int id, String value, long ttlMs) throws IOException {
        var body = json.createObjectNode().put("value", value).put("ttlMs", ttlMs);
        expect204(send(request(id).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()));
    }

    public void delete(int id) throws IOException {
        expect204(send(request(id).DELETE().build()));
    }

    private HttpRequest.Builder request(int id) {
        if (id <= 0) throw new IllegalArgumentException("id must be positive");
        return HttpRequest.newBuilder(baseUri.resolve("/keys/product:" + id)).timeout(timeout);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8), 2_097_152));
        try {
            // JDK25 request.timeout does not cover a body stalled after response headers.
            return pending.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException failure) {
            pending.cancel(true);
            throw new IOException("cache response deadline exceeded", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("cache request failed", failure.getCause());
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("cache request interrupted", failure);
        }
    }

    private static void expect204(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 204) throw new IOException("cache write status " + response.statusCode());
    }

    @Override public void close() {
        client.shutdownNow();
        try {
            if (!client.awaitTermination(Duration.ofSeconds(3))) {
                throw new IllegalStateException("cache client did not terminate");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
