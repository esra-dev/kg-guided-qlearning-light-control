package tools;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.logging.Logger;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

/**
 * DynamicsLearner — CArtAgO artifact that learns the <em>temporal response
 * delay</em> of each actuator (how long a lamp or blind needs until its effect
 * is visible in the room).
 *
 * <p>Used by the agent program {@code illuminance_controller_agent_dynamics.asl}
 * (project file {@code task_dynamics.jcm}, Gradle task {@code taskDynamics}),
 * which creates one instance under the name {@code dynamics}. It reads nothing
 * from disk; it writes the files named in the {@code save*} operations. It has
 * no observable properties and no signals.
 *
 * <p>Where {@link StereotypeLearner} accumulates the per-slot magnitude of an
 * action's effect, this artifact accumulates <em>how long</em> that effect
 * takes to manifest. The dynamics agent runs controlled probe trials: it pins
 * a baseline, reads the simulator's {@code Tick} clock, dispatches a single
 * actuator command, then polls until the target zone illuminance rank changes;
 * the tick difference is one delay sample (in environment ticks). Each sample
 * is folded into a per-action running mean / variance via Welford's algorithm.
 *
 * <p>Ticks are converted to seconds with a per-lab constant
 * {@code seconds_per_tick} so the learned delay is expressed in the same units
 * a temporal goal uses ("make the room bright within 5 minutes"). The fast task
 * lamp settles in ~0 ticks (≈ instantaneous); the motorized blind settles in
 * ~12 ticks (≈ 60 s). The learned delays are written back to the Knowledge
 * Graph as {@code ws:responseDelay} (seconds) so a deadline-aware planner can
 * prefer the fast lamp under a tight deadline and the cheap blind under a loose
 * one.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code initDynamics(numActions, actionUris, actionValues, actionLabels, secondsPerTick)}</li>
 *   <li>{@code recordDelaySample(actionIdx, measuredTicks)}</li>
 *   <li>{@code getDelayTicksForAction(actionIdx, Out)} / {@code getDelaySecondsForAction(actionIdx, Out)}</li>
 *   <li>{@code getSampleCount(actionIdx, Out)}</li>
 *   <li>{@code saveLearnedDynamics(filename)} — writes Turtle</li>
 *   <li>{@code saveDelayTable(filename)} — writes a CSV table of the learned delays</li>
 *   <li>{@code recordExploitResult(...)} / {@code saveExploitResults(filename)} —
 *       buffer and write the outcomes of the time-bounded goals (CSV)</li>
 *   <li>{@code resetStats()} / {@code printStats()}</li>
 * </ul>
 *
 * <p>Thresholds (defaults; override via -D system properties):
 * <ul>
 *   <li>{@code dynamics.learner.minSamples} (default 3) — minimum samples before a delay is written.</li>
 *   <li>{@code dynamics.instant.threshold.sec} (default 30.0) — learned delay below this is
 *       classified {@code ws:InstantaneousResponse}, otherwise {@code ws:DelayedResponse}.
 *       Set midway between the instant lamp (a 1–2 tick measurement floor when polling a
 *       50 ms-tick simulator over HTTP, i.e. ~5–10 s) and the motorized blind (12 ticks = 60 s).</li>
 * </ul>
 */
public class DynamicsLearner extends Artifact {

    private static final Logger LOGGER = Logger.getLogger(DynamicsLearner.class.getName());

    /** Minimum number of samples before an action's delay is written to the Turtle file. */
    private static final int    MIN_SAMPLES           = getIntProp("dynamics.learner.minSamples", 3);
    // 30 s = 6 ticks: a clean midpoint between the instant lamp (~5–10 s measurement floor)
    // and the motorized blind (60 s). Override via -Ddynamics.instant.threshold.sec.
    /** Delays below this many seconds count as instantaneous, others as delayed. */
    private static final double INSTANT_THRESHOLD_SEC = getDblProp("dynamics.instant.threshold.sec", 30.0);

    /** Integer system property {@code k}; {@code d} if it is unset or not an integer. */
    private static int getIntProp(String k, int d) {
        try { return Integer.parseInt(System.getProperty(k, Integer.toString(d))); }
        catch (NumberFormatException e) { return d; }
    }
    /** Decimal system property {@code k}; {@code d} if it is unset or not a number. */
    private static double getDblProp(String k, double d) {
        try { return Double.parseDouble(System.getProperty(k, Double.toString(d))); }
        catch (NumberFormatException e) { return d; }
    }

    // Configuration
    private int       numActions     = 0;
    private double    secondsPerTick = 1.0;
    private String[]  actionUris     = new String[0];
    private boolean[] actionValues   = new boolean[0];
    private String[]  actionLabels   = new String[0];

