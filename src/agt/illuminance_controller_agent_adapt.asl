// Fault-stage program (Phase 2): detect a broken device, exclude it, learn again.
//
// The single BDI agent of a fault-stage run. It loads the Q-table that the same
// arm learned in the healthy parent lab and runs in a faulty variant of that
// lab: the profile names the parent's knowledge graph and device description,
// and only the simulator flow carries the fault (a dead or an inverted device).
//   1. Watch: play episodes with the loaded greedy policy without learning;
//      after every action QLearner's observeForFaults compares the knowledge
//      graph's predicted effect of the action with the observed change. Blinds,
//      which a trained policy may never open, are tested on purpose when the
//      state allows it (getDiagnosticProbeAction).
//   2. Exclude: the first observation that names a device as dead or inverted
//      blacklists it (both of its actions leave the action list), and
//      warmRestart prepares the table for learning again.
//   3. Reachability: the agent tries every combination of the remaining
//      devices and lowers a zone's goal to the best rank it can still reach.
//   4. Learn again: epsilon-greedy learning as in training, until the greedy
//      policy has not changed for fault.recover.window (default 50) episodes
//      in a row ("recovered") or the budget is used up. Detection stays on, so
//      a second broken device is excluded the same way.
//   5. Check and save: greedy_eval_episodes greedy episodes measure the goal
//      rate; the adapted Q-table, metrics and the recovery log are written.
// The two arms differ as in training: ql_true loads the table its arm learned
// and has the fading soft preference of the knowledge arm, whose fade
// warmRestart starts again; ql_false loads the plain arm's table and has no
// preference. Both arms use the same fault detector.
//
// Started by: task_adapt.jcm through the Gradle task taskAdapt;
// run_phase2_adapt.ps1 runs "gradlew taskAdapt -Pprofile=<fault lab>
// -Pmode=<ql_true|ql_false>" per fault lab, arm and seed, with the learning
// settings of a settings profile (config/run_config.json, phase2.adapt_settings)
// passed as -P options. Includes lab_profiles.asl.
//
// Beliefs and properties that configure it:
//   use_stereotypes(B)       Default true; the system property adapt.mode
//                            (Gradle -Pmode) sets it: "ql_false" -> false,
//                            any other non-empty value -> true.
//   active_profile(P)        Replaced by the system property active.profile.
//                            adapt_source(P, Suffix) in lab_profiles.asl names
//                            the parent's file suffix of the table to load.
//   num_episodes(N)          Placeholder; replaced by adapt.episodes (Gradle
//                            -Padapt.episodes) or, without it, by the faulty
//                            profile's training_params budget.
//   greedy_eval_episodes(K)  20; replaced by the system property
//                            fault.recover.evalEpisodes, which no Gradle task
//                            forwards, so the runner runs use 20.
//   max_steps_per_episode, action_delay_ms
//                            Step limit and wait after each action; no runner
//                            rewrites this file.
//   Learning settings (reward.clip, stereo.*, phase2.pairedArmRng, run.seed)
//   are system properties read by QLearner. Its detector settings (fault.*)
//   are also system properties, but no Gradle task forwards them, so runner
//   runs use their defaults.
//
// Artifacts and operations:
//   LabEnvironment "lab": readLabStatus, configureDiscretization,
//     getScenarioIds, the scenario setters, setRandomLabState, invokeAction,
//     and the fallback-count report.
//   QLearner "qlearner": as in training, plus loadQTable, beginEpisodeFromState,
//     getDiagnosticProbeAction, observeForFaults, blacklistComponentAt,
//     warmRestart, getNumApplicableActions, the reachability operations
//     (getNumZones, beginReachabilityProbe, getProbeCombo, recordProbeRank,
//     finishReachabilityProbe, getNominalGoal, getGoalStatus),
//     updateRecoveryDetector, hasRecovered and the recovery-log writer.
//   StereotypeLearner "learner": initLearner, observe (after a detection only).
//
// Output files (<b> = true or false, <sfx> = the faulty profile's qtable_suffix):
//   qtable_adapted_stereotypes_<b><sfx>.csv (with per-zone and side files),
//   metrics_adapted_stereotypes_<b><sfx>.csv, recovery_stereotypes_<b><sfx>.csv.

/* ============================================================
 * Initial Beliefs
 * ============================================================ */

// The arm; the system property adapt.mode sets it at start.
use_stereotypes(true).

// Lab profiles. The active profile is a fault lab (e.g. lab2_f1dead);
// adapt_source/2 maps it to the file suffix of its healthy parent, whose
// final Q-table is loaded.
{ include("lab_profiles.asl") }

// num_episodes is a placeholder (see the start plan). action_delay_ms: wait
// after each action, longer than one 50 ms simulator tick.
num_episodes(2000).
max_steps_per_episode(20).
action_delay_ms(65).

