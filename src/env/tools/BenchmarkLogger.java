package tools;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

/**
 * CArtAgO artifact that records the test runs ("benchmark") and writes the result
 * files of the test stage.
 *
 * <p><b>Who uses it.</b> Only the test agent
 * {@code src/agt/illuminance_controller_agent_bench.asl} creates it, with
 * {@code makeArtifact("benchlogger", "tools.BenchmarkLogger", [], LogId)}; that agent
 * is started by {@code task_bench.jcm} through the Gradle tasks {@code taskBench}
 * (called by {@code run_full_project.ps1} for the test cells of Phases 1, 1b and 4)
 * and {@code runFullSweep}. The agent runs every test scenario of the lab
 * {@code bench_runs} times in one mode ({@code rule_based}, {@code ql_false} or
 * {@code ql_true}) and calls this artifact once per scenario start, per step and
 * per scenario end. It has no observable properties and reads no configuration:
 * which result format is used is decided by the agent (its belief
 * {@code protocol_version}, taken from the system property
 * {@code phase1.protocolVersion}, default {@code legacy}): with
 * {@code phase1-v2} it calls the protocol-v2 operations ({@link #beginScenarioV2},
 * {@link #recordStepV2}, {@link #endScenarioV2}), otherwise the legacy ones
 * ({@link #beginScenario}, {@link #recordStep}, {@link #endScenario}).
 * "Per step" means once per action: when the agent sees the goal it closes the
 * scenario without recording a step.
 *
 * <p><b>Files written</b> (names chosen by the agent, relative to the working
 * directory; {@code <mode>} is the test mode):
 * <ul>
 *   <li>{@code benchmark_results_<mode>.csv}, one row per scenario run
 *       ({@link #saveBenchmarkResults}; columns listed there). The workflows
 *       {@code phase1.yml} and {@code phase4.yml} upload it.</li>
 *   <li>{@code bench_step_log_<mode>.csv}, one row per step ({@link #saveStepLog}).</li>
 *   <li>{@code benchmark/results/<profile>/<mode>/trace_bench_<mode>.jsonl}, one JSON
 *       object per step, only in the learning modes ({@link #openTraceJsonl},
 *       {@link #recordRichStep}); read by {@code analysis/sweep_report.py}.</li>
 * </ul>
 * The two CSV files are written once, at the end of the run, from records kept in
 * memory; the trace is written and flushed step by step.
 *
 * <p><b>Per-scenario measures</b> (columns of the result file):
 * <ul>
 *   <li>{@code GoalReached}: 1 if the agent saw every zone at its target rank
 *       before the step limit, else 0.</li>
 *   <li>{@code Steps}: the step count the agent passes at the end of the scenario
 *       (the number of actions taken before the goal was seen, or the step limit).</li>
 *   <li>{@code CumIlluminanceDeviation}: sum over the recorded steps and the zones of
 *       |rank - target rank| (lower is better).</li>
 *   <li>{@code WastedSteps}: steps the agent marked as wasted (its test: no zone rank
 *       and no boolean status value changed).</li>
 *   <li>{@code ActuatorCyclingCount}: number of boolean status values that differ
 *       between consecutive snapshots, summed over the scenario
 *       ({@link #countReversals}); every change counts, not only a switch back.</li>
 *   <li>{@code CrossZoneInterferences}: steps the agent marked as interfering with
 *       another zone.</li>
 *   <li>{@code PolicyEnergyCost} (protocol v2 only): sum over the steps of the
 *       instantaneous device cost of the state after the step
 *       ({@link Phase1PolicyEnergy#instantaneousCost}); does not depend on timing.</li>
 *   <li>{@code TotalEnergyCost} (legacy) or {@code LegacyWallClockTotalEnergyCost}
 *       (v2): the simulator's energy counter as passed by the agent at the end; it
 *       depends on how much simulator time has passed, so in v2 it is kept only as a
 *       diagnostic.</li>
 *   <li>{@code SilentlyDropped}, {@code DelayedEffectSteps},
 *       {@code UnmodelledZoneEffect}, {@code ConditionInversions},
 *       {@code TopologyMismatches}: counts of weakness fingerprints
 *       ({@link #recordWeakness}); {@code ComfortDeviation}: summed temperature excess
 *       ({@link #recordComfortDeviation}). They stay 0 for labs whose profile declares
 *       no weakness flags and in the {@code rule_based} mode.</li>
 * </ul>
 */
public class BenchmarkLogger extends Artifact {

    private static final Logger LOGGER = Logger.getLogger(BenchmarkLogger.class.getName());

    // -----------------------------------------------------------------------
    // Per-scenario accumulator fields (reset by beginScenario)
    // -----------------------------------------------------------------------
    private int     currentScenarioId;
    private int     currentRunId;
    private String  currentAgentType;
    private double  cumIlluminanceDeviation = 0.0;
    private int     wastedSteps             = 0;
    private int     actuatorCyclingCount    = 0;
    private int     crossZoneInterferences  = 0;
    private double  policyEnergyCost        = 0.0;
    /** {@link #SCHEMA_LEGACY} or {@link #SCHEMA_V2}; set by the begin operation. */
    private String  metricSchema            = "legacy";

    // Weakness counters, filled by recordWeakness (tag in brackets) and
    // recordComfortDeviation; the tags are produced by QLearner.classifyWeaknesses.
    private int     silentlyDropped         = 0;  // [w4_dropped]
    private int     delayedEffectSteps      = 0;  // [w3_delayed]
    private int     unmodelledZoneEffect    = 0;  // [w1_unmodelled] and [w6_unmodelled]
    private int     conditionInversions     = 0;  // [w2_inversion]
    private int     topologyMismatches      = 0;  // [w5_topology]
    private double  comfortDeviation        = 0.0;// sum of recordComfortDeviation values

