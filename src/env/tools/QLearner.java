package tools;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

/**
 * Tabular Q-learning artifact (CArtAgO) of the illuminance controller. It holds
 * one Q-table per zone and implements the reward, the update rule, the action
 * choice with the soft preference from the knowledge graph, the fault detector
 * of the fault stage and the files that record a run.
 *
 * WHERE IT SITS
 *   The four agent programs in src/agt create it with
 *   makeArtifact("qlearner", "tools.QLearner", ...) and call configureQLearner:
 *   - illuminance_controller_agent_ql.asl: training (task_ql.jcm, Gradle task
 *     taskQl, started by run_full_project.ps1; Phases 1 and 4);
 *   - illuminance_controller_agent_bench.asl: test of a trained table
 *     (task_bench.jcm, taskBench, run_full_project.ps1; Phases 1 and 4);
 *   - illuminance_controller_agent_adapt.asl: fault stage (task_adapt.jcm,
 *     taskAdapt, run_phase2_adapt.ps1; Phase 2);
 *   - illuminance_controller_agent_dynamics.asl (task_dynamics.jcm,
 *     taskDynamics, run_phase3_dynamics.ps1; Phase 3) uses it only for the
 *     action list (getNumActions, getActionMetadataForLearner, actionToWoT) and
 *     learns nothing.
 *   The artifact never talks to the room: the agent reads the room through
 *   LabEnvironment, passes state vectors in and sends the chosen action. It owns
 *   one StereotypeReasoner (a plain Java class) that reads the lab's knowledge
 *   graph once in configureQLearner. It defines no observable properties; every
 *   result is returned through an output parameter (OpFeedbackParam). Unit tests
 *   in src/test/java/tools call the package-private static helpers
 *   (computeActionSeed, armMixedIntoSeed, the parse*Prop readers,
 *   maskingCoFeederPresent).
 *
 * READS AND WRITES
 *   Reads the knowledge-graph files named in configureQLearner (through the
 *   reasoner), a trained Q-table with its sidecar files (loadQTable) and the
 *   IV statistics (loadIVStats, through the reasoner). Writes, under file names
 *   chosen by the agent: Q-tables (saveQTable), per-episode metrics
 *   (saveMetrics), visit coverage (saveCoverage), first goal per start state
 *   (saveFirstGoalPerScenario), IV statistics (saveIVStats) and the fault-stage
 *   recovery row (saveRecoveryLog). The runners read some of its log lines
 *   (see 10 below).
 *
 * MAP OF THE CLASS
 *   1. State encoding. The state is a vector of small integers: the light rank
 *      of every zone, 0/1 for every switch, the sun rank. Its layout (number of
 *      entries, values per entry, which entries are zone levels and which is
 *      the sun) comes from the knowledge graph through the reasoner, so the
 *      number of states differs per lab. configureQLearner builds the strides;
 *      index = sum of value_i * stride_i (stateVecToIndex, decodeState);
 *      encodeState builds the vector from a room reading.
 *   2. Action registry. The reasoner's action list (getAllActions): an ON and
 *      an OFF action per switch, plus DO_NOTHING (wotActionType == null). It is
 *      the same in both arms. actionToWoT, wotTypeToActionIndex and
 *      getActionMetadataForLearner translate indices.
 *   3. Starting values. Knowledge arm (useStereotypes, stereo.priorScale > 0):
 *      initWithStereotypes takes every cell from
 *      StereotypeReasoner.getInitPenaltyForZone. Otherwise every cell is 0.
 *   4. Action choice. getActionFromState: with probability epsilon a random
 *      action from computeApplicableActions, otherwise greedyAction: the
 *      highest sum of the zone tables plus, in the knowledge arm, the soft
 *      preference (reasoner prior x weight that falls linearly over the
 *      episodes x adaptive-trust factor x per-cell fade) and the non-fading
 *      energy preference; ties are broken at random. No action is forbidden.
 *      getActionFromStateAntiStuck is a variant for the test agent.
 *   5. Update rule (calculateQ). For every zone z:
 *        Q_z(s,a) += alpha * (r_z + gamma * Q_z(s', a*) - Q_z(s,a))
 *      with alpha = 0.1, gamma = 0.9 and a* = jointArgmaxAction(s'), the one
 *      non-blacklisted action that is best for the sum of all zone tables.
 *   6. Reward. computeZoneReward gives every zone its own reward; calculateQ
 *      clips it to +-reward.clip, divides it by the number of zones and, only
 *      when reward.shaping=pbrs, adds a shaping term after that.
 *   7. Fault stage (Phase 2). observeForFaults is the detector (one clear
 *      observation flags a device as dead or inverted; isFaultSuspect,
 *      zoneHasSuspectCoFeeder and zoneHasMaskingCoFeeder are its guards);
 *      getDiagnosticProbeAction picks a test opening of a blind;
 *      blacklistComponentAt removes a device's actions; warmRestart re-primes
 *      learning; beginReachabilityProbe, getProbeCombo, recordProbeRank and
 *      finishReachabilityProbe lower a zone's effective goal when the nominal
 *      goal can no longer be reached; updateRecoveryDetector and hasRecovered
 *      decide when the policy has settled; saveRecoveryLog writes the result.
 *   8. Episode bookkeeping. beginEpisode / beginEpisodeFromState /
 *      beginEpisodeForScenario, endEpisode, decayEpsilon, notifyGoalReached,
 *      hasConverged.
 *   9. Persistence of the Q-table. saveQTable writes the summed table, one
 *      table per zone and two sidecars (visit counts, adaptive-trust counts);
 *      loadQTable reads them back, matching columns by action label
 *      (mapHeaderToActions), and moves the preference fade to its end.
 *  10. Receipts and log lines. Besides the files above, the runners read some
 *      log lines of this class from the JVM log to check the effective
 *      settings before they write their receipts. run_full_project.ps1 reads "Reward: clip=...", "EnergyPrior: weight=..."
 *      and "setTrainingBudgetEpisodes: ..."; run_phase2_adapt.ps1 reads these
 *      and also "QLearner RNG: ..." and "Goal: [...]". The texts of these lines
 *      must therefore not change.
 *
 * RANDOM NUMBERS
 *   One java.util.Random (rng) drives exploration, the random action and the
 *   tie-break in greedyAction. configureQLearner seeds it with
 *   computeActionSeed: 42, XOR an arm constant unless the arms are paired
 *   (armMixedIntoSeed), XOR mix64(run.seed) when run.seed is not 0. The
 *   run modes phase1_v2_*, phase1b_v2_* and phase4_v2* in
 *   config/run_config.json pass phase1.protocolVersion=phase1-v2, which pairs
 *   the arms in training; the older run modes do not. In the fault stage the settings profile in config/run_config.json
 *   (phase2.adapt_settings) decides: "record" (the setting of every archived
 *   fault-stage run) leaves phase2.pairedArmRng false, so the arm constant is
 *   mixed in; "paired" and "aligned" set it true. The test agent calls
 *   setSeed(42) after configureQLearner, which replaces the stream with the
 *   same fixed seed in both arms and for every run.seed.
 *
 * SYSTEM PROPERTIES (read once when the class is loaded, except
 * phase1.protocolVersion, which configureQLearner reads)
 *   reward.clip                     per-zone reward limit, default 50.0
 *   reward.shaping                  "pbrs" adds the shaping term, default "none"
 *   stereo.priorScale               weight of the soft preference at its
 *                                   start; 0 also switches off the starting
 *                                   values, default 1.0
 *   stereo.priorDecayEpisodes       episodes over which the weight falls to its
 *                                   floor; default -1 = automatic (2500 until
 *                                   setTrainingBudgetEpisodes sets a quarter of
 *                                   the budget)
 *   stereo.priorDecayFloor          weight factor at the end of the fall,
 *                                   default 0.0
 *   stereo.priorFadeVisits          visits after which the preference of a
 *                                   (state, action) cell shrinks; 0 = off,
 *                                   default 25
 *   stereo.energyPriorWeight        weight of the energy preference, default 0.0
 *   stereo.adaptiveTrust            adaptive trust on/off, default false
 *   stereo.adaptiveTrust.minSamples default 50
 *   stereo.adaptiveTrust.floor      default 0.1
 *   run.seed                        mixed into the seed, default 0 (no change)
 *   phase1.protocolVersion          "phase1-v2" pairs the arms' seeds and adds
 *                                   the scenario columns to the metrics and
 *                                   first-goal files, default "legacy"
 *   phase2.pairedArmRng             pairs the arms' seeds, default false
 *   fault.relearn.epsBoost          epsilon after a warm restart (at least),
 *                                   default 0.30
 *   fault.detect.suspectMin         dead + inverted observations that make a
 *                                   device a fault suspect, default 2
 *   fault.recover.window            stable episodes that count as recovered,
 *                                   default 50
 *   fault.detect.ivMinSunRank       lowest sun rank at which a blind is judged
 *                                   or probed, default 2
 *   build.gradle forwards -P values of these keys to the JVM except
 *   stereo.priorFadeVisits and the four fault.* keys; no runner or run mode
 *   sets those five, so their defaults apply in every run the runners start.
 */
public class QLearner extends Artifact {

    private static final Logger LOGGER = Logger.getLogger(QLearner.class.getName());

    // -----------------------------------------------------------------------
    // Q-learning settings. alpha (learning rate) and gamma (discount) are
    // fixed. epsilon is the exploration rate: it starts at 0.3, decayEpsilon
    // multiplies it by epsilonDecay (setEpsilonDecay sets the lab's value)
    // down to epsilonMin, and warmRestart raises it again.
    // -----------------------------------------------------------------------
    private double alpha   = 0.1;
    private double gamma   = 0.9;
    private double epsilon = 0.3;
    private double epsilonDecay = 0.995;
    private final double epsilonMin   = 0.01;

    // Limit of every per-zone reward (calculateQ clips to +-REWARD_CLIP).
    // The +200 / -200 goal terms of computeZoneReward exceed the default 50.
    private static final double REWARD_CLIP = parseDoubleProp("reward.clip", 50.0);
    // Weight of the soft preference at the start of the fade (greedyAction).
    // With 0 the knowledge arm also gets no starting values
    // (configureQLearner), so it behaves like the plain arm.
    private static final double STEREO_PRIOR_SCALE = parseDoubleProp("stereo.priorScale", 1.0);
    // Weight of the energy preference. greedyAction subtracts
    // ENERGY_PRIOR_WEIGHT * ws:energyCost from the value of every "on" action
    // of a device with a declared energy cost, in the knowledge arm only. It
    // does not fade (no episode weight, no per-cell fade), so it is also
    // active in the test. Energy is not part of the reward and not of the
    // starting values, so this is the only way in which the knowledge arm can
    // prefer the cheaper of two devices with the same light effect. Only the
    // lab5 graph declares ws:energyCost, so the term is 0 in the other labs
    // whatever the weight. The Phase-4 run modes pass 2.0.
    private static final double ENERGY_PRIOR_WEIGHT = parseDoubleProp("stereo.energyPriorWeight", 0.0);
    // Number of episodes over which the preference weight falls linearly to
    // its floor. PRIOR_DECAY_EPISODES_PROP is the property value (-1 =
    // automatic). In automatic mode priorDecayEpisodes is 2500 until
    // setTrainingBudgetEpisodes sets it to a quarter of the episode budget; a
    // positive property value is used as is and setTrainingBudgetEpisodes then
    // leaves it alone.
    private static final int    PRIOR_DECAY_EPISODES_PROP =
        parseIntProp("stereo.priorDecayEpisodes", -1);
    private int                 priorDecayEpisodes        =
        PRIOR_DECAY_EPISODES_PROP > 0 ? PRIOR_DECAY_EPISODES_PROP : 2500;
    // Factor of the preference weight at the end of the linear fall
    // (weight = STEREO_PRIOR_SCALE * PRIOR_DECAY_FLOOR from then on).
    private static final double PRIOR_DECAY_FLOOR    = parseDoubleProp("stereo.priorDecayFloor", 0.0);
    // Per-cell fade: once a (state, action) cell has more than this many
    // visits, its preference is multiplied by PRIOR_FADE_VISITS / visits, so
    // the learned value of a well-visited cell decides. 0 switches the fade off.
    private static final int    PRIOR_FADE_VISITS = parseIntProp("stereo.priorFadeVisits", 25);

    // -----------------------------------------------------------------------
    // Reward shaping, a switch that is off by default (reward.shaping=none).
    // With reward.shaping=pbrs, calculateQ adds to every per-zone reward the
    // term F_z = gamma * Phi_z(s') - Phi_z(s), with
    //   Phi_z(s) = -|level_z(s) - effectiveGoal_z|,
    // multiplied by 1 / (number of zones). This is the form of potential-based
    // shaping (Ng, Harada and Russell 1999), but the implementation does not
    // meet the conditions of their policy-invariance result, so it must not be
    // assumed to leave the learned policy unchanged:
    //   - F is added AFTER the reward limit, so a shaped reward can lie
    //     outside +-REWARD_CLIP / zones;
    //   - the potential uses effectiveGoal, which the fault stage can lower
    //     during a run, so the potential function can change;
    //   - the update bootstraps from s' in every step; a state at the goal
    //     is not treated as terminal.
    // The run modes in config/run_config.json set it (key reward_shaping);
    // among the phase1_v2_* modes only phase1_v2_pbrs_only uses "pbrs".
    private static final String  REWARD_SHAPING        = System.getProperty("reward.shaping", "none");
    private static final boolean REWARD_SHAPING_PBRS   = "pbrs".equalsIgnoreCase(REWARD_SHAPING);

    // -----------------------------------------------------------------------
    // Adaptive trust, a switch that is off by default. When
    // stereo.adaptiveTrust=true (Boolean.parseBoolean: any other text means
    // false), calculateQ keeps in the knowledge arm, per action and sun rank
    // (sunBucketOf of the state before the step), the running mean of
    // the fraction of zone levels whose observed direction of change matched
    // the knowledge graph's prediction (only zones with a non-zero
    // prediction count). Once an (action, sun rank) pair has at least
    // ADAPTIVE_TRUST_MIN_SAMPLES such steps, greedyAction multiplies that
    // action's preference by this mean, but by no less than
    // ADAPTIVE_TRUST_FLOOR.
    private static final boolean ADAPTIVE_TRUST               = Boolean.parseBoolean(System.getProperty("stereo.adaptiveTrust", "false"));
    private static final int     ADAPTIVE_TRUST_MIN_SAMPLES   = parseIntProp   ("stereo.adaptiveTrust.minSamples", 50);
    private static final double  ADAPTIVE_TRUST_FLOOR         = parseDoubleProp("stereo.adaptiveTrust.floor",      0.1);

    // Run seed (-Prun.seed): mixed into the seed of the action-selection
    // stream by computeActionSeed; 0 leaves the seed unchanged.
    private static final long    RUN_SEED                     = parseLongProp("run.seed", 0L);

    // Paired random stream for the two arms of the fault stage. With false
    // (default) and the legacy protocol version, the arm constant is mixed
    // into the action-selection seed, so the knowledge arm and the plain arm
    // draw from different streams; every archived fault-stage run was seeded
    // this way (settings profile "record"). With true (settings profiles
    // "paired" and "aligned") the arm constant is left out and both arms start
    // from the same seed. Unlike phase1.protocolVersion this switch changes
    // nothing else (no file format).
    private static final boolean PAIRED_ARM_RNG               = parseBooleanProp("phase2.pairedArmRng", false);