// Number of greedy check episodes after learning. They measure whether the
// final policy reaches the goal, not only whether it stopped changing: a
// policy can be stable and still fail, for example when the goal is out of
// reach. Replaced at start by the system property fault.recover.evalEpisodes.
greedy_eval_episodes(20).

/* ============================================================
 * Initial Goals
 * ============================================================ */

!apply_profile_then_start_adapt.

/* ============================================================
 * Profile bridge (the initial goal), as in the training program:
 * overrides, profile fields into beliefs, zone_goal, choice of the
 * training file, then !start_adapt.
 * ============================================================ */
@apply_profile_then_start_adapt
+!apply_profile_then_start_adapt <-
    !apply_runtime_overrides_adapt;
    !profile_print;
    !profile_td(TdUrl);                     +lab_td(TdUrl);
    !profile_light_bounds(LightBounds);     +light_rank_bounds(LightBounds);
    !profile_sunshine_bounds(SunBounds);    +sunshine_rank_bounds(SunBounds);
    !profile_sunshine_prob(SunProb);        +sunshine_satisfaction_prob(SunProb);
    !profile_zone_targets(ZoneTargetList);
    !derive_zone_goal(ZoneTargetList, []);
    !maybe_set_train_scenarios_file;
    !start_adapt.

/* ============================================================
 * Run-time overrides, read through tools.jia.system_prop:
 *   active.profile  replaces active_profile/1 when set;
 *   adapt.mode      when set, "ql_false" gives use_stereotypes(false)
 *                   and any other value use_stereotypes(true).
 * This program has no protocol_version belief.
 * ============================================================ */
@apply_runtime_overrides_adapt
+!apply_runtime_overrides_adapt <-
    tools.jia.system_prop("active.profile", "", OverrideProfile);
    if (OverrideProfile \== "") {
        ?active_profile(OldP);
        -active_profile(OldP);
        +active_profile(OverrideProfile);
        .print("[RuntimeOverride] active_profile: ", OldP, " -> ", OverrideProfile)
    };
    tools.jia.system_prop("adapt.mode", "", ModeOverride);
    if (ModeOverride \== "") {
        ?use_stereotypes(OldS);
        -use_stereotypes(OldS);
        if (ModeOverride == "ql_false") {
            +use_stereotypes(false);
            .print("[RuntimeOverride] use_stereotypes -> false (ql_false)")
        } else {
            +use_stereotypes(true);
            .print("[RuntimeOverride] use_stereotypes -> true (ql_true)")
        }
    }.
// If the override plan fails, continue with the beliefs as written in the files.
@apply_runtime_overrides_adapt_fail
-!apply_runtime_overrides_adapt <-
    .print("[RuntimeOverride] WARNING: tools.jia.system_prop failed; using pre-patched beliefs.").

// Build zone_goal/1, the list of target ranks in the order of the profile's
// zone_targets list (same plan as in the training program).
@derive_zone_goal_done
+!derive_zone_goal([], Acc) <-
    .reverse(Acc, Goal);
    +zone_goal(Goal).
@derive_zone_goal_step
+!derive_zone_goal([target(_, T)|R], Acc) <-
    !derive_zone_goal(R, [T|Acc]).

// Use the profile's training file when it is non-empty and the profile has a
// non-empty qtable_suffix (same rule as in the training program); otherwise
// episodes start from random situations.
@maybe_set_train_scenarios_file_full
+!maybe_set_train_scenarios_file
    : active_profile(P)
    & lab_profile(P, _, _, _, train_scenarios(File), _, _, _, _, _, _, qtable_suffix(QS), _)
    & QS \== "" & File \== "" <-
    +train_scenarios_file(File);
    .print("[Profile] train_scenarios_file enabled: ", File).
@maybe_set_train_scenarios_file_none
+!maybe_set_train_scenarios_file <- true.

// The parent's qtable_suffix from adapt_source/2; without a mapping the start fails.
@profile_adapt_source_ok
+!profile_adapt_source(CleanSuffix)
    : active_profile(P) & adapt_source(P, CleanSuffix) <- true.
@profile_adapt_source_missing
+!profile_adapt_source(_) <-
    .print("[Adapt] FATAL: no adapt_source/2 mapping for active profile — cannot warm-load a clean Q-table.");
    .fail.

/* ============================================================
 * Start: create and configure the artifacts, load the parent's
 * Q-table, then post !adapt(0).
 * ============================================================ */