    // Welford accumulators per action (delay measured in environment ticks)
    private long[]   count;
    private double[] mean;
    private double[] m2;

    /**
     * Initialise the learner. Idempotent — re-init zeroes all accumulators.
     *
     * @param numActionsIn    Total action count (incl. DO_NOTHING).
     * @param uris            Object[] of String — WoT action type URI per action.
     * @param values          Object[] of Boolean — activation flag per action.
     * @param labels          Object[] of String — human-readable label per action.
     * @param secondsPerTickIn Wall-clock-equivalent seconds represented by one
     *                         environment tick (per-lab scaling constant).
     */
    @OPERATION
    public void initDynamics(int numActionsIn,
                             Object[] uris,
                             Object[] values,
                             Object[] labels,
                             double secondsPerTickIn) {
        this.numActions     = numActionsIn;
        this.secondsPerTick = (secondsPerTickIn > 0) ? secondsPerTickIn : 1.0;
        this.actionUris     = new String[numActions];
        this.actionValues   = new boolean[numActions];
        this.actionLabels   = new String[numActions];
        for (int a = 0; a < numActions; a++) {
            actionUris[a]   = (a < uris.length   && uris[a]   != null) ? String.valueOf(uris[a])   : "";
            actionValues[a] = (a < values.length && values[a] != null) && Converters.toBoolean(values[a]);
            actionLabels[a] = (a < labels.length && labels[a] != null) ? String.valueOf(labels[a]) : ("action_" + a);
        }
        count = new long[numActions];
        mean  = new double[numActions];
        m2    = new double[numActions];
        LOGGER.info("DynamicsLearner: init numActions=" + numActions
                  + " secondsPerTick=" + secondsPerTick);
    }

    /**
     * Fold one measured response-delay sample (in environment ticks) into the
     * Welford accumulator for {@code actionIdx}. No-op for an out-of-range
     * action index or a negative measurement (probe timeout / no effect).
     */
    @OPERATION
    public void recordDelaySample(int actionIdx, int measuredTicks) {
        if (count == null || actionIdx < 0 || actionIdx >= numActions || measuredTicks < 0) return;
        count[actionIdx] += 1;
        double n       = count[actionIdx];
        double oldMean = mean[actionIdx];
        double newMean = oldMean + (measuredTicks - oldMean) / n;
        mean[actionIdx] = newMean;
        m2[actionIdx]  += (measuredTicks - oldMean) * (measuredTicks - newMean);
    }

    /**
     * Mean learned delay for an action, in environment ticks.
     * Sets {@code out} to -1.0 if the action has no samples yet.
     */
    @OPERATION
    public void getDelayTicksForAction(int actionIdx, OpFeedbackParam<Double> out) {
        if (count == null || actionIdx < 0 || actionIdx >= numActions || count[actionIdx] == 0) {
            out.set(-1.0);
            return;
        }
        out.set(mean[actionIdx]);
    }

    /**
     * Mean learned delay for an action, in seconds ({@code ticks * secondsPerTick}).
     * Sets {@code out} to -1.0 if the action has no samples yet (unknown delay).
     */
    @OPERATION
    public void getDelaySecondsForAction(int actionIdx, OpFeedbackParam<Double> out) {
        if (count == null || actionIdx < 0 || actionIdx >= numActions || count[actionIdx] == 0) {
            out.set(-1.0);
            return;
        }
        out.set(mean[actionIdx] * secondsPerTick);
    }

    /** Number of delay samples recorded for an action (0 if none / out of range). */
    @OPERATION
    public void getSampleCount(int actionIdx, OpFeedbackParam<Integer> out) {
        if (count == null || actionIdx < 0 || actionIdx >= numActions) { out.set(0); return; }
        out.set((int) count[actionIdx]);
    }

