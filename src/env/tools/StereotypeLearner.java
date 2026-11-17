package tools;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;

import cartago.Artifact;
import cartago.OPERATION;

/**
 * CArtAgO artifact that records, for every action and every entry of the
 * agent's state vector, the running mean and variance (Welford's online
 * algorithm) of the change of that entry over one step, and can write the
 * clearly non-zero changes as a Turtle file of "discovered effects".
 *
 * <p><b>Who uses it.</b>
 * <ul>
 *   <li>{@code illuminance_controller_agent_ql.asl} (training, both arms):
 *       creates it as {@code "learner"}, calls the 5-argument
 *       {@link #initLearner(int, int, Object[], Object[], Object[])} with the
 *       metadata of {@code QLearner.getActionMetadataForLearner},
 *       {@link #observe} after every step with the two state vectors of
 *       {@code QLearner.encodeState}, and at the end
 *       {@link #saveLearnedStereotypes} with
 *       {@code learned_stereotypes_<true|false><suffix>.ttl} and
 *       {@link #printStats}.</li>
 *   <li>{@code illuminance_controller_agent_adapt.asl} (fault stage): creates
 *       it, calls the 5-argument {@code initLearner} and {@link #observe}, and
 *       never writes anything, so the statistics it collects are not used.</li>
 *   <li>Tests only: the 7-argument {@code initLearner}, {@link #saveRawStats}.
 *       No agent program, runner or workflow calls {@link #saveRawStats} or
 *       {@link #resetStats}.</li>
 * </ul>
 *
 * <p><b>What the learned Turtle file contains and who reads it.</b> See
 * {@link #saveLearnedStereotypes}: only {@code learned:} triples, one node per
 * (action, state entry) pair that passes the three gates below, including
 * trivial ones such as an action's own device bit. The class has no access to
 * the knowledge graph and does not compare its findings with it. The only code
 * that reads such a file back is {@link OntologyArtifact#importLearnedStereotypes}
 * in the {@code rule_based} mode of the test program, for
 * {@code learned_stereotypes_true<suffix>.ttl}; it changes no decision there.
 * {@code run_full_project.ps1} checks that the file exists after training and
 * archives it with the other training files; the workflows upload it.
 *
 * <p><b>Gates</b> (JVM system properties, read once when the class is loaded;
 * an unparsable value falls back to the default; {@code build.gradle} does not
 * forward them to the agent JVM, so runs started through Gradle use the
 * defaults):
 * <ul>
 *   <li>{@code stereotype.learner.minSamples} (30): at least this many samples;</li>
 *   <li>{@code stereotype.learner.zCutoff} (3.0): |mean| / standard error of the
 *       mean at least this value;</li>
 *   <li>{@code stereotype.learner.minEffect} (0.05): |mean| at least this value
 *       (in units of the state entry; for a zone level, light ranks).</li>
 * </ul>
 *
 * <p><b>Flip-conditioned admission</b> (used only in tests). With
 * {@code -Dstereotype.learner.flipConditioned=true} (read in each
 * {@code initLearner} call; default false) a sample of an action that commands
 * a state bit is counted only if that bit actually changed to the expected
 * value, so a repeated ON command on a device that is already on adds no
 * zero-change sample. The gate needs the per-action bit metadata of the
 * 7-argument {@code initLearner}; with the 5-argument version every action has
 * bit -1 and every sample is counted. Actions without a bit (DO_NOTHING) are
 * always counted.
 *
 * <p>The artifact defines no observable property. Output goes to the files
 * named by the caller and to {@code java.util.logging}.
 */
public class StereotypeLearner extends Artifact {

    private static final Logger LOGGER = Logger.getLogger(StereotypeLearner.class.getName());

    private static final int    MIN_SAMPLES = getIntProp ("stereotype.learner.minSamples", 30);
    private static final double Z_CUTOFF    = getDblProp ("stereotype.learner.zCutoff",    3.0);
    private static final double MIN_EFFECT  = getDblProp ("stereotype.learner.minEffect",  0.05);
    /** Lower limit of the standard error in the z-score (avoids division by zero). */
    private static final double EPS         = 1e-9;

    /** Reads an int system property; the default if it is absent or not a number. */
    private static int getIntProp(String k, int d) {
        try { return Integer.parseInt(System.getProperty(k, Integer.toString(d))); }
        catch (NumberFormatException e) { return d; }
    }
    /** Reads a double system property; the default if it is absent or not a number. */
    private static double getDblProp(String k, double d) {
        try { return Double.parseDouble(System.getProperty(k, Double.toString(d))); }
        catch (NumberFormatException e) { return d; }
    }