@start_adapt
+!start_adapt : active_profile(ActiveP)
        & use_stereotypes(UseStereotypes)
        & lab_td(TdUrl)
        & zone_goal(Goal)
        & sunshine_satisfaction_prob(SunProb)
        & light_rank_bounds(LightBounds)
        & sunshine_rank_bounds(SunshineBounds) <-

    .print("==========================================================");
    .print("   ILLUMINANCE CONTROLLER AGENT - PHASE 2 ADAPTATION");
    .print("   Profile:  ", ActiveP, "  (faulty)");
    .print("   Stereo:   ", UseStereotypes);
    .print("   Goal:     ", Goal);
    .print("==========================================================");

    // Room connection (the fault lab's simulator flow), then one status read
    // so that an unreachable simulator fails the start right here.
    makeArtifact("lab", "tools.LabEnvironment", [TdUrl], LabId);
    +lab_artifact(LabId);
    .print("[probe] Checking simulator HTTP reachability ...");
    readLabStatus(_PZL, _PSR, _PSK, _PSV)[artifact_id(LabId)];
    .print("[probe] Simulator reachable — proceeding.");
    if (train_scenarios_file(ScenariosFile)) {
        getScenarioIds(ScenariosFile, ScIds)[artifact_id(LabId)];
        .length(ScIds, TCount);
        +train_scenarios_count(TCount);
        .print("[Profile] train_scenarios_count=", TCount)
    };
    configureDiscretization(LightBounds, SunshineBounds)[artifact_id(LabId)];
    .print("[Config] Discretisation bounds (static): light=", LightBounds,
           " sunshine=", SunshineBounds);

    // The fault lab's profile names the parent's knowledge graph, so QLearner
    // derives the same action list and state layout as in the parent's
    // training, and the loaded table fits.
    makeArtifact("qlearner", "tools.QLearner", [], QlId);
    +qlearner_artifact(QlId);
    !profile_ont(OntPaths);
    configureQLearner(Goal, UseStereotypes, OntPaths, SunProb)[artifact_id(QlId)];
    .print("QLearner artifact created and initialised.");

    // Episode budget: the property adapt.episodes if set (Gradle
    // -Padapt.episodes), else the fault profile's training_params value.
    // Epsilon decay: the profile's. setTrainingBudgetEpisodes sets the fade
    // length of the knowledge preference to a quarter of the budget, because
    // the fault-stage settings profiles never pass stereo.priorDecayEpisodes.
    !profile_training_params(NumEps, EpDecay);
    tools.jia.system_prop_num("adapt.episodes", NumEps, Budget);
    -+num_episodes(Budget);
    tools.jia.system_prop_num("fault.recover.evalEpisodes", 20, EvalK);
    -+greedy_eval_episodes(EvalK);
    setEpsilonDecay(EpDecay)[artifact_id(QlId)];
    setTrainingBudgetEpisodes(Budget)[artifact_id(QlId)];
    .print("[Profile] Re-learn params: num_episodes=", Budget,
           " (profile default ", NumEps, ") epsilonDecay=", EpDecay);

    // Load qtable_final_stereotypes_<b><parent suffix>.csv, the final table of
    // the same arm in the healthy parent (per-zone files and side files).
    // loadQTable also sets the episode counter to the end of the preference
    // fade, so before a detection the knowledge preference is at its floor
    // (0.0 in the settings profiles); warmRestart sets the counter back to 0.
    !profile_adapt_source(CleanSuffix);
    !sx_filename("qtable_final_stereotypes_", UseStereotypes, CleanSuffix, ".csv", CleanFile);
    loadQTable(CleanFile)[artifact_id(QlId)];
    .print("[Adapt] Warm-loaded clean Q-table from ", CleanFile);

    // StereotypeLearner, set up as in training. It is fed after a detection,
    // but this program never writes its statistics to a file.
    makeArtifact("learner", "tools.StereotypeLearner", [], LId);
    +learner_artifact(LId);
    getNumActions(NumActions)[artifact_id(QlId)];
    getStateVecLen(StateVecLen)[artifact_id(QlId)];
    getActionMetadataForLearner(ActUris, ActVals, ActLabels)[artifact_id(QlId)];
    initLearner(NumActions, StateVecLen, ActUris, ActVals, ActLabels)[artifact_id(LId)];
    getNumApplicableActions(App0)[artifact_id(QlId)];
    .print("[Adapt] Ready. Action space size (pre-fault) = ", App0,
           " of ", NumActions, " total.");
    !adapt(0).

// Compose <Prefix><Bool><Suffix><Ext> into a single filename (same plan as in
// the training program).
@sx_filename
+!sx_filename(Prefix, Bool, Suffix, Ext, Out) <-
    .concat(Prefix, Bool,  S1);
    .concat(S1,    Suffix, S2);
    .concat(S2,    Ext,    Out).

/* ============================================================
 * Episode loop: !adapt(N) runs episode N (from 0) and posts
 * !adapt(N + 1), until the budget is used up or, after a
 * detection, the recovery test succeeds. Same structure as the
 * training loop of the training program.
 * ============================================================ */
