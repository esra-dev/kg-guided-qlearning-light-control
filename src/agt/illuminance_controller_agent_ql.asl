// Training program: tabular Q-learning for one lab and one arm.
//
// The single BDI agent of a training run. It drives the simulated room through
// the LabEnvironment artifact, lets the QLearner artifact choose actions and
// update its per-zone Q-tables, and writes the learned tables and training
// metrics to files. The test program (illuminance_controller_agent_bench.asl)
// and the fault stage (illuminance_controller_agent_adapt.asl) later load the
// final Q-table.
//
// Started by: task_ql.jcm through the Gradle task taskQl. In the experiments
// run_full_project.ps1 runs "gradlew taskQl -Pprofile=<lab>" once per lab and
// arm (training of Phases 1, 1b and 4, and of the healthy parent labs of
// Phase 2). Includes lab_profiles.asl (lab profiles and the profile_* plans).
//
// Beliefs and properties that configure it:
//   use_stereotypes(B)   The arm. true = ql_true, the knowledge arm: QLearner
//                        gives the Q-tables starting values from the lab's
//                        knowledge graph and adds a soft preference among the
//                        actions when it picks the best one; the preference
//                        fades during training and never forbids an action.
//                        false = ql_false, the plain arm: all values start at 0,
//                        no preference. There is no system property for it;
//                        run_full_project.ps1 rewrites this line before each
//                        training cell. Default here: true.
//   num_episodes, max_steps_per_episode, action_delay_ms, exec_delay_ms,
//   exec_max_steps       Rewritten by run_full_project.ps1 to the run mode's
//                        values from config/run_config.json. num_episodes is
//                        replaced at start by the episode budget of the lab's
//                        training_params (see the start plan).
//   active_profile(P)    Defined in lab_profiles.asl; replaced by the system
//                        property active.profile (Gradle option -Pprofile).
//   protocol_version(V)  Replaced by the system property phase1.protocolVersion
//                        (default "legacy"). With "phase1-v2" every episode of
//                        the budget runs; with "legacy" training may stop early
//                        once the table has converged.
//   Learning settings (reward limit, preference strength and fade, knowledge
//   layer, seed, ...) are system properties read by the Java classes, not by
//   this program.
//
// Artifacts and operations:
//   LabEnvironment "lab": readLabStatus, configureDiscretization,
//     getScenarioIds, the scenario setters, setRandomLabState, invokeAction,
//     and the fallback-count report.
//   QLearner "qlearner": configureQLearner, configureTrainingScenarios,
//     setEpsilonDecay, setTrainingBudgetEpisodes, encodeState, isTerminal,
//     getActionFromState, actionToWoT, calculateQ, the episode bookkeeping
//     (beginEpisode..., endEpisode, notifyGoalReached, decayEpsilon,
//     hasConverged, getStatus) and the save operations.
//   StereotypeLearner "learner": initLearner, observe, saveLearnedStereotypes,
//     printStats.
//
// Cycle:
//   1. Read the run-time overrides, copy the active profile's fields into
//      beliefs and build zone_goal from the profile's zone targets.
//   2. Create the artifacts, check that the simulator answers, configure the
//      rank bounds and the learner, save the initial Q-table.
//   3. Each episode: put the room into the next start situation of the
//      training file (file order, cycling), wait 250 ms, record the start
//      state, then repeat choose / act / wait / observe / update until every
//      zone is at its target or max_steps_per_episode steps are used.
//   4. After each episode: record its metrics and lower epsilon.
//   5. After the budget: save the final Q-table and the other output files.
//   6. Play the greedy policy for at most exec_max_steps steps from the
//      current room state (printed only), then stop the platform.
//
// Output files (<b> = true or false, <sfx> = the profile's qtable_suffix):
//   qtable_initial_stereotypes_<b><sfx>.csv, qtable_final_stereotypes_<b><sfx>.csv
//   (each with per-zone files and visit-count and trust side files),
//   metrics_stereotypes_<b><sfx>.csv, iv_stats_stereotypes_<b><sfx>.json,
//   coverage_stereotypes_<b><sfx>.csv, first_goal_stereotypes_<b><sfx>.csv,
//   learned_stereotypes_<b><sfx>.ttl.

