package miniredis.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import miniredis.store.KeyValueStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

import static java.nio.charset.StandardCharsets.UTF_8;

/** HTTP/JSON 계약만 처리한다. 동시성·TTL은 저장소에서 처리한다. */
final class KeysHandler implements HttpHandler {
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final String PREFIX = "/keys/";
    private static final System.Logger LOG = System.getLogger(KeysHandler.class.getName());
    private final KeyValueStore store;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    KeysHandler(KeyValueStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.ERROR, "HTTP request failed", failure);
                if (exchange.getResponseCode() == -1) {
                    sendError(exchange, 500, "INTERNAL_ERROR");
                } else {
                    throw failure;
                }
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        if (!rawPath.startsWith(PREFIX)) {
            sendEmpty(exchange, 404);
            return;
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            sendError(exchange, 400, "INVALID_KEY");
            return;
        }

        String key;
        try {
            key = decodeKey(rawPath.substring(PREFIX.length()));
        } catch (IllegalArgumentException invalidKey) {
            sendError(exchange, 400, "INVALID_KEY");
            return;
        }

        switch (exchange.getRequestMethod()) {
            case "PUT" -> put(exchange, key);
            case "GET" -> {
                var value = store.get(key);
                if (value.isPresent()) {
                    sendJson(exchange, 200, json.createObjectNode().put("value", value.orElseThrow()));
                } else {
                    sendEmpty(exchange, 404);
                }
            }
            case "DELETE" -> {
                store.delete(key);
                sendEmpty(exchange, 204);
            }
            default -> {
                exchange.getResponseHeaders().set("Allow", "PUT, GET, DELETE");
                sendError(exchange, 405, "METHOD_NOT_ALLOWED");
            }
        }
    }

    private void put(HttpExchange exchange, String key) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            sendError(exchange, 413, "REQUEST_TOO_LARGE");
            return;
        }

        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException invalidJson) {
            sendError(exchange, 400, "INVALID_REQUEST");
            return;
        }
        if (root == null || !root.isObject()) {
            sendError(exchange, 400, "INVALID_REQUEST");
            return;
        }
        boolean hasTtl = root.has("ttlMs");
        if (root.size() != (hasTtl ? 2 : 1) || !root.has("value") || !root.get("value").isString()) {
            sendError(exchange, 400, "INVALID_REQUEST");
            return;
        }
        if (hasTtl && (!root.get("ttlMs").isIntegralNumber()
                || !root.get("ttlMs").canConvertToLong() || root.get("ttlMs").longValue() <= 0)) {
            sendError(exchange, 400, "INVALID_TTL");
            return;
        }

        // 전체 검증이 끝난 뒤 저장한다. 만료 시각 오버플로도 기존 값을 바꾸지 않는다.
        try {
            if (hasTtl) {
                store.put(key, root.get("value").stringValue(), root.get("ttlMs").longValue());
            } else {
                store.put(key, root.get("value").stringValue());
            }
        } catch (IllegalArgumentException invalidTtl) {
            sendError(exchange, 400, "INVALID_TTL");
            return;
        }
        sendEmpty(exchange, 204);
    }

    private static String decodeKey(String rawKey) {
        byte[] raw = rawKey.getBytes(UTF_8);
        byte[] decoded = new byte[raw.length];
        int length = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%') {
                if (i + 2 >= raw.length) {
                    throw new IllegalArgumentException("incomplete key escape");
                }
                int high = Character.digit((char) raw[++i], 16);
                int low = Character.digit((char) raw[++i], 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("invalid key escape");
                }
                decoded[length++] = (byte) ((high << 4) | low);
            } else {
                decoded[length++] = raw[i];
            }
        }
        String key;
        try {
            key = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded, 0, length)).toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("invalid UTF-8 key", malformedUtf8);
        }
        // URI 경로이므로 '+'는 공백으로 변환하지 않고, 디코딩도 정확히 한 번만 한다.
        if (key.isEmpty() || key.indexOf('/') >= 0 || key.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("key must be a nonempty path segment");
        }
        return key;
    }

    private void sendError(HttpExchange exchange, int status, String error) throws IOException {
        exchange.getResponseHeaders().set("Connection", "close");
        sendJson(exchange, status, json.createObjectNode().put("error", error));
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}
