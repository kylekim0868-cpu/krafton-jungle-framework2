package minidbms.http;

import minidbms.SqlStatement;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Optional transport to the independently running Mini Redis /keys API. */
final class RedisQueryCache implements AutoCloseable {
    private static final Duration DEADLINE = Duration.ofMillis(250);
    private static final long TTL_MS = 30_000;
    private static final int MAX_REDIS_BODY = 1_048_576;

    private final URI origin;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();
    // A surviving Redis must not serve entries created by a previous DBMS process.
    private final UUID session = UUID.randomUUID();

    RedisQueryCache(URI origin) {
        String host = origin.getHost();
        if (!"http".equals(origin.getScheme()) || host == null
                || !(host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("[::1]"))
                || origin.getPort() < 1 || origin.getPort() > 65535 || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Redis origin must be a loopback HTTP URL with a port");
        }
        this.origin = origin;
        client = HttpClient.newBuilder().connectTimeout(DEADLINE).build();
    }

    String key(SqlStatement.Select select, long revision) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(select));
            return "dbms:" + session + ":" + revision + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    byte[] get(String key) throws IOException {
        var response = send(request(key).GET().build());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("Redis GET failed: " + response.statusCode());
        var wrapper = json.readTree(response.body());
        if (wrapper == null || !wrapper.isObject() || wrapper.size() != 1 || !wrapper.path("value").isString()) {
            throw new IOException("Invalid Redis response");
        }
        String value = wrapper.get("value").stringValue();
        var result = json.readTree(value);
        if (result == null || !result.isObject() || result.size() != 5
                || !"SELECT".equals(result.path("command").asString())
                || !"TABLE_SCAN".equals(result.path("accessPath").asString())
                || result.path("affectedRows").asLong(-1) != 0 || !result.path("insertedId").isNull()
                || !result.path("rows").isArray()) {
            throw new IOException("Invalid cached SELECT result");
        }
        return value.getBytes(UTF_8);
    }

    void put(String key, byte[] result) throws IOException {
        if (result.length >= MAX_REDIS_BODY) return;
        byte[] body = json.writeValueAsBytes(json.createObjectNode()
                .put("value", new String(result, UTF_8)).put("ttlMs", TTL_MS));
        if (body.length > MAX_REDIS_BODY) return;
        var response = send(request(key).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build());
        if (response.statusCode() != 204) throw new IOException("Redis PUT failed: " + response.statusCode());
    }

    private HttpRequest.Builder request(String key) {
        return HttpRequest.newBuilder(origin.resolve("/keys/" + key)).timeout(DEADLINE);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(UTF_8), MAX_REDIS_BODY));
        try {
            // Covers the body as well as connection setup and response headers.
            return pending.get(DEADLINE.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new IOException("Redis request timed out", failure);
        } catch (ExecutionException failure) {
            throw new IOException("Redis request failed", failure.getCause());
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Redis request interrupted", failure);
        }
    }

    @Override public void close() {
        client.shutdownNow();
    }
}
