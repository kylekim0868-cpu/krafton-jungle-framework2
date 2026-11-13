package miniredis.benchmark;

import miniredis.store.KeyValueStore;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Clock;

/** 단일 스레드 직접 저장소 PUT 비교. HTTP/상품 캐시 실험과 섞지 않는다. */
public final class AofBenchmark {
    public record Result(int round, String mode, int warmup, int writes, int payloadChars,
                         long wallNanos, long logBytes, LatencySummary summary, long[] latenciesNanos) { }
    private AofBenchmark() { }

    public static Result run(Path directory, boolean durable, int round, int warmup, int writes, int payloadChars) throws Exception {
        if (warmup <= 0 || warmup > 100_000 || writes <= 0 || writes > 100_000 || payloadChars <= 0 || payloadChars > 100_000) {
            throw new IllegalArgumentException("invalid experiment size");
        }
        Path file = durable ? Files.createTempFile(directory, "mini-redis-aof-benchmark-", ".aof") : null;
        try {
            try (var store = durable ? KeyValueStore.openPersistent(warmup + writes, Clock.systemUTC(), file)
                    : new KeyValueStore(warmup + writes)) {
                String value = "x".repeat(payloadChars);
                for (int i = 0; i < warmup; i++) store.put("warmup:" + i, value);
                long[] samples = new long[writes];
                long start = System.nanoTime();
                for (int i = 0; i < writes; i++) {
                    String key = "measure:" + i;
                    long began = System.nanoTime();
                    store.put(key, value);
                    samples[i] = System.nanoTime() - began;
                }
                long wall = System.nanoTime() - start;
                if (store.size() != warmup + writes) throw new IllegalStateException("benchmark lost entries");
                for (int i = 0; i < writes; i++) {
                    if (!store.get("measure:" + i).orElseThrow().equals(value)) throw new IllegalStateException("benchmark value mismatch");
                }
                return new Result(round, durable ? "AOF_FORCE_EACH" : "MEMORY", warmup, writes, payloadChars,
                        wall, durable ? Files.size(file) : 0, LatencySummary.of(samples, wall), samples);
            }
        } finally {
            if (file != null) Files.deleteIfExists(file); // only this run's uniquely-created scratch file
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException(
                "Usage: AofBenchmark <existing-directory> <warmup> <writes> <payloadChars> <rounds>");
        Path directory = Path.of(args[0]);
        int rounds = Integer.parseInt(args[4]);
        if (rounds <= 0 || rounds > 20) throw new IllegalArgumentException("rounds must be1..20");
        var json = JsonMapper.builder().build();
        System.out.println(json.writeValueAsString(json.createObjectNode()
                .put("java", System.getProperty("java.runtime.version"))
                .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .put("arch", System.getProperty("os.arch")).put("filesystem", Files.getFileStore(directory).type())
                .put("topology", "single thread, direct store PUT; AOF FileChannel.force(true) after each full frame")));
        for (int round = 0; round < rounds; round++) for (int offset = 0; offset < 2; offset++) {
            boolean durable = (round + offset) % 2 == 1;
            System.out.println(json.writeValueAsString(run(directory, durable, round + 1,
                    Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]))));
        }
    }
}