    /**
     * Dump learned response dynamics to a Turtle file. Emits one
     * {@code learned:ResponseDynamic} blank node per action that accumulated at
     * least {@link #MIN_SAMPLES} samples, carrying the learned delay as
     * {@code ws:responseDelay} (seconds, the unit temporal goals use),
     * {@code ws:responseDelayTicks} (rounded ticks) and {@code ws:responseDelaySamples},
     * plus a {@code learned:responseClass} of {@code ws:InstantaneousResponse} or
     * {@code ws:DelayedResponse}. This is the learning-side writeback that
     * augments the asserted {@code ws:hasResponseDynamic} markers in the domain
     * ontology with numeric, measured delays. Does nothing but log a warning if
     * the learner is not initialised; an I/O error is logged, not thrown.
     *
     * @param filename path of the Turtle file (overwritten)
     */
    @OPERATION
    public void saveLearnedDynamics(String filename) {
        if (count == null) {
            LOGGER.warning("saveLearnedDynamics: learner not initialised — skipping");
            return;
        }
        int written = 0;
        int rejectedSamples = 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("@prefix ws:      <http://example.org/was/lab/stereotypes#> .");
            pw.println("@prefix learned: <http://example.org/was/learned#> .");
            pw.println("@prefix xsd:     <http://www.w3.org/2001/XMLSchema#> .");
            pw.println();
            pw.println("# Auto-generated by tools.DynamicsLearner");
            pw.println("# Learned per-actuator response delays (Phase 3: process dynamics).");
            pw.println("# secondsPerTick=" + secondsPerTick
                     + "  minSamples=" + MIN_SAMPLES
                     + "  instantThresholdSec=" + INSTANT_THRESHOLD_SEC);
            pw.println();
            for (int a = 0; a < numActions; a++) {
                long n = count[a];
                if (n < MIN_SAMPLES) { if (n > 0) rejectedSamples++; continue; }
                double muTicks  = mean[a];
                double var      = (n > 1) ? (m2[a] / (n - 1)) : 0.0;
                double sdTicks  = Math.sqrt(Math.max(var, 0.0));
                double muSec    = muTicks * secondsPerTick;
                long   ticksInt = Math.round(muTicks);
                String respClass = (muSec < INSTANT_THRESHOLD_SEC)
                                 ? "ws:InstantaneousResponse"
                                 : "ws:DelayedResponse";

                pw.println("[] a learned:ResponseDynamic ;");
                if (!actionUris[a].isEmpty()) {
                    pw.println("   learned:action               <" + actionUris[a] + "> ;");
                }
                pw.println("   learned:actionValue          \""
                         + (actionValues[a] ? "true" : "false") + "\"^^xsd:boolean ;");
                pw.println("   learned:actionLabel          \"" + escapeTurtle(actionLabels[a]) + "\" ;");
                pw.println("   ws:responseDelay             \""
                         + String.format(Locale.ROOT, "%.4f", muSec) + "\"^^xsd:decimal ;");
                pw.println("   ws:responseDelayTicks        \"" + ticksInt + "\"^^xsd:integer ;");
                pw.println("   ws:responseDelaySamples      \"" + n + "\"^^xsd:integer ;");
                pw.println("   learned:responseDelayStdTicks \""
                         + String.format(Locale.ROOT, "%.2f", sdTicks) + "\"^^xsd:double ;");
                pw.println("   learned:responseClass        " + respClass + " .");
                pw.println();
                written++;
            }
            LOGGER.info("saveLearnedDynamics: " + written + " dynamics written to " + filename
                      + " (rejected for <minSamples: " + rejectedSamples + ")");
        } catch (IOException e) {
            LOGGER.warning("saveLearnedDynamics: failed to write " + filename
                         + " — " + e.getMessage());
        }
    }

    /** Reset all Welford accumulators to zero (configuration is preserved). */
    @OPERATION
    public void resetStats() {
        if (count == null) return;
        java.util.Arrays.fill(count, 0L);
        java.util.Arrays.fill(mean,  0.0);
        java.util.Arrays.fill(m2,    0.0);
    }

    /**
     * Dump a per-action delay table as CSV for the analysis scripts.
     * Columns: action_label, action_value, samples, delay_ticks, delay_seconds,
     * std_ticks, response_class. Only actions with at least one sample are written.
     * Does nothing but log a warning if the learner is not initialised; an
     * I/O error is logged, not thrown.
     *
     * @param filename path of the CSV file (overwritten)
     */
    @OPERATION
    public void saveDelayTable(String filename) {
        if (count == null) {
            LOGGER.warning("saveDelayTable: learner not initialised — skipping");
            return;
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("action_label,action_value,samples,delay_ticks,delay_seconds,std_ticks,response_class");
            for (int a = 0; a < numActions; a++) {
                long n = count[a];
                if (n == 0) continue;
                double muTicks = mean[a];
                double var     = (n > 1) ? (m2[a] / (n - 1)) : 0.0;
                double sdTicks = Math.sqrt(Math.max(var, 0.0));
                double muSec   = muTicks * secondsPerTick;
                String respClass = (muSec < INSTANT_THRESHOLD_SEC) ? "instantaneous" : "delayed";
                pw.println(csv(actionLabels[a])
                        + "," + (actionValues[a] ? "true" : "false")
                        + "," + n
                        + "," + String.format(Locale.ROOT, "%.4f", muTicks)
                        + "," + String.format(Locale.ROOT, "%.4f", muSec)
                        + "," + String.format(Locale.ROOT, "%.4f", sdTicks)
                        + "," + respClass);
            }
            LOGGER.info("saveDelayTable: wrote " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveDelayTable: failed to write " + filename + " — " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Time-bounded exploitation results (deadline benchmark)
    // -----------------------------------------------------------------------

    /** CSV rows buffered by {@link #recordExploitResult} until {@link #saveExploitResults} writes them. */
    private final java.util.List<String> exploitRows = new java.util.ArrayList<>();

    /** Tag written into every result row that says how the energy columns were measured (in simulator ticks). */
    public static final String ENERGY_METER_VERSION = "tick-v1";

    /**
     * Buffer one time-bounded-goal outcome row. The deadline-aware planner in
     * the dynamics agent calls this once per goal after executing its chosen
     * actuator and measuring the actual time-to-target. {@code met} is 1 if the
     * target rank was reached within the deadline, else 0.
     *
     * <p>{@code tickEnergy} is the energy-accumulator DELTA over the
     * attempt and {@code tickSpan} the simulator-tick delta over the same two
     * reads — deterministic in simulator time. {@code energyCostWallclockLegacy}
     * is the cumulative wall-clock energy read, which is no longer used for results;
     * it is kept as a labelled diagnostic column only.
     *
     * @param profile                   lab profile name
     * @param mode                      planner mode ({@code ql_true} or {@code ql_false})
     * @param goalId                    identifier of the goal
     * @param zone                      zone number of the goal
     * @param targetRank                target illuminance rank
     * @param deadlineSec               deadline in seconds
     * @param chosenLabel               label of the action the planner chose
     * @param believedDelaySec          delay the planner assumed, in seconds
     * @param learnedDelaySec           delay learned by the probes, in seconds
     * @param actualDelaySec            measured time to reach the target, in seconds
     * @param tickEnergy                energy used during the attempt (simulator energy units)
     * @param tickSpan                  length of the attempt in simulator ticks
     * @param energyCostWallclockLegacy diagnostic wall-clock energy read
     * @param met                       1 if the deadline was met, else 0
     */
    @OPERATION
    public void recordExploitResult(String profile, String mode, String goalId,
                                    int zone, int targetRank, double deadlineSec,
                                    String chosenLabel, double believedDelaySec,
                                    double learnedDelaySec, double actualDelaySec,
                                    double tickEnergy, int tickSpan,
                                    double energyCostWallclockLegacy, int met) {
        exploitRows.add(csv(profile)
                + "," + csv(mode)
                + "," + csv(goalId)
                + "," + zone
                + "," + targetRank
                + "," + String.format(Locale.ROOT, "%.2f", deadlineSec)
                + "," + csv(chosenLabel)
                + "," + String.format(Locale.ROOT, "%.2f", believedDelaySec)
                + "," + String.format(Locale.ROOT, "%.2f", learnedDelaySec)
                + "," + String.format(Locale.ROOT, "%.2f", actualDelaySec)
                + "," + String.format(Locale.ROOT, "%.2f", tickEnergy)
                + "," + tickSpan
                + "," + String.format(Locale.ROOT, "%.2f", energyCostWallclockLegacy)
                + "," + ENERGY_METER_VERSION
                + "," + met);
    }

    /**
     * Write the buffered time-bounded results to CSV (with header) and clear the
     * buffer. An I/O error is logged, not thrown; the buffer is cleared anyway.
     *
     * @param filename path of the CSV file (overwritten)
     */
    @OPERATION
    public void saveExploitResults(String filename) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("profile,mode,goal_id,zone,target_rank,deadline_sec,chosen_label,"
                     + "believed_delay_sec,learned_delay_sec,actual_delay_sec,"
                     + "tick_energy,tick_span,energy_cost_wallclock_legacy,energy_meter,met");
            for (String row : exploitRows) pw.println(row);
            LOGGER.info("saveExploitResults: wrote " + exploitRows.size() + " rows to " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveExploitResults: failed to write " + filename + " — " + e.getMessage());
        }
        exploitRows.clear();
    }

    /** Print a short per-action summary of learned delays for diagnostics. */
    @OPERATION
    public void printStats() {
        if (count == null) {
            LOGGER.info("DynamicsLearner: not initialised");
            return;
        }
        StringBuilder sb = new StringBuilder("DynamicsLearner stats:");
        for (int a = 0; a < numActions; a++) {
            if (count[a] == 0) continue;
            sb.append(String.format(Locale.ROOT,
                    " [%s n=%d delay=%.2ft/%.1fs]",
                    actionLabels[a], count[a], mean[a], mean[a] * secondsPerTick));
        }
        LOGGER.info(sb.toString());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal CSV-cell quoting: wrap in quotes and escape embedded quotes if needed. */
    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Escapes quote, backslash and line-break characters for a Turtle string literal. */
    private static String escapeTurtle(String s) {
        if (s == null) return "";
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