@adapt
+!adapt(N) : num_episodes(MaxEpisodes) & N < MaxEpisodes <-
    ?lab_artifact(LabId);
    ?qlearner_artifact(QlId);

    // Start situation: the entry at zero-based position N mod count of the
    // training file (a position outside the file fails, it never falls back to
    // a random start); the start state is read after 250 ms. Without a training
    // file a random situation is used and counted as a fallback.
    if (train_scenarios_file(ScenariosFile)) {
        ?train_scenarios_count(TCount);
        ScenarioPosition = N mod TCount;
        setScenarioLabStateByPosition(ScenariosFile, ScenarioPosition, _ScenarioId)[artifact_id(LabId)];
        .wait(250);
        readLabStatus(ZLStart, SRStart, SKStart, SVStart)[artifact_id(LabId)];
        encodeState(ZLStart, SRStart, SKStart, SVStart, StartStateVec)[artifact_id(QlId)];
        beginEpisodeFromState(StartStateVec)[artifact_id(QlId)]
    } else {
        setRandomLabState[artifact_id(LabId)];
        .wait(250);
        beginEpisode[artifact_id(QlId)]
    };

    !run_episode_adapt(N, 0);

    readLabStatus(ZLEnd, SREnd, SKEnd, SVEnd)[artifact_id(LabId)];
    encodeState(ZLEnd, SREnd, SKEnd, SVEnd, FinalState)[artifact_id(QlId)];
    isTerminal(FinalState, GoalReached)[artifact_id(QlId)];
    endEpisode(N, GoalReached)[artifact_id(QlId)];
    decayEpsilon[artifact_id(QlId)];

    Pct = ((N + 1) * 100) / MaxEpisodes;
    if (MaxEpisodes <= 100 | ((N + 1) mod 10) == 0) {
        .print("[Adapt] ep ", N + 1, "/", MaxEpisodes, " (", Pct, "%) goal=", GoalReached)
    };

    // Recovery is tested only after a detection (warmRestart resets the
    // test). updateRecoveryDetector compares the greedy action of every state,
    // over the remaining actions, with that of the previous episode;
    // hasRecovered is true once it has not changed for fault.recover.window
    // (default 50) episodes in a row. This test ignores epsilon and whether the
    // goal was reached, so it also settles when the goal is reachable only in
    // some situations, which a test on the size of the table updates would not.
    // The first recovered episode is stored as reconverged(N) and the run ends.
    if (detected(_)) {
        updateRecoveryDetector[artifact_id(QlId)];
        hasRecovered(Recovered)[artifact_id(QlId)];
        if (Recovered & not reconverged(_)) {
            +reconverged(N);
            .print("[Adapt] RE-CONVERGED (greedy policy stable) after fault at episode ", N + 1, " — finishing.");
            !adapt(MaxEpisodes)   // selects @adapt_done
        } else {
            !adapt(N + 1)
        }
    } else {
        !adapt(N + 1)
    }.

@adapt_done
+!adapt(N) : num_episodes(MaxEpisodes) & N >= MaxEpisodes <-
    .print("");
    .print("==========================================================");
    .print("   Adaptation complete after ", N, " episodes.");
    .print("==========================================================");
    !adapt_finish.

/* ============================================================
 * Episode: same pair as run_episode / do_step in the training
 * program. Stops when every zone is at its effective goal (the
 * goal after a possible lowering) or after max_steps_per_episode.
 * ============================================================ */
@run_episode_adapt
+!run_episode_adapt(EpN, Step) <-
    ?qlearner_artifact(QlId);
    ?lab_artifact(LabId);
    ?max_steps_per_episode(MaxSteps);

    readLabStatus(ZoneLevels, SunshineRank, SKs, SVs)[artifact_id(LabId)];
    encodeState(ZoneLevels, SunshineRank, SKs, SVs, StateVec)[artifact_id(QlId)];
    isTerminal(StateVec, Terminal)[artifact_id(QlId)];

    if (not Terminal & Step < MaxSteps) {
        !do_step_adapt(EpN, Step, StateVec, ZoneLevels, SunshineRank, SKs, SVs)
    }.

/* ============================================================
 * One step: choose, act, wait, observe, learn (after a detection
 * only), then compare the observed change with the knowledge
 * graph's prediction.
 * ============================================================ */
