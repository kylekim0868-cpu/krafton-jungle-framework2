package miniredis.product;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** loopback 예제 상품 API. /stats는 호출 수 검증용이며 운영 API가 아니다. */
public final class ProductHttpServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ProductHttpServer.class.getName());
    private final InetSocketAddress address;
    private final ProductService service;
    private final InMemoryProductOrigin origin;
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(4);
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
    private HttpServer server;
    private boolean closed;

    public ProductHttpServer(InetSocketAddress address, ProductService service, InMemoryProductOrigin origin) {
        this.address = Objects.requireNonNull(address);
        this.service = Objects.requireNonNull(service);
        this.origin = Objects.requireNonNull(origin);
    }

    public synchronized void start() throws IOException {
        if (closed || server != null) throw new IllegalStateException("server cannot start");
        server = HttpServer.create(address, 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    public synchronized int port() {
        if (server == null) throw new IllegalStateException("server not started");
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (IOException originFailure) {
                LOG.log(System.Logger.Level.ERROR, "Product request I/O failed", originFailure);
                if (exchange.getResponseCode() == -1) {
                    respond(exchange, 503, "{\"error\":\"ORIGIN_UNAVAILABLE\"}");
                } else {
                    throw originFailure;
                }
            } catch (RuntimeException unexpected) {
                LOG.log(System.Logger.Level.ERROR, "Product request failed", unexpected);
                if (exchange.getResponseCode() == -1) {
                    respond(exchange, 500, "{\"error\":\"INTERNAL_ERROR\"}");
                } else {
                    throw unexpected;
                }
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (exchange.getRequestURI().getRawQuery() != null) {
            respond(exchange, 400, "");
            return;
        }
        if (path.equals("/stats") && exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 200, json.writeValueAsString(json.createObjectNode()
                    .put("originReads", origin.readCount()).put("cacheFailures", service.cacheFailureCount())));
            return;
        }
        if (!path.matches("/products/[1-9][0-9]*")) { respond(exchange, 404, ""); return; }
        int id;
        try {
            id = Integer.parseInt(path.substring("/products/".length()));
        } catch (NumberFormatException invalid) { respond(exchange, 400, ""); return; }
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                var product = service.get(id);
                respond(exchange, product.isPresent() ? 200 : 404,
                        product.isPresent() ? json.writeValueAsString(product.orElseThrow()) : "");
            }
            case "PUT" -> {
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                    respond(exchange, 415, ""); return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
                if (body.length > 1_048_576) { respond(exchange, 413, ""); return; }
                String name;
                try {
                    var root = json.readTree(body);
                    if (root == null || !root.isObject() || root.size() != 1 || !root.has("name")
                            || !root.get("name").isString()) { respond(exchange, 400, ""); return; }
                    name = root.get("name").stringValue();
                } catch (JacksonException invalid) { respond(exchange, 400, ""); return; }
                var product = service.update(id, name);
                respond(exchange, product.isPresent() ? 204 : 404, "");
            }
            default -> {
                exchange.getResponseHeaders().set("Allow", "GET, PUT");
                respond(exchange, 405, "");
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body.isEmpty()) { exchange.sendResponseHeaders(status, -1); return; }
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (server != null) server.stop(0);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("product workers still running");
            }
        } catch (InterruptedException failure) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
