package miniredis.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AofBenchmarkTest {
    @TempDir Path directory;
    @Test void comparesTheSameWarmupAndPayloadAndCleansOnlyOwnedScratchFiles() throws Exception {
        Path keep = Files.writeString(directory.resolve("keep.txt"), "untouched");
        var memory = AofBenchmark.run(directory, false, 1, 3, 10, 64);
        var durable = AofBenchmark.run(directory, true, 1, 3, 10, 64);
        assertEquals(10, memory.latenciesNanos().length);
        assertEquals(10, durable.latenciesNanos().length);
        assertEquals(0, memory.logBytes()); assertTrue(durable.logBytes() > 13 * 64);
        assertTrue(memory.summary().p50Ms() > 0); assertTrue(durable.summary().p50Ms() > 0);
        assertEquals("untouched", Files.readString(keep));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }
}