@do_step_adapt
+!do_step_adapt(EpN, Step, StateVec, ZoneLevels, SunshineRank, SKs, SVs) <-
    ?lab_artifact(LabId);
    ?qlearner_artifact(QlId);
    ?action_delay_ms(Delay);
    ?max_steps_per_episode(MaxSteps);

    // Action choice depends on whether a fault has been detected.
    // Before a detection: the loaded table is not changed, so the agent does
    // not learn its way around a fault before the fault is named. It plays the
    // greedy action, with one exception, the blind test. A trained policy may
    // never open a blind (a lamp gives light whatever the sun, so the learned
    // policy tends to use the lamp), and a broken blind would then never be seen.
    // getDiagnosticProbeAction returns the "on" action of the first device (by
    // action index) that the graph marks as depending on a condition (a blind;
    // also a lamp behind a power gate in the labs that declare one) and that is not
    // blacklisted, not yet confirmed healthy and currently off, when the sun
    // rank is at least fault.detect.ivMinSunRank (default 2) and every zone it
    // should raise is below its top rank; otherwise -1. The probe checks only
    // these conditions, not the other guards of observeForFaults (for example
    // a device that lights several zones, or another active device that could
    // hold the rank), so a probe step can end without a verdict; the blind is
    // then probed again in a later state that qualifies. A blind is confirmed
    // healthy when observeForFaults finds a correct response to its opening.
    // With the brick knowledge layer no device is marked as condition-dependent,
    // so no probe is ever taken.
    // After a detection: epsilon-greedy over the remaining actions (warmRestart
    // raised epsilon to at least fault.relearn.epsBoost, default 0.3).
    if (detected(_)) {
        getActionFromState(StateVec, true, Action)[artifact_id(QlId)]
    } else {
        getDiagnosticProbeAction(StateVec, Probe)[artifact_id(QlId)];
        if (Probe >= 0) {
            Action = Probe
        } else {
            getActionFromState(StateVec, false, Action)[artifact_id(QlId)]
        }
    };
    actionToWoT(Action, WotType, WotValue)[artifact_id(QlId)];
    if (WotType \== "none") {
        invokeAction(WotType, WotValue)[artifact_id(LabId)]
    };
    .wait(Delay);

    readLabStatus(ZoneLevels2, SunshineRank2, SKs2, SVs2)[artifact_id(LabId)];
    encodeState(ZoneLevels2, SunshineRank2, SKs2, SVs2, NextState)[artifact_id(QlId)];

    // Learning (Q-update and StereotypeLearner statistics) only after a
    // detection, so that before it the table cannot absorb the fault. detected/1
    // is added in @on_defect_new, which runs after observeForFaults below, so
    // the detecting step itself does not learn; every later step does.
    if (detected(_)) {
        calculateQ(StateVec, Action, NextState)[artifact_id(QlId)];
        ?learner_artifact(LId);
        observe(StateVec, Action, NextState)[artifact_id(LId)]
    };

    // Fault check, in every step (before and after a detection). observeForFaults
    // returns the WoT action type of the device if this single step shows it as
    // dead (switch did not change, or no zone it should move responded) or
    // inverted (a zone moved against the predicted direction), else "". It
    // abstains when the step cannot decide (for example "do nothing", a
    // redundant action, a zone already at its top or bottom rank, a device that
    // lights several zones, a device the graph says nothing about, a blind
    // other than opened at sun rank >= fault.detect.ivMinSunRank, or another
    // device that could explain a missing response). There is no counter: the
    // first such observation names the device.
    observeForFaults(StateVec, Action, NextState, NewlyDefective)[artifact_id(QlId)];
    if (NewlyDefective \== "") {
        !on_defect(NewlyDefective, EpN)
    };

    isTerminal(NextState, Terminal)[artifact_id(QlId)];
    if (Terminal) {
        notifyGoalReached(EpN)[artifact_id(QlId)]
    };
    if (not Terminal & Step + 1 < MaxSteps) {
        !run_episode_adapt(EpN, Step + 1)
    }.

/* ============================================================
 * Fault response for a device named by observeForFaults (Comp is
 * its WoT action type): print a notice, record the detection,
 * blacklist the device, warm restart, reachability test. The
 * context not defective(Comp) makes sure each device is handled
 * once.
 * ============================================================ */
