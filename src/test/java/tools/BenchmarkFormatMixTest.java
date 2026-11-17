package tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mixed protocol-v2 and legacy scenario records are an error, not a silent fallback. */
class BenchmarkFormatMixTest {
    @TempDir
    Path tempDir;

    @Test
    void schemaRuleForRecordSets() {
        assertEquals("legacy", BenchmarkLogger.outputSchema(List.of()));
        assertEquals("legacy", BenchmarkLogger.outputSchema(List.of("legacy", "legacy")));
        assertEquals("phase1-benchmark-v2",
                BenchmarkLogger.outputSchema(List.of("phase1-benchmark-v2", "phase1-benchmark-v2")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BenchmarkLogger.outputSchema(List.of("phase1-benchmark-v2", "legacy")));
        assertTrue(e.getMessage().contains("Mixed benchmark result formats"));
    }

    @Test
    void mixedRunWritesNoResultFile() {
        BenchmarkLogger logger = new BenchmarkLogger();
        logger.beginScenarioV2(1, 1, "ql_true", "", new Object[]{"#Z1Light"}, new Object[]{false});
        logger.endScenarioV2(true, 0, 1.0);
        logger.beginScenario(2, 1, "ql_true", "");
        logger.endScenario(false, 3, 2.0);
        Path out = tempDir.resolve("benchmark_results_ql_true.csv");
        assertThrows(IllegalStateException.class, () -> logger.saveBenchmarkResults(out.toString()));
        assertFalse(Files.exists(out));
    }

    @Test
    void legacyCloseOfAV2ScenarioIsAnError() {
        BenchmarkLogger logger = new BenchmarkLogger();
        logger.beginScenarioV2(4, 2, "ql_false", "", new Object[]{"#Z1Light"}, new Object[]{false});
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> logger.endScenario(false, 20, 3.0));
        assertTrue(e.getMessage().contains("endScenarioV2"));
    }

    @Test
    void unmixedRunsKeepTheirFormats() throws Exception {
        BenchmarkLogger v2 = new BenchmarkLogger();
        v2.beginScenarioV2(1, 1, "ql_true", "", new Object[]{"#Z1Light"}, new Object[]{false});
        v2.endScenarioV2(true, 0, 17.0);
        v2.beginScenarioV2(2, 1, "ql_true", "", new Object[]{"#Z1Light"}, new Object[]{true});
        v2.endScenarioV2(false, 20, 18.0);
        Path v2File = tempDir.resolve("v2.csv");
        v2.saveBenchmarkResults(v2File.toString());
        List<String> lines = Files.readAllLines(v2File);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("ScenarioId,RunId,AgentType,MetricSchema,"));
        assertEquals(18, lines.get(0).split(",").length);

        BenchmarkLogger legacy = new BenchmarkLogger();
        legacy.beginScenario(1, 1, "rule_based", "");
        legacy.endScenario(true, 0, 17.0);
        Path legacyFile = tempDir.resolve("legacy.csv");
        legacy.saveBenchmarkResults(legacyFile.toString());
        assertEquals(16, Files.readAllLines(legacyFile).get(0).split(",").length);
    }
}