/* ============================================================
 * Initial Beliefs
 * ============================================================ */

// The arm (true = ql_true, false = ql_false); rewritten by the full-run script.
use_stereotypes(true).
protocol_version("legacy").

// Lab profiles: device description, knowledge graph files, scenario files,
// rank bounds, zone targets, file suffix and training budget per lab. The
// active profile is active_profile/1 there, or the system property
// active.profile. zone_goal/1 is derived from the profile's zone targets.
{ include("lab_profiles.asl") }

// Episode loop settings. num_episodes is a placeholder: the start plan
// replaces it with the profile's training budget. max_steps_per_episode is the
// step limit of one training episode. action_delay_ms is the wait after each
// training action, longer than one 50 ms simulator tick. exec_delay_ms and
// exec_max_steps belong to the short greedy demonstration after training.
num_episodes(50).
max_steps_per_episode(20).
action_delay_ms(65).
exec_delay_ms(65).

exec_max_steps(20).

/* ============================================================
 * Initial Goals
 * ============================================================ */

!apply_profile_then_start.

/* ============================================================
 * Profile bridge (the initial goal). Applies the run-time
 * overrides, prints the active profile, copies its fields into
 * the beliefs lab_td, light_rank_bounds, sunshine_rank_bounds and
 * sunshine_satisfaction_prob, builds zone_goal, decides whether
 * training uses the profile's training file, then posts !start.
 * The same bridge, with other names, opens the test, fault-stage
 * and delay-learning programs.
 * ============================================================ */
@apply_profile_then_start
+!apply_profile_then_start <-
    !apply_runtime_overrides_ql;
    !profile_print;
    !profile_td(TdUrl);                     +lab_td(TdUrl);
    !profile_light_bounds(LightBounds);     +light_rank_bounds(LightBounds);
    !profile_sunshine_bounds(SunBounds);    +sunshine_rank_bounds(SunBounds);
    !profile_sunshine_prob(SunProb);        +sunshine_satisfaction_prob(SunProb);
    !profile_zone_targets(ZoneTargetList);
    !derive_zone_goal(ZoneTargetList, []);
    !maybe_set_train_scenarios_file;
    !start.

/* ============================================================
 * Run-time overrides, read through the internal action
 * tools.jia.system_prop:
 *   active.profile          replaces active_profile/1 when set
 *                           (Gradle -Pprofile);
 *   phase1.protocolVersion  always replaces protocol_version/1
 *                           (default "legacy").
 * ============================================================ */
@apply_runtime_overrides_ql
+!apply_runtime_overrides_ql <-
    tools.jia.system_prop("active.profile", "", OverrideProfile);
    if (OverrideProfile \== "") {
        ?active_profile(OldP);
        -active_profile(OldP);
        +active_profile(OverrideProfile);
        .print("[RuntimeOverride] active_profile: ", OldP, " -> ", OverrideProfile)
    };
    tools.jia.system_prop("phase1.protocolVersion", "legacy", ProtocolVersion);
    ?protocol_version(OldProtocol);
    -protocol_version(OldProtocol);
    +protocol_version(ProtocolVersion);
    .print("[RuntimeOverride] protocol_version=", ProtocolVersion).
// If the override plan fails (for example because the internal action cannot be
// loaded), print a warning and continue with the beliefs as written in the
// files. run_full_project.ps1 also writes the cell's profile into
// lab_profiles.asl, so the profile is still the requested one in that case.
@apply_runtime_overrides_ql_fail
-!apply_runtime_overrides_ql <-
    .print("[RuntimeOverride] WARNING: tools.jia.system_prop failed; active_profile unchanged (using pre-patched lab_profiles.asl value).").

// Build zone_goal/1, the list of target ranks, in the order of the profile's
// zone_targets list (one target(ZoneIdx, Rank) per zone).
@derive_zone_goal_done
+!derive_zone_goal([], Acc) <-
    .reverse(Acc, Goal);
    +zone_goal(Goal).
@derive_zone_goal_step
+!derive_zone_goal([target(_, T)|R], Acc) <-
    !derive_zone_goal(R, [T|Acc]).

