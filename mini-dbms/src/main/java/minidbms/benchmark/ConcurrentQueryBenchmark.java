package minidbms.benchmark;

import minidbms.Database;
import minidbms.SqlEngine;
import minidbms.SqlStatement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.*;

/** Measures checked SELECTs through Database with simultaneous clients and no HTTP/cache overhead. */
public final class ConcurrentQueryBenchmark {
    private record WorkerResult(long[] timings, long finished) { }

    private ConcurrentQueryBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("usage: ConcurrentQueryBenchmark DIRECTORY LAST_ID scan|index WORKERS WARMUP_PER_WORKER SAMPLES_PER_WORKER");
        }
        Path directory = Path.of(args[0]);
        long lastId = Long.parseLong(args[1]);
        var statement = SqlEngine.parseOne(switch (args[2]) {
            case "scan" -> "SELECT id FROM products WHERE name = 'product-" + lastId + "' LIMIT 1;";
            case "index" -> "SELECT id FROM products WHERE id = " + lastId + ";";
            default -> throw new IllegalArgumentException("workload must be scan or index");
        });
        int workers = Integer.parseInt(args[3]);
        int warmup = Integer.parseInt(args[4]);
        int samples = Integer.parseInt(args[5]);
        if (workers < 1 || warmup < 0 || samples < 1 || lastId < 1) {
            throw new IllegalArgumentException("invalid sample settings");
        }

        long[] latencies = new long[Math.multiplyExact(workers, samples)];
        try (var db = new Database(directory); var executor = Executors.newFixedThreadPool(workers)) {
            if (db.revision() != lastId) throw new IllegalStateException("unexpected fixture row count");
            var start = new CountDownLatch(1);
            var ready = new CountDownLatch(workers);
            var results = new ArrayList<Future<WorkerResult>>();
            long phaseStart;
            try {
                for (int worker = 0; worker < workers; worker++) {
                    results.add(executor.submit(() -> {
                        long[] timings;
                        try {
                            timings = new long[samples];
                            for (int i = 0; i < warmup; i++) check(db, statement, lastId);
                        } finally {
                            ready.countDown();
                        }
                        start.await();
                        for (int i = 0; i < samples; i++) {
                            long requestStart = System.nanoTime();
                            check(db, statement, lastId);
                            timings[i] = System.nanoTime() - requestStart;
                        }
                        return new WorkerResult(timings, System.nanoTime());
                    }));
                }
                ready.await();
                phaseStart = System.nanoTime();
            } finally {
                start.countDown();
            }
            int offset = 0;
            long phaseNanos = 0;
            for (var future : results) {
                var result = future.get();
                System.arraycopy(result.timings(), 0, latencies, offset, samples);
                offset += samples;
                phaseNanos = Math.max(phaseNanos, result.finished() - phaseStart);
            }
            Arrays.sort(latencies);
            double p95 = latencies[(int) Math.ceil(latencies.length * 0.95) - 1] / 1_000_000.0;
            System.out.printf(Locale.ROOT, "mode=%s workers=%d samples=%d throughput_qps=%.2f p95_ms=%.4f%n",
                    args[2], workers, latencies.length, latencies.length * 1_000_000_000.0 / phaseNanos, p95);
        }
    }

    private static void check(Database db, SqlStatement statement, long expectedId) throws Exception {
        var rows = db.execute(statement).rows();
        if (rows.size() != 1 || !Long.valueOf(expectedId).equals(rows.getFirst().get("id"))) {
            throw new IllegalStateException("unexpected query result");
        }
    }
}