    // Configuration, set by initLearner (arrays indexed by action index).
    private int numActions   = 0;
    private int stateVecLen  = 0;
    private String[]  actionUris   = new String[0];
    private boolean[] actionValues = new boolean[0];
    private String[]  actionLabels = new String[0];

    // Per-action bit metadata (7-argument initLearner only; -1 otherwise).
    // svBits[a] = state-vector entry the action commands (-1 = none, DO_NOTHING);
    // expectedBits[a] = value of that entry after a successful command (-1 = unknown).
    private int[] svBits       = new int[0];
    private int[] expectedBits = new int[0];
    // Flip-conditioned admission; read from -Dstereotype.learner.flipConditioned
    // in every initLearner call (so tests can switch it per instance); without
    // svBits metadata it admits every sample.
    private boolean flipConditioned = false;
    // Admission counters since the last init or reset (written by saveRawStats).
    private long admittedSamples = 0;
    private long rejectedNoFlip  = 0;

    // Welford accumulators per (action, state entry); null until initLearner.
    private long[][]   count;   // [numActions][stateVecLen]
    private double[][] mean;    // [numActions][stateVecLen]
    private double[][] m2;      // [numActions][stateVecLen]

    /**
     * Sets the action metadata and creates zeroed accumulators. This is the
     * version the agent programs call; it passes empty bit arrays to the
     * 7-argument version, so every action gets bit -1 and the flip-conditioned
     * gate admits every sample. Calling it again discards all statistics.
     *
     * @param numActionsIn  number of actions, including DO_NOTHING
     * @param stateVecLenIn length of the state vector
     * @param uris          {@code Object[]} of String: WoT action type URI per
     *                      action ({@code ""} for DO_NOTHING); missing entries
     *                      become {@code ""}
     * @param values        {@code Object[]} of Boolean: value the action sends
     *                      ({@code true} = on or open); missing entries become false
     * @param labels        {@code Object[]} of String: readable label per action;
     *                      missing entries become {@code action_<index>}
     */
    @OPERATION
    public void initLearner(int numActionsIn,
                            int stateVecLenIn,
                            Object[] uris,
                            Object[] values,
                            Object[] labels) {
        initLearner(numActionsIn, stateVecLenIn, uris, values, labels,
                    new Object[0], new Object[0]);
    }

    /**
     * Like the 5-argument version, plus the per-action bit metadata that the
     * flip-conditioned gate needs. Called only by tests. Reads
     * {@code stereotype.learner.flipConditioned}, resets the admission counters
     * and logs {@code StereotypeLearner: init numActions=... stateVecLen=...
     * flipConditioned=...}.
     *
     * @param numActionsIn   number of actions, including DO_NOTHING
     * @param stateVecLenIn  length of the state vector
     * @param uris           WoT action type URI per action (see the 5-argument version)
     * @param values         value the action sends, per action
     * @param labels         readable label per action
     * @param svBitsIn       {@code Object[]} of Integer: state-vector entry the
     *                       action commands (-1 for DO_NOTHING or unknown;
     *                       missing entries become -1)
     * @param expectedBitsIn {@code Object[]} of Integer: value of that entry after
     *                       a successful command (1 on, 0 off, -1 unknown;
     *                       missing entries become -1)
     */
    @OPERATION
    public void initLearner(int numActionsIn,
                            int stateVecLenIn,
                            Object[] uris,
                            Object[] values,
                            Object[] labels,
                            Object[] svBitsIn,
                            Object[] expectedBitsIn) {
        this.numActions  = numActionsIn;
        this.stateVecLen = stateVecLenIn;
        this.actionUris   = new String[numActions];
        this.actionValues = new boolean[numActions];
        this.actionLabels = new String[numActions];
        this.svBits       = new int[numActions];
        this.expectedBits = new int[numActions];
        for (int a = 0; a < numActions; a++) {
            actionUris[a]   = (a < uris.length   && uris[a]   != null) ? String.valueOf(uris[a])   : "";
            actionValues[a] = (a < values.length && values[a] != null) && toBoolean(values[a]);
            actionLabels[a] = (a < labels.length && labels[a] != null) ? String.valueOf(labels[a]) : ("action_" + a);
            svBits[a]       = (a < svBitsIn.length       && svBitsIn[a]       != null) ? toInt(svBitsIn[a])       : -1;
            expectedBits[a] = (a < expectedBitsIn.length && expectedBitsIn[a] != null) ? toInt(expectedBitsIn[a]) : -1;
        }
        this.flipConditioned =
            Boolean.parseBoolean(System.getProperty("stereotype.learner.flipConditioned", "false"));
        this.admittedSamples = 0;
        this.rejectedNoFlip  = 0;
        count = new long[numActions][stateVecLen];
        mean  = new double[numActions][stateVecLen];
        m2    = new double[numActions][stateVecLen];
        LOGGER.info("StereotypeLearner: init numActions=" + numActions
                  + " stateVecLen=" + stateVecLen
                  + " flipConditioned=" + flipConditioned);
    }