@on_defect_new
+!on_defect(Comp, EpN) : not defective(Comp) <-
    ?qlearner_artifact(QlId);
    +defective(Comp);
    .print("==========================================================");
    .print("   [FAULT] DEFECTIVE component detected: ", Comp);
    .print("   Detected at episode ", EpN + 1, ". Blacklisting + re-learning.");
    .print("==========================================================");
    // The first detection sets detected(EpN) and primary_defect(Comp); the
    // recovery log reports this device and episode, so the blamed device and
    // the detection episode always belong together.
    if (not detected(_)) {
        +detected(EpN);
        +primary_defect(Comp)
    } else {
        // A later detection of another device (labs with two faults): only the
        // episode of the second one is kept, as secondary_detect(EpN); the
        // recovery episode is still counted from the first detection.
        if (not secondary_detect(_)) {
            +secondary_detect(EpN);
            .print("[Adapt] SECONDARY defect detected at episode ", EpN + 1, ": ", Comp)
        }
    };
    // Remove both actions of the device for the rest of the run (QLearner never
    // removes the last remaining switch action). The episode is stored with the
    // event, so the recovery log lists every blacklisting.
    blacklistComponentAt(Comp, EpN, NRemoved)[artifact_id(QlId)];
    getNumApplicableActions(AppNow)[artifact_id(QlId)];
    .print("[Adapt] Blacklisted ", NRemoved, " action(s); action space now = ", AppNow);
    // warmRestart: set the removed actions' table columns and visit counts to
    // 0, halve all values in states whose best action was removed, raise
    // epsilon to at least fault.relearn.epsBoost, set the episode counter of
    // the preference fade to 0, and reset the convergence test, the recovery
    // test and the detector's evidence counters.
    warmRestart[artifact_id(QlId)];
    .print("[Adapt] Warm restart complete — re-learning over surviving components.");
    // Reachability test for every zone (see below); it lowers a zone's
    // effective goal only if the nominal goal can no longer be reached.
    getNumZones(NumZones)[artifact_id(QlId)];
    .print("[Adapt] Probing reachability across ", NumZones, " zone(s).");
    !assess_zones(0, NumZones).
@on_defect_dup
+!on_defect(_, _) <- true.   // device already handled: nothing to do.

/* ============================================================
 * Reachability test after a blacklisting. For each zone in turn
 * (0 .. number of zones - 1): beginReachabilityProbe lists the
 * remaining devices that have both an "on" and an "off" action
 * (k devices, 2^k combinations; more than 16 devices give a
 * single combination). Each combination is set in the live faulty
 * room, one action at a time with action_delay_ms after each, and
 * after one more wait the zone's rank is recorded. The rank closest
 * to the zone's nominal goal (ties: the higher rank) is the best
 * reachable rank. If it is below the nominal goal, the zone's
 * effective goal is lowered to it (degraded mode), degraded_mode/3
 * is added and a notice is printed; otherwise nothing changes.
 * The test runs at the sun level of that moment and leaves the
 * room in the last combination tried; the step that found the
 * fault then continues with the state it read before the test.
 * ============================================================ */
@assess_zones_done
+!assess_zones(Z, N) : Z >= N <- true.
@assess_zones_step
+!assess_zones(Z, N) : Z < N <-
    !assess_reachability(Z);
    !assess_zones(Z + 1, N).

@assess_reachability
+!assess_reachability(Zone) <-
    ?qlearner_artifact(QlId);
    beginReachabilityProbe(NumCombos)[artifact_id(QlId)];
    .print("[Adapt] Reachability probe: testing ", NumCombos, " surviving-actuator combination(s).");
    !probe_combo_loop(Zone, 0, NumCombos);
    finishReachabilityProbe(Zone, BestRank, Degraded)[artifact_id(QlId)];
    if (Degraded) {
        getNominalGoal(Zone, Nominal)[artifact_id(QlId)];
        +degraded_mode(Zone, BestRank, Nominal);
        .print("==========================================================");
        .print("   [DEGRADED] Goal rank ", Nominal, " is UNREACHABLE with the");
        .print("   components that survive after blacklisting.");
        .print("   Best achievable illuminance = rank ", BestRank, ".");
        .print("   The agent will get AS CLOSE AS POSSIBLE (rank ", BestRank, ")");
        .print("   and operate in DEGRADED mode. *** USER NOTIFIED. ***");
        .print("==========================================================")
    } else {
        .print("[Adapt] Nominal goal still reachable with surviving components — normal recovery.")
    }.

// Combinations 0 .. N-1: set the combination, wait, read, record the zone's rank.
@probe_combo_loop_done
+!probe_combo_loop(_, I, N) : I >= N <- true.
@probe_combo_loop_step
+!probe_combo_loop(Zone, I, N) : I < N <-
    ?qlearner_artifact(QlId);
    ?lab_artifact(LabId);
    ?action_delay_ms(Delay);
    getProbeCombo(I, ComboActions)[artifact_id(QlId)];
    !apply_combo(ComboActions);
    .wait(Delay);
    readLabStatus(ZL, SR, SK, SV)[artifact_id(LabId)];
    encodeState(ZL, SR, SK, SV, StateVec)[artifact_id(QlId)];
    recordProbeRank(StateVec, Zone)[artifact_id(QlId)];
    !probe_combo_loop(Zone, I + 1, N).

// Set one combination: send each remaining device's "on" or "off" action.
@apply_combo_done
+!apply_combo([]) <- true.
@apply_combo_step
+!apply_combo([A | Rest]) <-
    ?qlearner_artifact(QlId);
    ?lab_artifact(LabId);
    ?action_delay_ms(Delay);
    actionToWoT(A, WotType, WotValue)[artifact_id(QlId)];
    if (WotType \== "none") {
        invokeAction(WotType, WotValue)[artifact_id(LabId)]
    };
    .wait(Delay);
    !apply_combo(Rest).

