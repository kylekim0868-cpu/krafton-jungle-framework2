package minidbms.benchmark;

import minidbms.Database;
import minidbms.SqlEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/** Measures open-to-ready elapsed time with a warm JVM and checks recovery after each sample. */
public final class RecoveryBenchmark {
    private RecoveryBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException("usage: RecoveryBenchmark DIRECTORY ROWS WARMUP SAMPLES [CHECKPOINT_TEMPLATE]");
        }
        Path directory = Path.of(args[0]);
        long rows = Long.parseLong(args[1]);
        int warmup = Integer.parseInt(args[2]);
        int samples = Integer.parseInt(args[3]);
        if (rows < 1 || warmup < 0 || samples < 1) throw new IllegalArgumentException("invalid sample settings");
        Path checkpoint = directory.resolve("products.index");
        Path template = args.length == 5 ? Path.of(args[4]) : null;
        long[] elapsed = new long[samples];
        for (int i = -warmup; i < samples; i++) {
            if (template == null) Files.deleteIfExists(checkpoint);
            else Files.copy(template, checkpoint, StandardCopyOption.REPLACE_EXISTING);
            long start = System.nanoTime();
            try (var db = new Database(directory)) {
                long openNanos = System.nanoTime() - start;
                long lastId = (Long) new SqlEngine(db).execute("SELECT id FROM products WHERE id = " + rows)
                        .getFirst().rows().getFirst().get("id");
                if (db.revision() != rows || lastId != rows) throw new IllegalStateException("unexpected recovery result");
                if (i >= 0) elapsed[i] = openNanos;
            }
        }
        Arrays.sort(elapsed);
        System.out.printf(Locale.ROOT, "rows=%d warmup=%d samples=%d p50_ms=%.3f p95_ms=%.3f%n",
                rows, warmup, samples, percentile(elapsed, 0.50), percentile(elapsed, 0.95));
    }

    private static double percentile(long[] sortedNanos, double fraction) {
        return sortedNanos[(int) Math.ceil(sortedNanos.length * fraction) - 1] / 1_000_000.0;
    }
}