// Train from the profile's training file (train_scenarios_file/1) only if the
// profile names a non-empty file AND has a non-empty qtable_suffix. Otherwise
// every episode starts from a random state (setRandomLabState); this is the
// case for the old "custom" profile, whose suffix is empty. Every lab of the
// experiments has both a training file and a suffix.
@maybe_set_train_scenarios_file_full
+!maybe_set_train_scenarios_file
    : active_profile(P)
    & lab_profile(P, _, _, _, train_scenarios(File), _, _, _, _, _, _, qtable_suffix(QS), _)
    & QS \== "" & File \== "" <-
    +train_scenarios_file(File);
    .print("[Profile] train_scenarios_file enabled: ", File).
@maybe_set_train_scenarios_file_none
+!maybe_set_train_scenarios_file <- true.

/* ============================================================
 * Start: create and configure the artifacts, save the initial
 * Q-table, then post !train(0).
 * ============================================================ */
@start
+!start : active_profile(ActiveP)
        & use_stereotypes(UseStereotypes)
        & lab_td(TdUrl)
        & zone_goal(Goal)
        & sunshine_satisfaction_prob(SunProb)
        & light_rank_bounds(LightBounds)
        & sunshine_rank_bounds(SunshineBounds) <-

    .print("==========================================================");
    .print("   ILLUMINANCE CONTROLLER AGENT - Q-LEARNING");
    .print("   Profile:  ", ActiveP);
    .print("   Stereo:   ", UseStereotypes);
    .print("   Goal:     ", Goal);
    .print("==========================================================");

    // Room connection, built from the lab's Thing Description.
    makeArtifact("lab", "tools.LabEnvironment", [TdUrl], LabId);
    +lab_artifact(LabId);
    // One status read before anything else, so an unreachable simulator shows
    // up here, right after the banner, as a failed start.
    .print("[probe] Checking simulator HTTP reachability ...");
    readLabStatus(_PZL, _PSR, _PSK, _PSV)[artifact_id(LabId)];
    .print("[probe] Simulator reachable — proceeding.");
    // Number and ids of the training situations, in file order.
    if (train_scenarios_file(ScenariosFile)) {
        getScenarioIds(ScenariosFile, ScIds)[artifact_id(LabId)];
        .length(ScIds, TCount);
        +train_scenarios_count(TCount);
        +train_scenario_ids(ScIds);
        .print("[Profile] train_scenarios_count=", TCount)
    };
    .print("Lab artifact created.");

    // Lux-to-rank bounds of the active profile (light_bounds, sunshine_bounds).
    configureDiscretization(LightBounds, SunshineBounds)[artifact_id(LabId)];
    .print("[Config] Discretisation bounds (static): light=", LightBounds,
           " sunshine=", SunshineBounds);

    // The learner. configureQLearner reads the knowledge graph files of the
    // profile (the action list, the state layout and, in the knowledge arm
    // with stereo.priorScale > 0, the starting values), sets the goal and
    // seeds the random stream.
    makeArtifact("qlearner", "tools.QLearner", [], QlId);
    +qlearner_artifact(QlId);
    !profile_ont(OntPaths);
    configureQLearner(Goal, UseStereotypes, OntPaths, SunProb)[artifact_id(QlId)];
    // The ordered situation ids let QLearner record, per situation, at which
    // presentation the goal was first reached (first_goal file).
    if (train_scenario_ids(DeclaredScenarioIds)) {
        configureTrainingScenarios(DeclaredScenarioIds)[artifact_id(QlId)]
    };
    .print("QLearner artifact created and initialised.");
    // The profile's training_params give the episode budget (replacing the
    // num_episodes placeholder) and the factor by which epsilon is multiplied
    // after each episode.
    !profile_training_params(NumEps, EpDecay);
    -+num_episodes(NumEps);
    setEpsilonDecay(EpDecay)[artifact_id(QlId)];
    // Sets the fade length of the knowledge preference to a quarter of the
    // budget, unless the property stereo.priorDecayEpisodes is set (the
    // training run modes pass it), in which case that value is kept.
    setTrainingBudgetEpisodes(NumEps)[artifact_id(QlId)];
    .print("[Profile] Training params: num_episodes=", NumEps, " epsilonDecay=", EpDecay);
    // The StereotypeLearner records the observed effect of every action; it
    // gets the action list and the state length from QLearner.
    makeArtifact("learner", "tools.StereotypeLearner", [], LId);
    +learner_artifact(LId);
    getNumActions(NumActions)[artifact_id(QlId)];
    getStateVecLen(StateVecLen)[artifact_id(QlId)];
    getActionMetadataForLearner(ActUris, ActVals, ActLabels)[artifact_id(QlId)];
    initLearner(NumActions, StateVecLen, ActUris, ActVals, ActLabels)[artifact_id(LId)];
    .print("[QL] StereotypeLearner ready (numActions=", NumActions,
           " stateVecLen=", StateVecLen, ").");
    // Save the table as it stands before the first episode (all zeros in the
    // plain arm, the knowledge graph's starting values in the knowledge arm
    // when stereo.priorScale > 0).
    !profile_qtable_suffix(QtSuffix);
    .concat("qtable_initial_stereotypes_", UseStereotypes, IB1);
    .concat(IB1, QtSuffix, IB2);
    .concat(IB2, ".csv", InitFile);
    saveQTable(InitFile)[artifact_id(QlId)];
    .print("[QL] Initial Q-table saved to ", InitFile);
    !train(0).