    // Previous boolean status snapshot (parallel key/value arrays) for the
    // cycling count; firstStep means "no snapshot yet".
    private Object[] lastActuatorKeys   = new Object[0];
    private Object[] lastActuatorValues = new Object[0];
    private boolean  firstStep          = true;

    // -----------------------------------------------------------------------
    // Completed scenario records
    // -----------------------------------------------------------------------
    /** One finished scenario run: one row of the result file. */
    private static class ScenarioRecord {
        int    scenarioId;
        int    runId;
        String agentType;
        boolean goalReached;
        int    steps;
        double cumIlluminanceDeviation;
        int    wastedSteps;
        int    actuatorCyclingCount;
        int    crossZoneInterferences;
        double totalEnergyCost;
        double policyEnergyCost;
        String metricSchema;
        int    silentlyDropped;
        int    delayedEffectSteps;
        int    unmodelledZoneEffect;
        int    conditionInversions;
        int    topologyMismatches;
        double comfortDeviation;
    }

    /** All finished scenario runs of this artifact, in the order they ended. */
    private final List<ScenarioRecord> records = new ArrayList<>();

    // -----------------------------------------------------------------------
    // CArtAgO operations
    // -----------------------------------------------------------------------

    /**
     * Begin a scenario run in the legacy format: remember the identifiers and set
     * every per-scenario counter to zero. The first {@link #recordStep} afterwards
     * has no previous snapshot, so its switch changes are not counted as cycling.
     *
     * @param scenarioId   the scenario's {@code id} in the test scenario file
     * @param runId        repetition number, 1 to {@code bench_runs}
     * @param agentType    test mode: {@code rule_based}, {@code ql_false} or
     *                     {@code ql_true}
     * @param initialState free text, only logged (the test agent passes
     *                     {@code scenario_id=<id> file=<file>})
     */
    @OPERATION
    public void beginScenario(int scenarioId, int runId, String agentType, String initialState) {
        currentScenarioId         = scenarioId;
        currentRunId              = runId;
        currentAgentType          = agentType;
        cumIlluminanceDeviation   = 0.0;
        wastedSteps               = 0;
        actuatorCyclingCount      = 0;
        crossZoneInterferences    = 0;
        policyEnergyCost          = 0.0;
        metricSchema              = "legacy";
        silentlyDropped           = 0;
        delayedEffectSteps        = 0;
        unmodelledZoneEffect      = 0;
        conditionInversions       = 0;
        topologyMismatches        = 0;
        comfortDeviation          = 0.0;
        lastActuatorKeys          = new Object[0];
        lastActuatorValues        = new Object[0];
        firstStep                 = true;
        LOGGER.info("BenchmarkLogger.beginScenario: id=" + scenarioId
                    + " run=" + runId + " agent=" + agentType
                    + " state=" + initialState);
    }

    /**
     * Begin a scenario run in the protocol-v2 format. As {@link #beginScenario},
     * and in addition the boolean status snapshot of the start situation (read by
     * the agent after the room has settled) becomes the first comparison point, so
     * the switch changes of the first step are counted as cycling. The scenario
     * must be closed with {@link #endScenarioV2}.
     *
     * @param scenarioId     as in {@link #beginScenario}
     * @param runId          as in {@link #beginScenario}
     * @param agentType      as in {@link #beginScenario}
     * @param initialState   as in {@link #beginScenario}
     * @param actuatorKeys   boolean status URIs of the start situation (copied)
     * @param actuatorValues their values, parallel to {@code actuatorKeys} (copied)
     */
    @OPERATION
    public void beginScenarioV2(int scenarioId, int runId, String agentType,
                                String initialState, Object[] actuatorKeys,
                                Object[] actuatorValues) {
        beginScenario(scenarioId, runId, agentType, initialState);
        metricSchema = "phase1-benchmark-v2";
        lastActuatorKeys = actuatorKeys == null ? new Object[0] : actuatorKeys.clone();
        lastActuatorValues = actuatorValues == null ? new Object[0] : actuatorValues.clone();
        firstStep = false;
    }

    /**
     * Record one step of the current scenario: add the rank deviation, count a
     * wasted step and a cross-zone interference if flagged, and count the switch
     * changes against the previous snapshot. The arrays are kept by reference as the
     * next comparison point. In the legacy format the test agent passes the zone
     * ranks from before the action; {@link #recordStepV2} receives those from after
     * it.
     *
     * @param step                  step number in the scenario, from 0 (only logged)
     * @param zoneLevels            zone ranks (numbers), in zone order
     * @param targets               target ranks, parallel to {@code zoneLevels}; extra
     *                              elements of either array are ignored
     * @param actionType            label of the action taken (only logged)
     * @param wasted                true if the agent judged the step wasted
     * @param currActuatorKeys      boolean status URIs after the action
     * @param currActuatorValues    their values, parallel to {@code currActuatorKeys};
     *                              a key that is not in the previous snapshot is not
     *                              counted
     * @param crossZoneInterference true if the agent judged that the action moved
     *                              another zone away from its target (the test agent
     *                              uses rank changes and, in the learning modes, also
     *                              {@code QLearner.checkCrossZoneInterference})
     */
    @OPERATION
    public void recordStep(int step,
                           Object[] zoneLevels,
                           Object[] targets,
                           String   actionType,
                           boolean  wasted,
                           Object[] currActuatorKeys,
                           Object[] currActuatorValues,
                           boolean  crossZoneInterference) {

        // Illuminance deviation: Σ_z |level_z − target_z|
        for (int z = 0; z < zoneLevels.length && z < targets.length; z++) {
            int level  = toInt(zoneLevels[z]);
            int target = toInt(targets[z]);
            cumIlluminanceDeviation += Math.abs(level - target);
        }

        if (wasted) wastedSteps++;
        if (crossZoneInterference) crossZoneInterferences++;

        // Actuator cycling: count reversals relative to previous step's actuator states
        if (!firstStep && lastActuatorKeys.length > 0 && currActuatorKeys.length > 0) {
            actuatorCyclingCount += countReversals(
                    lastActuatorKeys, lastActuatorValues,
                    currActuatorKeys, currActuatorValues);
        }
        // Store current actuator state for next-step comparison
        lastActuatorKeys   = currActuatorKeys;
        lastActuatorValues = currActuatorValues;
        firstStep = false;

        LOGGER.fine("BenchmarkLogger.recordStep: step=" + step
                    + " deviation=" + cumIlluminanceDeviation
                    + " wasted=" + wastedSteps
                    + " cycling=" + actuatorCyclingCount
                    + " cross_zone=" + crossZoneInterferences
                    + " action=" + actionType);
    }

