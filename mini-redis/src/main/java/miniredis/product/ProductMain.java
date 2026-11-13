package miniredis.product;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;

public final class ProductMain {
    private ProductMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: ProductMain <port> <cacheOrigin|none> <ttlMs> <originDelayMs>");
        int port = Integer.parseInt(args[0]);
        long ttl = Long.parseLong(args[2]);
        long delay = Long.parseLong(args[3]);
        var origin = new InMemoryProductOrigin(List.of(new Product(42, "사과")), delay);
        var cache = args[1].equals("none") ? null : new HttpKeyCache(URI.create(args[1]), Duration.ofMillis(500));
        var service = new ProductService(origin, cache, ttl);
        var server = new ProductHttpServer(new InetSocketAddress("127.0.0.1", port), service, origin);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            if (cache != null) cache.close();
        }, "product-shutdown"));
        server.start();
        System.out.println("Product API listening on http://127.0.0.1:" + server.port());
    }
}