/* ============================================================
 * Training loop: !train(N) runs episode N (counted from 0) and
 * posts !train(N + 1) until num_episodes episodes have run.
 * ============================================================ */
@train
+!train(N) : num_episodes(MaxEpisodes) & N < MaxEpisodes <-
    ?lab_artifact(LabId);
    ?qlearner_artifact(QlId);

    // Start situation: the entry at zero-based position N mod count of the
    // training file (file order, cycling); the operation returns that entry's
    // id, which need not equal its position. The start state is read only
    // after the simulator has had 250 ms to apply the situation. Without a
    // training file the room is put into a situation drawn at random from
    // benchmark/train_scenarios.json, and LabEnvironment counts the fallback.
    if (train_scenarios_file(ScenariosFile)) {
        ?train_scenarios_count(TCount);
        ScenarioPosition = N mod TCount;
        setScenarioLabStateByPosition(ScenariosFile, ScenarioPosition, ScenarioId)[artifact_id(LabId)];
        .wait(250);
        readLabStatus(ZLStart, SRStart, SKStart, SVStart)[artifact_id(LabId)];
        encodeState(ZLStart, SRStart, SKStart, SVStart, StartStateVec)[artifact_id(QlId)];
        isTerminal(StartStateVec, StartTerminal)[artifact_id(QlId)];
        beginEpisodeForScenario(ScenarioId, StartStateVec, StartTerminal)[artifact_id(QlId)]
    } else {
        setRandomLabState[artifact_id(LabId)];
        .wait(250);
        beginEpisode[artifact_id(QlId)]
    };

    !run_episode(N, 0);

    // Episode metrics: "goal reached" is judged on the state after the last
    // step. endEpisode also feeds the convergence test used below.
    readLabStatus(ZLEnd, SREnd, SKEnd, SVEnd)[artifact_id(LabId)];
    encodeState(ZLEnd, SREnd, SKEnd, SVEnd, FinalState)[artifact_id(QlId)];
    isTerminal(FinalState, GoalReached)[artifact_id(QlId)];
    endEpisode(N, GoalReached)[artifact_id(QlId)];

    // epsilon = max(0.01, epsilon * decay), once per episode.
    decayEpsilon[artifact_id(QlId)];

    // Progress line: every episode for budgets of at most 100 episodes,
    // otherwise every 10th; a full status line every 50 episodes.
    Pct = ((N + 1) * 100) / MaxEpisodes;
    if (MaxEpisodes <= 100 | ((N + 1) mod 10) == 0) {
        .print("[Training] ep ", N + 1, "/", MaxEpisodes, " (", Pct, "%) goal=", GoalReached)
    };
    if (((N + 1) mod 50) == 0) {
        getStatus(Status)[artifact_id(QlId)];
        .print("[Training] >> ep ", N + 1, "/", MaxEpisodes, " | ", Status)
    };

    // Protocol "phase1-v2" (every accepted training run mode) has a fixed
    // horizon: all num_episodes episodes run. Under "legacy" training stops
    // early once hasConverged reports that the largest table change of an
    // episode stayed below 1e-3 for 100 episodes in a row.
    if (protocol_version("phase1-v2")) {
        !train(N + 1)
    } else {
        hasConverged(Converged)[artifact_id(QlId)];
        if (Converged) {
            .print("[Training] Converged after ", N + 1, " episodes — stopping early.");
            !train(MaxEpisodes)
        } else {
            !train(N + 1)
        }
    }.