    // The numeric property readers below return the default only when the
    // property is absent or empty. A value that is present but cannot be
    // parsed is a configuration error: the reader logs it (SEVERE) and throws
    // an IllegalArgumentException instead of silently running with the
    // default. The readers run in the static initialisers of this class, so
    // the class then fails to load and the artifact cannot be created.
    // reward.shaping and stereo.adaptiveTrust are read without these readers.
    static double parseDoubleProp(String key, double dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return dflt;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw malformedProperty(key, v, "a decimal number");
        }
    }
    static int parseIntProp(String key, int dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return dflt;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw malformedProperty(key, v, "a whole number");
        }
    }
    static long parseLongProp(String key, long dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return dflt;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw malformedProperty(key, v, "a whole number");
        }
    }
    /** Strict boolean reader: absent or empty gives the default, otherwise
     *  only "true" or "false" (any letter case) are accepted. */
    static boolean parseBooleanProp(String key, boolean dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return dflt;
        if ("true".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v)) return false;
        throw malformedProperty(key, v, "true or false");
    }
    /** Logs and returns (does not throw) the exception for a malformed property. */
    private static IllegalArgumentException malformedProperty(String key, String value, String expected) {
        String msg = "Malformed system property -D" + key + "='" + value
                   + "': expected " + expected + ". Refusing to fall back to the default.";
        LOGGER.severe(msg);
        return new IllegalArgumentException(msg);
    }
    /** SplitMix64 finalizer — spreads a small seed across all 64 bits so
     *  XOR-mixing tiny run.seed values (1..N) still flips most bits of the
     *  base seed and yields well-separated PRNG streams. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // Episode clock of the preference fade (greedyAction). The three
    // beginEpisode* operations add 1 (endEpisode does not). The test agent
    // never begins episodes, so loadQTable sets the clock to
    // priorDecayEpisodes (the end of the fade); setEpisodeCounter can also set
    // it, and warmRestart sets it back to 0.
    private int currentEpisodeNum = 0;

    // -----------------------------------------------------------------------
    // State and action dimensions, set by configureQLearner from the
    // reasoner's state-slot registry: nStates = product of domainSizes;
    // strides[i] = product of domainSizes[i+1 .. end].
    // -----------------------------------------------------------------------
    private int   nStates;
    private int   stateVecLen;
    private int[] domainSizes;       // [stateVecLen] number of values of each entry
    private int[] strides;           // [stateVecLen] — strides[stateVecLen-1] == 1
    private int[] zoneLevelIndices;  // zone 0-based → state-vector slot
    private int   sunshineIndex;     // -1 if no sunshine slot
    private int nActions;  // size of the reasoner's action list

    // -----------------------------------------------------------------------
    // Learning state
    // -----------------------------------------------------------------------
    private double[][][] qTables;    // Per-zone Q-tables [numZones][nStates][nActions]
    private int[]        goal;       // nominal target rank per zone, length = numZones
    // Effective target rank per zone. Starts as a copy of goal; in the fault
    // stage finishReachabilityProbe (or setEffectiveGoal) lowers it to the
    // closest reachable rank when the reachability probe finds the nominal
    // rank out of reach. isTerminal, the reward and the shaping potential use
    // effectiveGoal; recordProbeRank and the reported shortfall use goal.
    private int[]        effectiveGoal;
    // Reachability-probe scratch state (populated by beginReachabilityProbe).
    private int[][]      probeSurvivors;  // rows = {onActionIdx, offActionIdx} of each surviving actuator
    private int          probeBestRank = -1;
    private int          probeBestDist = Integer.MAX_VALUE;
    private boolean    useStereotypes;     // true = knowledge arm (ql_true)
    // Hard masking switch. Only setMaskStrict changes it, and no agent
    // program, runner, workflow or run mode calls setMaskStrict, so every run
    // uses false: all non-blacklisted actions are selectable and the knowledge
    // graph acts only through the soft preference.
    private boolean    maskStrict = false;
    private Random     rng;                // action-selection stream (see class comment)
    private int        firstGoalEpisode = -1;
    private int        goalCount        = 0;
    // Value of phase1.protocolVersion; "phase1-v2" pairs the arms' seeds and
    // selects the file formats of saveMetrics and saveFirstGoalPerScenario.
    private String     protocolVersion  = "legacy";

    // Knowledge reader, created in both arms: it supplies the action list and
    // the state layout in both, the starting values and preferences only in
    // the knowledge arm.
    private StereotypeReasoner reasoner;
    // Action metadata from reasoner (indexed by action index)
    private StereotypeReasoner.ActionInfo[] actionInfos;

    // Adaptive-trust counts (used only when ADAPTIVE_TRUST is true), indexed
    // [action][sun rank]: sum of the per-step match fractions and number of
    // steps. Kept per sun rank because a prediction can be right at one sun
    // rank and wrong at another (a blind opening). With no sunshine slot
    // there is a single column. saveQTable and loadQTable persist them.
    private double[][] actionCalSum;
    private long[][]   actionCalN;

    // Per-episode metrics (rows of saveMetrics) and their accumulators.
    // Only the first two zones have a reward column.
    private List<double[]> episodeMetrics = new ArrayList<>();
    private double episodeCumRewardZ1 = 0;
    private double episodeCumRewardZ2 = 0;
    private int episodeSteps = 0;
    // Wasted-action counters (reset each episode)
    private int episodeWastedByPenalty  = 0; // steps where a penalty term fired in some zone (regression / no-effect / lost goal)
    private int episodeWastedByNoEffect = 0; // steps where the state vector did not change

    // Last dispatched action index (set by notifyActionDispatched, read by getLastDispatchedAction)
    private int lastDispatchedAction = -1;

    // Largest |q_new - q_old| over the zones in the latest calculateQ
    // (getLastQDelta), and the largest such value in the current episode
    // (convergence test in endEpisode).
    private double lastBellmanDelta      = 0.0;
    private double episodeMaxBellmanDelta = 0.0;

    // Visits per (state, action), counted in calculateQ. Used by the per-cell
    // fade, saveCoverage and the visits sidecar; warmRestart zeroes the
    // columns of blacklisted actions.
    private long[][] visitCounts; // [nStates][nActions]

    // First goal per start state (legacy format of saveFirstGoalPerScenario):
    // start-state index -> episode number of the first goal from it.
    private final Map<Integer, Integer> firstGoalByStartState = new java.util.LinkedHashMap<>();
    private int currentEpisodeStartState = -1; // set by beginEpisodeFromState / beginEpisodeForScenario
    // First goal per scenario, counted in presentations (phase1-v2 format).
    private final FirstGoalPresentationTracker firstGoalPresentations =
            new FirstGoalPresentationTracker();
    private int currentEpisodeScenarioId = -1;

    // Convergence test of training (hasConverged): the largest per-step
    // Bellman change of an episode stayed below CONVERGENCE_THRESHOLD in
    // convergenceWindow consecutive episodes.
    private int    convergenceWindow = 100;
    private int    convergenceCount  = 0; // consecutive episodes within threshold
    private static final double CONVERGENCE_THRESHOLD = 1e-3;

    // -----------------------------------------------------------------------
    // Fault stage (Phase 2): detection, blacklist and re-learning.
    // -----------------------------------------------------------------------
    // observeForFaults flags a device as defective on the FIRST observation
    // in which the knowledge graph's prediction for one of its actions is
    // clearly contradicted; there is no evidence threshold:
    //   - DEAD:     the switch did not change, or it changed but no zone it
    //               should move responded;
    //   - INVERTED: a zone moved against the predicted direction.
    // False alarms are avoided by judging only falsifiable steps and by
    // abstaining in doubtful ones (see observeForFaults for the full list):
    // a zone already at the top or bottom rank, a device that feeds several
    // zones, a device the graph says nothing about, a zone fed by a device
    // already blacklisted, a zone with a fault-suspect or an active
    // co-feeder. Devices whose mechanism depends on a condition (IV-gated,
    // e.g. blinds and the sun) are judged only on their opening action and
    // only at sun rank IV_DETECT_MIN_SUN_RANK or higher. The agent then
    // calls blacklistComponentAt (removes the device's ON and OFF actions)
    // and warmRestart (re-primes learning over the remaining actions).
    private boolean[] blacklisted;     // [nActions] — true → removed from action space
    // Evidence counters per action, maintained by observeForFaults for every
    // judged step and read by isFaultSuspect; warmRestart sets them to 0.
    private int[]     faultObsN;       // [nActions] — judged (falsifiable) observations of the action
    private int[]     faultDeadN;      // [nActions] — observations with no zone response (dead)
    private int[]     faultInvertN;    // [nActions] — observations with inverted zone response
    // Version tags written to every row of saveRecoveryLog. They name the
    // detector and the recovery-log format; phase1.protocolVersion is a
    // separate setting with its own meaning.
    public static final String FAULT_DETECTOR_VERSION = "fault-detector-v2";
    public static final String PHASE2_PROTOCOL_VERSION = "phase2-v2";
    // Every successful blacklist event as "wotActionType@episode", in order
    // (column BlacklistEvents of saveRecoveryLog).
    private final java.util.List<String> blacklistEvents = new java.util.ArrayList<>();
    // Zones whose baseline is spoiled. When blacklistComponentImpl removes at
    // least one action of a device, every zone in that device's affectedZones
    // is marked (for any kind of device, blinds included): a removed device
    // keeps its last physical state, so a change or non-change in its zones
    // is no longer attributable to another device. observeForFaults skips
    // marked zones. Never cleared.
    private boolean[] zoneCauseContaminated;  // [nZones]
    // wotActionType of every IV-gated device, i.e. every device whose "on"
    // action has hasIV: a mechanism with an independent variable (a blind
    // and the sun) or, in the labs with ws:powerGates, a lamp behind a plug.
    // The reasoner sets hasIV only on the "on" action; both actions share one
    // wotActionType, so this set identifies both actions of such a device.
    // The sun-rank condition of observeForFaults and getDiagnosticProbeAction
    // is applied to every member, also to a power-gated lamp. The set is
    // empty when no action has hasIV (the brick knowledge layer), and then
    // blinds are judged like lamps and never probed.
    private java.util.Set<String> ivGatedComponents = new java.util.HashSet<>();
    // IV-gated devices that observeForFaults has judged healthy on an opening
    // (whether the opening came from the probe or from the policy);
    // getDiagnosticProbeAction does not probe them again.
    private java.util.Set<String> probeVerified = new java.util.HashSet<>();
    // Lower bound for epsilon after warmRestart.
    private static final double FAULT_EPS_BOOST    = parseDoubleProp("fault.relearn.epsBoost",   0.30);
    // Dead + inverted observations after which a device counts as a fault
    // suspect (isFaultSuspect; one inverted observation is enough on its own).
    private static final int    FAULT_SUSPECT_MIN  = parseIntProp   ("fault.detect.suspectMin",   2);

    // Recovery detector of the fault stage. "Recovered" means that the greedy
    // policy (jointArgmaxAction over every state, blacklisted actions
    // excluded) has not changed for RECOVERY_WINDOW consecutive episodes
    // (updateRecoveryDetector, hasRecovered). It compares the ranking of
    // actions, not the size of the updates, so it can settle although the
    // raised epsilon keeps changing values and a goal that depends on the sun
    // is reached only in some episodes. warmRestart resets it.
    private int[]  recoveryPolicy      = null;  // last greedy policy snapshot
    private int    recoveryStableCount = 0;     // consecutive episodes with no policy change
    private static final int RECOVERY_WINDOW = parseIntProp("fault.recover.window", 50);

    // Lowest sun rank at which an IV-gated device is judged (observeForFaults)
    // or probed (getDiagnosticProbeAction). In the lab2 and lab3 flows a
    // blind adds 0.50 x sun to its own zone, so at sun rank 2 (sun 400) a
    // healthy opening adds 200 lux; below that, a healthy blind may not move
    // its zone by a whole rank and a null response is no evidence. 3 limits
    // judging to sun rank 3.
    private static final int IV_DETECT_MIN_SUN_RANK =
        parseIntProp("fault.detect.ivMinSunRank", 2);

    // -----------------------------------------------------------------------
    // CArtAgO initialisation
    // -----------------------------------------------------------------------

    /**
     * Set up the learner; every agent program calls this once, right after
     * creating the artifact. It seeds the action-selection stream
     * (computeActionSeed), creates the StereotypeReasoner over the given
     * knowledge-graph files (the reasoner reads stereo.knowledgeLayer
     * itself), takes the action list and the state layout from it, allocates
     * the Q-tables, visit counts and fault-stage arrays, fills the starting
     * values and logs the effective settings (lines that the runners read
     * back, see the class comment).
     *
     * Starting values: knowledge arm with stereo.priorScale > 0 from the
     * reasoner (initWithStereotypes); knowledge arm with stereo.priorScale = 0
     * and plain arm: all 0. When the reasoner reports a degraded state-slot
     * registry, a warning block is logged and the run continues with the
     * reasoner's fallback layout. Exceptions thrown by the reasoner are not
     * caught here; CArtAgO then reports the operation as failed to the agent.
     *
     * @param goal                     Jason list of target ranks, one per zone
     *                                 (its length is the number of zones).
     * @param useStereotypes           true = knowledge arm (ql_true), false = plain arm.
     * @param ontologyPaths            Jason list of classpath resource names of the
     *                                 lab's knowledge graph (the ont([...]) entry of
     *                                 the lab profile in lab_profiles.asl).
     * @param sunshineSatisfactionProb probability of sun rank 1 or higher, from the
     *                                 lab profile; passed to the reasoner, which
     *                                 stores it but does not read it.
     */
    @OPERATION
    public void configureQLearner(Object[] goal, boolean useStereotypes,
                                  Object[] ontologyPaths, double sunshineSatisfactionProb) {
        this.goal = new int[goal.length];
        for (int i = 0; i < goal.length; i++) this.goal[i] = toInt(goal[i]);
        // The effective goal starts equal to the nominal goal (see effectiveGoal).
        this.effectiveGoal = this.goal.clone();
        this.useStereotypes = useStereotypes;
        // With protocol phase1-v2 (or phase2.pairedArmRng=true) no arm constant
        // enters the seed, so both arms start from the same stream; the streams
        // drift apart once the arms make different choices.
        this.protocolVersion = System.getProperty("phase1.protocolVersion", "legacy");
        long baseSeed = computeActionSeed(RUN_SEED, useStereotypes, protocolVersion, PAIRED_ARM_RNG);
        this.rng            = new Random(baseSeed);

        // Convert Object[] to String[]
        String[] paths = new String[ontologyPaths.length];
        for (int i = 0; i < ontologyPaths.length; i++) {
            paths[i] = ontologyPaths[i].toString();
        }

        // The reasoner is created in both arms: it supplies the action list and
        // the state layout. Only the starting values and the preference depend
        // on the arm.
        reasoner = new StereotypeReasoner(paths, sunshineSatisfactionProb);
        if (reasoner.isDegraded()) {
            LOGGER.warning("============================================================");
            LOGGER.warning("  StereotypeReasoner is in DEGRADED mode: state-slot registry");
            LOGGER.warning("  could not be loaded from the ontology. Falling back to the");
            LOGGER.warning("  legacy 8-slot layout. Stereotype-guided Q-init may not match");
            LOGGER.warning("  the simulator topology — results should be treated as");
            LOGGER.warning("  diagnostic, not as a valid stereotype-init comparison.");
            LOGGER.warning("============================================================");
        }
        nActions = reasoner.getNumActions();
        actionInfos = reasoner.getAllActions();

        // The reasoner needs the per-zone targets for its target-direction
        // ("band mirror") terms; with the stereo.bandMirror* settings at their
        // 0.0 defaults these terms are 0.
        reasoner.setZoneTargets(this.goal);

        // Pull the state-vector layout from the reasoner slot registry and
        // build strides for the flat Q-table index.
        domainSizes      = reasoner.getStateDomainSizes();
        zoneLevelIndices = reasoner.getZoneLevelIndices();
        sunshineIndex    = reasoner.getSunshineIndex();
        stateVecLen      = domainSizes.length;
        strides          = new int[stateVecLen];
        int prod = 1;
        for (int i = stateVecLen - 1; i >= 0; i--) {
            strides[i] = prod;
            prod *= domainSizes[i];
        }
        nStates = prod;

        int numZones = this.goal.length;
        qTables = new double[numZones][nStates][nActions];
        visitCounts = new long[nStates][nActions];
        // Fault stage: per-action blacklist and evidence counters.
        blacklisted  = new boolean[nActions];
        faultObsN    = new int[nActions];
        faultDeadN   = new int[nActions];
        faultInvertN = new int[nActions];
        zoneCauseContaminated = new boolean[zoneLevelIndices.length];
        // Collect the IV-gated devices (see ivGatedComponents).
        ivGatedComponents.clear();
        for (StereotypeReasoner.ActionInfo ai : actionInfos) {
            if (ai != null && ai.hasIV && ai.wotActionType != null) {
                ivGatedComponents.add(ai.wotActionType);
            }
        }
        int sunBuckets = (sunshineIndex >= 0 && sunshineIndex < domainSizes.length)
                       ? Math.max(1, domainSizes[sunshineIndex]) : 1;
        actionCalSum = new double[nActions][sunBuckets];
        actionCalN   = new long[nActions][sunBuckets];

        if (useStereotypes && STEREO_PRIOR_SCALE > 0.0) {
            initWithStereotypes();
            LOGGER.info("QLearner initialised — STEREOTYPE MODE: ON (ontology-driven,"
                      + " knowledgeLayer=" + reasoner.getKnowledgeLayer() + ")");
            LOGGER.info("  Decomposed Q-tables with per-zone penalties from SPARQL.");
        } else if (useStereotypes) {
            // priorScale = 0 switches off the soft preference; the starting
            // values are switched off as well, so that the knowledge arm
            // learns like the plain arm (the control run modes with
            // stereo.priorScale = 0 rely on this). The energy preference is
            // not affected.
            for (double[][] zt : qTables) for (double[] row : zt) Arrays.fill(row, 0.0);
            LOGGER.info("QLearner initialised — STEREOTYPE MODE: ON, priorScale=0 → zero Q-init (H5 ablation)");
        } else {
            for (double[][] zt : qTables) for (double[] row : zt) Arrays.fill(row, 0.0);
            LOGGER.info("QLearner initialised — STEREOTYPE MODE: OFF (standard zero-init)");
        }

        LOGGER.info("  Goal: " + Arrays.toString(this.goal));
        LOGGER.info("  States=" + nStates + " Actions=" + nActions);
        LOGGER.info("  StateLayout: domain=" + Arrays.toString(domainSizes)
                  + " strides=" + Arrays.toString(strides)
                  + " zoneLevel=" + Arrays.toString(zoneLevelIndices)
                  + " sunshine=" + sunshineIndex);
        LOGGER.info("  α=" + alpha + " γ=" + gamma + " ε=" + epsilon);
        LOGGER.info("  Reward: clip=" + REWARD_CLIP
                  + " shaping=" + REWARD_SHAPING
                  + " priorScale=" + STEREO_PRIOR_SCALE
                  + " priorDecayEps=" + priorDecayEpisodes
                  + (PRIOR_DECAY_EPISODES_PROP <= 0 ? " (auto)" : "")
                  + " priorDecayFloor=" + PRIOR_DECAY_FLOOR
                  + " priorFadeVisits=" + PRIOR_FADE_VISITS
                  + " adaptiveTrust=" + ADAPTIVE_TRUST
                  + (ADAPTIVE_TRUST
                        ? " (minSamples=" + ADAPTIVE_TRUST_MIN_SAMPLES + " floor=" + ADAPTIVE_TRUST_FLOOR + ")"
                        : "")
                  + " runSeed=" + RUN_SEED);
        // States how the action-selection random stream was seeded, so a
        // runner can verify the seeding mode it requested
        // (run_phase2_adapt.ps1 reads this line).
        LOGGER.info("  QLearner RNG: pairedArmRng=" + PAIRED_ARM_RNG
                  + " protocolVersion=" + protocolVersion
                  + " armMixedIntoSeed=" + armMixedIntoSeed(protocolVersion, PAIRED_ARM_RNG)
                  + " baseSeed=" + baseSeed);
        // The energy-preference weight is read once when the class loads; it
        // appears in no other log line, so report it here for the runners.
        LOGGER.info("  EnergyPrior: weight=" + ENERGY_PRIOR_WEIGHT);
    }

    /** {@link #computeActionSeed(long, boolean, String, boolean)} with
     *  pairedArmRng = false (used by Phase1ProtocolV2Test). */
    static long computeActionSeed(long runSeed, boolean useStereotypes, String protocolVersion) {
        return computeActionSeed(runSeed, useStereotypes, protocolVersion, false);
    }

    /** True when the arm constant enters the seed: the protocol version is
     *  not "phase1-v2" and phase2.pairedArmRng is false. */
    static boolean armMixedIntoSeed(String protocolVersion, boolean pairedArmRng) {
        return !pairedArmRng && !"phase1-v2".equals(protocolVersion);
    }

    /**
     * Seed of the action-selection random stream: 42, XOR an arm constant
     * (0x5A5A... knowledge arm, 0xA5A5... plain arm) when armMixedIntoSeed is
     * true, XOR mix64(runSeed) when runSeed is not 0.
     *
     * @param runSeed         value of run.seed
     * @param useStereotypes  true = knowledge arm
     * @param protocolVersion value of phase1.protocolVersion
     * @param pairedArmRng    value of phase2.pairedArmRng
     * @return the seed passed to java.util.Random
     */
    static long computeActionSeed(long runSeed, boolean useStereotypes, String protocolVersion,
                                  boolean pairedArmRng) {
        long baseSeed = 42L;
        if (armMixedIntoSeed(protocolVersion, pairedArmRng)) {
            baseSeed ^= useStereotypes
                    ? 0x5A5A5A5A5A5A5A5AL : 0xA5A5A5A5A5A5A5A5L;
        }
        if (runSeed != 0L) baseSeed ^= mix64(runSeed);
        return baseSeed;
    }

    /**
     * Fill every (zone, state, action) cell with its starting value from
     * StereotypeReasoner.getInitPenaltyForZone (a penalty or a bonus, depending
     * on the rule that applies; see that method). Called by configureQLearner
     * in the knowledge arm only.
     */
    private void initWithStereotypes() {
        for (int s = 0; s < nStates; s++) {
            int[] fullSv = decodeState(s);
            for (int z = 0; z < goal.length; z++) {
                for (int a = 0; a < nActions; a++) {
                    qTables[z][s][a] = reasoner.getInitPenaltyForZone(fullSv, a, z, goal);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // State encoding / decoding
    // -----------------------------------------------------------------------

    /**
     * Build the state vector from one room reading (the four outputs of
     * LabEnvironment.readLabStatus). The vector has stateVecLen entries in the
     * lab's layout: zone ranks at the zone-level slots, the sun rank at the
     * sunshine slot, and 1/0 for every switch whose WoT state URI the
     * reasoner maps to a slot. Readings without a slot are ignored; slots
     * without a reading stay 0. Values are not range-checked here
     * (stateVecToIndex clamps them).
     *
     * @param zoneLevels      light rank per zone (0-based zone order).
     * @param sunshineRank    sun rank.
     * @param boolStateKeys   WoT state URIs of the switches.
     * @param boolStateValues their values (Boolean, or text parsed with Boolean.parseBoolean).
     * @param stateVec        output: the state vector as an Object[] of Integer.
     */
    @OPERATION
    public void encodeState(
            Object[] zoneLevels,
            int      sunshineRank,
            Object[] boolStateKeys,
            Object[] boolStateValues,
            OpFeedbackParam<Object[]> stateVec) {

        // Build state vector dynamically from the slot registry layout
        Object[] sv = new Object[stateVecLen];
        // Initialise all slots to 0
        for (int i = 0; i < stateVecLen; i++) sv[i] = 0;

        // Zone illuminance levels at the slots declared as "zone_level"
        for (int z = 0; z < zoneLevelIndices.length && z < zoneLevels.length; z++) {
            sv[zoneLevelIndices[z]] = toInt(zoneLevels[z]);
        }

        // Sunshine rank
        if (sunshineIndex >= 0 && sunshineIndex < stateVecLen) {
            sv[sunshineIndex] = sunshineRank;
        }

        // Populate boolean component states from WoT state URI → state vector index map
        Map<String, Integer> wotMap = reasoner.getWotStateToSvIndexMap();
        for (int i = 0; i < boolStateKeys.length; i++) {
            String uri = String.valueOf(boolStateKeys[i]);
            Integer svIdx = wotMap.get(uri);
            if (svIdx != null && svIdx < stateVecLen) {
                boolean val = boolStateValues[i] instanceof Boolean
                        ? (Boolean) boolStateValues[i]
                        : Boolean.parseBoolean(String.valueOf(boolStateValues[i]));
                sv[svIdx] = val ? 1 : 0;
            }
        }

        stateVec.set(sv);
        LOGGER.fine("encodeState: " + Arrays.toString(sv));
    }

    // -----------------------------------------------------------------------
    // Core Q-learning operations
    // -----------------------------------------------------------------------

    /**
     * One learning step for the transition (s, a, s'). For every zone z:
     * r_z = computeZoneReward, clipped to +-REWARD_CLIP and multiplied by
     * 1 / (number of zones); with reward.shaping=pbrs the shaping term is
     * added after that (see REWARD_SHAPING); then
     *   Q_z(s,a) += alpha * (r_z + gamma * Q_z(s', a*) - Q_z(s,a)),
     * where a* = jointArgmaxAction(s') is the same for all zones. There is no
     * special case for a terminal s'. The step also updates the episode
     * metrics (clipped rewards of zones 1 and 2, without shaping and before
     * the division; wasted-step counters), the visit count of (s, a), the
     * reasoner's IV statistics (recordActionOutcome) and, when adaptive trust
     * is on in the knowledge arm, the trust counts. The fault-stage agent
     * calls it only after the first detection. An action index outside the
     * table makes the operation fail (array index error).
     *
     * @param stateVec     state vector before the action.
     * @param action       index of the action taken.
     * @param nextStateVec state vector after the action.
     */
    @OPERATION
    public void calculateQ(Object[] stateVec, int action, Object[] nextStateVec) {
        int sIdx  = stateVecToIndex(stateVec);
        int sNext = stateVecToIndex(nextStateVec);

        // Per-zone updates. Each per-zone reward is clipped to
        // [-REWARD_CLIP, +REWARD_CLIP] and divided by the number of zones, so
        // the summed reward of all zones stays within the same range whatever
        // the zone count.
        double[] rewards = new double[goal.length];
        boolean anyZoneWastedByPenalty = false;
        double maxAbsDelta = 0.0;
        double zoneNorm = 1.0 / Math.max(1, goal.length);
        // Joint bootstrap target: all zones share ONE action, so a separate
        // max over actions per zone would over-estimate the next value. Every
        // zone's bootstrap uses the single action that maximises the SUM of
        // the zone values at s'.
        int aStarNext = jointArgmaxAction(sNext);
        for (int z = 0; z < goal.length; z++) {
            RewardResult rr = computeZoneReward(z, stateVec, action, nextStateVec);
            double rClipped = Math.max(-REWARD_CLIP, Math.min(REWARD_CLIP, rr.reward));
            double rForQ    = rClipped * zoneNorm;
            // Shaping term F = gamma * Phi(s') - Phi(s), Phi = -|level - effectiveGoal|,
            // added after the clip (see REWARD_SHAPING for why this is not
            // claimed to be policy-invariant).
            if (REWARD_SHAPING_PBRS
                    && z < zoneLevelIndices.length
                    && zoneLevelIndices[z] >= 0
                    && zoneLevelIndices[z] < stateVec.length) {
                int slot = zoneLevelIndices[z];
                int prevLevel = toInt(stateVec[slot]);
                int nextLevel = toInt(nextStateVec[slot]);
                int target    = effectiveGoal[z];
                double phiPrev = -Math.abs(prevLevel - target);
                double phiNext = -Math.abs(nextLevel - target);
                double F = gamma * phiNext - phiPrev;
                rForQ += F * zoneNorm;
            }
            rewards[z] = rClipped;
            if (rr.wastedByPenalty) anyZoneWastedByPenalty = true;
            double maxNextQz = qTables[z][sNext][aStarNext];
            double oldQz = qTables[z][sIdx][action];
            double newQz = oldQz + alpha * (rForQ + gamma * maxNextQz - oldQz);
            qTables[z][sIdx][action] = newQz;
            double absDelta = Math.abs(newQz - oldQz);
            if (absDelta > maxAbsDelta) maxAbsDelta = absDelta;
        }
        lastBellmanDelta = maxAbsDelta;
        if (maxAbsDelta > episodeMaxBellmanDelta) episodeMaxBellmanDelta = maxAbsDelta;

        // Episode metrics: clipped, unshaped rewards of zones 1 and 2
        episodeCumRewardZ1 += (goal.length > 0) ? rewards[0] : 0;
        episodeCumRewardZ2 += (goal.length > 1) ? rewards[1] : 0;
        episodeSteps++;

        // Wasted steps: a penalty term fired in some zone (regression /
        // no-effect / lost goal), or the state vector did not change at all
        if (anyZoneWastedByPenalty) episodeWastedByPenalty++;
        int[] prevSvArr = toIntArray(stateVec);
        int[] nextSvArr = toIntArray(nextStateVec);
        if (Arrays.equals(prevSvArr, nextSvArr)) episodeWastedByNoEffect++;

        // Visit count of (s, a): per-cell fade, coverage file, visits sidecar
        visitCounts[sIdx][action]++;

        // Let the reasoner update its outcome statistics, among them the tries
        // and successes of IV-gated "on" actions per IV rank (used by its
        // IV-unsat preference and written by saveIVStats)
        reasoner.recordActionOutcome(action, prevSvArr, nextSvArr);

        // Adaptive trust: per zone-level slot with a non-zero predicted
        // change, compare the predicted direction with the observed one; add
        // the matched fraction to the (action, sun rank) counts that
        // greedyAction reads.
        if (ADAPTIVE_TRUST && useStereotypes && actionCalSum != null) {
            int[] pred = reasoner.getActionPrediction(prevSvArr, action);
            int matched = 0, considered = 0;
            for (int z = 0; z < zoneLevelIndices.length; z++) {
                int slot = zoneLevelIndices[z];
                if (slot < 0 || slot >= pred.length) continue;
                int predSign   = Integer.signum(pred[slot]);
                if (predSign == 0) continue; // action makes no claim on this zone
                int actualSign = Integer.signum(nextSvArr[slot] - prevSvArr[slot]);
                considered++;
                if (predSign == actualSign) matched++;
            }
            if (considered > 0) {
                int sunBucket = sunBucketOf(prevSvArr);
                actionCalSum[action][sunBucket] += (double) matched / considered;
                actionCalN[action][sunBucket]   += 1;
            }
        }

        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            StringBuilder rbuf = new StringBuilder();
            for (int z = 0; z < goal.length; z++) {
                if (z > 0) rbuf.append(' ');
                rbuf.append("rZ").append(z + 1).append('=')
                    .append(String.format("%.1f", rewards[z]));
            }
            LOGGER.fine("calculateQ: s=" + sIdx + " a=" + action + " " + rbuf
                      + " s'=" + sNext);
        }
    }

    /**
     * Choose an action. With explore = true and probability epsilon: a
     * uniformly random action from computeApplicableActions (blacklisted
     * actions excluded). Otherwise: greedyAction, i.e. the highest sum of the
     * zone values plus, in the knowledge arm, the soft and the energy
     * preference, ties broken at random. Both branches draw from rng.
     *
     * @param stateVec  current state vector.
     * @param explore   true = epsilon-greedy (training, re-learning after a
     *                  fault), false = greedy (test, fault-stage monitoring).
     * @param action    output: index of the chosen action.
     */
    @OPERATION
    public void getActionFromState(Object[] stateVec, boolean explore,
                                   OpFeedbackParam<Integer> action) {
        int sIdx = stateVecToIndex(stateVec);
        int[] applicable = computeApplicableActions(stateVec);

        int chosen;
        if (explore && rng.nextDouble() < epsilon) {
            chosen = applicable[rng.nextInt(applicable.length)];
            LOGGER.fine("getActionFromState: EXPLORE → action=" + chosen);
        } else {
            chosen = greedyAction(sIdx, applicable);
            LOGGER.fine("getActionFromState: EXPLOIT → action=" + chosen);
        }
        action.set(chosen);
    }

    /**
     * Greedy choice that avoids recently taken actions. The test agent calls
     * it instead of getActionFromState(..., false, ...) only when its belief
     * bench_anti_stuck is true and its last three state vectors were equal;
     * the belief is false in the agent program and no runner changes it, so
     * the runners' tests do not use this operation.
     *   - recentActions null or without a valid index: same as the greedy
     *     getActionFromState, stuckFired = false.
     *   - Otherwise the valid indices in recentActions and every DO_NOTHING
     *     action are excluded and greedyAction chooses among the rest
     *     (ties random); stuckFired = true. If nothing would remain, the full
     *     applicable set is used and stuckFired = false.
     * Logs an INFO line when the exclusion took effect.
     *
     * @param stateVec      current state vector.
     * @param recentActions indices of the actions to avoid.
     * @param action        output: index of the chosen action.
     * @param stuckFired    output: true when the exclusion took effect.
     */
    @OPERATION
    public void getActionFromStateAntiStuck(Object[] stateVec,
                                            Object[] recentActions,
                                            OpFeedbackParam<Integer> action,
                                            OpFeedbackParam<Boolean> stuckFired) {
        int sIdx = stateVecToIndex(stateVec);
        int[] applicable = computeApplicableActions(stateVec);

        // Build the exclusion set from recentActions + DO_NOTHING action ids.
        // DO_NOTHING actions have wotActionType == null.
        boolean[] excluded = new boolean[nActions];
        boolean anyExclude = false;
        if (recentActions != null) {
            for (Object o : recentActions) {
                int a = toInt(o);
                if (a >= 0 && a < nActions) { excluded[a] = true; anyExclude = true; }
            }
        }
        if (anyExclude) {
            for (int a = 0; a < nActions; a++) {
                if (actionInfos[a].wotActionType == null) excluded[a] = true;
            }
        }

        // Filter applicable down to non-excluded actions.
        int[] filtered = applicable;
        if (anyExclude) {
            int n = 0;
            int[] buf = new int[applicable.length];
            for (int a : applicable) if (!excluded[a]) buf[n++] = a;
            if (n > 0) {
                filtered = new int[n];
                System.arraycopy(buf, 0, filtered, 0, n);
            } else {
                // All applicable actions were excluded: fall back to the full
                // set and do not signal stuckFired (no exclusion took effect).
                anyExclude = false;
            }
        }

        int chosen = greedyAction(sIdx, filtered);
        action.set(chosen);
        stuckFired.set(anyExclude);
        if (anyExclude) {
            LOGGER.info("getActionFromStateAntiStuck: STUCK fired → chose action=" + chosen
                      + " (excluded " + java.util.Arrays.toString(recentActions) + ")");
        }
    }

    /**
     * The selectable actions in the given state (computeApplicableActions):
     * every non-blacklisted action, because hard masking is never switched
     * on (see maskStrict). Used by the test agent.
     *
     * @param stateVec current state vector.
     * @param actions  output: Object[] of Integer action indices.
     */
    @OPERATION
    public void getApplicableActions(Object[] stateVec,
                                     OpFeedbackParam<Object[]> actions) {
        int[] a = computeApplicableActions(stateVec);
        Object[] result = new Object[a.length];
        for (int i = 0; i < a.length; i++) result[i] = a[i];
        actions.set(result);
    }

    /**
     * Zones into which an action spills according to the reasoner's
     * cross-zone effects (StereotypeReasoner.getCrossZoneEffects). No agent
     * program in src/agt calls this operation.
     *
     * @param actionIdx   action index.
     * @param targetZones output: Object[] of Integer 0-based zone indices, one
     *                    per cross-zone effect of the action (empty if none).
     */
    @OPERATION
    public void getCrossZoneEffectForAction(int actionIdx,
                                            OpFeedbackParam<Object[]> targetZones) {
        List<StereotypeReasoner.CrossZoneEffect> effects = reasoner.getCrossZoneEffects();
        List<Integer> result = new ArrayList<>();
        for (StereotypeReasoner.CrossZoneEffect cz : effects) {
            if (cz.actionIndex == actionIdx) {
                result.add(cz.targetZoneIdx);
            }
        }
        Object[] arr = new Object[result.size()];
        for (int i = 0; i < result.size(); i++) arr[i] = result.get(i);
        targetZones.set(arr);
    }

    /**
     * Remember the index of the action the test agent has just sent, so that
     * it can read it back with getLastDispatchedAction after the step (for
     * its cross-zone check and weakness classification).
     *
     * @param actionIdx  index of the dispatched action.
     */
    @OPERATION
    public void notifyActionDispatched(int actionIdx) {
        lastDispatchedAction = actionIdx;
    }

    /**
     * The index recorded by the last notifyActionDispatched call, or -1 if
     * there was none.
     *
     * @param actionIdx output: the action index.
     */
    @OPERATION
    public void getLastDispatchedAction(OpFeedbackParam<Integer> actionIdx) {
        actionIdx.set(lastDispatchedAction);
    }

    /**
     * The knowledge graph's predicted change of every state entry for an
     * action in a state: the output of
     * {@link StereotypeReasoner#getActionPrediction(int[], int)}, converted to
     * Object[] (see that method for the meaning of the values). Used by the
     * test agent.
     *
     * @param stateVec    current state vector.
     * @param actionIdx   action index.
     * @param predDelta   output: Object[] of Integer, one per state entry.
     */
    @OPERATION
    public void getPredictedDelta(Object[] stateVec, int actionIdx,
                                  OpFeedbackParam<Object[]> predDelta) {
        int[] sv = toIntArray(stateVec);
        int[] pred = reasoner.getActionPrediction(sv, actionIdx);
        Object[] out = new Object[pred.length];
        for (int i = 0; i < pred.length; i++) out[i] = pred[i];
        predDelta.set(out);
    }

    /**
     * Size of the latest update: the largest |q_new - q_old| over the zones
     * in the latest {@link #calculateQ}; 0.0 if no update has run. The test
     * agent, the only caller, never calls calculateQ, so there it reads 0.0.
     *
     * @param qDelta output: the update size.
     */
    @OPERATION
    public void getLastQDelta(OpFeedbackParam<Double> qDelta) {
        qDelta.set(lastBellmanDelta);
    }

    /** Number of actions in the action list, blacklisted ones included
     *  (getNumApplicableActions excludes them). */
    @OPERATION
    public void getNumActions(OpFeedbackParam<Integer> n) {
        n.set(nActions);
    }

    /**
     * Replace the action-selection stream by a new java.util.Random(seed).
     * Only the test agent calls it (setSeed(42), right after
     * configureQLearner), so in the test both arms and every run.seed use the
     * same stream; it drives the random tie-break of greedyAction.
     *
     * @param seed the new seed.
     */
    @OPERATION
    public void setSeed(long seed) {
        this.rng = new Random(seed);
        LOGGER.info("QLearner RNG reseeded with seed=" + seed);
    }

    /** Length of the state vector. */
    @OPERATION
    public void getStateVecLen(OpFeedbackParam<Integer> n) {
        n.set(stateVecLen);
    }

    /**
     * Metadata of all actions, for StereotypeLearner.initLearner (training
     * and fault-stage agents) and DynamicsLearner (dynamics agent). Each
     * output array has nActions entries, indexed by action index.
     *
     * @param wotActionTypes  output: Object[] of String, the WoT action URI
     *                        ("" for DO_NOTHING).
     * @param actionValues    output: Object[] of Boolean (true = ON/OPEN).
     * @param actionLabels    output: Object[] of String, the action label
     *                        ("action_i" when the reasoner gave none).
     */
    @OPERATION
    public void getActionMetadataForLearner(OpFeedbackParam<Object[]> wotActionTypes,
                                            OpFeedbackParam<Object[]> actionValues,
                                            OpFeedbackParam<Object[]> actionLabels) {
        Object[] uris   = new Object[nActions];
        Object[] vals   = new Object[nActions];
        Object[] labels = new Object[nActions];
        for (int i = 0; i < nActions; i++) {
            StereotypeReasoner.ActionInfo ai = actionInfos[i];
            uris[i]   = (ai.wotActionType != null) ? ai.wotActionType : "";
            vals[i]   = ai.wotValue;
            labels[i] = (ai.label != null) ? ai.label : ("action_" + i);
        }
        wotActionTypes.set(uris);
        actionValues.set(vals);
        actionLabels.set(labels);
    }

    /**
     * Classify which weakness fingerprints fired during a single (before, action,
     * after) transition. Returns a deduplicated Object[] of String fingerprint
     * names drawn from {@code w1_unmodelled, w2_inversion, w3_delayed,
     * w4_dropped, w5_topology}. The test agent calls it only for labs whose
     * profile declares weakness flags and keeps only the tags in the lab's
     * weakness_flags list. An action index outside the list gives an empty
     * array. The predicted change comes from StereotypeReasoner.getActionPrediction.
     *
     * Triggers, per zone level ("change" = after - before):
     *   - w1_unmodelled: the zone changed, the prediction for it is 0 and the
     *                    zone is in the action's affectedZones; or, for
     *                    DO_NOTHING, any zone changed (then no other tag is
     *                    checked).
     *   - w5_topology:   the zone changed, the prediction for it is 0 and the
     *                    zone is not in affectedZones.
     *   - w2_inversion:  observed and predicted change have opposite signs.
     *   - w3_delayed:    the prediction is not 0, |observed| < 0.5 x
     *                    |predicted|, and the action's switch changed as
     *                    predicted (the command arrived, the light did not
     *                    follow yet).
     *   - w4_dropped:    the switch was predicted to change but did not.
     * W6 (heat / comfort) is not classified here; the test agent records it
     * through BenchmarkLogger.recordComfortDeviation.
     *
     * @param stateVecBefore state vector before the action.
     * @param stateVecAfter  state vector after the action.
     * @param actionIdx      index of the action taken.
     * @param fired          output: Object[] of String tags, without duplicates.
     */
    @OPERATION
    public void classifyWeaknesses(Object[] stateVecBefore,
                                   Object[] stateVecAfter,
                                   int actionIdx,
                                   OpFeedbackParam<Object[]> fired) {
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        if (actionIdx < 0 || actionIdx >= nActions) {
            fired.set(new Object[0]);
            return;
        }
        int[] before = toIntArray(stateVecBefore);
        int[] after  = toIntArray(stateVecAfter);
        int[] pred   = reasoner.getActionPrediction(before, actionIdx);
        StereotypeReasoner.ActionInfo ai = actionInfos[actionIdx];

        // DO_NOTHING (no actuator bit): any change of a zone level is a
        // change the graph does not explain, tagged W1.
        if (ai.stateVecBitIndex < 0) {
            for (int z = 0; z < zoneLevelIndices.length; z++) {
                int slot = zoneLevelIndices[z];
                if (slot < 0 || slot >= before.length) continue;
                if (after[slot] - before[slot] != 0) {
                    tags.add("w1_unmodelled");
                    break;
                }
            }
            fired.set(tags.toArray(new Object[0]));
            return;
        }

        // Did the action's switch change as predicted? Decides W3 versus W4.
        boolean bitFlippedAsPredicted = false;
        boolean bitDropped            = false;
        int bitSlot = ai.stateVecBitIndex;
        if (bitSlot >= 0 && bitSlot < before.length && bitSlot < after.length) {
            int bitPred = (bitSlot < pred.length) ? pred[bitSlot] : 0;
            int bitObs  = after[bitSlot] - before[bitSlot];
            if (bitPred != 0) {
                if (bitObs == bitPred) bitFlippedAsPredicted = true;
                else if (bitObs == 0)  bitDropped            = true;
            }
        }
        if (bitDropped) {
            tags.add("w4_dropped");
        }

        // Per-zone-level checks.
        java.util.Set<Integer> aff =
                (ai.affectedZones != null) ? ai.affectedZones : java.util.Collections.emptySet();
        for (int z = 0; z < zoneLevelIndices.length; z++) {
            int slot = zoneLevelIndices[z];
            if (slot < 0 || slot >= before.length) continue;
            int obsD  = after[slot] - before[slot];
            int predD = (slot < pred.length) ? pred[slot] : 0;

            // W2 inversion: opposite-sign Δ in any zone.
            if (obsD != 0 && predD != 0 && (long) obsD * (long) predD < 0L) {
                tags.add("w2_inversion");
            }
            // W1 vs W5: the zone moved although no change was predicted.
            //   - zone in affectedZones     → W1 (effect not modelled)
            //   - zone not in affectedZones → W5 (cross-zone feed missing)
            if (obsD != 0 && predD == 0) {
                if (aff.contains(z)) tags.add("w1_unmodelled");
                else                 tags.add("w5_topology");
            }
            // W3 delayed: a change was predicted, the observed change is less
            // than half of it, and the switch did change as predicted (the
            // command arrived, the light has not followed yet). A dropped
            // command is W4 (recorded above), so W3 requires the switch change.
            if (predD != 0 && Math.abs(obsD) < 0.5 * Math.abs(predD)
                    && bitFlippedAsPredicted) {
                tags.add("w3_delayed");
            }
        }

        Object[] out = tags.toArray(new Object[0]);
        fired.set(out);
    }

    // -----------------------------------------------------------------------
    // Fault stage (Phase 2): probe, detector, blacklist, warm restart
    // -----------------------------------------------------------------------

    /**
     * Diagnostic probe: the "on" (opening) action of an IV-gated device that
     * should be opened now as a test, or -1. A trained policy may never open a
     * blind (a lamp reaches the goal without the sun), so a broken blind would
     * never show; the fault-stage agent therefore takes this action instead of
     * its greedy action whenever the result is not -1, until the first
     * detection.
     *
     * The method returns the first action, in index order, that meets all of:
     *   - the state has a sunshine slot and the sun rank is at least
     *     IV_DETECT_MIN_SUN_RANK;
     *   - it is the "on" action of a device in ivGatedComponents;
     *   - the action is not blacklisted and its device is not in
     *     probeVerified;
     *   - the reasoner predicts a change of the device's switch (it is off);
     *   - at least one zone has a predicted rise, and every zone with a
     *     predicted rise is below its top rank.
     *
     * The detector (observeForFaults) applies further guards that this
     * method does NOT check: the knowledge-graph-silent device, the device
     * that feeds several zones, the zone spoiled by a blacklisted device, and
     * the zone with a fault-suspect or an active co-feeder (for example a
     * lamp that is on in the same zone). In a probe step where one of these
     * applies, the detector may abstain: the device is then neither verified
     * nor blacklisted, and the probe can choose it again whenever its
     * conditions hold again. The method is kept as it is because the
     * archived fault-stage runs used it.
     *
     * Reads state only; changes nothing.
     *
     * @param stateVec    current state vector.
     * @param probeAction output: the action index to take, or -1.
     */
    @OPERATION
    public void getDiagnosticProbeAction(Object[] stateVec,
                                         OpFeedbackParam<Integer> probeAction) {
        probeAction.set(-1);
        if (reasoner == null || actionInfos == null) return;
        int[] s = toIntArray(stateVec);
        if (sunshineIndex < 0 || sunshineIndex >= s.length) return;
        if (s[sunshineIndex] < IV_DETECT_MIN_SUN_RANK) return; // sun too low to test any IV-gated device
        for (int a = 0; a < nActions; a++) {
            StereotypeReasoner.ActionInfo ai = actionInfos[a];
            if (ai == null || ai.wotActionType == null) continue;
            if (!ai.wotValue) continue;                                  // only "on" (opening) actions
            if (!ivGatedComponents.contains(ai.wotActionType)) continue; // IV-gated devices only
            if (blacklisted != null && a < blacklisted.length && blacklisted[a]) continue;
            if (probeVerified.contains(ai.wotActionType)) continue;      // already judged healthy
            int bitSlot = ai.stateVecBitIndex;
            if (bitSlot < 0 || bitSlot >= s.length) continue;
            int[] pred = reasoner.getActionPrediction(s, a);
            int bitPred = (bitSlot < pred.length) ? pred[bitSlot] : 0;
            if (bitPred == 0) continue;                                  // already on (open): nothing to test
            // Every zone with a predicted rise must be below its top rank, so
            // that a healthy opening can raise the rank there.
            boolean testable = false, saturated = false;
            for (int z = 0; z < zoneLevelIndices.length; z++) {
                int slot = zoneLevelIndices[z];
                if (slot < 0 || slot >= s.length) continue;
                int predD = (slot < pred.length) ? pred[slot] : 0;
                if (predD <= 0) continue;
                int maxRank = (slot < domainSizes.length) ? domainSizes[slot] - 1 : 3;
                if (s[slot] >= maxRank) { saturated = true; break; }     // no headroom here
                testable = true;
            }
            if (saturated || !testable) continue;
            probeAction.set(a);
            return;
        }
    }

    /**
     * Fault detector: compares, for one step, what the knowledge graph
     * predicts for the action (StereotypeReasoner.getActionPrediction in the
     * state before) with what happened. The fault-stage agent calls it after
     * every step. One clear observation is enough: there is no evidence
     * threshold.
     *
     * The step is ignored (no verdict, no counter changes) when:
     *   1. the action index is out of range, the action is DO_NOTHING, it is
     *      already blacklisted, or its device is knowledge-graph-silent
     *      (kgSilent: the graph makes no claim about its effect);
     *   2. the device feeds more than one zone (affectedZones size > 1, e.g. a
     *      shared spotlight): its share of each zone may be too small to
     *      change a rank, so such devices are never judged;
     *   3. the device is IV-gated and the action is its "off" action, or the
     *      sun rank before the step is below IV_DETECT_MIN_SUN_RANK;
     *   4. the predicted change of the device's switch is 0 (the switch is
     *      already in that position).
     * Otherwise:
     *   - the switch did not change: DEAD;
     *   - the switch changed as predicted: every zone level with a predicted
     *     change is examined, except a zone marked in zoneCauseContaminated
     *     and a zone at the top rank (predicted rise) or at rank 0 (predicted
     *     fall). A zone that moved against the prediction makes the verdict
     *     INVERTED (this evidence is never held back). A zone that did not
     *     move counts as "no response", unless another device feeding it is
     *     a fault suspect (zoneHasSuspectCoFeeder) or its switch is on
     *     (zoneHasMaskingCoFeeder); then the whole step is ignored, unless
     *     some zone moved against the prediction. If no zone was examined,
     *     the step is ignored. If every examined zone gave no response: DEAD;
     *     otherwise the step is a healthy observation.
     * For every judged step the evidence counters are updated (faultObsN,
     * faultDeadN, faultInvertN). A healthy judged opening of an IV-gated
     * device adds it to probeVerified.
     *
     * Changes neither the table nor the action list. On a DEAD or INVERTED
     * verdict it logs a WARNING ("flagged DEFECTIVE", counted by
     * scripts/curate_phase2_structonly_v3.ps1); the agent then calls
     * blacklistComponentAt and warmRestart for a device it has not handled
     * before.
     *
     * @param stateVecBefore state vector before the action.
     * @param actionIdx      index of the action taken.
     * @param stateVecAfter  state vector after the action.
     * @param newlyDefective output: the device's wotActionType on a DEAD or
     *                       INVERTED verdict, otherwise "".
     */
    @OPERATION
    public void observeForFaults(Object[] stateVecBefore, int actionIdx,
                                 Object[] stateVecAfter,
                                 OpFeedbackParam<String> newlyDefective) {
        newlyDefective.set("");
        if (actionIdx < 0 || actionIdx >= nActions) return;
        StereotypeReasoner.ActionInfo ai = actionInfos[actionIdx];
        if (ai == null || ai.wotActionType == null) return;          // DO_NOTHING — no claim
        if (blacklisted != null && blacklisted[actionIdx]) return;   // already removed
        // A knowledge-graph-silent device was found only through its WoT
        // mapping; the graph makes no claim about its effect, so there is
        // nothing to compare.
        if (ai.kgSilent) return;

        // Devices that feed several zones are never judged: each zone gets
        // only part of their light, so a healthy switch may not change any
        // zone's rank, and co-feeders confound the response. In the lab
        // graphs this concerns the shared spotlight; the injected faults
        // concern single-zone devices.
        if (ai.affectedZones != null && ai.affectedZones.size() > 1) return;

        int[] before = toIntArray(stateVecBefore);
        int[] after  = toIntArray(stateVecAfter);
        int[] pred   = reasoner.getActionPrediction(before, actionIdx);

        // IV-gated device: judged only on its "on" (opening) action and only
        // at sun rank IV_DETECT_MIN_SUN_RANK or higher; closing, or opening
        // under weak sun, can leave the rank unchanged for a healthy blind.
        if (ivGatedComponents.contains(ai.wotActionType)) {
            if (!ai.wotValue) return;                                    // only the "on" action is judged
            if (sunshineIndex < 0 || sunshineIndex >= before.length) return;
            if (before[sunshineIndex] < IV_DETECT_MIN_SUN_RANK) return; // sun too low: null response expected
        }

        int bitSlot = ai.stateVecBitIndex;
        if (bitSlot < 0 || bitSlot >= before.length) return;
        int bitPred = (bitSlot < pred.length) ? pred[bitSlot] : 0;
        if (bitPred == 0) return; // switch already in that position: nothing to judge
        int bitObs = after[bitSlot] - before[bitSlot];

        boolean dead = false;
        boolean inverted = false;
        if (bitObs == 0) {
            // The switch did not change: the command had no effect.
            dead = true;
        } else if (bitObs == bitPred) {
            // The switch changed as commanded. Did the zones it should move respond?
            int claimed = 0, noResp = 0, opp = 0;
            boolean ambiguous = false;   // a zone's null response may be explained by a co-feeder
            for (int z = 0; z < zoneLevelIndices.length; z++) {
                int slot = zoneLevelIndices[z];
                if (slot < 0 || slot >= before.length) continue;
                // A zone fed by a blacklisted device has a spoiled baseline
                // (see zoneCauseContaminated): skip it.
                if (zoneCauseContaminated != null && z < zoneCauseContaminated.length
                        && zoneCauseContaminated[z]) continue;
                int predD = (slot < pred.length) ? pred[slot] : 0;
                if (predD == 0) continue;
                int maxRank = (slot < domainSizes.length) ? domainSizes[slot] - 1 : 3;
                if (predD > 0 && before[slot] >= maxRank) continue; // can't rise — not falsifiable
                if (predD < 0 && before[slot] <= 0)       continue; // can't fall — not falsifiable
                int obsD = after[slot] - before[slot];
                if (obsD != 0 && (long) obsD * (long) predD < 0L) {
                    // The zone moved against the prediction: INVERTED. The step
                    // changes only this device's switch, so the opposite sign is
                    // attributed to it. This evidence is never held back by the
                    // co-feeder guards below.
                    claimed++;
                    opp++;
                } else if (obsD == 0) {
                    // No rank change. Another device feeding the same zone can
                    // explain this: a fault-suspect device (for example an
                    // inverted lamp that holds the zone at rank 0), or a device
                    // whose switch is on (for example an open blind in strong
                    // sun that holds the zone's rank while a healthy lamp
                    // switches off). In both cases the zone gives no evidence
                    // about this device and the whole step is ignored below
                    // (unless another zone moved against the prediction).
                    // Detection then waits for a step without such a co-feeder.
                    if (zoneHasSuspectCoFeeder(z, actionIdx)
                            || zoneHasMaskingCoFeeder(z, actionIdx, before)) {
                        ambiguous = true;
                        continue;
                    }
                    claimed++;
                    noResp++;
                } else {
                    // Moved in the predicted direction: a healthy response.
                    claimed++;
                }
            }
            if (opp > 0) {
                // Some zone moved against the prediction: inverted, whatever
                // the other zones did.
                inverted = true;
            } else if (ambiguous) {
                // A no-response zone had a co-feeder that may explain it: no
                // "dead" verdict can be drawn, and the step is ignored as a
                // whole (no partial verdict from the remaining zones, no
                // counter change).
                return;
            } else if (claimed > 0) {
                if (noResp == claimed) dead = true;
                // else: at least one zone moved as predicted — a healthy observation.
            } else {
                return; // no zone could be examined — ignore this step
            }
        } else {
            return; // switch changed by a value other than predicted (not expected for a 0/1 entry)
        }

        // Update the evidence counters (read by isFaultSuspect for other
        // devices' later steps).
        faultObsN[actionIdx]++;
        if (dead)     faultDeadN[actionIdx]++;
        if (inverted) faultInvertN[actionIdx]++;

        // A judged, healthy opening of an IV-gated device: mark it verified so
        // that getDiagnosticProbeAction does not probe it again.
        if (!dead && !inverted && ai.wotValue
                && ivGatedComponents.contains(ai.wotActionType)) {
            probeVerified.add(ai.wotActionType);
        }

        // Verdict on this single observation; the agent blacklists the device.
        if (dead || inverted) {
            newlyDefective.set(ai.wotActionType);
            LOGGER.warning(String.format(
                "observeForFaults: component %s flagged DEFECTIVE via action %d (%s) "
              + "on FIRST anomalous observation [%s]",
                ai.wotActionType, actionIdx, ai.label,
                dead ? "dead/no-response" : "inverted/opposite-response"));
        }
    }

    /**
     * Whether action b counts as a fault suspect, whose influence on a shared
     * zone may explain another device's missing response: true when b has at
     * least one inverted observation, or at least FAULT_SUSPECT_MIN dead plus
     * inverted observations, in the evidence counters.
     *
     * The counters grow only in steps that end with a DEAD or INVERTED
     * verdict for b, and warmRestart sets them all to 0; the fault-stage
     * agent calls warmRestart after every device it flags for the first
     * time. Devices that feed several zones are never judged, so their
     * counters stay 0 and they are never suspects; the guard can therefore
     * spare only single-zone devices whose zone has another feeder.
     *
     * @param b action index (out of range gives false).
     * @return true if b is a fault suspect.
     */
    private boolean isFaultSuspect(int b) {
        if (faultObsN == null || b < 0 || b >= faultObsN.length) return false;
        if (faultInvertN[b] >= 1) return true;   // one inverted observation is enough
        return (faultDeadN[b] + faultInvertN[b]) >= FAULT_SUSPECT_MIN;
    }

    /**
     * Whether an action of another device lists {@code zone} in its
     * affectedZones and is a fault suspect (isFaultSuspect). Both actions of
     * the device under test are excluded through their shared wotActionType;
     * DO_NOTHING is skipped. Cross-zone spill arcs are not considered here
     * (unlike maskingCoFeederPresent). Used by observeForFaults to hold back a
     * "no response" in that zone.
     *
     * @param zone       0-based zone index.
     * @param selfAction action index of the device under test.
     * @return true if such a suspect co-feeder exists.
     */
    private boolean zoneHasSuspectCoFeeder(int zone, int selfAction) {
        StereotypeReasoner.ActionInfo self =
            (selfAction >= 0 && selfAction < nActions) ? actionInfos[selfAction] : null;
        String selfType = (self != null) ? self.wotActionType : null;
        for (int b = 0; b < nActions; b++) {
            if (b == selfAction) continue;
            StereotypeReasoner.ActionInfo bi = actionInfos[b];
            if (bi == null || bi.wotActionType == null) continue;
            if (selfType != null && selfType.equals(bi.wotActionType)) continue; // same component
            if (bi.affectedZones == null || !bi.affectedZones.contains(zone)) continue;
            if (isFaultSuspect(b)) return true;
        }
        return false;
    }

    /**
     * Whether another device feeds {@code zone} and has its switch on in
     * {@code before}. Such a co-feeder's light can hold the zone's rank while
     * the device under test switches (for example an open blind in strong sun
     * keeps a zone at rank 3 while a healthy lamp switches off), so a missing
     * response is not attributable to the device under test. The co-feeder
     * need not look faulty (unlike zoneHasSuspectCoFeeder). "Feeds" means:
     * the zone is in its affectedZones, or a cross-zone effect of the
     * reasoner links that action to the zone (any coupling class). Both
     * actions of the device under test are excluded through their shared
     * wotActionType; DO_NOTHING is skipped; blacklisted devices are not
     * skipped (a blacklisted device left on still counts). Only
     * "no response" evidence is held back by this test, never inverted
     * evidence. Package-private and static so that Phase2DetectorV2Test can
     * call it.
     *
     * @param infos      the action list.
     * @param crossZone  the reasoner's cross-zone effects (may be null).
     * @param zone       0-based zone index.
     * @param selfAction action index of the device under test.
     * @param before     state vector before the step.
     * @return true if an active co-feeder exists.
     */
    static boolean maskingCoFeederPresent(StereotypeReasoner.ActionInfo[] infos,
                                          java.util.List<StereotypeReasoner.CrossZoneEffect> crossZone,
                                          int zone, int selfAction, int[] before) {
        if (infos == null || before == null) return false;
        StereotypeReasoner.ActionInfo self =
            (selfAction >= 0 && selfAction < infos.length) ? infos[selfAction] : null;
        String selfType = (self != null) ? self.wotActionType : null;
        for (int b = 0; b < infos.length; b++) {
            StereotypeReasoner.ActionInfo bi = infos[b];
            if (bi == null || bi.wotActionType == null) continue;          // DO_NOTHING
            if (selfType != null && selfType.equals(bi.wotActionType)) continue; // same component
            boolean feedsZone = bi.affectedZones != null && bi.affectedZones.contains(zone);
            if (!feedsZone && crossZone != null) {
                for (StereotypeReasoner.CrossZoneEffect cz : crossZone) {
                    if (cz.actionIndex == b && cz.targetZoneIdx == zone) {
                        feedsZone = true;
                        break;
                    }
                }
            }
            if (!feedsZone) continue;
            int bit = bi.stateVecBitIndex;
            if (bit >= 0 && bit < before.length && before[bit] == 1) return true;
        }
        return false;
    }

    /** {@link #maskingCoFeederPresent} with this learner's action list and the
     *  reasoner's cross-zone effects. */
    private boolean zoneHasMaskingCoFeeder(int zone, int selfAction, int[] before) {
        java.util.List<StereotypeReasoner.CrossZoneEffect> crossZone =
            (reasoner != null) ? reasoner.getCrossZoneEffects() : null;
        return maskingCoFeederPresent(actionInfos, crossZone, zone, selfAction, before);
    }

    /**
     * Same as {@link #blacklistComponentAt} with episode -1 in the event
     * record. No agent program in src/agt calls this operation; the
     * fault-stage agent uses blacklistComponentAt.
     *
     * @param wotActionType the device's WoT action URI.
     * @param nRemoved      output: number of actions removed (0, 1 or 2).
     */
    @OPERATION
    public void blacklistComponent(String wotActionType, OpFeedbackParam<Integer> nRemoved) {
        blacklistComponentImpl(wotActionType, -1, nRemoved);
    }

    /**
     * Remove the actions of a defective device from the action list, so that
     * neither choice nor bootstrap (computeApplicableActions,
     * jointArgmaxAction) uses them again; see blacklistComponentImpl for the
     * details. The fault-stage agent calls it after observeForFaults has
     * named the device, followed by warmRestart.
     *
     * @param wotActionType the device's WoT action URI.
     * @param episode       fault-stage episode, stored in the event record
     *                      (column BlacklistEvents of saveRecoveryLog).
     * @param nRemoved      output: number of actions removed (0, 1 or 2).
     */
    @OPERATION
    public void blacklistComponentAt(String wotActionType, int episode,
                                     OpFeedbackParam<Integer> nRemoved) {
        blacklistComponentImpl(wotActionType, episode, nRemoved);
    }

    /**
     * Blacklist every not yet blacklisted action with this wotActionType
     * (DO_NOTHING is never touched), in index order. An action is not removed
     * if no other non-DO_NOTHING action would remain selectable; a WARNING is
     * logged and the next action is tried. This protection can leave a device
     * half removed: when only its two actions are left, the first is removed
     * and the second kept. If at least one action was removed, every zone in
     * the affectedZones of the device's actions is marked in
     * zoneCauseContaminated and "wotActionType@episode" is appended to
     * blacklistEvents. A null or empty wotActionType, or a call before
     * configureQLearner, removes nothing.
     *
     * @param wotActionType the device's WoT action URI.
     * @param episode       episode number for the event record.
     * @param nRemoved      output: number of actions removed.
     */
    private void blacklistComponentImpl(String wotActionType, int episode,
                                        OpFeedbackParam<Integer> nRemoved) {
        int removed = 0;
        if (wotActionType == null || wotActionType.isEmpty() || blacklisted == null) {
            nRemoved.set(0);
            return;
        }
        for (int a = 0; a < nActions; a++) {
            StereotypeReasoner.ActionInfo ai = actionInfos[a];
            if (ai == null || ai.wotActionType == null) continue;       // never DO_NOTHING
            if (!wotActionType.equals(ai.wotActionType) || blacklisted[a]) continue;
            // Guarantee at least one non-DO_NOTHING action survives the removal.
            int surviving = 0;
            for (int b = 0; b < nActions; b++) {
                if (b == a) continue;
                StereotypeReasoner.ActionInfo bi = actionInfos[b];
                if (bi != null && bi.wotActionType != null && !blacklisted[b]) surviving++;
            }
            if (surviving < 1) {
                LOGGER.warning("blacklistComponent: refusing to remove the last actuator action "
                             + a + " (" + ai.label + ")");
                continue;
            }
            blacklisted[a] = true;
            removed++;
            LOGGER.warning("blacklistComponent: removed action " + a + " (" + ai.label + ")");
        }
        // The removed device keeps its last physical state, so the zones it
        // feeds no longer give evidence about other devices: mark them (for
        // every kind of device, see zoneCauseContaminated).
        if (removed > 0 && zoneCauseContaminated != null) {
            for (int a = 0; a < nActions; a++) {
                StereotypeReasoner.ActionInfo ai = actionInfos[a];
                if (ai == null || ai.wotActionType == null) continue;
                if (!wotActionType.equals(ai.wotActionType) || ai.affectedZones == null) continue;
                for (int z : ai.affectedZones)
                    if (z >= 0 && z < zoneCauseContaminated.length) zoneCauseContaminated[z] = true;
            }
        }
        if (removed > 0) {
            blacklistEvents.add(wotActionType + "@" + episode);
        }
        nRemoved.set(removed);
    }

    /**
     * Prepare re-learning after a blacklist; the fault-stage agent calls it
     * right after blacklistComponentAt. The table is kept, except:
     *   1. "Poisoned" states are found first: states whose best action by the
     *      summed table, over ALL actions (unfilteredArgmax), is blacklisted.
     *   2. For every blacklisted action (including ones blacklisted earlier),
     *      all its table values in every zone and its visit counts are set
     *      to 0.
     *   3. In every poisoned state, all values of all zones are halved, so the
     *      remaining actions can be re-ranked quickly.
     *   4. epsilon is raised to at least FAULT_EPS_BOOST
     *      (fault.relearn.epsBoost).
     *   5. The episode clock of the preference fade is set to 0, so in the
     *      knowledge arm the soft preference has its full start weight again
     *      (the plain arm has no soft preference).
     *   6. The training convergence count and the recovery detector are reset.
     *   7. The evidence counters of all actions are set to 0, so the next
     *      device is judged only on evidence from the reduced action list.
     * Logs one WARNING line with the numbers. Does nothing before
     * configureQLearner.
     */
    @OPERATION
    public void warmRestart() {
        if (blacklisted == null) return;
        // (1) Find poisoned states with the table as it is (before any wipe).
        boolean[] poisonedState = new boolean[nStates];
        int poisoned = 0;
        for (int s = 0; s < nStates; s++) {
            int oldArg = unfilteredArgmax(s);
            if (oldArg >= 0 && blacklisted[oldArg]) { poisonedState[s] = true; poisoned++; }
        }
        // (2) Wipe blacklisted action columns + their visit counts.
        int wiped = 0;
        for (int a = 0; a < nActions; a++) {
            if (!blacklisted[a]) continue;
            wiped++;
            for (int z = 0; z < qTables.length; z++)
                for (int s = 0; s < nStates; s++) qTables[z][s][a] = 0.0;
            for (int s = 0; s < nStates; s++) visitCounts[s][a] = 0L;
        }
        // (3) Halve every value in the poisoned states.
        for (int s = 0; s < nStates; s++) {
            if (!poisonedState[s]) continue;
            for (int z = 0; z < qTables.length; z++)
                for (int a = 0; a < nActions; a++) qTables[z][s][a] *= 0.5;
        }
        // (4) epsilon raise, (5) fade clock reset, (6) convergence reset.
        epsilon = Math.max(epsilon, FAULT_EPS_BOOST);
        currentEpisodeNum = 0;
        convergenceCount = 0;
        // (6) Reset the recovery detector, so that its window starts at the
        //     warm restart.
        recoveryPolicy = null;
        recoveryStableCount = 0;
        // (7) Clear the evidence counters: evidence gathered while the removed
        //     device was still in use is not carried over to the judgement
        //     of the remaining devices.
        if (faultObsN != null) {
            java.util.Arrays.fill(faultObsN, 0);
            java.util.Arrays.fill(faultDeadN, 0);
            java.util.Arrays.fill(faultInvertN, 0);
        }
        LOGGER.warning(String.format(
            "warmRestart: wiped %d action column(s), decayed %d poisoned state(s), "
          + "ε←%.3f, episodeClock←0, convergence reset",
            wiped, poisoned, epsilon));
    }

    /** Number of actions that are not blacklisted (DO_NOTHING included). */
    @OPERATION
    public void getNumApplicableActions(OpFeedbackParam<Integer> n) {
        int c = 0;
        for (int a = 0; a < nActions; a++) if (blacklisted == null || !blacklisted[a]) c++;
        n.set(c);
    }

    /** True if at least one action with this wotActionType is blacklisted.
     *  No agent program in src/agt calls this operation. */
    @OPERATION
    public void isComponentBlacklisted(String wotActionType, OpFeedbackParam<Boolean> result) {
        boolean any = false;
        if (wotActionType != null && blacklisted != null) {
            for (int a = 0; a < nActions; a++) {
                StereotypeReasoner.ActionInfo ai = actionInfos[a];
                if (ai != null && wotActionType.equals(ai.wotActionType) && blacklisted[a]) {
                    any = true;
                    break;
                }
            }
        }
        result.set(any);
    }

    /**
     * Append one row to the fault-stage recovery file; the header is written
     * first when the file does not exist yet. Columns:
     *   DefectComponent         blacklistedLabel ("" for null)
     *   DetectEpisode           detectEp
     *   ReconvergeEpisode       reconvergeEp
     *   RecoveryEpisodes        reconvergeEp - detectEp, or -1 if either is negative
     *   SecondaryDetectEpisode  secondaryDetectEp
     *   RecoveredGoalRate       recoveredGoalRate (4 decimals)
     *   NominalGoal, BestEffortRank
     *   RankShortfall           nominalGoal - bestEffortRank, or 0 if either is negative
     *   DegradedMode            1 if degraded, else 0
     *   BlacklistEvents         all entries of blacklistEvents, joined with ';'
     *   DetectorVersion, ProtocolVersion  FAULT_DETECTOR_VERSION, PHASE2_PROTOCOL_VERSION
     * The values are computed by the fault-stage agent; RecoveredGoalRate is
     * the goal rate of its final greedy check, which tells a policy that only
     * stopped changing from one that reaches the (effective) goal. With
     * DegradedMode = 1 that rate refers to the lowered effective goal. The
     * fault-stage agent passes the nominal goal, effective goal and degraded
     * flag of zone 0 only (getGoalStatus(0, ...)). An IOException is logged
     * as a WARNING and the row is lost.
     *
     * @param filename          path of the CSV file.
     * @param detectEp          episode of the first detection (agent's value
     *                          when there was none).
     * @param reconvergeEp      episode in which recovery was declared.
     * @param secondaryDetectEp episode in which a second device was detected.
     * @param recoveredGoalRate goal rate of the final greedy check.
     * @param blacklistedLabel  the first detected device.
     * @param nominalGoal       nominal goal rank.
     * @param bestEffortRank    effective goal rank.
     * @param degraded          true if the goal was lowered.
     */
    @OPERATION
    public void saveRecoveryLog(String filename, int detectEp, int reconvergeEp,
                                int secondaryDetectEp, double recoveredGoalRate,
                                String blacklistedLabel,
                                int nominalGoal, int bestEffortRank, boolean degraded) {
        boolean exists = new java.io.File(filename).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, true))) {
            // BlacklistEvents names every blacklisted device with its episode
            // ("uri@ep;uri@ep"); DetectorVersion and ProtocolVersion name the
            // detector and the file format that produced the row.
            if (!exists) pw.println("DefectComponent,DetectEpisode,ReconvergeEpisode,RecoveryEpisodes,SecondaryDetectEpisode,RecoveredGoalRate,NominalGoal,BestEffortRank,RankShortfall,DegradedMode,BlacklistEvents,DetectorVersion,ProtocolVersion");
            int rec = (reconvergeEp >= 0 && detectEp >= 0) ? (reconvergeEp - detectEp) : -1;
            int shortfall = (nominalGoal >= 0 && bestEffortRank >= 0) ? (nominalGoal - bestEffortRank) : 0;
            pw.printf("%s,%d,%d,%d,%d,%.4f,%d,%d,%d,%d,%s,%s,%s%n",
                blacklistedLabel == null ? "" : blacklistedLabel,
                detectEp, reconvergeEp, rec, secondaryDetectEp, recoveredGoalRate,
                nominalGoal, bestEffortRank, shortfall, degraded ? 1 : 0,
                String.join(";", blacklistEvents),
                FAULT_DETECTOR_VERSION, PHASE2_PROTOCOL_VERSION);
            LOGGER.info("saveRecoveryLog: appended recovery row to " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveRecoveryLog: failed " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Index of the action with this WoT action URI and value, or -1 if there
     * is none (a null wotType makes the operation fail). No agent program in
     * src/agt calls this operation.
     *
     * @param wotType   WoT action URI.
     * @param wotValue  true for the ON/OPEN action, false for OFF/CLOSE.
     * @param actionIdx output: the action index or -1.
     */
    @OPERATION
    public void wotTypeToActionIndex(String wotType, boolean wotValue,
                                     OpFeedbackParam<Integer> actionIdx) {
        for (int i = 0; i < nActions; i++) {
            StereotypeReasoner.ActionInfo ai = actionInfos[i];
            if (wotType.equals(ai.wotActionType) && ai.wotValue == wotValue) {
                actionIdx.set(i);
                return;
            }
        }
        actionIdx.set(-1);
    }

    /**
     * Whether the reasoner lists at least one cross-zone effect (light spilled
     * into another zone) for this action. No agent program in src/agt calls
     * this operation.
     *
     * @param actionIdx    action index.
     * @param hasCrossZone output: true if a cross-zone effect exists.
     */
    @OPERATION
    public void actionHasCrossZoneEffect(int actionIdx, OpFeedbackParam<Boolean> hasCrossZone) {
        List<StereotypeReasoner.CrossZoneEffect> effects = reasoner.getCrossZoneEffects();
        for (StereotypeReasoner.CrossZoneEffect cz : effects) {
            if (cz.actionIndex == actionIdx) {
                hasCrossZone.set(true);
                return;
            }
        }
        hasCrossZone.set(false);
    }

    /**
     * Whether the action pushed a zone it spills into (a cross-zone effect of
     * the reasoner) away from that zone's target: true as soon as one such
     * zone is farther from its target after the step than before, or was at
     * its target and left it. Used by the test agent after each step.
     *
     * @param actionIdx       index of the dispatched action.
     * @param prevZoneLevels  zone ranks before the action.
     * @param currZoneLevels  zone ranks after the action.
     * @param zoneTargets     target rank per zone (supplied by the agent).
     * @param interference    output: true if a spill zone was pushed away from its target.
     */
    @OPERATION
    public void checkCrossZoneInterference(int actionIdx,
                                           Object[] prevZoneLevels,
                                           Object[] currZoneLevels,
                                           Object[] zoneTargets,
                                           OpFeedbackParam<Boolean> interference) {
        List<StereotypeReasoner.CrossZoneEffect> effects = reasoner.getCrossZoneEffects();
        for (StereotypeReasoner.CrossZoneEffect cz : effects) {
            if (cz.actionIndex != actionIdx) continue;
            int tz = cz.targetZoneIdx;
            if (tz < 0 || tz >= prevZoneLevels.length) continue;
            int prev   = toInt(prevZoneLevels[tz]);
            int curr   = toInt(currZoneLevels[tz]);
            int target = toInt(zoneTargets[tz]);
            // Interference: zone moved further from target, OR was at target and moved away
            if (Math.abs(curr - target) > Math.abs(prev - target)
                    || (prev == target && curr != target)) {
                interference.set(true);
                return;
            }
        }
        interference.set(false);
    }

    /**
     * The WoT action that realises an action index; the agents pass it to
     * LabEnvironment.invokeAction unless it is "none".
     *
     * @param action    action index.
     * @param wotType   output: WoT action URI, or "none" for DO_NOTHING and
     *                  for an index out of range.
     * @param value     output: boolean payload (false for an index out of range).
     */
    @OPERATION
    public void actionToWoT(int action,
                            OpFeedbackParam<String>  wotType,
                            OpFeedbackParam<Boolean> value) {
        if (action < 0 || action >= nActions) {
            wotType.set("none");
            value.set(false);
            return;
        }
        StereotypeReasoner.ActionInfo ai = actionInfos[action];
        wotType.set(ai.wotActionType != null ? ai.wotActionType : "none");
        value.set(ai.wotValue);
    }

    /**
     * Whether every zone is exactly at its effective goal rank (equal, not
     * "at least"). The agents end an episode on true. The effective goal is
     * the nominal goal unless the fault stage has lowered it.
     *
     * @param stateVec state vector.
     * @param terminal output: true if all zones are at their effective goal.
     */
    @OPERATION
    public void isTerminal(Object[] stateVec, OpFeedbackParam<Boolean> terminal) {
        boolean t = true;
        // "Terminal" means the effective goal is reached (see effectiveGoal).
        for (int z = 0; z < effectiveGoal.length; z++) {
            if (toInt(stateVec[zoneLevelIndices[z]]) != effectiveGoal[z]) { t = false; break; }
        }
        terminal.set(t);
    }

    // -----------------------------------------------------------------------
    // Fault stage: reachability probe and effective goal
    // -----------------------------------------------------------------------
    //
    // After every blacklist the fault-stage agent checks, zone by zone,
    // whether the nominal goal can still be reached: beginReachabilityProbe
    // lists the remaining devices, the agent drives the room into every
    // on/off combination of them (getProbeCombo), reads the room and reports
    // the zone's rank (recordProbeRank), and finishReachabilityProbe lowers
    // the zone's effective goal to the closest rank found when that rank is
    // below the nominal goal. The probe runs in the room as it is at that
    // moment (current sun), so "unreachable" means "not reached by any
    // combination under the conditions of the probe". The effective goal is
    // never raised again. Training and the test never call these operations.

    /**
     * Number of zones (length of the goal list). The fault-stage agent runs
     * the reachability probe for every zone from 0 to this number - 1.
     *
     * @param out output: number of zones.
     */
    @OPERATION
    public void getNumZones(OpFeedbackParam<Integer> out) {
        out.set(goal.length);
    }

    /**
     * Nominal goal rank of a zone (as given to configureQLearner), or -1 for a
     * zone out of range. The fault-stage agent prints it when a zone's goal is
     * lowered.
     *
     * @param zone 0-based zone index.
     * @param out  output: the nominal goal rank.
     */
    @OPERATION
    public void getNominalGoal(int zone, OpFeedbackParam<Integer> out) {
        out.set((zone >= 0 && zone < goal.length) ? goal[zone] : -1);
    }

    /**
     * Set the effective goal rank of a zone to any value (a zone out of range
     * is ignored) and log a WARNING. No agent program in src/agt calls this
     * operation; the fault-stage agent lowers goals through
     * finishReachabilityProbe.
     *
     * @param zone 0-based zone index.
     * @param rank new effective goal rank.
     */
    @OPERATION
    public void setEffectiveGoal(int zone, int rank) {
        if (zone >= 0 && zone < effectiveGoal.length) {
            effectiveGoal[zone] = rank;
            LOGGER.warning("setEffectiveGoal: zone " + zone + " effective goal set to rank "
                + rank + " (nominal " + goal[zone] + ")");
        }
    }

    /**
     * Goal status of a zone: nominal rank, effective rank (-1 each for a zone
     * out of range) and whether the zone is degraded (effective &lt; nominal).
     * The fault-stage agent reads it for zone 0 for the recovery row.
     *
     * @param zone      0-based zone index.
     * @param nominal   output: nominal goal rank.
     * @param effective output: effective goal rank.
     * @param degraded  output: effective &lt; nominal.
     */
    @OPERATION
    public void getGoalStatus(int zone, OpFeedbackParam<Integer> nominal,
                              OpFeedbackParam<Integer> effective,
                              OpFeedbackParam<Boolean> degraded) {
        int g = (zone >= 0 && zone < goal.length) ? goal[zone] : -1;
        int e = (zone >= 0 && zone < effectiveGoal.length) ? effectiveGoal[zone] : -1;
        nominal.set(g);
        effective.set(e);
        degraded.set(e < g);
    }

    /**
     * Start a reachability probe: collect, in action-index order, every device
     * whose ON and OFF actions are both not blacklisted (a half-removed device
     * is left out), and reset the best rank found. With k such devices the
     * result is 2^k combinations for k <= 16; for k > 16 it is 1, so only
     * combination 0 (every device off) is tried. Logs one INFO line.
     *
     * @param numCombos output: number of combinations the agent should try.
     */
    @OPERATION
    public void beginReachabilityProbe(OpFeedbackParam<Integer> numCombos) {
        java.util.LinkedHashMap<String, int[]> byType = new java.util.LinkedHashMap<>();
        for (int a = 0; a < nActions; a++) {
            StereotypeReasoner.ActionInfo ai = actionInfos[a];
            if (ai == null || ai.wotActionType == null) continue; // skip DO_NOTHING
            if (blacklisted[a]) continue;                         // skip blacklisted component
            int[] pair = byType.computeIfAbsent(ai.wotActionType, k -> new int[]{-1, -1});
            if (ai.wotValue) pair[0] = a; else pair[1] = a;       // [onIdx, offIdx]
        }
        java.util.List<int[]> survivors = new java.util.ArrayList<>();
        for (int[] pair : byType.values()) {
            if (pair[0] >= 0 && pair[1] >= 0) survivors.add(pair);
        }
        probeSurvivors = survivors.toArray(new int[0][]);
        probeBestRank  = -1;
        probeBestDist  = Integer.MAX_VALUE;
        int k = probeSurvivors.length;
        // Cap: up to 16 devices give 2^k combinations; more give 1 (combination 0 only).
        int combos = (k >= 0 && k <= 16) ? (1 << k) : 1;
        numCombos.set(combos);
        LOGGER.info("beginReachabilityProbe: " + k + " surviving actuator(s) → "
            + combos + " combination(s) to test.");
    }

    /**
     * The actions that put the room into combination {@code comboIdx}: for the
     * j-th device of the probe, its ON action if bit j of comboIdx is 1, else
     * its OFF action. The agent sends them one by one (actionToWoT,
     * invokeAction).
     *
     * @param comboIdx   combination number, 0 to numCombos - 1.
     * @param actionList output: Object[] of Integer action indices, one per device.
     */
    @OPERATION
    public void getProbeCombo(int comboIdx, OpFeedbackParam<Object[]> actionList) {
        int k = (probeSurvivors == null) ? 0 : probeSurvivors.length;
        Object[] out = new Object[k];
        for (int j = 0; j < k; j++) {
            int bit = (comboIdx >> j) & 1;
            out[j] = (bit == 1) ? probeSurvivors[j][0] : probeSurvivors[j][1];
        }
        actionList.set(out);
    }

    /**
     * Record the rank of {@code zone} after a probed combination. Keeps the
     * rank closest to the zone's NOMINAL goal (smallest |rank - goal|; on a
     * tie the higher rank).
     *
     * @param stateVec state vector read after the combination.
     * @param zone     0-based zone index.
     */
    @OPERATION
    public void recordProbeRank(Object[] stateVec, int zone) {
        int rank = toInt(stateVec[zoneLevelIndices[zone]]);
        int dist = Math.abs(rank - goal[zone]);
        if (dist < probeBestDist || (dist == probeBestDist && rank > probeBestRank)) {
            probeBestDist = dist;
            probeBestRank = rank;
        }
        LOGGER.fine("recordProbeRank: zone " + zone + " achieved rank " + rank
            + " (best so far " + probeBestRank + ")");
    }

    /**
     * Finish the probe: the best achievable rank is reported. If it is below the
     * nominal goal, the zone is DEGRADED — the effective goal is lowered to that
     * best rank and {@code degraded} is set true (WARNING log). Otherwise,
     * including when no rank was recorded (best rank -1), nothing changes
     * (INFO log); an effective goal lowered earlier is not raised again.
     *
     * @param zone     0-based zone index (the zone of recordProbeRank).
     * @param bestRank output: the closest rank found, -1 if none was recorded.
     * @param degraded output: true if the effective goal was lowered.
     */
    @OPERATION
    public void finishReachabilityProbe(int zone, OpFeedbackParam<Integer> bestRank,
                                        OpFeedbackParam<Boolean> degraded) {
        bestRank.set(probeBestRank);
        boolean deg = probeBestRank >= 0 && probeBestRank < goal[zone];
        if (deg) {
            effectiveGoal[zone] = probeBestRank;
            LOGGER.warning("finishReachabilityProbe: zone " + zone + " NOMINAL goal rank "
                + goal[zone] + " UNREACHABLE — best-effort rank " + probeBestRank
                + " (shortfall " + (goal[zone] - probeBestRank) + "). Entering DEGRADED mode.");
        } else {
            LOGGER.info("finishReachabilityProbe: zone " + zone + " nominal goal rank "
                + goal[zone] + " still reachable (best probed rank " + probeBestRank + ").");
        }
        degraded.set(deg);
    }

    /**
     * Set the factor by which decayEpsilon multiplies epsilon (default 0.995).
     * The training and fault-stage agents pass the lab's value from
     * training_params in lab_profiles.asl.
     *
     * @param decay the decay factor.
     */
    @OPERATION
    public void setEpsilonDecay(double decay) {
        this.epsilonDecay = decay;
        LOGGER.info("setEpsilonDecay: epsilonDecay=" + decay);
    }

    /**
     * epsilon = max(epsilonMin, epsilon * epsilonDecay). The training and
     * fault-stage agents call it after every episode.
     */
    @OPERATION
    public void decayEpsilon() {
        epsilon = Math.max(epsilonMin, epsilon * epsilonDecay);
        LOGGER.fine("decayEpsilon: ε=" + epsilon);
    }

    /**
     * Record that the goal was reached in a step of an episode (the training
     * and fault-stage agents call it when isTerminal becomes true). Counts the
     * goals, keeps the first goal episode, and keeps the first goal episode
     * per start state for the legacy first-goal file (only when the episode
     * was begun with a start state).
     *
     * @param episodeNum the agent's episode number.
     */
    @OPERATION
    public void notifyGoalReached(int episodeNum) {
        goalCount++;
        if (firstGoalEpisode < 0) {
            firstGoalEpisode = episodeNum;
            LOGGER.info("notifyGoalReached: first goal reached in episode " + episodeNum);
        }
        // First goal per start state (legacy format of saveFirstGoalPerScenario)
        if (currentEpisodeStartState >= 0
                && !firstGoalByStartState.containsKey(currentEpisodeStartState)) {
            firstGoalByStartState.put(currentEpisodeStartState, episodeNum);
        }
        LOGGER.fine("notifyGoalReached: ep=" + episodeNum + " totalGoals=" + goalCount);
    }

    // -----------------------------------------------------------------------
    // Episode bookkeeping, convergence and recovery detection
    // -----------------------------------------------------------------------

    /**
     * Start an episode without a recorded start state: resets the episode
     * accumulators and adds 1 to the fade clock. The training and fault-stage
     * agents use it only when the lab profile has no training-scenario file
     * (random start).
     */
    @OPERATION
    public void beginEpisode() {
        episodeCumRewardZ1 = 0;
        episodeCumRewardZ2 = 0;
        episodeSteps = 0;
        episodeWastedByPenalty  = 0;
        episodeWastedByNoEffect = 0;
        currentEpisodeStartState = -1;
        currentEpisodeScenarioId = -1;
        episodeMaxBellmanDelta = 0.0;
        currentEpisodeNum++;
    }

    /**
     * Same as beginEpisode, but records the start state, so that
     * notifyGoalReached can keep the first goal per start state. Used by the
     * fault-stage agent when the profile has a training-scenario file.
     *
     * @param startStateVec  state vector at the start of the episode.
     */
    @OPERATION
    public void beginEpisodeFromState(Object[] startStateVec) {
        episodeCumRewardZ1 = 0;
        episodeCumRewardZ2 = 0;
        episodeSteps = 0;
        episodeWastedByPenalty  = 0;
        episodeWastedByNoEffect = 0;
        currentEpisodeStartState = stateVecToIndex(startStateVec);
        currentEpisodeScenarioId = -1;
        episodeMaxBellmanDelta = 0.0;
        currentEpisodeNum++;
    }

    /**
     * Give the first-goal tracker the ordered list of training-scenario ids
     * (FirstGoalPresentationTracker.configure). The training agent calls it
     * once before the first episode when the profile has a training-scenario
     * file.
     *
     * @param orderedScenarioIds scenario ids in file order.
     */
    @OPERATION
    public void configureTrainingScenarios(Object[] orderedScenarioIds) {
        List<Integer> ids = new ArrayList<>();
        for (Object id : orderedScenarioIds) ids.add(toInt(id));
        firstGoalPresentations.configure(ids);
        LOGGER.info("configureTrainingScenarios: " + ids);
    }

    /**
     * Same as beginEpisodeFromState, and also records the scenario id (for
     * the ScenarioId metrics column and the first-goal tracker). The training
     * agent calls it after placing the room in the scenario and waiting for
     * the simulator.
     *
     * @param scenarioId      id of the training scenario.
     * @param startStateVec   state vector at the start of the episode.
     * @param terminalAtStart true if the start state is already at the goal.
     */
    @OPERATION
    public void beginEpisodeForScenario(int scenarioId, Object[] startStateVec,
                                        boolean terminalAtStart) {
        episodeCumRewardZ1 = 0;
        episodeCumRewardZ2 = 0;
        episodeSteps = 0;
        episodeWastedByPenalty = 0;
        episodeWastedByNoEffect = 0;
        currentEpisodeStartState = stateVecToIndex(startStateVec);
        currentEpisodeScenarioId = scenarioId;
        episodeMaxBellmanDelta = 0.0;
        currentEpisodeNum++;
        firstGoalPresentations.begin(scenarioId, terminalAtStart);
    }

    /**
     * End an episode: append one metrics row (episode, steps, summed clipped
     * rewards of zones 1 and 2, goal reached, current epsilon, the two
     * wasted-step counters, scenario id; see saveMetrics), close the
     * scenario's presentation in the first-goal tracker when the episode had
     * a scenario id, and update the training convergence count. The training
     * and fault-stage agents call it before decayEpsilon, so the row holds the
     * epsilon used in the episode.
     *
     * @param episodeNum  the agent's episode number.
     * @param goalReached whether the final state of the episode is terminal.
     */
    @OPERATION
    public void endEpisode(int episodeNum, boolean goalReached) {
        episodeMetrics.add(new double[]{
            episodeNum,
            episodeSteps,
            episodeCumRewardZ1,
            episodeCumRewardZ2,
            goalReached ? 1.0 : 0.0,
            epsilon,
            episodeWastedByPenalty,
            episodeWastedByNoEffect,
            currentEpisodeScenarioId
        });
        if (currentEpisodeScenarioId >= 0) {
            firstGoalPresentations.end(goalReached);
        }
        // Convergence count: the largest per-step update of this episode was below the threshold
        convergenceCount = (episodeMaxBellmanDelta < CONVERGENCE_THRESHOLD) ? convergenceCount + 1 : 0;
    }

    /**
     * Training convergence test: true when, in each of the last 100
     * consecutive episodes, the largest per-step update was below 1e-3
     * (convergenceWindow, CONVERGENCE_THRESHOLD). warmRestart resets the count.
     *
     * @param converged  output: true if converged.
     */
    @OPERATION
    public void hasConverged(OpFeedbackParam<Boolean> converged) {
        converged.set(convergenceCount >= convergenceWindow);
    }

    /**
     * Advance the recovery detector by one episode: take the greedy policy
     * ({@link #jointArgmaxAction} of every state, blacklisted actions
     * excluded; the soft and energy preferences are not part of it) and
     * compare it with the previous snapshot. Equal: the stable count grows
     * by 1; different (or no snapshot yet): the count is 0 and the new policy
     * is stored. The fault-stage agent calls it after every episode once a
     * device has been detected. The test ignores epsilon and whether the goal
     * was reached, so it can settle when the update-size test cannot.
     */
    @OPERATION
    public void updateRecoveryDetector() {
        int[] pol = new int[nStates];
        for (int s = 0; s < nStates; s++) pol[s] = jointArgmaxAction(s);
        if (recoveryPolicy != null && java.util.Arrays.equals(pol, recoveryPolicy)) {
            recoveryStableCount++;
        } else {
            recoveryStableCount = 0;
            recoveryPolicy = pol;
        }
    }

    /**
     * True once the stable count of the recovery detector has reached
     * RECOVERY_WINDOW (fault.recover.window, default 50), i.e. the greedy
     * policy was unchanged in that many consecutive comparisons. The
     * fault-stage agent then ends the run.
     *
     * @param recovered  output: true if recovered.
     */
    @OPERATION
    public void hasRecovered(OpFeedbackParam<Boolean> recovered) {
        recovered.set(recoveryStableCount >= RECOVERY_WINDOW);
    }

    /**
     * Write all episode rows (endEpisode) to a CSV file, overwriting it.
     * Columns: Episode, Steps, RewardZ1, RewardZ2, GoalReached, Epsilon,
     * WastedByPenalty, WastedByNoEffect, and with protocol phase1-v2 also
     * ScenarioId (-1 for an episode without scenario). After an empty line
     * follow three summary lines starting with "#": FirstGoalEpisode
     * ("none" if never), TotalGoalCount, and GoalRate_last100 (goal rate of
     * the last up to 100 rows, "N/A" without rows). Numbers are formatted
     * with String.format / printf in the JVM's default locale. An IOException
     * is logged as a WARNING.
     *
     * @param filename path of the CSV file.
     */
    @OPERATION
    public void saveMetrics(String filename) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            if ("phase1-v2".equals(protocolVersion)) {
                pw.println("Episode,Steps,RewardZ1,RewardZ2,GoalReached,Epsilon,WastedByPenalty,WastedByNoEffect,ScenarioId");
            } else {
                pw.println("Episode,Steps,RewardZ1,RewardZ2,GoalReached,Epsilon,WastedByPenalty,WastedByNoEffect");
            }
            for (double[] m : episodeMetrics) {
                if ("phase1-v2".equals(protocolVersion)) {
                    pw.printf("%d,%d,%.2f,%.2f,%d,%.6f,%d,%d,%d%n",
                        (int) m[0], (int) m[1], m[2], m[3], (int) m[4], m[5],
                        (int) m[6], (int) m[7], (int) m[8]);
                } else {
                    pw.printf("%d,%d,%.2f,%.2f,%d,%.6f,%d,%d%n",
                        (int) m[0], (int) m[1], m[2], m[3], (int) m[4], m[5],
                        (int) m[6], (int) m[7]);
                }
            }
            pw.println();
            pw.println("# Summary");
            pw.println("# FirstGoalEpisode," + (firstGoalEpisode < 0 ? "none" : firstGoalEpisode));
            pw.println("# TotalGoalCount," + goalCount);
            int last100Goals = 0;
            int start = Math.max(0, episodeMetrics.size() - 100);
            for (int i = start; i < episodeMetrics.size(); i++) {
                if (episodeMetrics.get(i)[4] > 0.5) last100Goals++;
            }
            int window = episodeMetrics.size() - start;
            pw.println("# GoalRate_last100," + (window > 0 ? String.format("%.2f", (double) last100Goals / window) : "N/A"));
            LOGGER.info("saveMetrics: written to " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveMetrics: failed to write " + filename + " — " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Persistence of the Q-table
    // -----------------------------------------------------------------------

    /**
     * Write the Q-tables. With base = filename with every ".csv" removed:
     *   filename              the summed table of all zones;
     *   base_zone1.csv, ...   one table per zone;
     *   base_visits.csv       visit counts (saveVisitCountsSidecar);
     *   base_trust.csv        adaptive-trust counts (saveAdaptiveTrustSidecar).
     * Table files have the header "StateIndex,<action labels>" and one row per
     * state with at least one non-zero value, values rounded to 2 decimals
     * (so a table read back by loadQTable is rounded). The training agent
     * writes the starting table and the final table, the fault-stage agent
     * the adapted table. Errors are logged, not thrown.
     *
     * @param filename path of the summed-table CSV file.
     */
    @OPERATION
    public void saveQTable(String filename) {
        // Column names are the action labels
        String[] actionNames = new String[nActions];
        for (int i = 0; i < nActions; i++) {
            actionNames[i] = actionInfos[i].label;
        }
        saveQTableToFile(filename, actionNames, true, null);
        String base = filename.replace(".csv", "");
        for (int z = 0; z < qTables.length; z++) {
            try {
                saveQTableToFile(base + "_zone" + (z + 1) + ".csv", actionNames, false, qTables[z]);
            } catch (Throwable t) {
                LOGGER.log(java.util.logging.Level.SEVERE,
                    "saveQTable: zone " + (z + 1) + " threw: " + t.getClass().getName()
                    + " - " + t.getMessage(), t);
            }
        }
        // Sidecars, so that a loaded table also gets its visit counts (per-cell
        // fade) and adaptive-trust counts back.
        saveVisitCountsSidecar(base + "_visits.csv");
        saveAdaptiveTrustSidecar(base + "_trust.csv");
    }

    /**
     * Write the visit counts: header "StateIndex,<action labels>", one row per
     * state with at least one visit. An IOException is logged as a WARNING.
     *
     * @param filename path of the sidecar file.
     */
    private void saveVisitCountsSidecar(String filename) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename), 1 << 20);
             PrintWriter pw = new PrintWriter(bw)) {
            StringBuilder row = new StringBuilder(256);
            // Columns are keyed by action label, so that the loader can map
            // them to the current action order.
            row.append("StateIndex");
            for (int a = 0; a < nActions; a++) row.append(',').append(actionInfos[a].label);
            pw.println(row);
            long rowsWritten = 0;
            for (int s = 0; s < nStates; s++) {
                long sum = 0;
                for (int a = 0; a < nActions; a++) sum += visitCounts[s][a];
                if (sum == 0) continue;
                row.setLength(0);
                row.append(s);
                for (int a = 0; a < nActions; a++) row.append(',').append(visitCounts[s][a]);
                pw.println(row);
                rowsWritten++;
            }
            LOGGER.info("saveVisitCountsSidecar: " + rowsWritten + " rows → " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveVisitCountsSidecar: failed " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Write the adaptive-trust counts: header "ActionLabel,SunBucket,Sum,N",
     * one row per (action, sun rank) with N > 0. With adaptive trust off the
     * file has only the header. Not written before configureQLearner. An
     * IOException is logged as a WARNING.
     *
     * @param filename path of the sidecar file.
     */
    private void saveAdaptiveTrustSidecar(String filename) {
        if (actionCalSum == null || actionCalN == null) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            // Rows are keyed by action label (an older format used the
            // header "Action,..." with positional indices; the loader refuses it).
            pw.println("ActionLabel,SunBucket,Sum,N");
            long rowsWritten = 0;
            for (int a = 0; a < actionCalN.length; a++) {
                for (int sb = 0; sb < actionCalN[a].length; sb++) {
                    if (actionCalN[a][sb] == 0) continue;
                    pw.println(actionInfos[a].label + "," + sb + ","
                             + actionCalSum[a][sb] + "," + actionCalN[a][sb]);
                    rowsWritten++;
                }
            }
            LOGGER.info("saveAdaptiveTrustSidecar: " + rowsWritten + " rows → " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveAdaptiveTrustSidecar: failed " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Write one table file (see saveQTable for the format).
     *
     * @param filename    path of the file.
     * @param actionNames column names (action labels).
     * @param combined    true: write the sum of all zone tables (singleTable unused).
     * @param singleTable the zone table to write when combined is false.
     */
    private void saveQTableToFile(String filename, String[] actionNames,
                                  boolean combined, double[][] singleTable) {
        // A large BufferedWriter, one reused StringBuilder and manual
        // rounding (appendRounded2) instead of String.format per cell: the
        // tables can have millions of cells, and per-cell formatting caused
        // enough garbage-collection load to stall the JVM. The manual
        // rounding is also independent of the locale.
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename), 1 << 20);
             PrintWriter pw = new PrintWriter(bw)) {
            StringBuilder row = new StringBuilder(512);
            row.append("StateIndex");
            for (String a : actionNames) { row.append(',').append(a); }
            pw.println(row);
            long rowsWritten = 0;
            for (int s = 0; s < nStates; s++) {
                boolean anyNonZero = false;
                for (int a = 0; a < nActions; a++) {
                    double v = combined ? combinedQ(s, a) : singleTable[s][a];
                    if (v != 0.0) { anyNonZero = true; break; }
                }
                if (!anyNonZero) continue;
                row.setLength(0);
                row.append(s);
                for (int a = 0; a < nActions; a++) {
                    double v = combined ? combinedQ(s, a) : singleTable[s][a];
                    row.append(',');
                    appendRounded2(row, v);
                }
                pw.println(row);
                rowsWritten++;
            }
            LOGGER.info("saveQTable: written to " + filename + " (" + rowsWritten + " rows)");
        } catch (IOException e) {
            LOGGER.warning("saveQTable: failed to write " + filename + " - " + e.getMessage());
        } catch (Throwable t) {
            LOGGER.log(java.util.logging.Level.SEVERE,
                "saveQTable: unexpected error writing " + filename
                + " (type=" + t.getClass().getName() + ", msg=" + t.getMessage() + ")", t);
        }
    }

    /** Append v rounded to 2 decimals to sb (halves rounded away from zero; a
     *  value that rounds to 0 is written "0.00" without sign; NaN and
     *  infinities as "NaN" / "Infinity" / "-Infinity"), with '.' as decimal
     *  separator in every locale. */
    private static void appendRounded2(StringBuilder sb, double v) {
        if (Double.isNaN(v)) { sb.append("NaN"); return; }
        if (Double.isInfinite(v)) { sb.append(v > 0 ? "Infinity" : "-Infinity"); return; }
        // Round half-up to 2 decimals.
        long scaled = (long) Math.floor(Math.abs(v) * 100.0 + 0.5);
        if (v < 0 && scaled != 0) sb.append('-');
        sb.append(scaled / 100);
        sb.append('.');
        long frac = scaled % 100;
        if (frac < 10) sb.append('0');
        sb.append(frac);
    }

    /**
     * Write the reasoner's IV statistics (tries and successes of IV-gated
     * "on" actions) to a JSON file (StereotypeReasoner.saveIVStats). The
     * training agent calls it at the end of training.
     *
     * @param filename  destination path (iv_stats_stereotypes_&lt;arm&gt;&lt;suffix&gt;.json).
     */
    @OPERATION
    public void saveIVStats(String filename) {
        reasoner.saveIVStats(filename);
    }

    /**
     * Read IV statistics written by saveIVStats into the reasoner
     * (StereotypeReasoner.loadIVStats), so that its IV-unsat preference in the
     * test uses the counts of training. Called by the test agent.
     *
     * @param filename  source path.
     */
    @OPERATION
    public void loadIVStats(String filename) {
        reasoner.loadIVStats(filename);
    }

    /**
     * Write the visit counts as a coverage file: header
     * "StateIndex,<action labels>,TotalVisits", one row per visited state,
     * then an empty line and three "#" summary lines (VisitedStates,
     * VisitedStateActionPairs, TotalVisits). The training agent calls it at
     * the end of training. An IOException is logged as a WARNING.
     *
     * @param filename  destination CSV path.
     */
    @OPERATION
    public void saveCoverage(String filename) {
        String[] actionNames = new String[nActions];
        for (int i = 0; i < nActions; i++) actionNames[i] = actionInfos[i].label;

        long visitedStates  = 0;
        long totalVisits    = 0;
        long visitedSA      = 0;

        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.print("StateIndex");
            for (String a : actionNames) pw.print("," + a);
            pw.println(",TotalVisits");

            for (int s = 0; s < nStates; s++) {
                long rowSum = 0;
                for (int a = 0; a < nActions; a++) rowSum += visitCounts[s][a];
                if (rowSum == 0) continue;
                visitedStates++;
                totalVisits += rowSum;
                pw.print(s);
                for (int a = 0; a < nActions; a++) {
                    pw.print("," + visitCounts[s][a]);
                    if (visitCounts[s][a] > 0) visitedSA++;
                }
                pw.println("," + rowSum);
            }

            pw.println();
            pw.println("# VisitedStates," + visitedStates + " / " + nStates);
            pw.println("# VisitedStateActionPairs," + visitedSA + " / " + (nStates * nActions));
            pw.println("# TotalVisits," + totalVisits);
            LOGGER.info("saveCoverage: " + visitedStates + " visited states, "
                       + visitedSA + " (s,a) pairs → " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveCoverage: failed to write " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Write the first-goal file. With protocol phase1-v2: one row per
     * training scenario from FirstGoalPresentationTracker (ProtocolVersion,
     * ScenarioId, Presentations, FirstSuccessPresentation, Censored,
     * AnalysisPresentation, TerminalAtStartPresentations). Otherwise (legacy):
     * StartStateIndex, FirstGoalEpisode, one row per start state from which a
     * goal was reached (filled only for episodes begun with
     * beginEpisodeFromState or beginEpisodeForScenario). Both end with an
     * empty line and "# TotalScenarioRows,n". An IOException is logged as a
     * WARNING.
     *
     * @param filename  destination CSV path.
     */
    @OPERATION
    public void saveFirstGoalPerScenario(String filename) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            if ("phase1-v2".equals(protocolVersion)) {
                pw.println("ProtocolVersion,ScenarioId,Presentations,FirstSuccessPresentation,Censored,AnalysisPresentation,TerminalAtStartPresentations");
                for (FirstGoalPresentationTracker.Record record : firstGoalPresentations.records()) {
                    String first = record.firstSuccessPresentation == null
                            ? "" : String.valueOf(record.firstSuccessPresentation);
                    pw.println("phase1-v2," + record.scenarioId + "," + record.presentations
                            + "," + first + "," + record.censored() + ","
                            + record.analysisValue() + ","
                            + record.terminalAtStartPresentations);
                }
            } else {
                pw.println("StartStateIndex,FirstGoalEpisode");
                for (Map.Entry<Integer, Integer> entry : firstGoalByStartState.entrySet()) {
                    pw.println(entry.getKey() + "," + entry.getValue());
                }
            }
            pw.println();
            int count = "phase1-v2".equals(protocolVersion)
                    ? firstGoalPresentations.records().size() : firstGoalByStartState.size();
            pw.println("# TotalScenarioRows," + count);
            LOGGER.info("saveFirstGoalPerScenario: " + count + " entries → " + filename);
        } catch (IOException e) {
            LOGGER.warning("saveFirstGoalPerScenario: failed to write " + filename
                          + " — " + e.getMessage());
        }
    }

    /**
     * One-line status text (arm, knowledge layer, maskStrict, epsilon, goal,
     * goals reached, first goal episode); the training agent prints it.
     *
     * @param summary output: the status text.
     */
    @OPERATION
    public void getStatus(OpFeedbackParam<String> summary) {
        summary.set("mode=" + (useStereotypes ? "STEREOTYPE" : "STANDARD")
                  + " knowledgeLayer=" + (reasoner == null ? "n/a" : reasoner.getKnowledgeLayer())
                  + " maskStrict=" + maskStrict
                  + " ε=" + String.format("%.4f", epsilon)
                  + " goal=" + Arrays.toString(goal)
                  + " goalsReached=" + goalCount
                  + " firstGoalEp=" + (firstGoalEpisode < 0 ? "none" : firstGoalEpisode));
    }

    /**
     * Switch hard masking on or off. With true, in the knowledge arm,
     * computeApplicableActions keeps only the actions the reasoner allows in
     * the state (StereotypeReasoner.getApplicableActions), for exploration and
     * greedy choice, and greedyAction adds no soft preference (the energy
     * preference stays). With false (the default) every non-blacklisted
     * action can be chosen and the knowledge graph acts through the soft
     * preference. No agent program, runner, workflow or run mode calls this
     * operation, so no run of the runners uses hard masking.
     *
     * @param strict  true = hard masking, false = soft preference.
     */
    @OPERATION
    public void setMaskStrict(boolean strict) {
        this.maskStrict = strict;
        LOGGER.info("QLearner.setMaskStrict: maskStrict=" + strict);
    }

    /**
     * Couple the fade length to the episode budget: priorDecayEpisodes =
     * max(1, numEpisodes / 4), so the shape of the fade is the same for every
     * budget. When stereo.priorDecayEpisodes is set to a positive value, that
     * value is kept and only logged. The training and fault-stage agents call
     * it once with their episode budget; both log lines are read back by the
     * runners (see the class comment). In the fault stage it runs before
     * loadQTable, so loadQTable moves the clock to the end of this length.
     *
     * @param numEpisodes  planned number of episodes.
     */
    @OPERATION
    public void setTrainingBudgetEpisodes(int numEpisodes) {
        if (PRIOR_DECAY_EPISODES_PROP > 0) {
            LOGGER.info("QLearner.setTrainingBudgetEpisodes: ignored (sysprop"
                      + " -Dstereo.priorDecayEpisodes=" + PRIOR_DECAY_EPISODES_PROP
                      + " wins). priorDecayEpisodes=" + priorDecayEpisodes);
            return;
        }
        int newDecay = Math.max(1, numEpisodes / 4);
        this.priorDecayEpisodes = newDecay;
        LOGGER.info("QLearner.setTrainingBudgetEpisodes: numEpisodes=" + numEpisodes
                  + " → priorDecayEpisodes=" + newDecay + " (auto = 25% of budget)");
    }

    /**
     * Set the episode clock of the preference fade (currentEpisodeNum) to
     * max(0, n). loadQTable sets the same field directly and does not call
     * this operation; no agent program in src/agt calls it.
     *
     * @param n  new value of the clock.
     */
    @OPERATION
    public void setEpisodeCounter(int n) {
        this.currentEpisodeNum = Math.max(0, n);
        LOGGER.info("QLearner.setEpisodeCounter: currentEpisodeNum=" + this.currentEpisodeNum
                  + " (priorDecayEpisodes=" + priorDecayEpisodes
                  + " → priorWeight at floor=" + (this.currentEpisodeNum >= priorDecayEpisodes) + ")");
    }

    /**
     * Read a table written by saveQTable (the test agent and the fault-stage
     * agent use it). With base = filename with every ".csv" removed:
     *   - if base_zone1.csv ... base_zoneN.csv all exist, each is read into
     *     its zone table (loadQTableIntoZone);
     *   - otherwise the summed table in filename is read and every value is
     *     divided equally among the zones (loadQTableCombined).
     * Columns are matched by action label (mapHeaderToActions). Cells that
     * the files do not contain (states without a row, actions without a
     * column) keep the values set by configureQLearner. Then the fade clock
     * is set to priorDecayEpisodes, i.e. to the end of the fade, so the test
     * runs with the preference weight at its floor; finally the two sidecars
     * are read if present (base_visits.csv, base_trust.csv). Errors are
     * logged, not thrown.
     *
     * @param filename  path of the summed-table CSV file.
     */
    @OPERATION
    public void loadQTable(String filename) {
        // Prefer the per-zone files: they hold each zone's values directly,
        // without the equal split of the summed table.
        String base = filename.replace(".csv", "");
        boolean zoneFilesExist = true;
        for (int z = 0; z < qTables.length; z++) {
            if (!new java.io.File(base + "_zone" + (z + 1) + ".csv").exists()) {
                zoneFilesExist = false;
                break;
            }
        }
        if (zoneFilesExist) {
            for (int z = 0; z < qTables.length; z++) {
                loadQTableIntoZone(base + "_zone" + (z + 1) + ".csv", z);
            }
            LOGGER.info("loadQTable: loaded per-zone files for base=" + base);
        } else {
            // Fall back: load combined file, distribute equally across zones
            loadQTableCombined(filename);
        }

        // Move the fade clock to the end of the fade: the test agent never
        // begins episodes, and with the clock at 0 the test would run with the
        // preference at its full start weight instead of its floor.
        this.currentEpisodeNum = priorDecayEpisodes;
        LOGGER.info("loadQTable: currentEpisodeNum ← priorDecayEpisodes="
                  + priorDecayEpisodes + " (bench prior at floor regime)");

        // Restore visit counts and adaptive-trust counts if the sidecars
        // exist; without them the per-cell fade and the trust factor have no
        // data after loading. Missing files are tolerated (tables written
        // without sidecars).
        loadVisitCountsSidecar(base + "_visits.csv");
        loadAdaptiveTrustSidecar(base + "_trust.csv");
    }

    /**
     * Read visit counts written by saveVisitCountsSidecar, columns matched by
     * action label. A missing file is logged (INFO) and skipped; a file whose
     * header matches no current action label (an older positional file, or
     * another lab's file) is ignored with a WARNING. Rows with a state index
     * out of range are skipped. A read or number error is logged as a
     * WARNING; rows read before the error stay loaded.
     *
     * @param filename path of the sidecar file.
     */
    private void loadVisitCountsSidecar(String filename) {
        java.io.File f = new java.io.File(filename);
        if (!f.exists()) {
            LOGGER.info("loadVisitCountsSidecar: not found " + filename
                      + " (cellMul fade will be inactive at bench)");
            return;
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String header = br.readLine();
            if (header == null) return;
            // Map columns by action label. An older positional sidecar
            // (header "StateIndex,a0,a1,...") matches no label, so
            // mapHeaderToActions returns null and the file is ignored:
            // positional counts cannot be trusted if the action order changed.
            int[] colToAction = mapHeaderToActions(header, filename);
            if (colToAction == null) {
                LOGGER.warning("loadVisitCountsSidecar: " + filename + " ignored"
                    + " (cellMul fade will be inactive at bench)");
                return;
            }
            String line;
            long rowsLoaded = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                int stateIdx = Integer.parseInt(parts[0].trim());
                if (stateIdx < 0 || stateIdx >= nStates) continue;
                for (int c = 0; c < colToAction.length && c + 1 < parts.length; c++) {
                    int a = colToAction[c];
                    if (a < 0) continue;
                    visitCounts[stateIdx][a] = Long.parseLong(parts[c + 1].trim());
                }
                rowsLoaded++;
            }
            LOGGER.info("loadVisitCountsSidecar: loaded " + rowsLoaded + " rows from " + filename);
        } catch (IOException | NumberFormatException e) {
            LOGGER.warning("loadVisitCountsSidecar: failed " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Read adaptive-trust counts written by saveAdaptiveTrustSidecar. A
     * missing file is logged (INFO) and skipped; a file whose header does not
     * start with "ActionLabel" is refused (SEVERE). Rows with an unknown label
     * are skipped and listed in one WARNING; rows with fewer than four fields
     * or a sun rank out of range are skipped. A read or number error is
     * logged as a WARNING; rows read before the error stay loaded.
     *
     * @param filename path of the sidecar file.
     */
    private void loadAdaptiveTrustSidecar(String filename) {
        java.io.File f = new java.io.File(filename);
        if (!f.exists()) {
            LOGGER.info("loadAdaptiveTrustSidecar: not found " + filename
                      + " (calMul adaptive trust will be inactive at bench)");
            return;
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String header = br.readLine();
            if (header == null) return;
            // Rows are keyed by action label (header "ActionLabel,SunBucket,Sum,N").
            // An older positional file (header "Action,SunBucket,Sum,N", rows
            // keyed by index) cannot be trusted if the action order changed
            // and is refused.
            if (!header.startsWith("ActionLabel")) {
                LOGGER.severe("loadAdaptiveTrustSidecar: " + filename + " is a legacy"
                    + " positional trust sidecar (header '" + header.trim() + "') —"
                    + " IGNORED after the action-space inversion. Re-train to"
                    + " regenerate. (calMul adaptive trust will be inactive at bench.)");
                return;
            }
            java.util.Map<String, Integer> byLabel = new java.util.HashMap<>();
            for (int a = 0; a < nActions; a++) byLabel.put(actionInfos[a].label, a);
            java.util.Set<String> unknown = new java.util.TreeSet<>();
            String line;
            long rowsLoaded = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 4) continue;
                Integer a  = byLabel.get(parts[0].trim());
                if (a == null) { unknown.add(parts[0].trim()); continue; }
                int sb     = Integer.parseInt(parts[1].trim());
                double sum = Double.parseDouble(parts[2].trim());
                long   n   = Long.parseLong(parts[3].trim());
                if (sb < 0 || sb >= actionCalN[a].length) continue;
                actionCalSum[a][sb] = sum;
                actionCalN[a][sb]   = n;
                rowsLoaded++;
            }
            if (!unknown.isEmpty()) {
                LOGGER.warning("loadAdaptiveTrustSidecar: " + filename
                    + " — unknown action labels skipped: " + unknown);
            }
            LOGGER.info("loadAdaptiveTrustSidecar: loaded " + rowsLoaded + " rows from " + filename);
        } catch (IOException | NumberFormatException e) {
            LOGGER.warning("loadAdaptiveTrustSidecar: failed " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Map the action columns of a saved CSV header ("StateIndex,<labels>") to
     * the current action indices by action label, so that saved files remain
     * usable if the order of the action list changes. Logs INFO for an
     * identity mapping, a WARNING listing unknown columns and actions left
     * without a column otherwise, and SEVERE when nothing matches.
     *
     * @param headerLine the header line of the file.
     * @param filename   file name, for the log.
     * @return an array whose entry c is the current action index for CSV
     *         column c+1, or -1 when that label does not exist now; or
     *         {@code null} when the header has no action column or NO label
     *         matches (an older positional file, or a file of another lab),
     *         in which case the caller ignores the file.
     */
    private int[] mapHeaderToActions(String headerLine, String filename) {
        String[] cols = headerLine.split(",");
        if (cols.length < 2) {
            LOGGER.warning("mapHeaderToActions: no action columns in " + filename);
            return null;
        }
        java.util.Map<String, Integer> byLabel = new java.util.HashMap<>();
        for (int a = 0; a < nActions; a++) byLabel.put(actionInfos[a].label, a);
        int[] map = new int[cols.length - 1];
        java.util.List<String> unknown = new java.util.ArrayList<>();
        int matched = 0;
        boolean identity = (cols.length - 1 == nActions);
        for (int c = 1; c < cols.length; c++) {
            Integer a = byLabel.get(cols[c].trim());
            map[c - 1] = (a == null) ? -1 : a;
            if (a == null) { unknown.add(cols[c].trim()); identity = false; }
            else { matched++; if (a != c - 1) identity = false; }
        }
        if (matched == 0) {
            LOGGER.severe("mapHeaderToActions: NO header label of " + filename
                + " matches the current action registry — file IGNORED."
                + " (Legacy positional artifact from before the action-space"
                + " inversion, or an artifact of a different lab. Re-train to"
                + " regenerate label-keyed artifacts.)");
            return null;
        }
        boolean[] covered = new boolean[nActions];
        for (int m : map) if (m >= 0) covered[m] = true;
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (int a = 0; a < nActions; a++) if (!covered[a]) missing.add(actionInfos[a].label);
        if (identity) {
            LOGGER.info("mapHeaderToActions: " + filename + " — identity mapping ("
                + matched + " actions)");
        } else {
            LOGGER.warning("mapHeaderToActions: " + filename + " — label-remapped "
                + matched + "/" + (cols.length - 1) + " columns"
                + (unknown.isEmpty() ? "" : "; unknown columns skipped: " + unknown)
                + (missing.isEmpty() ? "" : "; actions left at init values: " + missing));
        }
        return map;
    }

    /**
     * Read one per-zone table file into qTables[zoneIdx], columns matched by
     * label. Rows with a state index out of range and columns without a
     * current action are skipped. A missing or unreadable file, or a number
     * error, is logged as a WARNING and the operation continues; rows read
     * before the error stay loaded, all other cells keep their values.
     *
     * @param filename path of the zone file.
     * @param zoneIdx  0-based zone index.
     */
    private void loadQTableIntoZone(String filename, int zoneIdx) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(filename))) {
            String header = br.readLine();
            if (header == null) { LOGGER.warning("loadQTableIntoZone: empty " + filename); return; }
            int[] colToAction = mapHeaderToActions(header, filename);
            if (colToAction == null) return;
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                int stateIdx = Integer.parseInt(parts[0].trim());
                if (stateIdx < 0 || stateIdx >= nStates) continue;
                for (int c = 0; c < colToAction.length && c + 1 < parts.length; c++) {
                    int a = colToAction[c];
                    if (a < 0) continue;
                    qTables[zoneIdx][stateIdx][a] = Double.parseDouble(parts[c + 1].trim());
                }
            }
            LOGGER.info("loadQTableIntoZone: loaded " + filename + " → zone " + zoneIdx);
        } catch (IOException | NumberFormatException e) {
            LOGGER.warning("loadQTableIntoZone: failed " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Read a summed table and put value / (number of zones) into every zone
     * table, columns matched by label. Errors as in loadQTableIntoZone, but
     * logged as SEVERE.
     *
     * @param filename path of the summed-table file.
     */
    private void loadQTableCombined(String filename) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(filename))) {
            String header = br.readLine();
            if (header == null) { LOGGER.warning("loadQTableCombined: empty " + filename); return; }
            int[] colToAction = mapHeaderToActions(header, filename);
            if (colToAction == null) return;
            int numZones = qTables.length;
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                int stateIdx = Integer.parseInt(parts[0].trim());
                if (stateIdx < 0 || stateIdx >= nStates) continue;
                for (int c = 0; c < colToAction.length && c + 1 < parts.length; c++) {
                    int a = colToAction[c];
                    if (a < 0) continue;
                    double combined = Double.parseDouble(parts[c + 1].trim());
                    double perZone = combined / numZones;
                    for (int z = 0; z < numZones; z++) {
                        qTables[z][stateIdx][a] = perZone;
                    }
                }
            }
            LOGGER.info("loadQTableCombined: loaded from " + filename);
        } catch (IOException | NumberFormatException e) {
            LOGGER.severe("loadQTableCombined: failed " + filename + " — " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Per-zone reward computation
    // -----------------------------------------------------------------------

    /**
     * The unclipped reward of one zone for one step, plus a flag telling
     * whether one of the penalty terms regression, lost goal or no effect
     * fired (counted as WastedByPenalty in calculateQ).
     */
    private static class RewardResult {
        final double  reward;
        final boolean wastedByPenalty;
        RewardResult(double reward, boolean wastedByPenalty) {
            this.reward = reward;
            this.wastedByPenalty = wastedByPenalty;
        }
    }

    /**
     * Reward of one zone for the step (prev, action, next), towards the
     * zone's effective goal rank. The terms add up:
     *   -1     every step;
     *   +40 x  (distance to target before - distance after), so +-40 per rank
     *          of progress or regression (up to +-120 with ranks 0 to 3);
     *   +200   the zone reaches its target in this step;
     *   +5     the zone was at its target and stays there;
     *   -200   the zone was at its target and leaves it;
     *   -10    the action is not DO_NOTHING, this zone is in its affectedZones
     *          (from the knowledge graph's structure, in both arms), the rank
     *          did not change and the zone is not at its target;
     *   -5     DO_NOTHING while the zone is not at its target.
     * The sum is not clipped here: calculateQ clips it to +-REWARD_CLIP, so
     * with the default limit of 50 the +200 and -200 terms are cut to the
     * limit. Energy plays no part.
     *
     * @param zoneIdx      0-based zone index.
     * @param prevStateVec state vector before the step.
     * @param action       action index.
     * @param nextStateVec state vector after the step.
     * @return the reward and the wasted-by-penalty flag.
     */
    private RewardResult computeZoneReward(int zoneIdx, Object[] prevStateVec,
                                           int action, Object[] nextStateVec) {
        // Zone level is stored at zoneLevelIndices[zoneIdx] in the state vector
        int prevLevel = toInt(prevStateVec[zoneLevelIndices[zoneIdx]]);
        int nextLevel = toInt(nextStateVec[zoneLevelIndices[zoneIdx]]);
        // Reward towards the effective goal (see effectiveGoal).
        int target = effectiveGoal[zoneIdx];

        double  r      = 0.0;
        boolean wasted = false;

        // Per-step cost encourages reaching goal quickly
        r -= 1.0;

        // Progress reward / regression penalty: 40 per rank of change in the
        // distance to the target.
        int prevDist = Math.abs(prevLevel - target);
        int nextDist = Math.abs(nextLevel - target);
        int gapChange = prevDist - nextDist; // positive = progress, negative = regression
        if (gapChange != 0) {
            r += gapChange * 40.0;
            if (gapChange < 0) wasted = true; // regression component fired
        }

        // Goal bonus only on the step INTO the target, so that staying at the
        // target does not collect it again.
        if (prevLevel != target && nextLevel == target) {
            r += 200.0;
        } else if (nextLevel == target) {
            // Small holding reward while staying at the target.
            r += 5.0;
        }

        // Lost-goal penalty: was at target, now moved away
        if (prevLevel == target && nextLevel != target) {
            r -= 200.0;
            wasted = true;
        }

        // No-effect penalty (per zone): the action should affect this zone but
        // the zone level did not change and the zone is not at its target.
        // affectedZones restricts it to the zones the action feeds.
        if (actionInfos[action].wotActionType != null
                && actionInfos[action].affectedZones.contains(zoneIdx)
                && prevLevel == nextLevel
                && nextLevel != target) {
            r -= 10.0;
            wasted = true; // no-effect component fired
        }

        // Stagnation penalty: DO_NOTHING while not at the target
        if (actionInfos[action].wotActionType == null && nextLevel != target) {
            r -= 5.0;
        }

        return new RewardResult(r, wasted);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Flat table index of a state vector: sum of value_i * strides[i], each
     *  value first clamped to 0 .. domainSizes[i] - 1. */
    private int stateVecToIndex(Object[] sv) {
        int idx = 0;
        for (int i = 0; i < stateVecLen; i++) {
            int v = clamp(toInt(sv[i]), 0, domainSizes[i] - 1);
            idx += v * strides[i];
        }
        return idx;
    }

    /** State vector of a flat table index (inverse of stateVecToIndex for
     *  in-range values). */
    private int[] decodeState(int idx) {
        int[] out = new int[stateVecLen];
        for (int i = 0; i < stateVecLen; i++) {
            out[i] = idx / strides[i];
            idx %= strides[i];
        }
        return out;
    }

    /** Sum of the zone tables' values for (state, action); used for action
     *  choice, the bootstrap action and the summed-table file. */
    private double combinedQ(int stateIdx, int action) {
        double sum = 0.0;
        for (double[][] zt : qTables) sum += zt[stateIdx][action];
        return sum;
    }

    /**
     * The non-blacklisted action with the highest summed value at a state
     * (the sum of the zone tables, as in value decomposition). Used as the
     * bootstrap action of every zone in {@link #calculateQ}, so all zones
     * assume the one action the policy actually takes, and by the recovery
     * detector. Considers every non-blacklisted action (no masking, no
     * preference). Ties go to the lowest action index.
     */
    private int jointArgmaxAction(int stateIdx) {
        int best = -1;
        double bestVal = Double.NEGATIVE_INFINITY;
        for (int a = 0; a < nActions; a++) {
            if (blacklisted != null && blacklisted[a]) continue; // skip blacklisted actions
            double v = combinedQ(stateIdx, a);
            if (v > bestVal) { bestVal = v; best = a; }
        }
        if (best < 0) best = 0; // safety only: DO_NOTHING is never blacklisted
        return best;
    }

    /**
     * The action with the highest summed value at a state over ALL actions,
     * blacklisted ones included (ties: lowest index). Used by
     * {@link #warmRestart} to find "poisoned" states whose best action is
     * blacklisted.
     */
    private int unfilteredArgmax(int stateIdx) {
        int best = -1;
        double bestVal = Double.NEGATIVE_INFINITY;
        for (int a = 0; a < nActions; a++) {
            double v = combinedQ(stateIdx, a);
            if (v > bestVal) { bestVal = v; best = a; }
        }
        return best;
    }

    /**
     * The actions that may be chosen in a state. In the plain arm, and in the
     * knowledge arm with maskStrict false (every run, see maskStrict): every
     * non-blacklisted action. Only in the knowledge arm with maskStrict true:
     * the actions StereotypeReasoner.getApplicableActions allows, minus the
     * blacklisted ones.
     */
    private int[] computeApplicableActions(Object[] stateVec) {
        if (!useStereotypes || !maskStrict) {
            // Every non-blacklisted action; the knowledge graph acts only
            // through the soft preference in greedyAction.
            int n = 0;
            int[] buf = new int[nActions];
            for (int i = 0; i < nActions; i++) {
                if (blacklisted == null || !blacklisted[i]) buf[n++] = i;
            }
            return Arrays.copyOf(buf, n);
        }
        // Hard masking (maskStrict true; not used by any run)
        int[] sv = toIntArray(stateVec);
        int[] masked = reasoner.getApplicableActions(sv);
        if (blacklisted == null) return masked;
        int n = 0;
        int[] buf = new int[masked.length];
        for (int a : masked) if (!blacklisted[a]) buf[n++] = a;
        return Arrays.copyOf(buf, n);
    }

    /**
     * Greedy choice among {@code applicable}. For each action:
     *   value = sum of the zone tables
     *         + (knowledge arm, maskStrict false) priorWeight * prior(a)
     *           * trust factor * cell fade
     *         - (knowledge arm, energy weight > 0, "on" action of a device
     *           with ws:energyCost > 0) ENERGY_PRIOR_WEIGHT * energyCost.
     * prior(a) comes from StereotypeReasoner.getActionPriors(state);
     * priorWeight = STEREO_PRIOR_SCALE * (1 - f * (1 - PRIOR_DECAY_FLOOR))
     * with f = min(1, currentEpisodeNum / priorDecayEpisodes) (f = 1 when
     * priorDecayEpisodes <= 0); the trust factor is 1 unless adaptive trust
     * is on and has enough samples; the cell fade is
     * min(1, PRIOR_FADE_VISITS / visits(s, a)) for visited cells (1 for
     * unvisited cells or when the fade is off). The preference is added only
     * for the comparison and never written into the table. Actions within
     * 1e-9 of the best value are tied; one of them is drawn from rng.
     *
     * @param stateIdx   flat state index.
     * @param applicable candidate actions (not empty).
     * @return the chosen action index.
     */
    private int greedyAction(int stateIdx, int[] applicable) {
        // Preferences only in the knowledge arm with soft preference
        double[] priors = null;
        double   priorWeight = 0.0;
        int      sunBucket = 0;
        if (useStereotypes && !maskStrict) {
            // Decode state vector from index (needed for priors)
            int[] sv = decodeState(stateIdx);
            sunBucket = sunBucketOf(sv);
            priors = reasoner.getActionPriors(sv);
            // The weight falls linearly from STEREO_PRIOR_SCALE to
            // STEREO_PRIOR_SCALE * PRIOR_DECAY_FLOOR over priorDecayEpisodes,
            // so the knowledge shapes early learning and the learned values
            // decide later. In the test, loadQTable has set the clock to the
            // end of the fade, so the weight is at its floor there.
            double frac = priorDecayEpisodes <= 0 ? 1.0
                        : Math.min(1.0, (double) currentEpisodeNum / priorDecayEpisodes);
            double w = 1.0 - frac * (1.0 - PRIOR_DECAY_FLOOR);
            priorWeight = STEREO_PRIOR_SCALE * w;
        }

        // Collect the best actions and break ties at random, so that equal
        // values (for example an untouched row of zeros) do not always pick
        // the first index.
        double bestQ = Double.NEGATIVE_INFINITY;
        int[] tieBuf = new int[applicable.length];
        int   tieN   = 0;
        for (int a : applicable) {
            double effectivePrior = 0.0;
            if (priors != null) {
                double calMul = 1.0;
                if (ADAPTIVE_TRUST && actionCalN != null
                        && a < actionCalN.length
                        && sunBucket < actionCalN[a].length
                        && actionCalN[a][sunBucket] >= ADAPTIVE_TRUST_MIN_SAMPLES) {
                    double cal = actionCalSum[a][sunBucket] / actionCalN[a][sunBucket];
                    calMul = Math.max(ADAPTIVE_TRUST_FLOOR, cal);
                }
                // Per-cell fade: beyond PRIOR_FADE_VISITS visits the cell's
                // learned value has enough data of its own, so its preference
                // shrinks by PRIOR_FADE_VISITS / visits. This also removes a
                // non-zero floor (PRIOR_DECAY_FLOOR > 0) from well-visited
                // cells.
                double cellMul = 1.0;
                if (PRIOR_FADE_VISITS > 0 && visitCounts != null) {
                    long v = visitCounts[stateIdx][a];
                    if (v > 0) {
                        cellMul = Math.min(1.0, (double) PRIOR_FADE_VISITS / v);
                    }
                }
                effectivePrior = priorWeight * priors[a] * calMul * cellMul;
            }
            double q = combinedQ(stateIdx, a) + effectivePrior;
            // Energy preference (see ENERGY_PRIOR_WEIGHT): only in the knowledge
            // arm, with a positive weight, for an "on" action of a device with
            // ws:energyCost > 0. It does not depend on priorWeight, the cell
            // fade or maskStrict, so it stays active after the fade and in the
            // test.
            if (useStereotypes && ENERGY_PRIOR_WEIGHT > 0.0
                    && actionInfos != null && a < actionInfos.length
                    && actionInfos[a] != null
                    && actionInfos[a].wotValue
                    && actionInfos[a].energyCost > 0.0) {
                q -= ENERGY_PRIOR_WEIGHT * actionInfos[a].energyCost;
            }
            if (q > bestQ + 1e-9) {
                bestQ = q;
                tieN  = 0;
                tieBuf[tieN++] = a;
            } else if (Math.abs(q - bestQ) <= 1e-9) {
                tieBuf[tieN++] = a;
            }
        }
        if (tieN <= 1) return tieBuf[0];
        return tieBuf[rng.nextInt(tieN)];
    }

    /**
     * Sun-rank column of the adaptive-trust counts for a state vector: the sun
     * rank, clamped to 0 .. domain size - 1; 0 when the state has no sunshine
     * slot (single column).
     */
    private int sunBucketOf(int[] sv) {
        if (sv == null || sunshineIndex < 0 || sunshineIndex >= sv.length) return 0;
        int b = sv[sunshineIndex];
        if (b < 0) return 0;
        int max = (sunshineIndex < domainSizes.length) ? domainSizes[sunshineIndex] : 1;
        if (b >= max) return Math.max(0, max - 1);
        return b;
    }

    // ---- primitive coercion helpers (delegate to Converters) -------------

    private static int[] toIntArray(Object[] arr) { return Converters.toIntArray(arr); }

    private static int toInt(Object o) { return Converters.toInt(o); }

    private static int clamp(int v, int lo, int hi) { return Converters.clamp(v, lo, hi); }
}