/* ============================================================
 * Finish: save the adapted table and the metrics, run the greedy
 * check, log the fallback count, write the recovery log, stop the
 * platform. run_phase2_adapt.ps1 waits for the recovery log, which
 * is written last before the stop.
 * ============================================================ */
@adapt_finish
+!adapt_finish <-
    ?qlearner_artifact(QlId);
    ?use_stereotypes(UseStereotypes);
    !profile_qtable_suffix(QtSuffix);
    !sx_filename("qtable_adapted_stereotypes_", UseStereotypes, QtSuffix, ".csv", AdaptedFile);
    saveQTable(AdaptedFile)[artifact_id(QlId)];
    .print("[Adapt] Adapted Q-table saved to ", AdaptedFile);
    !sx_filename("metrics_adapted_stereotypes_", UseStereotypes, QtSuffix, ".csv", MetricsFile);
    saveMetrics(MetricsFile)[artifact_id(QlId)];
    .print("[Adapt] Adaptation metrics saved to ", MetricsFile);
    // Greedy check of the final policy (see the plans below).
    !certify_recovery(GoalRate);
    .print("[Adapt] Greedy goal-rate of final policy = ", GoalRate);
    // Values of the recovery log: detection, recovery and second-detection
    // episodes (-1 if none) and the blamed device ("none" if none).
    !get_detect_episode(DetectEp);
    !get_reconverge_episode(ReconvergeEp);
    !get_secondary_detect_episode(SecondaryEp);
    !get_defect_label(DefectLabel);
    // Nominal goal, effective goal and degraded flag of zone 0 only; the log has
    // no columns for other zones. When the goal was lowered, GoalRate is the
    // share of greedy episodes that reached the lowered (effective) goal.
    getGoalStatus(0, NominalGoal, EffectiveGoal, DegradedFlag)[artifact_id(QlId)];
    // Log the number of random starts (learning and greedy check) before the
    // recovery log exists, so the runner always finds the line.
    ?lab_artifact(LabFbId);
    reportScenarioFallbackCount[artifact_id(LabFbId)];
    !sx_filename("recovery_stereotypes_", UseStereotypes, QtSuffix, ".csv", RecoveryFile);
    saveRecoveryLog(RecoveryFile, DetectEp, ReconvergeEp, SecondaryEp, GoalRate, DefectLabel,
                    NominalGoal, EffectiveGoal, DegradedFlag)[artifact_id(QlId)];
    .print("[Adapt] Recovery metric saved to ", RecoveryFile,
           " (detect=", DetectEp, " reconverge=", ReconvergeEp, " secondary=", SecondaryEp,
           " goalRate=", GoalRate, " nominalGoal=", NominalGoal, " bestEffort=", EffectiveGoal,
           " degraded=", DegradedFlag, ")");
    if (not detected(_)) {
        .print("[Adapt] NOTE: no fault was detected within the budget.")
    };
    if (detected(_) & not reconverged(_)) {
        .print("[Adapt] NOTE: fault detected but did NOT re-converge within the budget.")
    };
    .print("[Adapt] All done — stopping MAS.");
    .stopMAS.

// Getters for the recovery log, with the defaults -1 and "none".
@get_detect_episode_hit
+!get_detect_episode(E) : detected(E) <- true.
@get_detect_episode_miss
+!get_detect_episode(-1) <- true.
@get_reconverge_episode_hit
+!get_reconverge_episode(E) : reconverged(E) <- true.
@get_reconverge_episode_miss
+!get_reconverge_episode(-1) <- true.
@get_secondary_detect_episode_hit
+!get_secondary_detect_episode(E) : secondary_detect(E) <- true.
@get_secondary_detect_episode_miss
+!get_secondary_detect_episode(-1) <- true.
@get_defect_label_hit
+!get_defect_label(L) : primary_defect(L) <- true.
@get_defect_label_miss
+!get_defect_label("none") <- true.

/* ============================================================
 * Greedy check. Plays greedy_eval_episodes episodes with the
 * final table: greedy choice over the remaining actions (in the
 * knowledge arm including the preference at its weight at the end
 * of the run), no exploration, no learning, no fault check, no
 * recovery test. GoalRate = episodes that reached the effective
 * goal / greedy_eval_episodes (-1.0 if that number is 0). A policy
 * that the recovery test called stable can still score low here,
 * for example when the goal cannot be reached any more.
 * Episode I starts from position I mod count of the training file
 * (so the check uses the first entries of the file), or from a
 * random situation without a training file.
 * ============================================================ */
@certify_recovery
+!certify_recovery(GoalRate) <-
    ?greedy_eval_episodes(K);
    !greedy_eval_loop(K, 0, 0, Goals);
    if (K > 0) { GoalRate = Goals / K } else { GoalRate = -1.0 }.