// End of training: write every output file, log the fallback count, then
// start the greedy demonstration. The file names carry the arm (true/false)
// and the profile's qtable_suffix.
@train_done
+!train(N) : num_episodes(MaxEpisodes) & N >= MaxEpisodes <-
    .print("");
    .print("==========================================================");
    .print("   Training complete after ", N, " episodes.");
    .print("   Switching to policy execution mode.");
    .print("==========================================================");
    ?qlearner_artifact(QlId);
    ?use_stereotypes(UseStereotypes);
    !profile_qtable_suffix(QtSuffix);
    !sx_filename("qtable_final_stereotypes_",  UseStereotypes, QtSuffix, ".csv",  FinalFile);
    saveQTable(FinalFile)[artifact_id(QlId)];
    .print("[QL] Final Q-table saved to ", FinalFile);
    !sx_filename("metrics_stereotypes_",       UseStereotypes, QtSuffix, ".csv",  MetricsFile);
    saveMetrics(MetricsFile)[artifact_id(QlId)];
    .print("[QL] Convergence metrics saved to ", MetricsFile);
    !sx_filename("iv_stats_stereotypes_",      UseStereotypes, QtSuffix, ".json", IVFile);
    saveIVStats(IVFile)[artifact_id(QlId)];
    .print("[QL] IV effectiveness stats saved to ", IVFile);
    !sx_filename("coverage_stereotypes_",      UseStereotypes, QtSuffix, ".csv",  CovFile);
    saveCoverage(CovFile)[artifact_id(QlId)];
    .print("[QL] Coverage data saved to ", CovFile);
    !sx_filename("first_goal_stereotypes_",    UseStereotypes, QtSuffix, ".csv",  FGFile);
    saveFirstGoalPerScenario(FGFile)[artifact_id(QlId)];
    .print("[QL] First-goal-per-scenario data saved to ", FGFile);
    // The effects StereotypeLearner observed, as Turtle. The test program's
    // rule_based mode merges learned_stereotypes_true<sfx>.ttl if present.
    ?learner_artifact(LId);
    !sx_filename("learned_stereotypes_",       UseStereotypes, QtSuffix, ".ttl",  LearnFile);
    saveLearnedStereotypes(LearnFile)[artifact_id(LId)];
    printStats[artifact_id(LId)];
    .print("[QL] Learned stereotypes saved to ", LearnFile);
    // Logs how many episodes started from a random state because no training
    // file was used; run_full_project.ps1 reads the line into its receipt.
    ?lab_artifact(LabFbId);
    reportScenarioFallbackCount[artifact_id(LabFbId)];
    !execute_policy(0).

// Compose <Prefix><Bool><Suffix><Ext> into a single filename (the fault-stage
// program has the same plan).
@sx_filename
+!sx_filename(Prefix, Bool, Suffix, Ext, Out) <-
    .concat(Prefix, Bool,  S1);
    .concat(S1,    Suffix, S2);
    .concat(S2,    Ext,    Out).

/* ============================================================
 * Episode: !run_episode(EpN, Step) reads the room and, unless
 * every zone is at its target or the step limit is reached, posts
 * !do_step, which posts !run_episode for the next step. The
 * fault-stage program has the same pair under the names
 * run_episode_adapt / do_step_adapt.
 * ============================================================ */
