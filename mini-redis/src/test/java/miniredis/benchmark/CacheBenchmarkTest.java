package miniredis.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CacheBenchmarkTest {
    @Test void calculatesNearestRankPercentilesAndThroughputWithoutMutatingSamples() {
        long[] samples = {4_000_000, 1_000_000, 3_000_000, 2_000_000};
        var result = LatencySummary.of(samples, 2_000_000_000L);
        assertEquals(2, result.p50Ms());
        assertEquals(4, result.p95Ms());
        assertEquals(2, result.requestsPerSecond());
        assertEquals(4_000_000, samples[0]);
        assertThrows(IllegalArgumentException.class, () -> LatencySummary.of(new long[0], 1));
        assertThrows(IllegalArgumentException.class, () -> LatencySummary.of(new long[]{0}, 1));
        assertThrows(IllegalArgumentException.class, () -> LatencySummary.of(new long[]{1}, 0));
    }
    @ParameterizedTest @EnumSource(CacheBenchmark.Mode.class)
    void measuresRealHttpAndVerifiesOriginReadCounts(CacheBenchmark.Mode mode) throws Exception {
        var result = CacheBenchmark.run(1, mode, 8, 16, 2, 0, 64);
        assertEquals(mode == CacheBenchmark.Mode.HIT ? 0 : 16, result.originReads());
        assertEquals(16, result.latenciesNanos().length);
        assertEquals(0, result.cacheFailures());
        assertTrue(result.responseBytes() > 16 * 64);
        assertTrue(result.summary().p50Ms() > 0);
        assertTrue(result.summary().p95Ms() >= result.summary().p50Ms());
        assertTrue(result.summary().requestsPerSecond() > 0);
    }
    @Test void rejectsInvalidExperimentParameters() {
        assertThrows(IllegalArgumentException.class, () -> CacheBenchmark.run(1, CacheBenchmark.Mode.NONE, 0, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CacheBenchmark.run(1, CacheBenchmark.Mode.NONE, 1, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CacheBenchmark.run(1, CacheBenchmark.Mode.NONE, 1, 1, 0, 0, 1));
    }
}
