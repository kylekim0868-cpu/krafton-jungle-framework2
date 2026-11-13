package miniredis;

import miniredis.http.MiniRedisHttpServer;
import miniredis.store.KeyValueStore;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;

public final class Main {
    private static final System.Logger LOG = System.getLogger(Main.class.getName());
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: miniredis.Main <port> <expectedEntries> [aofPath]");
        }
        int port = Integer.parseInt(args[0]);
        int expectedEntries = Integer.parseInt(args[1]);
        var address = new InetSocketAddress("127.0.0.1", port);
        var store = args.length == 3 ? KeyValueStore.openPersistent(expectedEntries, Clock.systemUTC(), Path.of(args[2]))
                : new KeyValueStore(expectedEntries);
        var server = new MiniRedisHttpServer(address, store);
        try {
            server.start();
        } catch (Exception | Error failure) {
            try { server.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            try { store.close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } finally {
                try { store.close(); } catch (java.io.IOException failure) {
                    LOG.log(System.Logger.Level.ERROR, "Failed to close persistent store", failure);
                }
            }
        }, "mini-redis-shutdown"));
        System.out.println("Mini Redis listening on http://127.0.0.1:" + server.port());
    }
}