@run_episode
+!run_episode(EpN, Step) <-
    ?qlearner_artifact(QlId);
    ?lab_artifact(LabId);
    ?max_steps_per_episode(MaxSteps);

    readLabStatus(ZoneLevels, SunshineRank, SKs, SVs)[artifact_id(LabId)];
    encodeState(ZoneLevels, SunshineRank, SKs, SVs, StateVec)[artifact_id(QlId)];
    isTerminal(StateVec, Terminal)[artifact_id(QlId)];

    if (not Terminal & Step < MaxSteps) {
        !do_step(EpN, Step, StateVec, ZoneLevels, SunshineRank, SKs, SVs)
    }.

/* ============================================================
 * One training step: choose, act, wait, observe, update.
 * ============================================================ */
@do_step
+!do_step(EpN, Step, StateVec, ZoneLevels, SunshineRank, SKs, SVs) <-
    ?lab_artifact(LabId);
    ?qlearner_artifact(QlId);
    ?action_delay_ms(Delay);
    ?max_steps_per_episode(MaxSteps);

    // Epsilon-greedy choice (explore = true). The best action is the one with
    // the highest sum of the zone tables; in the knowledge arm QLearner adds
    // the fading knowledge preference (and, if stereo.energyPriorWeight > 0,
    // a non-fading energy term) before comparing; ties are broken at random.
    // No action is masked.
    getActionFromState(StateVec, true, Action)[artifact_id(QlId)];

    // Action index to WoT action type and value; "none" is "do nothing".
    actionToWoT(Action, WotType, WotValue)[artifact_id(QlId)];

    if (WotType \== "none") {
        invokeAction(WotType, WotValue)[artifact_id(LabId)]
    };
    .wait(Delay);

    readLabStatus(ZoneLevels2, SunshineRank2, SKs2, SVs2)[artifact_id(LabId)];
    encodeState(ZoneLevels2, SunshineRank2, SKs2, SVs2, NextState)[artifact_id(QlId)];

    // Q-update: QLearner computes one reward per zone and updates each zone table.
    calculateQ(StateVec, Action, NextState)[artifact_id(QlId)];

    // StereotypeLearner keeps running statistics of each action's observed
    // effect (written as learned_stereotypes_*.ttl at the end).
    ?learner_artifact(LId);
    observe(StateVec, Action, NextState)[artifact_id(LId)];

    isTerminal(NextState, Terminal)[artifact_id(QlId)];

    if (Terminal) {
        notifyGoalReached(EpN)[artifact_id(QlId)]
    };
    if (not Terminal & Step + 1 < MaxSteps) {
        !run_episode(EpN, Step + 1)
    }.

/* ============================================================
 * Greedy demonstration after training: from the room's current
 * state, take greedy actions (no exploration, no learning) for at
 * most exec_max_steps steps, printing each step, and stop the
 * platform when the goal is reached or the steps are used up.
 * Nothing of it is written to a file.
 * ============================================================ */
@execute_policy
+!execute_policy(Step) : exec_max_steps(MaxExecSteps) & Step < MaxExecSteps <-
    ?lab_artifact(LabId);
    ?qlearner_artifact(QlId);
    ?exec_delay_ms(Delay);

    readLabStatus(ZoneLevels, SunshineRank, SKs, SVs)[artifact_id(LabId)];
    encodeState(ZoneLevels, SunshineRank, SKs, SVs, StateVec)[artifact_id(QlId)];
    isTerminal(StateVec, Terminal)[artifact_id(QlId)];

    // Prints the whole zone list, so the line works for any number of zones
    // (a failing step here would be retried without limit, see below).
    .print("[Execute] Step ", Step, " State: zones=", ZoneLevels, " sun=", SunshineRank);

    if (Terminal) {
        .print("");
        .print("==========================================================");
        .print("   SUCCESS! Target illuminance levels achieved!");
        .print("==========================================================");
        .print("[QL] Demonstration reached goal — stopping MAS.");
        .stopMAS
    } else {
        getActionFromState(StateVec, false, Action)[artifact_id(QlId)];
        actionToWoT(Action, WotType, WotValue)[artifact_id(QlId)];
        .print("[Execute] Action: ", Action, " -> ", WotType, "=", WotValue);

        if (WotType \== "none") {
            invokeAction(WotType, WotValue)[artifact_id(LabId)]
        };
        .wait(Delay);
        !execute_policy(Step + 1)
    }.

