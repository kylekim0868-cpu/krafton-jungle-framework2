package miniredis;

import miniredis.http.MiniRedisHttpServer;
import miniredis.store.KeyValueStore;

import java.net.InetSocketAddress;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: miniredis.Main <port> <expectedEntries>");
        }
        int port = Integer.parseInt(args[0]);
        int expectedEntries = Integer.parseInt(args[1]);
        var server = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", port), new KeyValueStore(expectedEntries));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mini-redis-shutdown"));
        server.start();
        System.out.println("Mini Redis listening on http://127.0.0.1:" + server.port());
    }
}
