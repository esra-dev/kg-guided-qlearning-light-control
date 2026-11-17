package tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protects the bookkeeping of the corrected learning-experiment protocol
 * ("protocol v2") of the first experiment:
 * <ul>
 *   <li>{@link ScenarioCatalog}: scenarios are scheduled in file order by their
 *       declared ids (the ids need not be contiguous); duplicate, missing or
 *       unknown ids are rejected;</li>
 *   <li>{@link FirstGoalPresentationTracker}: terminal starts and never-solved
 *       (censored) scenarios are counted as specified;</li>
 *   <li>the random action stream is paired across the two arms (with and without
 *       knowledge) so that they differ only in the knowledge;</li>
 *   <li>{@link Phase1PolicyEnergy} and the energy and cycling counters of
 *       {@code BenchmarkLogger} are deterministic, and the old and new benchmark
 *       file layouts stay distinguishable.</li>
 * </ul>
 * Some tests read the committed scenario files under {@code benchmark/} and need
 * the project root as working directory (Gradle's default).
 */
class Phase1ProtocolV2Test {
    @TempDir
    Path tempDir;

    /** Ids 1, 5, 16 keep their file order; position 1 holds id 5. */
    @Test
    void nonContiguousIdsAreScheduledByFilePosition() throws Exception {
        Path file = Files.createTempFile("phase1-scenarios", ".json");
        try {
            Files.writeString(file, "[{\"id\":1},{\"id\":5},{\"id\":16}]");
            ScenarioCatalog catalog = ScenarioCatalog.load(file);
            assertEquals(List.of(1, 5, 16), catalog.orderedIds());
            assertEquals(5, catalog.idAt(1));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A duplicate id, a row without id, and a lookup of an unknown id all fail. */
    @Test
    void duplicateAndMissingIdsAreFatal() throws Exception {
        Path duplicate = Files.createTempFile("phase1-duplicate", ".json");
        Path missing = Files.createTempFile("phase1-missing", ".json");
        try {
            Files.writeString(duplicate, "[{\"id\":2},{\"id\":2}]");
            Files.writeString(missing,
                    "[{\"id\":1},{\"description\":\"missing id after a valid row\"}]");
            assertThrows(Exception.class, () -> ScenarioCatalog.load(duplicate));
            assertThrows(Exception.class, () -> ScenarioCatalog.load(missing));
            Path valid = Files.createTempFile("phase1-valid", ".json");
            try {
                Files.writeString(valid, "[{\"id\":2}]");
                assertThrows(IllegalArgumentException.class,
                        () -> ScenarioCatalog.load(valid).scenarioById(3));
            } finally {
                Files.deleteIfExists(valid);
            }
        } finally {
            Files.deleteIfExists(duplicate);
            Files.deleteIfExists(missing);
        }
    }

    /** The committed training schedules of lab1, lab2 and lab3 load with exactly the expected ids. */
    @Test
    void allThreePhase1SchedulesPreserveDeclaredNonContiguousIds() throws Exception {
        assertEquals(List.of(1, 2, 3, 4, 6, 7),
                ScenarioCatalog.load(Path.of("benchmark/train_scenarios_lab1.json")).orderedIds());
        assertEquals(List.of(1, 2, 4, 5, 6, 7, 9, 10, 12, 13, 16),
                ScenarioCatalog.load(Path.of("benchmark/train_scenarios_lab2.json")).orderedIds());
        assertEquals(List.of(1, 2, 5, 6, 7, 9, 10, 11, 13, 16),
                ScenarioCatalog.load(Path.of("benchmark/train_scenarios_lab3.json")).orderedIds());
    }

    /** A solved scenario reports its first success; an unsolved one is censored at presentations + 1. */
    @Test
    void trackerIncludesTerminalStartsAndCensoredScenarios() {
        FirstGoalPresentationTracker tracker = new FirstGoalPresentationTracker();
        tracker.configure(List.of(1, 5));
        tracker.begin(1, true);
        tracker.end(true);
        tracker.begin(5, false);
        tracker.end(false);
        tracker.begin(5, false);
        tracker.end(false);

        var records = tracker.records();
        assertEquals(1, records.get(0).firstSuccessPresentation);
        assertEquals(1, records.get(0).terminalAtStartPresentations);
        assertFalse(records.get(0).censored());
        assertTrue(records.get(1).censored());
        assertEquals(3, records.get(1).analysisValue());
    }

    /** With the corrected protocol both arms get the same action seed; with the legacy one they differ. */
    @Test
    void phase1V2PairsRngSeedsAcrossArms() {
        assertEquals(QLearner.computeActionSeed(7, true, "phase1-v2"),
                QLearner.computeActionSeed(7, false, "phase1-v2"));
        assertNotEquals(QLearner.computeActionSeed(7, true, "legacy"),
                QLearner.computeActionSeed(7, false, "legacy"));
    }

    /** Default policy energy: lamp 1, spotlight 2, blind 0; the same input gives the same value. */
    @Test
    void policyEnergyIsDeterministicForPhase1Actuators() {
        Object[] keys = {"#Z1Light", "#Z2Light", "#Z1Blinds", "#Spotlight"};
        assertEquals(0.0, Phase1PolicyEnergy.instantaneousCost(keys,
                new Object[]{false, false, true, false}));
        assertEquals(1.0, Phase1PolicyEnergy.instantaneousCost(keys,
                new Object[]{true, false, false, false}));
        assertEquals(4.0, Phase1PolicyEnergy.instantaneousCost(keys,
                new Object[]{true, true, false, true}));
        assertEquals(4.0, Phase1PolicyEnergy.instantaneousCost(keys,
                new Object[]{true, true, false, true}));
    }

    /** Switching an actuator relative to the initial state counts as a change; an unchanged state does not. */
    @Test
    void initialActuatorStateMakesFirstActionCountForCycling() {
        Object[] keys = {"#Z1Light", "#Z1Blinds"};
        assertEquals(1, BenchmarkLogger.countReversals(
                keys, new Object[]{false, false},
                keys, new Object[]{true, false}));
        assertEquals(0, BenchmarkLogger.countReversals(
                keys, new Object[]{true, false},
                keys, new Object[]{true, false}));
    }

    /** BenchmarkLogger energy and cycling counters over a do-nothing step and a spotlight switch-on. */
    @Test
    void benchmarkEnergyCoversDoNothingBlindsAndZeroStepTerminalScenarios() {
        BenchmarkLogger logger = new BenchmarkLogger();
        Object[] keys = {"#Z1Light", "#Z1Blinds", "#Spotlight"};
        Object[] initial = {true, true, false};
        logger.beginScenarioV2(1, 1, "ql_true", "terminal", keys, initial);
        assertEquals(0.0, logger.currentPolicyEnergyCostForTest());

        // A do-nothing decision samples the still-active lamp once; the blind
        // remains zero-cost. Turning on the spotlight adds two on the next step.
        logger.recordStepV2(0, new Object[]{1}, new Object[]{2}, "none", true,
                keys, initial, false);
        logger.recordStepV2(1, new Object[]{2}, new Object[]{2}, "Spotlight=ON", false,
                keys, new Object[]{true, true, true}, false);
        assertEquals(4.0, logger.currentPolicyEnergyCostForTest());
        assertEquals(1, logger.currentCyclingCountForTest());
    }

    /** Two runs over the same sequence of actuator states sum to the same policy energy. */
    @Test
    void identicalActionStateTracesHaveIdenticalPolicyEnergy() {
        Object[] keys = {"#Z1Light", "#Z2Light", "#Z1Blinds", "#Spotlight"};
        Object[][] trace = {
                {false, false, true, false},
                {true, false, true, false},
                {true, false, false, true}
        };
        double first = 0.0;
        double second = 0.0;
        for (Object[] state : trace) {
            first += Phase1PolicyEnergy.instantaneousCost(keys, state);
            second += Phase1PolicyEnergy.instantaneousCost(keys, state);
        }
        assertEquals(first, second);
        assertEquals(4.0, first);
    }

    /** The new benchmark CSV header has the policy-energy columns; the legacy header does not. */
    @Test
    void benchmarkV2AndLegacySchemasRemainExplicitAndCompatible() throws Exception {
        BenchmarkLogger v2 = new BenchmarkLogger();
        v2.beginScenarioV2(1, 1, "ql_true", "", new Object[]{"#Z1Light"},
                new Object[]{false});
        v2.endScenarioV2(true, 0, 17.0);
        Path v2File = tempDir.resolve("v2.csv");
        v2.saveBenchmarkResults(v2File.toString());
        String v2Header = Files.readAllLines(v2File).get(0);
        assertTrue(v2Header.contains("PolicyEnergyCost"));
        assertTrue(v2Header.contains("LegacyWallClockTotalEnergyCost"));

        BenchmarkLogger legacy = new BenchmarkLogger();
        legacy.beginScenario(1, 1, "ql_true", "");
        legacy.endScenario(true, 0, 17.0);
        Path legacyFile = tempDir.resolve("legacy.csv");
        legacy.saveBenchmarkResults(legacyFile.toString());
        String legacyHeader = Files.readAllLines(legacyFile).get(0);
        assertTrue(legacyHeader.contains("TotalEnergyCost"));
        assertFalse(legacyHeader.contains("PolicyEnergyCost"));
    }
}