@execute_policy_limit
+!execute_policy(Step) : exec_max_steps(MaxExecSteps) & Step >= MaxExecSteps <-
    ?qlearner_artifact(QlId);
    getStatus(Status)[artifact_id(QlId)];
    .print("[Execute] Max steps reached (", MaxExecSteps, "). Final status: ", Status);
    .print("[QL] All done — stopping MAS.");
    .stopMAS.

/* ============================================================
 * Failure plans. A failed operation fails the plan that called
 * it; the matching plan below then runs instead.
 *   start:          print the error; no plan writes output or
 *                   stops the platform after this.
 *   train:          print, wait 1 s, retry the same episode N
 *                   through retry_train (attempt 1 to 3; a later
 *                   attempt fails on purpose). The waits keep a
 *                   persistent failure from retrying in a tight
 *                   loop.
 *   run_episode, do_step: print a warning; the rest of that
 *                   episode is skipped and training continues
 *                   with the end-of-episode bookkeeping.
 *   execute_policy: wait 1 s and retry the same demonstration
 *                   step, without a limit.
 * The fault-stage program uses the same pattern; there, giving up
 * after three attempts finishes the run instead of failing.
 * ============================================================ */
-!start[error(Err), error_msg(Msg)] <-
    .print("ERROR -!start: ", Err, " - ", Msg).

-!start <-
    .print("ERROR: Failed to start. Check that the simulator is running and");
    .print("       the Thing Description URL is accessible.").

-!train(N)[error(Err), error_msg(Msg)] <-
    .print("[train-fail] -!train(", N, "): ", Err, " - ", Msg, " — retrying in 1s");
    .wait(1000);
    !retry_train(N, 1).

-!train(N) <-
    .print("[train-fail] WARNING: Training step failed at N=", N, " — retrying in 1s");
    .wait(1000);
    !retry_train(N, 1).

@retry_train_ok
+!retry_train(N, Attempt) : Attempt <= 3 <-
    .print("[train-fail] retry attempt ", Attempt, "/3 at N=", N);
    !train(N).
@retry_train_giveup
+!retry_train(N, Attempt) <-
    .print("[train-fail] giving up after ", Attempt - 1, " retries at N=", N, " — aborting training");
    .fail.
-!retry_train(N, Attempt)[error(Err), error_msg(Msg)] <-
    .print("[train-fail] retry(", N, ",", Attempt, ") failed: ", Err, " - ", Msg, " — next attempt in 1s");
    .wait(1000);
    NextAttempt = Attempt + 1;
    !retry_train(N, NextAttempt).

-!run_episode(EpN, Step)[error(Err), error_msg(Msg)] <-
    .print("WARNING -!run_episode(", EpN, ",", Step, "): ", Err, " - ", Msg).

-!run_episode(EpN, Step) <-
    .print("WARNING: Episode step ", Step, " failed.").

-!do_step(EpN, Step, _, _, _, _, _)[error(Err), error_msg(Msg)] <-
    .print("WARNING -!do_step(ep=", EpN, " step=", Step, "): ", Err, " - ", Msg).

-!do_step(EpN, Step, _, _, _, _, _) <-
    .print("WARNING: do_step(", Step, ") failed - skipping step.").

-!execute_policy(Step) <-
    .print("WARNING: Policy execution step ", Step, " failed. Retrying.");
    .wait(1000);
    !execute_policy(Step).

/* ============================================================
 * Report plans for a simulator failure and for an input rejected
 * by the Thing Description check. No plan of this program and no
 * artifact posts these two goals (a failing LabEnvironment
 * operation fails the calling plan instead, see above); the plans
 * are kept as they are. The test program has the same two plans.
 * ============================================================ */
+!handle_simulator_failure(Reason, Op, Detail) <-
    .print("ERROR: Simulator failure during QL — reason=", Reason,
           " op=", Op, " detail=", Detail);
    .print("       Stopping training.").

+!handle_invalid_input(Actuator, Expected, Actual) <-
    .print("ERROR: Invalid input during QL — actuator=", Actuator,
           " expected=", Expected, " actual=", Actual).