    /**
     * Adds one step to the statistics of {@code actionIdx}: for every state
     * entry {@code s} below the shortest of the two vectors and the configured
     * length, the change {@code stateAfter[s] - stateBefore[s]} (entries
     * converted to int) updates count, mean and sum of squared deviations.
     *
     * <p>Does nothing if {@code initLearner} has not run or {@code actionIdx} is
     * out of range. With the flip-conditioned gate on and a commanded bit known
     * for the action, a step in which that bit did not change to the expected
     * value (or lies beyond the vectors) is not counted and increments
     * {@code rejectedNoFlip}. A vector entry that is not a number raises a
     * {@link NumberFormatException}, which makes the operation fail.
     *
     * @param stateBefore state vector before the action ({@code QLearner.encodeState})
     * @param actionIdx   index of the action taken
     * @param stateAfter  state vector after the action
     */
    @OPERATION
    public void observe(Object[] stateBefore, int actionIdx, Object[] stateAfter) {
        if (count == null || actionIdx < 0 || actionIdx >= numActions) return;
        int len = Math.min(stateBefore.length, Math.min(stateAfter.length, stateVecLen));
        // Flip-conditioned admission: with the gate on and a commanded bit
        // known for this action, count the sample only if that bit changed
        // to its expected value. Actions without a bit (DO_NOTHING) always count.
        if (flipConditioned && actionIdx < svBits.length && svBits[actionIdx] >= 0) {
            int bit = svBits[actionIdx];
            if (bit >= len) { rejectedNoFlip++; return; }
            int before = toInt(stateBefore[bit]);
            int after  = toInt(stateAfter[bit]);
            boolean flipped = before != after
                && (expectedBits[actionIdx] < 0 || after == expectedBits[actionIdx]);
            if (!flipped) { rejectedNoFlip++; return; }
        }
        admittedSamples++;
        for (int s = 0; s < len; s++) {
            double delta = toInt(stateAfter[s]) - toInt(stateBefore[s]);
            // Welford online update (m2 = sum of squared deviations from the mean)
            count[actionIdx][s] += 1;
            double n = count[actionIdx][s];
            double oldMean = mean[actionIdx][s];
            double newMean = oldMean + (delta - oldMean) / n;
            mean[actionIdx][s] = newMean;
            m2[actionIdx][s]  += (delta - oldMean) * (delta - newMean);
        }
    }