    /**
     * Record one step in the protocol-v2 format: as {@link #recordStep}, and in
     * addition add the instantaneous device cost of the state after the action
     * ({@link Phase1PolicyEnergy#instantaneousCost} of {@code currActuatorKeys} /
     * {@code currActuatorValues}) to {@code PolicyEnergyCost}, so the cost is sampled
     * exactly once per action. The test agent calls it after the room has settled,
     * with the zone ranks from after the action. Parameters as in
     * {@link #recordStep}.
     */
    @OPERATION
    public void recordStepV2(int step,
                             Object[] zoneLevels,
                             Object[] targets,
                             String actionType,
                             boolean wasted,
                             Object[] currActuatorKeys,
                             Object[] currActuatorValues,
                             boolean crossZoneInterference) {
        recordStep(step, zoneLevels, targets, actionType, wasted,
                currActuatorKeys, currActuatorValues, crossZoneInterference);
        policyEnergyCost += Phase1PolicyEnergy.instantaneousCost(
                currActuatorKeys, currActuatorValues);
    }

    /**
     * Return the instantaneous device cost of a status snapshot
     * ({@link Phase1PolicyEnergy#instantaneousCost}), without recording anything. No
     * agent program calls this operation.
     *
     * @param actuatorKeys   boolean status URIs
     * @param actuatorValues their values
     * @param cost           output: the summed weights of the devices that are on
     */
    @OPERATION
    public void readInstantaneousPolicyEnergyCost(
            Object[] actuatorKeys, Object[] actuatorValues,
            OpFeedbackParam<Double> cost) {
        cost.set(Phase1PolicyEnergy.instantaneousCost(actuatorKeys, actuatorValues));
    }

    /** Running {@code PolicyEnergyCost} of the current scenario; for the unit tests. */
    double currentPolicyEnergyCostForTest() {
        return policyEnergyCost;
    }

    /** Running {@code ActuatorCyclingCount} of the current scenario; for the unit tests. */
    int currentCyclingCountForTest() {
        return actuatorCyclingCount;
    }

    /**
     * Close a scenario run begun with {@link #beginScenario} and keep its record for
     * the result file.
     *
     * <p>A scenario begun with {@link #beginScenarioV2} must be closed with
     * {@link #endScenarioV2}; calling this operation for it logs an error and throws
     * {@link IllegalStateException}, which ends the operation, and no record is
     * kept.
     *
     * @param goalReached     true if every zone reached its target rank
     * @param totalSteps      the agent's step count (the test agent passes the number
     *                        of actions taken before it saw the goal, the step limit,
     *                        or the step at which a step failed)
     * @param totalEnergyCost the simulator's energy counter at the end
     *                        ({@code LabEnvironment.readEnergyCost}); column
     *                        {@code TotalEnergyCost}
     */
    @OPERATION
    public void endScenario(boolean goalReached, int totalSteps, double totalEnergyCost) {
        // A scenario begun in the protocol-v2 format must be closed with
        // endScenarioV2; the legacy call there is a programming error in the
        // agent, reported instead of passed over.
        if (SCHEMA_V2.equals(metricSchema)) {
            String msg = "endScenario (legacy) called for scenario " + currentScenarioId
                    + " run " + currentRunId + ", which was begun with beginScenarioV2;"
                    + " a protocol-v2 run must close scenarios with endScenarioV2.";
            LOGGER.severe(msg);
            throw new IllegalStateException(msg);
        }
        commitScenario(goalReached, totalSteps, totalEnergyCost);
    }

    /**
     * Copy the current counters and the given end values into a new
     * {@link ScenarioRecord}, append it to {@link #records} and log a summary line.
     * The counters themselves are reset only by the next begin operation.
     *
     * @param goalReached     goal flag
     * @param totalSteps      step count
     * @param totalEnergyCost simulator energy counter
     */
    private void commitScenario(boolean goalReached, int totalSteps, double totalEnergyCost) {
        ScenarioRecord r = new ScenarioRecord();
        r.scenarioId              = currentScenarioId;
        r.runId                   = currentRunId;
        r.agentType               = currentAgentType;
        r.goalReached             = goalReached;
        r.steps                   = totalSteps;
        r.cumIlluminanceDeviation = cumIlluminanceDeviation;
        r.wastedSteps             = wastedSteps;
        r.actuatorCyclingCount    = actuatorCyclingCount;
        r.crossZoneInterferences  = crossZoneInterferences;
        r.totalEnergyCost         = totalEnergyCost;
        r.policyEnergyCost        = policyEnergyCost;
        r.metricSchema            = metricSchema;
        r.silentlyDropped         = silentlyDropped;
        r.delayedEffectSteps      = delayedEffectSteps;
        r.unmodelledZoneEffect    = unmodelledZoneEffect;
        r.conditionInversions     = conditionInversions;
        r.topologyMismatches      = topologyMismatches;
        r.comfortDeviation        = comfortDeviation;
        records.add(r);
        LOGGER.info("BenchmarkLogger.endScenario: id=" + currentScenarioId
                    + " run=" + currentRunId + " goalReached=" + goalReached
                    + " steps=" + totalSteps + " cumDev=" + String.format("%.1f", cumIlluminanceDeviation)
                    + " energy=" + String.format("%.1f", totalEnergyCost));
    }

