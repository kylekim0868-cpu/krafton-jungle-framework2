package minidbms.benchmark;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class IndexBenchmarkTest {
    @TempDir Path directory;
    @Test void exercisesBothRealAccessPathsAndRebuildsTheIndex() throws Exception {
        var result=IndexBenchmark.run(directory,1000,8,2);
        assertEquals(1000,result.structure().entries()); assertTrue(result.structure().height()>1);
        assertEquals(8,result.idNanos().length); assertEquals(8,result.scanNanos().length);
        assertEquals(1,result.keys()[0]); assertEquals(1000,result.keys()[7]);
        assertTrue(result.fileBytes()>1000*20); assertTrue(result.idIndex().p50Ms()>0);
        assertThrows(IllegalArgumentException.class,()->IndexBenchmark.run(directory,1000,8,2));
    }
    @Test void calculatesNearestRankAndActiveOperationThroughput() {
        long[] samples={4_000_000,1_000_000,3_000_000,2_000_000};
        var summary=IndexBenchmark.summary(samples);
        assertEquals(2,summary.p50Ms()); assertEquals(4,summary.p95Ms()); assertEquals(400,summary.operationsPerSecond());
        assertEquals(4_000_000,samples[0]);
        assertThrows(IllegalArgumentException.class,()->IndexBenchmark.summary(new long[]{0}));
        assertThrows(IllegalArgumentException.class,()->IndexBenchmark.summary(new long[0]));
    }
}