    /**
     * Writes the significant effects as a Turtle file (overwriting it).
     *
     * <p>A pair (action {@code a}, state entry {@code s}) is written when
     * {@code n >= minSamples}, {@code |mean| / max(sd / sqrt(n), 1e-9) >= zCutoff}
     * and {@code |mean| >= minEffect} (sd = sample standard deviation with
     * {@code n - 1}; checked in this order). The comment line
     * {@code # test: |mean|/SEM > zCutoff ...} that the method writes into the
     * file says "&gt;"; the code admits equality.
     *
     * <p>File content: {@code @prefix} lines for {@code learned:}
     * ({@code http://example.org/was/learned#}), {@code elem:} (declared but
     * not used by any triple) and {@code xsd:}; three comment lines with the
     * gate values; then per admitted pair one blank node
     * {@code [] a learned:DiscoveredEffect} with {@code learned:action} (the
     * WoT action URI as IRI; omitted when empty), {@code learned:actionValue}
     * (xsd:boolean), {@code learned:actionLabel}, {@code learned:slotIndex}
     * (xsd:int), {@code learned:meanDelta}, {@code learned:stddev},
     * {@code learned:sem} (xsd:double, 4 decimals), {@code learned:zScore}
     * (xsd:double, 2 decimals) and {@code learned:samples} (xsd:int). Numbers
     * are formatted with {@code Locale.ROOT}. A file without admitted pairs
     * holds only the header. The log line
     * {@code saveLearnedStereotypes: <k> effects written to <file> (considered=...
     * rejected: samples=..., z=..., effect=...)} gives the counts. Readers: see
     * the class comment.
     *
     * <p>On error: before {@code initLearner} a warning is logged and no file is
     * written. An I/O error is logged as a warning; the file may then be
     * incomplete.
     *
     * @param filename path of the Turtle file, relative to the working directory
     *                 of the JVM (the agent passes
     *                 {@code learned_stereotypes_<true|false><suffix>.ttl})
     */
    @OPERATION
    public void saveLearnedStereotypes(String filename) {
        if (count == null) {
            LOGGER.warning("saveLearnedStereotypes: learner not initialised — skipping");
            return;
        }
        int written = 0;
        int considered = 0;
        int rejectedSamples = 0;
        int rejectedZ = 0;
        int rejectedEffect = 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("@prefix learned: <http://example.org/was/learned#> .");
            pw.println("@prefix elem:    <http://www.w3id.org/elementary#> .");
            pw.println("@prefix xsd:     <http://www.w3.org/2001/XMLSchema#> .");
            pw.println();
            pw.println("# Auto-generated by tools.StereotypeLearner");
            pw.println("# test: |mean|/SEM > zCutoff  AND  |mean| >= minEffect  AND  n >= minSamples");
            pw.println("# zCutoff="  + Z_CUTOFF
                     + "  minSamples=" + MIN_SAMPLES
                     + "  minEffect="  + MIN_EFFECT);
            pw.println();
            for (int a = 0; a < numActions; a++) {
                for (int s = 0; s < stateVecLen; s++) {
                    long n = count[a][s];
                    considered++;
                    if (n < MIN_SAMPLES) { rejectedSamples++; continue; }
                    double mu  = mean[a][s];
                    double var = (n > 1) ? (m2[a][s] / (n - 1)) : 0.0;
                    double sd  = Math.sqrt(Math.max(var, 0.0));
                    // Standard error of the mean, sd / sqrt(n): the test asks
                    // whether the mean change differs from zero.
                    double sem = sd / Math.sqrt(n);
                    double zScore = Math.abs(mu) / Math.max(sem, EPS);
                    if (zScore < Z_CUTOFF)               { rejectedZ++;      continue; }
                    if (Math.abs(mu) < MIN_EFFECT)       { rejectedEffect++; continue; }
                    pw.println("[] a learned:DiscoveredEffect ;");
                    if (!actionUris[a].isEmpty()) {
                        pw.println("   learned:action      <" + actionUris[a] + "> ;");
                    }
                    pw.println("   learned:actionValue \""
                             + (actionValues[a] ? "true" : "false")
                             + "\"^^xsd:boolean ;");
                    pw.println("   learned:actionLabel \"" + escapeTurtle(actionLabels[a]) + "\" ;");
                    pw.println("   learned:slotIndex   \"" + s + "\"^^xsd:int ;");
                    pw.println("   learned:meanDelta   \"" + String.format(java.util.Locale.ROOT, "%.4f", mu) + "\"^^xsd:double ;");
                    pw.println("   learned:stddev      \"" + String.format(java.util.Locale.ROOT, "%.4f", sd) + "\"^^xsd:double ;");
                    pw.println("   learned:sem         \"" + String.format(java.util.Locale.ROOT, "%.4f", sem) + "\"^^xsd:double ;");
                    pw.println("   learned:zScore      \"" + String.format(java.util.Locale.ROOT, "%.2f", zScore) + "\"^^xsd:double ;");
                    pw.println("   learned:samples     \"" + n + "\"^^xsd:int .");
                    pw.println();
                    written++;
                }
            }
            LOGGER.info("saveLearnedStereotypes: " + written + " effects written to " + filename
                      + " (considered=" + considered
                      + " rejected: samples=" + rejectedSamples
                      + ", z=" + rejectedZ
                      + ", effect=" + rejectedEffect + ")");
        } catch (IOException e) {
            LOGGER.warning("saveLearnedStereotypes: failed to write " + filename
                         + " — " + e.getMessage());
        }
    }

    /**
     * Writes all accumulators, without any gate, with the action metadata as a
     * CSV file (overwriting it). This is the input format of
     * {@link OverlaySynthesizer} ({@code OverlaySynthesizer.RawStats.parse}).
     * Only tests call this operation; no agent program, runner or workflow does,
     * so no run produces such a file.
     *
     * <p>Format: a version line and five {@code key=value} comment lines, the
     * column header, then one row per (action, state entry) in action order.
     * {@code mean} and {@code sd} are written with {@code Double.toString} so
     * that they read back exactly; commas in labels become semicolons; an action
     * URI is written as it is. Example of the head:
     * <pre>
     *   # stereotype_learner_raw_stats_v1
     *   # numActions=5
     *   # stateVecLen=3
     *   # flipConditioned=true
     *   # admittedSamples=1234
     *   # rejectedNoFlip=56
     *   actionIdx,wotActionUri,actionValue,actionLabel,svBit,expectedBit,slot,n,mean,sd
     * </pre>
     *
     * <p>On error: before {@code initLearner} a warning is logged and no file is
     * written. An I/O error is logged as a warning; the file may then be
     * incomplete.
     *
     * @param filename path of the CSV file
     */
    @OPERATION
    public void saveRawStats(String filename) {
        if (count == null) {
            LOGGER.warning("saveRawStats: learner not initialised — skipping");
            return;
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("# stereotype_learner_raw_stats_v1");
            pw.println("# numActions=" + numActions);
            pw.println("# stateVecLen=" + stateVecLen);
            pw.println("# flipConditioned=" + flipConditioned);
            pw.println("# admittedSamples=" + admittedSamples);
            pw.println("# rejectedNoFlip=" + rejectedNoFlip);
            pw.println("actionIdx,wotActionUri,actionValue,actionLabel,svBit,expectedBit,slot,n,mean,sd");
            for (int a = 0; a < numActions; a++) {
                int svBit  = (a < svBits.length)       ? svBits[a]       : -1;
                int expBit = (a < expectedBits.length) ? expectedBits[a] : -1;
                for (int s = 0; s < stateVecLen; s++) {
                    long n = count[a][s];
                    double mu  = mean[a][s];
                    double var = (n > 1) ? (m2[a][s] / (n - 1)) : 0.0;
                    double sd  = Math.sqrt(Math.max(var, 0.0));
                    pw.println(a + ","
                             + actionUris[a] + ","
                             + actionValues[a] + ","
                             + actionLabels[a].replace(',', ';') + ","
                             + svBit + ","
                             + expBit + ","
                             + s + ","
                             + n + ","
                             + mu + ","
                             + sd);
                }
            }
            LOGGER.info("saveRawStats: " + (numActions * stateVecLen)
                      + " cells written to " + filename
                      + " (flipConditioned=" + flipConditioned
                      + " admitted=" + admittedSamples
                      + " rejectedNoFlip=" + rejectedNoFlip + ")");
        } catch (IOException e) {
            LOGGER.warning("saveRawStats: failed to write " + filename
                         + " — " + e.getMessage());
        }
    }

    /**
     * Sets all accumulators and the two admission counters to zero; the action
     * metadata and the gate setting stay. Does nothing before
     * {@code initLearner}. No agent program calls it.
     */
    @OPERATION
    public void resetStats() {
        if (count == null) return;
        for (int a = 0; a < numActions; a++) {
            java.util.Arrays.fill(count[a], 0L);
            java.util.Arrays.fill(mean[a], 0.0);
            java.util.Arrays.fill(m2[a],   0.0);
        }
        admittedSamples = 0;
        rejectedNoFlip  = 0;
    }

    /**
     * Logs one INFO line {@code StereotypeLearner stats: total samples=<sum of
     * all counts> populated cells=<cells with a count>/<all cells>} (or
     * {@code StereotypeLearner: not initialised}). The total counts each step
     * once per state entry. Called by the training program after
     * {@link #saveLearnedStereotypes}.
     */
    @OPERATION
    public void printStats() {
        if (count == null) {
            LOGGER.info("StereotypeLearner: not initialised");
            return;
        }
        long total = 0;
        int  filled = 0;
        for (int a = 0; a < numActions; a++) {
            for (int s = 0; s < stateVecLen; s++) {
                total += count[a][s];
                if (count[a][s] > 0) filled++;
            }
        }
        LOGGER.info("StereotypeLearner stats: total samples=" + total
                  + " populated cells=" + filled
                  + "/" + (numActions * stateVecLen));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Converts a Jason term to int; see {@link Converters#toInt}. */
    private static int toInt(Object o) { return Converters.toInt(o); }

    /** Converts a Jason term to boolean; see {@link Converters#toBoolean}. */
    private static boolean toBoolean(Object o) { return Converters.toBoolean(o); }

    /**
     * Escapes a label for a Turtle string literal (quote, backslash, newline,
     * carriage return, tab); {@code null} becomes the empty string.
     */
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