    /**
     * Close a scenario run in the protocol-v2 format and keep its record. The
     * simulator's energy counter is stored only as the diagnostic column
     * {@code LegacyWallClockTotalEnergyCost}; the energy measure of v2 is
     * {@code PolicyEnergyCost}. This operation does not check how the scenario was
     * begun: after {@link #beginScenario} the record stays in the legacy format.
     *
     * @param goalReached           as in {@link #endScenario}
     * @param totalSteps            as in {@link #endScenario}
     * @param legacyWallClockEnergy the simulator's energy counter at the end
     */
    @OPERATION
    public void endScenarioV2(boolean goalReached, int totalSteps,
                              double legacyWallClockEnergy) {
        commitScenario(goalReached, totalSteps, legacyWallClockEnergy);
    }

    /** Format name of protocol-v2 records; also the value of the {@code MetricSchema} column. */
    static final String SCHEMA_V2 = "phase1-benchmark-v2";
    /** Format name of legacy records. */
    static final String SCHEMA_LEGACY = "legacy";

    /**
     * File format for a set of scenario records: the protocol-v2 format when
     * every record is v2, the legacy format when none is (also for no record at
     * all). A mix (some scenarios begun with beginScenarioV2, others with the
     * legacy beginScenario) has no valid format: the legacy file would silently
     * drop the v2 columns, so it is an error.
     *
     * @param recordSchemas the format name of every record
     * @return {@link #SCHEMA_V2} or {@link #SCHEMA_LEGACY}
     * @throws IllegalStateException for a mix of both formats
     */
    static String outputSchema(List<String> recordSchemas) {
        long v2 = recordSchemas.stream().filter(SCHEMA_V2::equals).count();
        if (v2 == 0) return SCHEMA_LEGACY;
        if (v2 == recordSchemas.size()) return SCHEMA_V2;
        throw new IllegalStateException("Mixed benchmark result formats in one run: "
                + v2 + " of " + recordSchemas.size() + " scenario records use " + SCHEMA_V2
                + " and the others the legacy format. A protocol-v2 run must begin every"
                + " scenario with beginScenarioV2; no result file is written.");
    }