@greedy_eval_loop_done
+!greedy_eval_loop(K, I, Acc, Acc) : I >= K <- true.
@greedy_eval_loop_step
+!greedy_eval_loop(K, I, Acc, Out) : I < K <-
    ?lab_artifact(LabId);
    if (train_scenarios_file(ScFile)) {
        ?train_scenarios_count(TCount);
        ScPosition = I mod TCount;
        setScenarioLabStateByPosition(ScFile, ScPosition, _ScId)[artifact_id(LabId)]
    } else {
        setRandomLabState[artifact_id(LabId)]
    };
    .wait(250);
    !greedy_rollout(0, Reached);
    NewAcc = Acc + Reached;
    !greedy_eval_loop(K, I + 1, NewAcc, Out).

// One greedy episode: Reached = 1 if every zone is at its effective goal before
// more than max_steps_per_episode actions have been taken, else 0.
@greedy_rollout
+!greedy_rollout(Step, Reached) <-
    ?lab_artifact(LabId);
    ?qlearner_artifact(QlId);
    ?max_steps_per_episode(MaxSteps);
    ?action_delay_ms(Delay);
    readLabStatus(ZL, SR, SK, SV)[artifact_id(LabId)];
    encodeState(ZL, SR, SK, SV, StateVec)[artifact_id(QlId)];
    isTerminal(StateVec, Terminal)[artifact_id(QlId)];
    if (Terminal) {
        Reached = 1
    } else {
        if (Step >= MaxSteps) {
            Reached = 0
        } else {
            getActionFromState(StateVec, false, Action)[artifact_id(QlId)];
            actionToWoT(Action, WotType, WotValue)[artifact_id(QlId)];
            if (WotType \== "none") {
                invokeAction(WotType, WotValue)[artifact_id(LabId)]
            };
            .wait(Delay);
            !greedy_rollout(Step + 1, Reached)
        }
    }.

/* ============================================================
 * Failure plans, as in the training program:
 *   start_adapt:   print the error; no plan writes output or stops
 *                  the platform after this.
 *   adapt:         print, wait 1 s, retry episode N through
 *                  retry_adapt (attempts 1 to 3); a later attempt
 *                  finishes the run with !adapt_finish.
 *   run_episode_adapt, do_step_adapt: print a warning; the rest
 *                  of that episode is skipped.
 *   on_defect:     print a warning; the device stays marked as
 *                  defective(Comp), so it is not handled again.
 * ============================================================ */
-!start_adapt[error(Err), error_msg(Msg)] <-
    .print("ERROR -!start_adapt: ", Err, " - ", Msg).
-!start_adapt <-
    .print("ERROR: Failed to start adaptation. Check that the FAULTY simulator is");
    .print("       running and the clean Phase-1 Q-table exists on disk.").

-!adapt(N)[error(Err), error_msg(Msg)] <-
    .print("[adapt-fail] -!adapt(", N, "): ", Err, " - ", Msg, " — retrying in 1s");
    .wait(1000);
    !retry_adapt(N, 1).
-!adapt(N) <-
    .print("[adapt-fail] WARNING: Adaptation step failed at N=", N, " — retrying in 1s");
    .wait(1000);
    !retry_adapt(N, 1).
@retry_adapt_ok
+!retry_adapt(N, Attempt) : Attempt <= 3 <-
    .print("[adapt-fail] retry attempt ", Attempt, "/3 at N=", N);
    !adapt(N).
@retry_adapt_giveup
+!retry_adapt(N, Attempt) <-
    .print("[adapt-fail] giving up after ", Attempt - 1, " retries at N=", N, " — finishing");
    !adapt_finish.
-!retry_adapt(N, Attempt)[error(Err), error_msg(Msg)] <-
    .print("[adapt-fail] retry(", N, ",", Attempt, ") failed: ", Err, " - ", Msg, " — next attempt in 1s");
    .wait(1000);
    NextAttempt = Attempt + 1;
    !retry_adapt(N, NextAttempt).

-!run_episode_adapt(EpN, Step)[error(Err), error_msg(Msg)] <-
    .print("WARNING -!run_episode_adapt(", EpN, ",", Step, "): ", Err, " - ", Msg).
-!run_episode_adapt(_, _) <-
    .print("WARNING: adaptation episode step failed.").
-!do_step_adapt(EpN, Step, _, _, _, _, _)[error(Err), error_msg(Msg)] <-
    .print("WARNING -!do_step_adapt(ep=", EpN, " step=", Step, "): ", Err, " - ", Msg).
-!do_step_adapt(_, _, _, _, _, _, _) <-
    .print("WARNING: do_step_adapt failed - skipping step.").
-!on_defect(Comp, _) <-
    .print("WARNING: fault-response failed for component ", Comp, ".").
