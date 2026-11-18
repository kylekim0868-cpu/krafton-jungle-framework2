package miniredis.benchmark;

import miniredis.http.MiniRedisHttpServer;
import miniredis.product.*;
import miniredis.store.KeyValueStore;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 두 HTTP 서버를 같은 JVM에서 측정하는 소규모 재현 실험. JMH/운영 성능 주장이 아니다. */
public final class CacheBenchmark {
    public enum Mode { NONE, MISS, HIT }
    public record Result(int round, Mode mode, int warmup, int requests, int concurrency,
                         long originDelayMs, int payloadChars, long originReads, long responseBytes,
                         long cacheFailures, long wallNanos, LatencySummary summary, long[] latenciesNanos) { }
    private record Phase(long[] latencies, long bytes, long wallNanos) { }
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CacheBenchmark() { }

    public static Result run(int round, Mode mode, int warmup, int requests, int concurrency,
                             long originDelayMs, int payloadChars) throws Exception {
        if (warmup <= 0 || warmup > 100_000 || requests <= 0 || requests > 100_000
                || concurrency <= 0 || concurrency > 64 || payloadChars <= 0 || payloadChars > 100_000
                || originDelayMs < 0 || originDelayMs > 1000) throw new IllegalArgumentException("invalid benchmark parameters");
        var data = new ArrayList<Product>();
        for (int i = 1; i <= requests; i++) data.add(new Product(i, "x".repeat(payloadChars)));
        var origin = new InMemoryProductOrigin(data, originDelayMs);
        var store = new KeyValueStore(requests);
        try (var redis = new MiniRedisHttpServer(new InetSocketAddress("127.0.0.1", 0), store)) {
            redis.start();
            try (var cache = new HttpKeyCache(URI.create("http://127.0.0.1:" + redis.port()), Duration.ofSeconds(5));
                 var workers = Executors.newFixedThreadPool(concurrency)) {
                var service = new ProductService(origin, mode == Mode.NONE ? null : cache, 3_600_000);
                try (var products = new ProductHttpServer(new InetSocketAddress("127.0.0.1", 0), service, origin);
                     var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                    products.start();
                    URI base = URI.create("http://127.0.0.1:" + products.port());
                    if (mode == Mode.HIT) for (int id = 1; id <= requests; id++) service.get(id);
                    exercise(base, client, workers, warmup, requests, concurrency, payloadChars);
                    // Cache preparation is outside BOTH per-request latency and measured wall time.
                    if (mode == Mode.MISS) for (int id = 1; id <= requests; id++) cache.delete(id);
                    long beforeReads = origin.readCount();
                    long beforeFailures = service.cacheFailureCount();
                    var phase = exercise(base, client, workers, requests, requests, concurrency, payloadChars);
                    long reads = origin.readCount() - beforeReads;
                    long failures = service.cacheFailureCount() - beforeFailures;
                    if (reads != (mode == Mode.HIT ? 0 : requests) || failures != 0) {
                        throw new IllegalStateException("scenario did not produce requested hit/miss pattern");
                    }
                    return new Result(round, mode, warmup, requests, concurrency, originDelayMs, payloadChars,
                            reads, phase.bytes, failures, phase.wallNanos,
                            LatencySummary.of(phase.latencies, phase.wallNanos), phase.latencies);
                }
            }
        }
    }

    private static Phase exercise(URI base, HttpClient client, ExecutorService workers, int count,
                                  int keys, int concurrency, int payloadChars) throws Exception {
        long[] latencies = new long[count];
        var next = new AtomicInteger();
        var bytes = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int w = 0; w < concurrency; w++) futures.add(workers.submit(() -> {
            for (int index; (index = next.getAndIncrement()) < count;) {
                int id = index % keys + 1;
                var request = HttpRequest.newBuilder(base.resolve("/products/" + id)).timeout(Duration.ofSeconds(5)).GET().build();
                long began = System.nanoTime();
                var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<byte[]> response;
                try {
                    response = pending.get(6, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    pending.cancel(true);
                    throw new IllegalStateException("benchmark HTTP failed", failure);
                }
                latencies[index] = System.nanoTime() - began;
                var product = JSON.readValue(response.body(), Product.class);
                if (response.statusCode() != 200 || product.id() != id
                        || !product.name().equals("x".repeat(payloadChars))) {
                    throw new IllegalStateException("benchmark response differs");
                }
                bytes.addAndGet(response.body().length);
            }
        }));
        for (var future : futures) future.get(120, TimeUnit.SECONDS);
        return new Phase(latencies, bytes.get(), System.nanoTime() - start);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException(
                "Usage: CacheBenchmark <warmup> <requests> <concurrency> <originDelayMs> <payloadChars> <rounds>");
        int rounds = Integer.parseInt(args[5]);
        if (rounds <= 0 || rounds > 20) throw new IllegalArgumentException("rounds must be 1..20");
        System.out.println(JSON.writeValueAsString(JSON.createObjectNode()
                .put("java", System.getProperty("java.runtime.version"))
                .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .put("arch", System.getProperty("os.arch")).put("processors", Runtime.getRuntime().availableProcessors())
                .put("topology", "single JVM, two loopback HTTP servers, client-observed response latency")));
        for (int round = 0; round < rounds; round++) {
            for (int offset = 0; offset < Mode.values().length; offset++) {
                Mode mode = Mode.values()[(round + offset) % Mode.values().length];
                System.out.println(JSON.writeValueAsString(run(round + 1, mode,
                        Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                        Long.parseLong(args[3]), Integer.parseInt(args[4]))));
            }
        }
    }
}