    /**
     * Write all scenario records to a CSV file (replacing an existing file), one row
     * per scenario run in the order the runs ended. Numbers with decimals have two
     * decimals and are formatted with the JVM's default locale; {@code GoalReached}
     * is 1 or 0.
     *
     * <p>Protocol-v2 format, 18 columns: {@code ScenarioId, RunId, AgentType,
     * MetricSchema, GoalReached, Steps, CumIlluminanceDeviation, WastedSteps,
     * ActuatorCyclingCount, CrossZoneInterferences, PolicyEnergyCost,
     * LegacyWallClockTotalEnergyCost, SilentlyDropped, DelayedEffectSteps,
     * UnmodelledZoneEffect, ConditionInversions, TopologyMismatches,
     * ComfortDeviation}.
     *
     * <p>Legacy format, 16 columns: {@code ScenarioId, RunId, AgentType, GoalReached,
     * Steps, CumIlluminanceDeviation, WastedSteps, ActuatorCyclingCount,
     * CrossZoneInterferences, TotalEnergyCost, SilentlyDropped, DelayedEffectSteps,
     * UnmodelledZoneEffect, ConditionInversions, TopologyMismatches,
     * ComfortDeviation}.
     *
     * <p>Errors: if the records mix both formats ({@link #outputSchema}), an error is
     * logged, no file is written and {@link IllegalStateException} ends the
     * operation. An I/O error while writing is only logged as a warning.
     *
     * @param filename path of the CSV file (the test agent passes
     *                 {@code benchmark_results_<mode>.csv})
     */
    @OPERATION
    public void saveBenchmarkResults(String filename) {
        // Decide the file format before opening the file, so a mixed run
        // leaves no result file behind.
        String schema;
        try {
            schema = outputSchema(records.stream().map(r -> r.metricSchema)
                    .collect(java.util.stream.Collectors.toList()));
        } catch (IllegalStateException e) {
            LOGGER.severe("BenchmarkLogger.saveBenchmarkResults: " + filename + " not written. "
                          + e.getMessage());
            throw e;
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            boolean v2 = SCHEMA_V2.equals(schema);
            if (v2) {
                pw.println("ScenarioId,RunId,AgentType,MetricSchema,GoalReached,Steps,"
                         + "CumIlluminanceDeviation,WastedSteps,"
                         + "ActuatorCyclingCount,CrossZoneInterferences,PolicyEnergyCost,"
                         + "LegacyWallClockTotalEnergyCost,"
                         + "SilentlyDropped,DelayedEffectSteps,UnmodelledZoneEffect,"
                         + "ConditionInversions,TopologyMismatches,ComfortDeviation");
                for (ScenarioRecord r : records) {
                    pw.printf("%d,%d,%s,%s,%d,%d,%.2f,%d,%d,%d,%.2f,%.2f,%d,%d,%d,%d,%d,%.2f%n",
                        r.scenarioId, r.runId, r.agentType, r.metricSchema,
                        r.goalReached ? 1 : 0, r.steps, r.cumIlluminanceDeviation,
                        r.wastedSteps, r.actuatorCyclingCount, r.crossZoneInterferences,
                        r.policyEnergyCost, r.totalEnergyCost, r.silentlyDropped,
                        r.delayedEffectSteps, r.unmodelledZoneEffect,
                        r.conditionInversions, r.topologyMismatches, r.comfortDeviation);
                }
            } else {
                // Legacy column set, kept because the archived legacy-format runs
                // and their analysis scripts use it.
                pw.println("ScenarioId,RunId,AgentType,GoalReached,Steps,"
                         + "CumIlluminanceDeviation,WastedSteps,"
                         + "ActuatorCyclingCount,CrossZoneInterferences,TotalEnergyCost,"
                         + "SilentlyDropped,DelayedEffectSteps,UnmodelledZoneEffect,"
                         + "ConditionInversions,TopologyMismatches,ComfortDeviation");
                for (ScenarioRecord r : records) {
                    pw.printf("%d,%d,%s,%d,%d,%.2f,%d,%d,%d,%.2f,%d,%d,%d,%d,%d,%.2f%n",
                        r.scenarioId, r.runId, r.agentType, r.goalReached ? 1 : 0,
                        r.steps, r.cumIlluminanceDeviation, r.wastedSteps,
                        r.actuatorCyclingCount, r.crossZoneInterferences,
                        r.totalEnergyCost, r.silentlyDropped, r.delayedEffectSteps,
                        r.unmodelledZoneEffect, r.conditionInversions,
                        r.topologyMismatches, r.comfortDeviation);
                }
            }
            LOGGER.info("BenchmarkLogger.saveBenchmarkResults: written " + records.size()
                        + " records to " + filename);
        } catch (IOException e) {
            LOGGER.warning("BenchmarkLogger.saveBenchmarkResults: failed to write "
                           + filename + " — " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Step log (bench_step_log_<mode>.csv)
    // -----------------------------------------------------------------------

    /** One step: one row of the step log. */
    private static class StepLogRecord {
        int    scenarioId;
        int    runId;
        int    step;
        int[]  zoneLevelsBefore;
        int[]  targets;
        String actionLabel;
        int[]  zonelevelsAfter;
        boolean wasMasked; // as passed by the agent; the test agent always passes false
        boolean crossZoneInterference;
        // Filled only by recordStepDetailRichV2; the defaults give empty cells.
        double[]  temperatures   = new double[0];
        String[]  actuatorKeys   = new String[0];
        boolean[] actuatorVals   = new boolean[0];
        int       sunshineRank   = -1;
        boolean   stuckFired     = false; // anti-stuck rule chose the action this step
    }

    /** All recorded steps of this artifact, over all scenarios and runs. */
    private final List<StepLogRecord> stepLog = new ArrayList<>();

    /**
     * Add a step to the step log without temperatures, status snapshot and sun
     * rank (those cells stay empty). No agent program calls this operation; the test
     * agent uses {@link #recordStepDetailRichV2}.
     *
     * @param step                  step number in the scenario, from 0
     * @param zoneLevelsBefore      zone ranks before the action
     * @param zonelevelsAfter       zone ranks after the action
     * @param targets               target ranks
     * @param actionLabel           action label, for example
     *                              {@code http://example.org/was#SetZ1Light=true}
     * @param wasMasked             flag written to the {@code WasMasked} column
     * @param crossZoneInterference flag written to the {@code CrossZoneInterference}
     *                              column
     */
    @OPERATION
    public void recordStepDetail(int      step,
                                 Object[] zoneLevelsBefore,
                                 Object[] zonelevelsAfter,
                                 Object[] targets,
                                 String   actionLabel,
                                 boolean  wasMasked,
                                 boolean  crossZoneInterference) {
        StepLogRecord r = new StepLogRecord();
        r.scenarioId           = currentScenarioId;
        r.runId                = currentRunId;
        r.step                 = step;
        r.zoneLevelsBefore     = toIntArr(zoneLevelsBefore);
        r.zonelevelsAfter      = toIntArr(zonelevelsAfter);
        r.targets              = toIntArr(targets);
        r.actionLabel          = actionLabel;
        r.wasMasked            = wasMasked;
        r.crossZoneInterference = crossZoneInterference;
        stepLog.add(r);
    }

    /**
     * Add a step to the step log with temperatures, status snapshot and sun rank;
     * {@code StuckFired} is written as 0. Calls {@link #recordStepDetailRichV2}. No
     * agent program calls this operation.
     */
    @OPERATION
    public void recordStepDetailRich(int      step,
                                     Object[] zoneLevelsBefore,
                                     Object[] zonelevelsAfter,
                                     Object[] targets,
                                     String   actionLabel,
                                     boolean  wasMasked,
                                     boolean  crossZoneInterference,
                                     Object[] temperatures,
                                     Object[] actuatorKeys,
                                     Object[] actuatorVals,
                                     int      sunshineRank) {
        recordStepDetailRichV2(step, zoneLevelsBefore, zonelevelsAfter, targets,
                               actionLabel, wasMasked, crossZoneInterference,
                               temperatures, actuatorKeys, actuatorVals,
                               sunshineRank, false);
    }

    /**
     * Add a step to the step log with all columns. The test agent calls this once
     * per action in every mode and format. The step is attributed to the scenario
     * and run of the last begin operation.
     *
     * @param step                  step number in the scenario, from 0
     * @param zoneLevelsBefore      zone ranks before the action (numbers)
     * @param zonelevelsAfter       zone ranks after the action
     * @param targets               target ranks
     * @param actionLabel           action label (the test agent writes
     *                              {@code <action type URI>=<value>},
     *                              {@code DO_NOTHING}, {@code BLOCKED:...} or
     *                              {@code unknown}); commas become semicolons in the file
     * @param wasMasked             flag for the {@code WasMasked} column (the test
     *                              agent always passes false)
     * @param crossZoneInterference flag for the {@code CrossZoneInterference} column
     * @param temperatures          zone temperatures from
     *                              {@code LabEnvironment.readZoneTemperatures} (may be
     *                              empty)
     * @param actuatorKeys          boolean status URIs after the action
     * @param actuatorVals          their values
     * @param sunshineRank          sun rank 0 to 3; a negative value gives an empty cell
     * @param stuckFired            true if the test agent's anti-stuck rule chose the
     *                              action (only possible when its
     *                              {@code bench_anti_stuck} belief is true)
     */
    @OPERATION
    public void recordStepDetailRichV2(int      step,
                                       Object[] zoneLevelsBefore,
                                       Object[] zonelevelsAfter,
                                       Object[] targets,
                                       String   actionLabel,
                                       boolean  wasMasked,
                                       boolean  crossZoneInterference,
                                       Object[] temperatures,
                                       Object[] actuatorKeys,
                                       Object[] actuatorVals,
                                       int      sunshineRank,
                                       boolean  stuckFired) {
        StepLogRecord r = new StepLogRecord();
        r.scenarioId           = currentScenarioId;
        r.runId                = currentRunId;
        r.step                 = step;
        r.zoneLevelsBefore     = toIntArr(zoneLevelsBefore);
        r.zonelevelsAfter      = toIntArr(zonelevelsAfter);
        r.targets              = toIntArr(targets);
        r.actionLabel          = actionLabel;
        r.wasMasked            = wasMasked;
        r.crossZoneInterference = crossZoneInterference;
        r.temperatures         = toDoubleArr(temperatures);
        r.actuatorKeys         = toStringArr(actuatorKeys);
        r.actuatorVals         = toBoolArr(actuatorVals);
        r.sunshineRank         = sunshineRank;
        r.stuckFired           = stuckFired;
        stepLog.add(r);
    }

    /**
     * Write the step log to a CSV file (replacing an existing file), one row per
     * recorded step in recording order.
     *
     * <p>25 columns: {@code ScenarioId, RunId, Step, Z1Before..Z4Before,
     * Z1Target..Z4Target, ActionLabel, Z1After..Z4After, WasMasked,
     * CrossZoneInterference, Z1Temp..Z4Temp, SunshineRank, ActuatorState,
     * StuckFired}. Zone columns beyond the lab's zone count, and negative ranks, are
     * empty cells; zones beyond the fourth are not written. Temperatures have two
     * decimals (locale-independent). {@code ActuatorState} is the status snapshot as
     * {@code key=value;key=value} ({@link #buildActuatorString}). Flags are 1 or 0.
     *
     * <p>Errors: an I/O error is only logged as a warning.
     *
     * @param filename path of the CSV file (the test agent passes
     *                 {@code bench_step_log_<mode>.csv})
     */
    @OPERATION
    public void saveStepLog(String filename) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            // Per-zone columns Z1..Z4 carry empty cells when the active profile
            // uses fewer zones.
            pw.println("ScenarioId,RunId,Step,"
                     + "Z1Before,Z2Before,Z3Before,Z4Before,"
                     + "Z1Target,Z2Target,Z3Target,Z4Target,"
                     + "ActionLabel,"
                     + "Z1After,Z2After,Z3After,Z4After,"
                     + "WasMasked,CrossZoneInterference,"
                     + "Z1Temp,Z2Temp,Z3Temp,Z4Temp,"
                     + "SunshineRank,ActuatorState,StuckFired");
            for (StepLogRecord r : stepLog) {
                StringBuilder sb = new StringBuilder();
                sb.append(r.scenarioId).append(',').append(r.runId).append(',').append(r.step).append(',');
                appendIntCell(sb, r.zoneLevelsBefore, 0); sb.append(',');
                appendIntCell(sb, r.zoneLevelsBefore, 1); sb.append(',');
                appendIntCell(sb, r.zoneLevelsBefore, 2); sb.append(',');
                appendIntCell(sb, r.zoneLevelsBefore, 3); sb.append(',');
                appendIntCell(sb, r.targets, 0); sb.append(',');
                appendIntCell(sb, r.targets, 1); sb.append(',');
                appendIntCell(sb, r.targets, 2); sb.append(',');
                appendIntCell(sb, r.targets, 3); sb.append(',');
                String label = r.actionLabel == null ? "" : r.actionLabel.replace(",", ";");
                sb.append(label).append(',');
                appendIntCell(sb, r.zonelevelsAfter, 0); sb.append(',');
                appendIntCell(sb, r.zonelevelsAfter, 1); sb.append(',');
                appendIntCell(sb, r.zonelevelsAfter, 2); sb.append(',');
                appendIntCell(sb, r.zonelevelsAfter, 3); sb.append(',');
                sb.append(r.wasMasked ? 1 : 0).append(',');
                sb.append(r.crossZoneInterference ? 1 : 0).append(',');
                appendDoubleCell(sb, r.temperatures, 0); sb.append(',');
                appendDoubleCell(sb, r.temperatures, 1); sb.append(',');
                appendDoubleCell(sb, r.temperatures, 2); sb.append(',');
                appendDoubleCell(sb, r.temperatures, 3); sb.append(',');
                if (r.sunshineRank >= 0) sb.append(r.sunshineRank);
                sb.append(',');
                sb.append(buildActuatorString(r.actuatorKeys, r.actuatorVals));
                sb.append(',').append(r.stuckFired ? 1 : 0);
                pw.println(sb.toString());
            }
            LOGGER.info("BenchmarkLogger.saveStepLog: written " + stepLog.size()
                        + " records to " + filename);
        } catch (IOException e) {
            LOGGER.warning("BenchmarkLogger.saveStepLog: failed to write "
                           + filename + " — " + e.getMessage());
        }
    }

    /** Append {@code arr[i]} if it exists and is not negative; otherwise nothing (empty cell). */
    private static void appendIntCell(StringBuilder sb, int[] arr, int i) {
        if (i < arr.length && arr[i] >= 0) sb.append(arr[i]);
    }

    /** Append {@code arr[i]} with two decimals ({@code Locale.ROOT}) if it exists; otherwise nothing. */
    private static void appendDoubleCell(StringBuilder sb, double[] arr, int i) {
        if (i < arr.length) sb.append(String.format(java.util.Locale.ROOT, "%.2f", arr[i]));
    }

    /**
     * Build a {@code key=val;key=val} string from parallel actuator arrays,
     * collapsing each URI to its fragment (text after '#'). Commas, semicolons
     * and equals signs inside keys are removed to keep the CSV column safe; a key
     * that is then empty is left out. Extra elements of the longer array are
     * ignored.
     *
     * @param keys status URIs
     * @param vals their values
     * @return for example {@code Z1Light=true;Z1Blinds=false}, or "" for no keys
     */
    private static String buildActuatorString(String[] keys, boolean[] vals) {
        if (keys == null || keys.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(keys.length, vals.length);
        for (int i = 0; i < n; i++) {
            String k = keys[i] == null ? "" : keys[i];
            int hash = k.lastIndexOf('#');
            if (hash >= 0) k = k.substring(hash + 1);
            k = k.replace(",", "").replace(";", "").replace("=", "");
            if (k.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(k).append('=').append(vals[i]);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Weakness counters (called by the test agent in the learning modes)
    // -----------------------------------------------------------------------

    /**
     * Add to a weakness counter of the current scenario. The test agent calls this
     * with magnitude 1 for every tag that {@code QLearner.classifyWeaknesses}
     * reports for a step and that the lab profile lists in its
     * {@code weakness_flags}.
     *
     * <p>Tags and columns: {@code w1_unmodelled} and {@code w6_unmodelled} add to
     * {@code UnmodelledZoneEffect}, {@code w2_inversion} to
     * {@code ConditionInversions}, {@code w3_delayed} to {@code DelayedEffectSteps},
     * {@code w4_dropped} to {@code SilentlyDropped}, {@code w5_topology} to
     * {@code TopologyMismatches}. {@code QLearner.classifyWeaknesses} does not produce
     * {@code w6_unmodelled}. A null tag is ignored; an unknown tag is logged as a
     * warning and ignored.
     *
     * @param flag      the tag
     * @param magnitude amount to add
     */
    @OPERATION
    public void recordWeakness(String flag, int magnitude) {
        if (flag == null) return;
        switch (flag) {
            case "w1_unmodelled":
            case "w6_unmodelled":
                unmodelledZoneEffect += magnitude; break;
            case "w2_inversion":   conditionInversions += magnitude; break;
            case "w3_delayed":     delayedEffectSteps  += magnitude; break;
            case "w4_dropped":     silentlyDropped     += magnitude; break;
            case "w5_topology":    topologyMismatches  += magnitude; break;
            default:
                LOGGER.warning("BenchmarkLogger.recordWeakness: unknown flag " + flag);
        }
    }

    /**
     * Add a positive value to {@code ComfortDeviation} of the current scenario;
     * zero and negative values are ignored. In the learning modes, for labs whose
     * profile lists weakness {@code w6}, the test agent calls this after each action
     * of the learner for every zone whose temperature is more than 1.0 °C away from
     * 22.0 °C, with the part of the distance beyond 1.0 °C.
     *
     * @param dev temperature excess in °C
     */
    @OPERATION
    public void recordComfortDeviation(double dev) {
        if (dev > 0.0) comfortDeviation += dev;
    }

    // -----------------------------------------------------------------------
    // Private helpers (conversions from agent terms, see Converters)
    // -----------------------------------------------------------------------

    private static int[] toIntArr(Object[] arr) { return Converters.toIntArray(arr); }

    private static double[] toDoubleArr(Object[] arr) { return Converters.toDoubleArray(arr); }

    private static String[] toStringArr(Object[] arr) { return Converters.toStringArray(arr); }

    private static boolean[] toBoolArr(Object[] arr) { return Converters.toBooleanArray(arr); }

    /**
     * Count the keys whose boolean value differs between two snapshots. For every
     * key of the previous snapshot the first equal key (compared as strings) of the
     * current snapshot is looked up; keys present in only one snapshot do not count.
     * Values are converted with {@link Converters#toBoolean}.
     *
     * @param prevKeys previous keys
     * @param prevVals previous values, parallel to {@code prevKeys}
     * @param currKeys current keys
     * @param currVals current values, parallel to {@code currKeys}
     * @return number of changed values
     */
    static int countReversals(Object[] prevKeys, Object[] prevVals,
                              Object[] currKeys, Object[] currVals) {
        int reversals = 0;
        for (int i = 0; i < prevKeys.length; i++) {
            String key = String.valueOf(prevKeys[i]);
            boolean prevVal = toBoolean(prevVals[i]);
            // Find matching key in curr
            for (int j = 0; j < currKeys.length; j++) {
                if (key.equals(String.valueOf(currKeys[j]))) {
                    boolean currVal = toBoolean(currVals[j]);
                    if (prevVal != currVal) reversals++;
                    break;
                }
            }
        }
        return reversals;
    }

    private static int toInt(Object o) { return Converters.toInt(o); }

    private static boolean toBoolean(Object o) { return Converters.toBoolean(o); }

    // -----------------------------------------------------------------------
    // Step trace (JSON Lines)
    //   Optional file with one JSON object per step, opened by the test agent in
    //   the learning modes (openTraceJsonl), written and flushed by
    //   recordRichStep, and closed by closeTraceJsonl at the end of the test.
    //   Read by analysis/sweep_report.py.
    // -----------------------------------------------------------------------

    private PrintWriter traceWriter = null;
    private String      traceFile   = null;

    /**
     * Open a trace file for {@link #recordRichStep}; an existing file is replaced.
     * A trace that is already open is closed first. Missing parent directories are
     * created (the test agent passes
     * {@code benchmark/results/<profile>/<mode>/trace_bench_<mode>.jsonl}).
     *
     * <p>Errors: if the file cannot be opened, a warning is logged and no trace is
     * written; the operation does not fail.
     *
     * @param filename path of the trace file
     */
    @OPERATION
    public void openTraceJsonl(String filename) {
        try {
            if (traceWriter != null) {
                traceWriter.flush();
                traceWriter.close();
            }
            // Ensure the parent directory exists when callers pass a nested
            // path like "benchmark/results/<profile>/<mode>/trace_bench_…".
            java.io.File f = new java.io.File(filename);
            java.io.File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            traceWriter = new PrintWriter(new FileWriter(f, false));
            traceFile   = filename;
            LOGGER.info("openTraceJsonl: writing rich trace to " + filename);
        } catch (IOException e) {
            LOGGER.warning("openTraceJsonl: failed to open " + filename + " — " + e.getMessage());
            traceWriter = null;
            traceFile   = null;
        }
    }

    /**
     * Write one line to the trace and flush it; does nothing if no trace is open.
     * The line is a JSON object with the keys {@code scenarioId}, {@code runId} (of
     * the last begin operation), {@code step}, {@code actionIdx}, {@code qDelta},
     * {@code stateBefore}, {@code stateAfter}, {@code predictedDelta},
     * {@code applicableActions} (integer arrays) and {@code weaknessFired} (string
     * array). The test agent calls it only for labs with weakness flags and only
     * after an action of the learner.
     *
     * @param step               step value written to the line (the test agent
     *                           passes 0 for every step, so it does not number the
     *                           steps)
     * @param stateBefore        state vector before the step
     *                           ({@code QLearner.encodeState})
     * @param actionIdx          index of the action taken
     * @param applicableActions  action indices from {@code QLearner.getApplicableActions}
     * @param predictedDelta     predicted change per state slot from
     *                           {@code QLearner.getPredictedDelta}
     * @param stateAfter         state vector after the step
     * @param qDelta             value of {@code QLearner.getLastQDelta}, written with
     *                           {@link Double#toString}
     * @param weaknessFlagsFired tags from {@code QLearner.classifyWeaknesses}
     */
    @OPERATION
    public void recordRichStep(int      step,
                               Object[] stateBefore,
                               int      actionIdx,
                               Object[] applicableActions,
                               Object[] predictedDelta,
                               Object[] stateAfter,
                               double   qDelta,
                               Object[] weaknessFlagsFired) {
        if (traceWriter == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
          .append("\"scenarioId\":").append(currentScenarioId).append(',')
          .append("\"runId\":").append(currentRunId).append(',')
          .append("\"step\":").append(step).append(',')
          .append("\"actionIdx\":").append(actionIdx).append(',')
          .append("\"qDelta\":").append(qDelta).append(',');
        appendIntArr(sb, "stateBefore", stateBefore).append(',');
        appendIntArr(sb, "stateAfter",  stateAfter).append(',');
        appendIntArr(sb, "predictedDelta", predictedDelta).append(',');
        appendIntArr(sb, "applicableActions", applicableActions).append(',');
        appendStrArr(sb, "weaknessFired", weaknessFlagsFired);
        sb.append('}');
        traceWriter.println(sb.toString());
        traceWriter.flush();
    }

    /** Flush and close the trace if one is open; otherwise do nothing. */
    @OPERATION
    public void closeTraceJsonl() {
        if (traceWriter != null) {
            traceWriter.flush();
            traceWriter.close();
            traceWriter = null;
            LOGGER.info("closeTraceJsonl: closed " + traceFile);
            traceFile = null;
        }
    }

    /** Append {@code "key":[i1,i2,...]} with every element converted by {@link Converters#toInt}. */
    private static StringBuilder appendIntArr(StringBuilder sb, String key, Object[] arr) {
        sb.append('"').append(key).append("\":[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(toInt(arr[i]));
        }
        return sb.append(']');
    }

    /** Append {@code "key":["s1","s2",...]} with every element escaped by {@link #escapeJson}. */
    private static StringBuilder appendStrArr(StringBuilder sb, String key, Object[] arr) {
        sb.append('"').append(key).append("\":[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(String.valueOf(arr[i]))).append('"');
        }
        return sb.append(']');
    }

    /**
     * Escape a string for a JSON string literal: double quote, backslash, newline,
     * carriage return and tab. Other control characters are not escaped.
     */
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
