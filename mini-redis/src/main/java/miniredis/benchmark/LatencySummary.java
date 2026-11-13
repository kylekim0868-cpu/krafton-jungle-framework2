package miniredis.benchmark;

import java.util.Arrays;

/** nearest-rank 백분위. 개별 HTTP 전체 응답 지연은 ns로 수집하고 ms로 보고한다. */
public record LatencySummary(double p50Ms, double p95Ms, double requestsPerSecond) {
    public static LatencySummary of(long[] samples, long wallNanos) {
        if (samples.length == 0 || wallNanos <= 0) throw new IllegalArgumentException("empty measurement");
        long[] sorted = samples.clone();
        for (long sample : sorted) if (sample <= 0) throw new IllegalArgumentException("invalid latency");
        Arrays.sort(sorted);
        return new LatencySummary(percentile(sorted, 0.50) / 1_000_000.0,
                percentile(sorted, 0.95) / 1_000_000.0, samples.length * 1_000_000_000.0 / wallNanos);
    }

    private static long percentile(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1];
    }
}
