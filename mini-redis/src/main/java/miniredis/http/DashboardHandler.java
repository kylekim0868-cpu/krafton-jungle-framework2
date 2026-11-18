package miniredis.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import miniredis.store.KeyValueStore;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;

final class DashboardHandler implements HttpHandler {
    private final KeyValueStore store;
    private final byte[] page;
    private final JsonMapper json = JsonMapper.builder().build();

    DashboardHandler(KeyValueStore store) throws IOException {
        this.store = store;
        try (var input = Objects.requireNonNull(
                DashboardHandler.class.getResourceAsStream("/dashboard.html"), "dashboard resource")) {
            page = input.readAllBytes();
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            if (!path.equals("/dashboard") && !path.equals("/stats")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            boolean dashboard = path.equals("/dashboard");
            byte[] response = dashboard ? page : json.writeValueAsBytes(store.stats());
            exchange.getResponseHeaders().set("Content-Type",
                    dashboard ? "text/html; charset=utf-8" : "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }
}
