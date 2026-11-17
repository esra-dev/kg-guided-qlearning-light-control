// Test program: plays the lab's test situations with one contestant.
//
// The single BDI agent of a test run. For one lab and one mode it puts the
// simulated room into every situation of the lab's test file, bench_runs times,
// lets the contestant act for at most exec_max_steps steps per situation, and
// records the outcome through the BenchmarkLogger artifact. Modes:
//   ql_true    greedy policy of the Q-table trained in the knowledge arm
//   ql_false   greedy policy of the Q-table trained in the plain arm
//   rule_based fixed rules over the knowledge graph (OntologyArtifact), no table
// The Q-learning modes never explore and never update the table.
//
// Started by: task_bench.jcm, either through the Gradle task taskBench
// (manual runs, -Pmode) or through the Gradle task runFullSweep, which
// run_full_project.ps1 calls for the test phase (Phases 1, 1b and 4) and
// which starts this program once per lab and mode with the system
// properties active.profile and bench.mode. Includes lab_profiles.asl.
//
// Beliefs and properties that configure it:
//   bench_mode(M)        Default "ql_true"; replaced by the system property
//                        bench.mode (Gradle -Pmode, or set by runFullSweep).
//   active_profile(P)    Defined in lab_profiles.asl; replaced by the system
//                        property active.profile.
//   protocol_version(V)  Replaced by the system property phase1.protocolVersion
//                        (default "legacy"). "phase1-v2" (the accepted run
//                        modes) selects the v2 recording operations.
//   bench_runs, exec_max_steps, exec_delay_ms
//                        Rewritten by run_full_project.ps1 to the run mode's
//                        values from config/run_config.json.
//   bench_anti_stuck(B)  false; no runner or workflow changes it (see the
//                        Q-learning dispatch plan).
//
// Artifacts and operations:
//   LabEnvironment "lab": configureDiscretization, getScenarioIds,
//     setScenarioLabState, readLabStatus, invokeAction, readEnergyCost,
//     readZoneTemperatures.
//   BenchmarkLogger "benchlogger": beginScenario / beginScenarioV2,
//     recordStep / recordStepV2, recordStepDetailRichV2, endScenario /
//     endScenarioV2, the JSONL trace and weakness operations, and the save
//     operations.
//   QLearner "qlearner" (Q-learning modes): configureQLearner, setSeed,
//     loadQTable, loadIVStats, encodeState, getActionFromState, actionToWoT,
//     notifyActionDispatched, getLastDispatchedAction,
//     checkCrossZoneInterference and the weakness-classification operations.
//   OntologyArtifact "ontology" (rule_based): importLearnedStereotypes,
//     discoverZones, discoverZoneQuantity, queryBestIncreaseAction,
//     queryBestDecreaseAction and the two shared-device checks.
//
// Cycle:
//   1. Read the overrides, copy the profile into beliefs, assert one
//      zone_target(ZoneIdx, Rank) per zone.
//   2. Create the artifacts; in a Q-learning mode load the arm's final
//      Q-table, in rule_based mode read zones and goals from the graph.
//   3. For run 1 .. bench_runs, for every situation id of the test file in
//      file order: set the situation, wait 300 ms, begin the record, then
//      step (read, stop if every zone is at its target, else act, wait
//      exec_delay_ms, read, record) until the goal or exec_max_steps.
//   4. Save benchmark_results_<mode>.csv and bench_step_log_<mode>.csv, close
//      the trace, stop the platform.

/* ============================================================
 * Beliefs
 * ============================================================ */

// Mode: "rule_based", "ql_false" or "ql_true"; the system property bench.mode
// replaces it at start.
bench_mode("ql_true").
protocol_version("legacy").

// bench_runs: how often every test situation is played. exec_max_steps: step
// limit per situation. exec_delay_ms: wait after each action before the room
// is read again (the simulator recomputes the light every 50 ms; the
// accepted run modes write 100 here).
bench_runs(2).
exec_max_steps(20).
exec_delay_ms(65).

// Anti-stuck replacement of the greedy action (see the Q-learning dispatch
// plan). false: the test plays the trained greedy policy unchanged, as in
// training, where no such replacement exists.
bench_anti_stuck(false).

// Lab profiles: device description, knowledge graph files, test file, rank
// bounds, zone targets and file suffix per lab.
{ include("lab_profiles.asl") }

/* ============================================================
 * Initial Goals
 * ============================================================ */

!apply_profile_then_start.

/* ============================================================
 * Profile bridge (the initial goal). Applies the run-time
 * overrides, prints the active profile, copies its fields into
 * the beliefs lab_td, light_rank_bounds, sunshine_rank_bounds and
 * sunshine_satisfaction_prob, asserts zone_target/2 per zone,
 * then posts !bench_start. The training, fault-stage and
 * delay-learning programs open with the same kind of bridge.
 * ============================================================ */
@apply_profile_then_start
+!apply_profile_then_start <-
    !apply_runtime_overrides;
    !profile_print;
    !profile_td(TdUrl);                     +lab_td(TdUrl);
    !profile_light_bounds(LightBounds);     +light_rank_bounds(LightBounds);
    !profile_sunshine_bounds(SunBounds);    +sunshine_rank_bounds(SunBounds);
    !profile_sunshine_prob(SunProb);        +sunshine_satisfaction_prob(SunProb);
    !profile_zone_targets(ZoneTargetList);
    !assert_zone_targets(ZoneTargetList);
    !bench_start.

/* ============================================================
 * Run-time overrides, read through tools.jia.system_prop:
 *   active.profile          replaces active_profile/1 when set;
 *   bench.mode              replaces bench_mode/1 when set;
 *   phase1.protocolVersion  always replaces protocol_version/1
 *                           (default "legacy").
 * ============================================================ */
@apply_runtime_overrides
+!apply_runtime_overrides <-
    tools.jia.system_prop("active.profile", "", OverrideProfile);
    if (OverrideProfile \== "") {
        ?active_profile(OldP);
        -active_profile(OldP);
        +active_profile(OverrideProfile);
        .print("[RuntimeOverride] active_profile: ", OldP, " -> ", OverrideProfile)
    };
    tools.jia.system_prop("bench.mode", "", OverrideMode);
    if (OverrideMode \== "") {
        ?bench_mode(OldM);
        -bench_mode(OldM);
        +bench_mode(OverrideMode);
        .print("[RuntimeOverride] bench_mode: ", OldM, " -> ", OverrideMode)
    };
    tools.jia.system_prop("phase1.protocolVersion", "legacy", ProtocolVersion);
    ?protocol_version(OldProtocol);
    -protocol_version(OldProtocol);
    +protocol_version(ProtocolVersion);
    .print("[RuntimeOverride] protocol_version=", ProtocolVersion).
// If the override plan fails (for example because the internal action cannot be
// loaded), print a warning and continue with the beliefs as written in the
// files. No runner writes active_profile or bench_mode into the files for the
// test, so the run would then use the defaults of lab_profiles.asl and this file;
// the start banner prints both values.
@apply_runtime_overrides_fail
-!apply_runtime_overrides <-
    .print("[RuntimeOverride] WARNING: tools.jia.system_prop failed; active_profile/bench_mode unchanged (using pre-patched values).").

// One zone_target(ZoneIdx, Rank) belief per target(ZoneIdx, Rank) of the profile.
@assert_zone_targets_done
+!assert_zone_targets([]) <- true.
@assert_zone_targets_step
+!assert_zone_targets([target(I,T)|R]) <-
    +zone_target(I, T);
    !assert_zone_targets(R).

/* ============================================================
 * Start: create the room connection and the recorder, prepare the
 * mode, then post !run_all_runs(1, bench_runs).
 * ============================================================ */
@bench_start
+!bench_start
    : bench_mode(Mode) & lab_td(TdUrl) & bench_runs(TotalRuns)
    & light_rank_bounds(LightBounds) & sunshine_rank_bounds(SunshineBounds)
    & active_profile(ActiveP) & bench_anti_stuck(AntiStuck) <-
    .print("==========================================================");
    .print("   BENCHMARK AGENT — Execution-Phase Comparison");
    .print("   Mode: ", Mode, "  |  Runs per scenario: ", TotalRuns);
    // Printed so that a run that fell back to the file defaults (see
    // @apply_runtime_overrides_fail) is visible in the log.
    .print("   active_profile=", ActiveP, "  bench_anti_stuck=", AntiStuck);
    .print("==========================================================");
    makeArtifact("lab", "tools.LabEnvironment", [TdUrl], LabId);
    +lab_artifact(LabId);
    configureDiscretization(LightBounds, SunshineBounds)[artifact_id(LabId)];
    .print("[Bench] Lab artifact ready. bounds light=", LightBounds,
           " sunshine=", SunshineBounds);
    makeArtifact("benchlogger", "tools.BenchmarkLogger", [], LogId);
    +bench_logger(LogId);
    .print("[Bench] BenchmarkLogger artifact ready.");
    !init_mode(Mode);
    !run_all_runs(1, TotalRuns).

/* ============================================================
 * Mode set-up.
 *   rule_based: open the lab's knowledge graph in OntologyArtifact,
 *     merge a learned-effects file if one exists, read the zones
 *     and build one bench_zone_goal/5 belief per zone.
 *   ql_true / ql_false: create QLearner for the same lab (same
 *     action list and state layout as in training), load the
 *     arm's final Q-table and IV statistics, open the JSONL trace.
 * ============================================================ */
@init_mode_rule_based
+!init_mode("rule_based") <-
    !profile_ont(OntPaths);
    makeArtifact("ontology", "tools.OntologyArtifact",
                 [OntPaths], OntId);
    +ontology_artifact(OntId);
    !try_import_learned_stereotypes(OntId);
    discoverZones(ZoneURIs, ZoneIdxs)[artifact_id(OntId)];
    !setup_bench_zone_goals(ZoneURIs, ZoneIdxs);
    .print("[Bench] Rule-based mode ready. Zone goals established.").

/* ============================================================
 * Learned-effects import (rule_based only). If the working
 * directory holds learned_stereotypes_true<suffix>.ttl, the file
 * that StereotypeLearner writes at the end of a ql_true training
 * run, its triples are merged into the graph that the rule
 * queries read. No query of OntologyArtifact uses a term of the
 * learned vocabulary, so the merge changes no answer of the rule
 * controller. Without the file the operation logs a warning and
 * merges nothing (count 0).
 * ============================================================ */
@try_import_learned_stereotypes
+!try_import_learned_stereotypes(OntId) <-
    !profile_qtable_suffix(QtSuffix);
    .concat("learned_stereotypes_true", QtSuffix, P1);
    .concat(P1, ".ttl", LearnPath);
    importLearnedStereotypes(LearnPath, NMerged)[artifact_id(OntId)];
    .print("[Bench] importLearnedStereotypes(", LearnPath, ") -> ",
           NMerged, " triple(s) merged.").

@init_mode_ql
+!init_mode(Mode)
    : (Mode == "ql_true" | Mode == "ql_false")
    & sunshine_satisfaction_prob(SunProb) <-
    .findall(t(I,T), zone_target(I,T), ZTUnsorted);
    .sort(ZTUnsorted, ZTSorted);
    .findall(T, .member(t(_,T), ZTSorted), Goals);
    !ql_flag_and_file(Mode, UseStereotypes, QTableFileBase);
    !profile_qtable_suffix(QtSuffix);
    !apply_qtable_suffix(QTableFileBase, QtSuffix, QTableFile);
    !profile_ont(OntPaths);
    makeArtifact("qlearner", "tools.QLearner", [], QlId);
    +qlearner_artifact(QlId);
    configureQLearner(Goals, UseStereotypes,
                      OntPaths, SunProb)[artifact_id(QlId)];
    // Replaces the random stream that configureQLearner seeded (from run.seed,
    // and under protocol legacy also from the arm) with a fixed seed 42.
    // With exploration off the stream only
    // breaks ties between equal best actions; in the test these tie-breaks are
    // therefore the same for every run.seed and both arms.
    setSeed(42)[artifact_id(QlId)];
    // Loads the per-zone tables and the visit-count and trust side files, and
    // sets QLearner's episode counter to the end of the preference fade, so
    // the fading knowledge preference is at its floor (stereo.priorDecayFloor)
    // during the test. A non-fading energy term, if configured, still applies.
    loadQTable(QTableFile)[artifact_id(QlId)];
    !ql_iv_file(Mode, IVFile);
    loadIVStats(IVFile)[artifact_id(QlId)];
    .print("[Bench] IV stats loaded from ", IVFile);
    .print("[Bench] QLearner ready. Loaded Q-table from ", QTableFile);
    // Per-step JSONL trace; recordRichStep writes to it only on labs that
    // declare weakness flags (see the fingerprinting plans).
    ?bench_logger(LogId);
    ?active_profile(Profile);
    .concat("benchmark/results/", Profile, "/", Mode, "/trace_bench_", Mode, ".jsonl", TraceFile);
    openTraceJsonl(TraceFile)[artifact_id(LogId)];
    .print("[Bench] Rich trace opened: ", TraceFile).

// Insert the profile's qtable_suffix between the base name and ".csv", e.g.
// "qtable_final_stereotypes_true.csv" + "_lab2" -> "qtable_final_stereotypes_true_lab2.csv".
@apply_qtable_suffix_empty
+!apply_qtable_suffix(Base, "", Base) <- true.
@apply_qtable_suffix_nonempty
+!apply_qtable_suffix(Base, Suffix, Out) : Suffix \== "" <-
    .delete(".csv", Base, NoExt);
    .concat(NoExt, Suffix, NoExt2);
    .concat(NoExt2, ".csv", Out).

// Mode -> arm flag and base name of the final Q-table written by training.
@ql_flag_and_file_true
+!ql_flag_and_file("ql_true",  true,  "qtable_final_stereotypes_true.csv")  <- true.
@ql_flag_and_file_false
+!ql_flag_and_file("ql_false", false, "qtable_final_stereotypes_false.csv") <- true.

// iv_stats_stereotypes_<b><suffix>.json: the training run's counts of how often
// each condition-dependent "on" action raised the light, per rank of its
// condition (the sun rank for a blind).
@ql_iv_file
+!ql_iv_file(Mode, IVFile) <-
    !ql_flag_and_file(Mode, UseStereotypes, _);
    !profile_qtable_suffix(QtSuffix);
    .concat("iv_stats_stereotypes_", UseStereotypes, S1);
    .concat(S1, QtSuffix, S2);
    .concat(S2, ".json", IVFile).

/* ============================================================
 * Zone goals for the rule-based mode: for every zone the graph
 * lists (URI and ws:zoneIndex), the profile's target for that
 * index and the zone's sensed quantity, as
 * bench_zone_goal(ZoneUri, ZoneIdx, QuantityUri, QuantityLabel, Target).
 * ============================================================ */
@setup_bench_zone_goals_done
+!setup_bench_zone_goals([], []) <- true.

@setup_bench_zone_goals_step
+!setup_bench_zone_goals([ZUri|RestU], [ZIdx|RestI]) <-
    ?ontology_artifact(OntId);
    ?zone_target(ZIdx, Target);
    discoverZoneQuantity(ZUri, QUri, QLabel)[artifact_id(OntId)];
    +bench_zone_goal(ZUri, ZIdx, QUri, QLabel, Target);
    .print("[Bench] Zone goal idx=", ZIdx, " quantity='", QLabel,
           "' target=", Target);
    !setup_bench_zone_goals(RestU, RestI).

/* ============================================================
 * Outer loop: run RunId plays the whole list of test situations;
 * after run bench_runs the two result files are written, the
 * trace is closed and the platform stops.
 * ============================================================ */
@run_all_runs_step
+!run_all_runs(RunId, TotalRuns) : RunId <= TotalRuns <-
    .print("");
    .print("--- Run ", RunId, " of ", TotalRuns, " ---");
    !scenarios_list(Scenarios);
    !run_scenario_list(Scenarios, RunId);
    !run_all_runs(RunId + 1, TotalRuns).

@run_all_runs_done
+!run_all_runs(RunId, TotalRuns) : RunId > TotalRuns <-
    ?bench_mode(Mode);
    ?bench_logger(LogId);
    .concat("benchmark_results_", Mode, Tmp);
    .concat(Tmp, ".csv", OutFile);
    saveBenchmarkResults(OutFile)[artifact_id(LogId)];
    .concat("bench_step_log_", Mode, Tmp2);
    .concat(Tmp2, ".csv", StepLogFile);
    saveStepLog(StepLogFile)[artifact_id(LogId)];
    closeTraceJsonl[artifact_id(LogId)];
    .print("");
    .print("==========================================================");
    .print("   Benchmark complete. Results saved to: ", OutFile);
    .print("==========================================================");
    .stopMAS.

/* ============================================================
 * Test situations: the ids of the profile's test file
 * (scenarios(File) in lab_profiles.asl) in file order, as a list
 * of scenario(Id, File). Each situation is set by its id with
 * setScenarioLabState, which sends every state field of that
 * entry (all keys except id, description, comment, energyBudget),
 * so the program needs no knowledge of the lab's fields.
 * ============================================================ */
@scenarios_list
+!scenarios_list(S) <-
    ?lab_artifact(LabId);
    !profile_scenarios(File);
    getScenarioIds(File, Ids)[artifact_id(LabId)];
    .term2string(File, FileStr);
    .print("[Bench] Loaded scenario IDs from ", FileStr, ": ", Ids);
    !ids_to_scenarios(Ids, File, [], S).

@ids_to_scenarios_done
+!ids_to_scenarios([], _, Acc, S) <-
    .reverse(Acc, S).
@ids_to_scenarios_step
+!ids_to_scenarios([Id|Rest], File, Acc, S) <-
    !ids_to_scenarios(Rest, File, [scenario(Id, File)|Acc], S).

// Play every situation of the list once in run RunId: set the situation, wait
// 300 ms (several 50 ms simulator ticks) so the light levels have been
// recomputed, read the switch positions, open the scenario record (v2 also
// stores these starting positions, needed to count switch changes from the
// first step on), clear the anti-stuck buffers, then play the steps.
@run_scenario_list_done
+!run_scenario_list([], _) <- true.

@run_scenario_list_step
+!run_scenario_list([scenario(ScenId, File)|Rest], RunId) <-
    ?lab_artifact(LabId);
    ?bench_logger(LogId);
    ?bench_mode(Mode);
    setScenarioLabState(File, ScenId)[artifact_id(LabId)];
    .wait(300);
    readLabStatus(_InitialLevels, _InitialSun, InitialKeys, InitialValues)[artifact_id(LabId)];
    .concat("scenario_id=", ScenId, StateStr0);
    .concat(StateStr0, " file=", StateStr1);
    .concat(StateStr1, File, StateStr);
    if (protocol_version("phase1-v2")) {
        beginScenarioV2(ScenId, RunId, Mode, StateStr,
                        InitialKeys, InitialValues)[artifact_id(LogId)]
    } else {
        beginScenario(ScenId, RunId, Mode, StateStr)[artifact_id(LogId)]
    };
    // The anti-stuck buffers start empty in every situation.
    .abolish(recent_states(_));
    .abolish(recent_actions(_));
    .abolish(bench_stuck_fired(_));
    .print("[Bench] Scenario ", ScenId, " Run ", RunId, " | file=", File);
    !run_scenario_step(ScenId, RunId, 0);
    !run_scenario_list(Rest, RunId).

/* ============================================================
 * Step loop of one situation. Each step:
 *   1. Read the room.
 *   2. If every zone's rank equals its target, close the record
 *      as reached (Steps = the number of actions taken).
 *   3. Otherwise let the mode act, wait exec_delay_ms, read the
 *      room again and record the step: wasted (no rank and no
 *      switch changed), cross-zone interference, action label;
 *      v2 records the ranks after the action and adds the policy
 *      energy of the new switch positions, legacy records the
 *      ranks before the action (kept because the archived legacy
 *      runs used it). Then post the next step.
 * After exec_max_steps actions the record is closed as not
 * reached. The energy passed on closing is the simulator's
 * cumulative counter; under v2 it is kept only as a diagnostic.
 * ============================================================ */
@run_scenario_step_continue
+!run_scenario_step(ScenId, RunId, Step)
    : exec_max_steps(MaxSteps) & Step < MaxSteps <-
    ?lab_artifact(LabId);
    ?bench_logger(LogId);
    ?bench_mode(Mode);
    ?exec_delay_ms(Delay);
    readLabStatus(ZoneLevels, SunshineRank, SKs, SVs)[artifact_id(LabId)];
    // Targets in zone-index order, parallel to ZoneLevels.
    .findall(t(I,T), zone_target(I,T), ZTUnsorted);
    .sort(ZTUnsorted, ZTSorted);
    .findall(T, .member(t(_,T), ZTSorted), Targets);
    !check_terminal(ZoneLevels, Targets, AtGoal);
    if (AtGoal) {
        readEnergyCost(EnergyCost)[artifact_id(LabId)];
        if (protocol_version("phase1-v2")) {
            endScenarioV2(true, Step, EnergyCost)[artifact_id(LogId)]
        } else {
            endScenario(true, Step, EnergyCost)[artifact_id(LogId)]
        };
        .print("[Bench] Scenario ", ScenId, " Run ", RunId,
               " REACHED TARGET in ", Step, " steps.")
    } else {
        !dispatch(Mode, ZoneLevels, SunshineRank, SKs, SVs, Targets);
        .wait(Delay);
        readLabStatus(ZoneLevels2, _, SKs2, SVs2)[artifact_id(LabId)];
        readZoneTemperatures(Temps)[artifact_id(LabId)];
        !is_wasted(ZoneLevels, ZoneLevels2, SVs, SVs2, Wasted);
        !is_cross_zone(ZoneLevels, ZoneLevels2, Targets, CrossZone);
        !get_last_action_label(Mode, ActionLabel);
        if (protocol_version("phase1-v2")) {
            recordStepV2(Step, ZoneLevels2, Targets, ActionLabel, Wasted,
                         SKs2, SVs2, CrossZone)[artifact_id(LogId)]
        } else {
            recordStep(Step, ZoneLevels, Targets, ActionLabel, Wasted,
                       SKs2, SVs2, CrossZone)[artifact_id(LogId)]
        };
        // One row of bench_step_log_<mode>.csv (WasMasked is always false).
        !get_stuck_fired(StuckFired);
        recordStepDetailRichV2(Step, ZoneLevels, ZoneLevels2, Targets,
                               ActionLabel, false, CrossZone,
                               Temps, SKs2, SVs2,
                               SunshineRank, StuckFired)[artifact_id(LogId)];
        // Weakness fingerprinting (Q-learning modes on labs with weakness flags).
        !fingerprint_weakness(Mode, ZoneLevels, SunshineRank, SKs, SVs,
                              ZoneLevels2, SKs2, SVs2);
        !run_scenario_step(ScenId, RunId, Step + 1)
    }.

@run_scenario_step_timeout
+!run_scenario_step(ScenId, RunId, Step)
    : exec_max_steps(MaxSteps) & Step >= MaxSteps <-
    ?lab_artifact(LabId);
    ?bench_logger(LogId);
    readEnergyCost(EnergyCost)[artifact_id(LabId)];
    if (protocol_version("phase1-v2")) {
        endScenarioV2(false, Step, EnergyCost)[artifact_id(LogId)]
    } else {
        endScenario(false, Step, EnergyCost)[artifact_id(LogId)]
    };
    .print("[Bench] Scenario ", ScenId, " Run ", RunId,
           " FAILED — max steps (", MaxSteps, ") reached.").

/* ============================================================
 * Step tests, written as goals whose last argument is bound by
 * the first applicable plan (plans are tried in order):
 *   check_terminal: true iff every zone's rank equals its target.
 *   is_wasted:      true iff neither the zone ranks nor the switch
 *                   positions changed.
 *   is_cross_zone:  true iff some zone (any zone, also the one
 *                   acted on) moved further from its target. In
 *                   the Q-learning modes QLearner's
 *                   checkCrossZoneInterference (knowledge-graph
 *                   spill arcs) is OR-ed with that level test;
 *                   every case it reports is also caught by the
 *                   level test, so the result equals the level test.
 * ============================================================ */
@check_terminal_entry
+!check_terminal(ZoneLevels, Targets, Result) <-
    !walk_terminal(ZoneLevels, Targets, Result).
@walk_terminal_done
+!walk_terminal([], [], true) <- true.
@walk_terminal_mismatch
+!walk_terminal([L|_], [T|_], false) : L \== T <- true.
@walk_terminal_match
+!walk_terminal([_|RL], [_|RT], Result) <-
    !walk_terminal(RL, RT, Result).

@is_wasted_yes
+!is_wasted(L1, L2, S1, S2, true) : L1 == L2 & S1 == S2 <- true.
@is_wasted_no
+!is_wasted(_, _, _, _, false) <- true.

@is_cross_zone_entry
+!is_cross_zone(Prev, Curr, Targets, Result) <-
    ?bench_mode(Mode);
    // rule_based has no QLearner, so it uses the level test only.
    if (Mode == "ql_true" | Mode == "ql_false") {
        ?qlearner_artifact(QlId);
        getLastDispatchedAction(ActionIdx)[artifact_id(QlId)];
        if (ActionIdx >= 0) {
            checkCrossZoneInterference(ActionIdx, Prev, Curr, Targets, OntoCross)[artifact_id(QlId)];
            !walk_cross_zone(Prev, Curr, Targets, LevelCross);
            !or2(OntoCross, LevelCross, Result)
        } else {
            !walk_cross_zone(Prev, Curr, Targets, Result)
        }
    } else {
        !walk_cross_zone(Prev, Curr, Targets, Result)
    }.

@or2_t1
+!or2(true, _, true) <- true.
@or2_t2
+!or2(_, true, true) <- true.
@or2_f
+!or2(_, _, false) <- true.

@walk_cross_zone_done
+!walk_cross_zone([], [], [], false) <- true.
@walk_cross_zone_worse
+!walk_cross_zone([P|_], [C|_], [T|_], true)
    : math.abs(C - T) > math.abs(P - T) <- true.
@walk_cross_zone_next
+!walk_cross_zone([_|RP], [_|RC], [_|RT], Result) <-
    !walk_cross_zone(RP, RC, RT, Result).

/* ============================================================
 * Action label of the step for the records.
 *   Q-learning modes: "<WoT action type>=<true|false>" of the last
 *     dispatched action, "DO_NOTHING", or "unknown" before any
 *     dispatch.
 *   rule_based: the rb_last_action/1 belief set by the rule
 *     dispatch ("<type>=true|false", "BLOCKED:<type>=true|false"
 *     or "DO_NOTHING").
 * ============================================================ */
@get_last_action_label_ql
+!get_last_action_label(Mode, Label)
    : (Mode == "ql_true" | Mode == "ql_false") <-
    ?qlearner_artifact(QlId);
    getLastDispatchedAction(ActionIdx)[artifact_id(QlId)];
    if (ActionIdx >= 0) {
        actionToWoT(ActionIdx, WotType, WotValue)[artifact_id(QlId)];
        if (WotType \== "none") {
            .concat(WotType, "=", WotType2);
            .term2string(WotValue, ValStr);
            .concat(WotType2, ValStr, Label)
        } else {
            Label = "DO_NOTHING"
        }
    } else {
        Label = "unknown"
    }.

@get_last_action_label_rule_based_belief
+!get_last_action_label("rule_based", L) : rb_last_action(LL) <- L = LL.
@get_last_action_label_rule_based_default
+!get_last_action_label("rule_based", "DO_NOTHING") <- true.

/* ============================================================
 * Rule-based dispatch. Takes the first zone, in the order of the
 * bench_zone_goal beliefs (the graph's zone order), whose rank
 * differs from its target, and acts for that zone only:
 *   too dark:   queryBestIncreaseAction returns the first device
 *               of the zone that raises its quantity, is off and,
 *               if its mechanism depends on a condition, has that
 *               condition met (sun rank >= ws:ivMinRank); it is
 *               switched on unless checkSharedActuatorSafe finds
 *               that the device also lights another zone that is
 *               already at or above its target.
 *   too bright: queryBestDecreaseAction returns the first such
 *               device that is on; it is switched off unless
 *               checkSharedActuatorSafeToDeactivate finds that the
 *               device lights other zones and all of them are below
 *               their targets.
 * A blocked or missing candidate means no action in this step.
 * ============================================================ */
@dispatch_rule_based
+!dispatch("rule_based", ZoneLevels, SunshineRank, SKs, SVs, _) <-
    ?ontology_artifact(OntId);
    // The label is "DO_NOTHING" unless the increase or decrease plan sets another.
    .abolish(rb_last_action(_));
    +rb_last_action("DO_NOTHING");
    .findall(I, bench_zone_goal(_, I, _, _, _), ZIdxUnsorted);
    .sort(ZIdxUnsorted, ZIdxSorted);
    .findall(goal(ZUri, ZIdx, QUri, QLabel, Target),
             bench_zone_goal(ZUri, ZIdx, QUri, QLabel, Target),
             ZoneGoals);
    !rb_process_first_off_target(ZoneGoals, ZIdxSorted,
                                 ZoneLevels, SunshineRank, SKs, SVs).

@rb_process_first_off_target_done
+!rb_process_first_off_target([], _, _, _, _, _) <- true.

@rb_process_first_off_target_step
+!rb_process_first_off_target([goal(ZUri, ZIdx, QUri, QLabel, Target)|Rest],
                              ZIdxSorted, ZoneLevels, SunshineRank, SKs, SVs) <-
    !get_bench_zone_level(ZIdxSorted, ZoneLevels, ZIdx, Level);
    if (Level == Target) {
        !rb_process_first_off_target(Rest, ZIdxSorted, ZoneLevels,
                                     SunshineRank, SKs, SVs)
    } else {
        if (Level < Target) {
            !rb_increase(ZUri, ZIdx, QUri, QLabel, SunshineRank, SKs, SVs, ZoneLevels)
        } else {
            !rb_decrease(ZUri, ZIdx, QUri, QLabel, SunshineRank, SKs, SVs, ZoneLevels)
        }
    }.

// Rank of zone ZIdx: ZoneLevels is ordered like the sorted zone indices.
@get_bench_zone_level_found
+!get_bench_zone_level([ZIdx|_], [Level|_], ZIdx, Level) <- true.
@get_bench_zone_level_search
+!get_bench_zone_level([_|RI], [_|RL], ZIdx, Level) <-
    !get_bench_zone_level(RI, RL, ZIdx, Level).

@rb_increase
+!rb_increase(ZUri, ZIdx, QUri, QLabel, SunshineRank, SKs, SVs, ZoneLevels) <-
    ?ontology_artifact(OntId);
    ?lab_artifact(LabId);
    queryBestIncreaseAction(ZUri, QUri, SunshineRank, SKs, SVs,
        CompId, Mechanism, MvLabel, DvLabel, IvLabel, WotActionType)[artifact_id(OntId)];
    if (CompId \== "none") {
        .findall(t(I2,T2), zone_target(I2, T2), ZTU);
        .sort(ZTU, ZTS);
        .findall(T3, .member(t(_,T3), ZTS), ZoneTargets);
        checkSharedActuatorSafe(WotActionType, ZIdx, ZoneLevels,
                                ZoneTargets, Safe)[artifact_id(OntId)];
        if (Safe) {
            invokeAction(WotActionType, true)[artifact_id(LabId)];
            .concat(WotActionType, "=true", RbLabel);
            .abolish(rb_last_action(_));
            +rb_last_action(RbLabel);
            .print("[Bench Zone ", ZIdx, "] INCREASE via ", CompId,
                   " (", WotActionType, ")")
        } else {
            .concat("BLOCKED:", WotActionType, BL0);
            .concat(BL0, "=true", BL1);
            .abolish(rb_last_action(_));
            +rb_last_action(BL1);
            .print("[Bench Zone ", ZIdx, "] INCREASE BLOCKED: ", CompId,
                   " unsafe for other zones")
        }
    } else {
        .print("[Bench Zone ", ZIdx, "] No applicable INCREASE action")
    }.

@rb_decrease
+!rb_decrease(ZUri, ZIdx, QUri, QLabel, SunshineRank, SKs, SVs, ZoneLevels) <-
    ?ontology_artifact(OntId);
    ?lab_artifact(LabId);
    queryBestDecreaseAction(ZUri, QUri, SunshineRank, SKs, SVs,
        CompId, Mechanism, MvLabel, DvLabel, IvLabel, WotActionType)[artifact_id(OntId)];
    if (CompId \== "none") {
        .findall(t(I2,T2), zone_target(I2, T2), ZTU);
        .sort(ZTU, ZTS);
        .findall(T3, .member(t(_,T3), ZTS), ZoneTargets);
        checkSharedActuatorSafeToDeactivate(WotActionType, ZIdx, ZoneLevels,
                                            ZoneTargets, SafeRm)[artifact_id(OntId)];
        if (SafeRm) {
            invokeAction(WotActionType, false)[artifact_id(LabId)];
            .concat(WotActionType, "=false", RbLabel);
            .abolish(rb_last_action(_));
            +rb_last_action(RbLabel);
            .print("[Bench Zone ", ZIdx, "] DECREASE via ", CompId,
                   " (", WotActionType, ")")
        } else {
            .concat("BLOCKED:", WotActionType, BL0);
            .concat(BL0, "=false", BL1);
            .abolish(rb_last_action(_));
            +rb_last_action(BL1);
            .print("[Bench Zone ", ZIdx, "] DECREASE BLOCKED: ", CompId,
                   " needed by another zone")
        }
    } else {
        .print("[Bench Zone ", ZIdx, "] No applicable DECREASE action")
    }.

/* ============================================================
 * Q-learning dispatch: the greedy action of the loaded table
 * (getActionFromState with explore = false; no update follows).
 * Anti-stuck option: the buffers recent_states/1 and
 * recent_actions/1 keep the last three states and actions of the
 * situation. If bench_anti_stuck(true) and the current state equals
 * the two most recent stored states, getActionFromStateAntiStuck
 * picks the best action outside the stored recent actions and "do
 * nothing" instead. The training program has no such replacement,
 * so with it the test would no longer play the trained policy;
 * bench_anti_stuck is false and no runner or workflow changes it.
 * The buffers are filled in either case. The chosen action is
 * reported to QLearner (notifyActionDispatched) for the label,
 * cross-zone and fingerprint plans.
 * ============================================================ */
@dispatch_ql
+!dispatch(Mode, ZoneLevels, SunshineRank, SKs, SVs, _)
    : (Mode == "ql_true" | Mode == "ql_false") <-
    ?qlearner_artifact(QlId);
    ?lab_artifact(LabId);
    encodeState(ZoneLevels, SunshineRank, SKs, SVs, StateVec)[artifact_id(QlId)];
    ?bench_anti_stuck(AntiStuckEnabled);
    !get_recent_states(RecentStates);
    !get_recent_actions(RecentActions);
    !is_stuck3(StateVec, RecentStates, IsStuck);
    if (AntiStuckEnabled & IsStuck) {
        getActionFromStateAntiStuck(StateVec, RecentActions, Action, StuckFired)[artifact_id(QlId)]
    } else {
        getActionFromState(StateVec, false, Action)[artifact_id(QlId)];
        StuckFired = false
    };
    .abolish(bench_stuck_fired(_));
    +bench_stuck_fired(StuckFired);
    !push_recent_state(StateVec);
    !push_recent_action(Action);
    actionToWoT(Action, WotType, WotValue)[artifact_id(QlId)];
    notifyActionDispatched(Action)[artifact_id(QlId)];
    if (WotType \== "none") {
        invokeAction(WotType, WotValue)[artifact_id(LabId)];
        .print("[Bench QL] action=", Action, " wot=", WotType, "=", WotValue,
               " stuck=", StuckFired)
    } else {
        .print("[Bench QL] action=", Action, " (do-nothing) stuck=", StuckFired)
    }.

/* Anti-stuck buffer helpers (an absent buffer reads as []). */
@get_recent_states_have
+!get_recent_states(L) : recent_states(L) <- true.
@get_recent_states_init
+!get_recent_states([]) <- true.

@get_recent_actions_have
+!get_recent_actions(L) : recent_actions(L) <- true.
@get_recent_actions_init
+!get_recent_actions([]) <- true.

// Stuck: the two most recent stored states both equal the current state.
@is_stuck3_yes
+!is_stuck3(Sv, [Sv, Sv | _], true) <- true.
@is_stuck3_no
+!is_stuck3(_, _, false) <- true.

// Put the new entry in front and keep at most three entries.
@push_recent_state
+!push_recent_state(Sv) <-
    !get_recent_states(L0);
    !take_first2(L0, L1);
    .abolish(recent_states(_));
    +recent_states([Sv | L1]).

@push_recent_action
+!push_recent_action(A) <-
    !get_recent_actions(L0);
    !take_first2(L0, L1);
    .abolish(recent_actions(_));
    +recent_actions([A | L1]).

@take_first2_empty
+!take_first2([], []) <- true.
@take_first2_one
+!take_first2([X], [X]) <- true.
@take_first2_two
+!take_first2([X, Y | _], [X, Y]) <- true.

@get_stuck_fired_have
+!get_stuck_fired(F) : bench_stuck_fired(F) <- true.
@get_stuck_fired_init
+!get_stuck_fired(false) <- true.

/* ============================================================
 * Weakness fingerprinting (Q-learning modes, labs with flags).
 * The profile's weakness_flags list (atoms w1..w6) names the
 * weaknesses a lab's simulator injects. For such a lab, after
 * every step QLearner's classifyWeaknesses names the fingerprints
 * that the transition shows; one JSONL trace record is written,
 * and every fired fingerprint that the lab declares is counted
 * with recordWeakness. For w6 the zone temperatures are read and
 * their excess deviation is added (accumulate_comfort).
 * rule_based: nothing (no QLearner). Labs with weakness_flags([])
 * skip it entirely; these are all labs except the fault-stage
 * labs, which are not played by this program in the experiment
 * workflows. The result-file
 * metrics come from the step loop, not from here.
 * ============================================================ */
@fingerprint_weakness_skip_rule_based
+!fingerprint_weakness("rule_based", _, _, _, _, _, _, _) <- true.

@fingerprint_weakness_clean_lab
+!fingerprint_weakness(Mode, _, _, _, _, _, _, _)
    : (Mode == "ql_true" | Mode == "ql_false")
    & active_profile(P)
    & lab_profile(P, _, _, _, _, _, _, _, _, _, weakness_flags([]), _, _) <-
    true.

@fingerprint_weakness_ql
+!fingerprint_weakness(Mode, ZoneLevels, SunshineRank, SKs, SVs,
                       ZoneLevels2, SKs2, SVs2)
    : (Mode == "ql_true" | Mode == "ql_false") <-
    ?qlearner_artifact(QlId);
    ?bench_logger(LogId);
    ?lab_artifact(LabId);
    encodeState(ZoneLevels,  SunshineRank, SKs,  SVs,  StateVec1)[artifact_id(QlId)];
    encodeState(ZoneLevels2, SunshineRank, SKs2, SVs2, StateVec2)[artifact_id(QlId)];
    getLastDispatchedAction(ActionIdx)[artifact_id(QlId)];
    if (ActionIdx >= 0) {
        classifyWeaknesses(StateVec1, StateVec2, ActionIdx, Fired)[artifact_id(QlId)];
        getPredictedDelta(StateVec1, ActionIdx, PredDelta)[artifact_id(QlId)];
        getApplicableActions(StateVec1, AppActions)[artifact_id(QlId)];
        getLastQDelta(QDelta)[artifact_id(QlId)];
        recordRichStep(0, StateVec1, ActionIdx, AppActions, PredDelta,
                       StateVec2, QDelta, Fired)[artifact_id(LogId)];
        !profile_weakness_flags(WF);
        !fp_record_all(WF, Fired, LogId);
        // W6 (comfort) is measured directly from zone temperatures, not
        // fingerprinted from action effects. Only sample if the lab declares
        // w6 in its weakness_flags.
        if (.member(w6, WF)) {
            readZoneTemperatures(Temps)[artifact_id(LabId)];
            !accumulate_comfort(Temps, LogId)
        }
    }.

// For every zone temperature T: if |T - 22.0| exceeds 1.0 (degrees C), add the
// excess |T - 22.0| - 1.0 with recordComfortDeviation. Setpoint and tolerance
// are fixed here for all labs.
@accumulate_comfort_empty
+!accumulate_comfort([], _) <- true.
@accumulate_comfort_step
+!accumulate_comfort([T|Rest], LogId) <-
    Dev = math.abs(T - 22.0);
    if (Dev > 1.0) {
        Excess = Dev - 1.0;
        recordComfortDeviation(Excess)[artifact_id(LogId)]
    };
    !accumulate_comfort(Rest, LogId).

// For every flag the lab declares, count it once if its fingerprint fired.
@fp_record_all_done
+!fp_record_all([], _, _) <- true.
@fp_record_all_step
+!fp_record_all([Flag|Rest], Fired, LogId) <-
    !fp_record(Flag, Fired, LogId);
    !fp_record_all(Rest, Fired, LogId).

// Flag atom -> fingerprint name of classifyWeaknesses (w1_unmodelled,
// w2_inversion, w3_delayed, w4_dropped, w5_topology, w6_unmodelled).
@fp_record_w1_hit
+!fp_record(w1, Fired, LogId) : .member("w1_unmodelled", Fired) <-
    recordWeakness("w1_unmodelled", 1)[artifact_id(LogId)].
@fp_record_w1_miss
+!fp_record(w1, _, _) <- true.

@fp_record_w2_hit
+!fp_record(w2, Fired, LogId) : .member("w2_inversion", Fired) <-
    recordWeakness("w2_inversion", 1)[artifact_id(LogId)].
@fp_record_w2_miss
+!fp_record(w2, _, _) <- true.

@fp_record_w3_hit
+!fp_record(w3, Fired, LogId) : .member("w3_delayed", Fired) <-
    recordWeakness("w3_delayed", 1)[artifact_id(LogId)].
@fp_record_w3_miss
+!fp_record(w3, _, _) <- true.

@fp_record_w4_hit
+!fp_record(w4, Fired, LogId) : .member("w4_dropped", Fired) <-
    recordWeakness("w4_dropped", 1)[artifact_id(LogId)].
@fp_record_w4_miss
+!fp_record(w4, _, _) <- true.

@fp_record_w5_hit
+!fp_record(w5, Fired, LogId) : .member("w5_topology", Fired) <-
    recordWeakness("w5_topology", 1)[artifact_id(LogId)].
@fp_record_w5_miss
+!fp_record(w5, _, _) <- true.

@fp_record_w6_hit
+!fp_record(w6, Fired, LogId) : .member("w6_unmodelled", Fired) <-
    recordWeakness("w6_unmodelled", 1)[artifact_id(LogId)].
@fp_record_w6_miss
+!fp_record(w6, _, _) <- true.

// Any other flag atom is ignored.
@fp_record_unknown
+!fp_record(_, _, _) <- true.

// A failure in fingerprinting only prints a warning; the step loop continues.
-!fingerprint_weakness(_, _, _, _, _, _, _, _) <-
    .print("[Bench] WARNING: fingerprint_weakness failed (skipping).").

/* ============================================================
 * Failure plans. A failed operation fails the plan that called
 * it; the matching plan below then runs instead.
 *   bench_start:       print a hint; nothing is recorded and no
 *                      plan stops the platform after this. (The
 *                      printed port 1881 is that of the old custom
 *                      lab; each lab's port is its sim_port.)
 *   run_scenario_step: close the situation's record as not
 *                      reached at the failing step, in the format
 *                      it was begun in (BenchmarkLogger rejects a
 *                      legacy close of a v2 record); the next
 *                      situation follows.
 *   dispatch:          print a warning; the step continues (wait,
 *                      read, record) with whatever was done before
 *                      the failure.
 * ============================================================ */
-!bench_start <-
    .print("ERROR: Benchmark agent failed to start.");
    .print("Check that the simulator is running (port 1881) and that");
    .print("qtable_final_stereotypes_*.csv files exist for ql_* modes.").

-!run_scenario_step(ScenId, RunId, Step) <-
    .print("WARNING: run_scenario_step failed at scenario ", ScenId,
           " run ", RunId, " step ", Step);
    ?lab_artifact(LabId);
    ?bench_logger(LogId);
    readEnergyCost(EnergyCost)[artifact_id(LabId)];
    if (protocol_version("phase1-v2")) {
        endScenarioV2(false, Step, EnergyCost)[artifact_id(LogId)]
    } else {
        endScenario(false, Step, EnergyCost)[artifact_id(LogId)]
    }.

-!dispatch(_, _, _, _, _, _) <-
    .print("WARNING: dispatch failed — treating step as a no-op.").

/* ============================================================
 * Report plans for a simulator failure and for an input rejected
 * by the Thing Description check. No plan of this program and no
 * artifact posts these two goals; despite the printed text they do
 * not mark a situation as failed. Kept as they are; the training
 * program has the same two plans.
 * ============================================================ */
+!handle_simulator_failure(Reason, Op, Detail) <-
    .print("ERROR: Simulator failure during benchmark — reason=", Reason,
           " op=", Op, " detail=", Detail);
    .print("       Current scenario will be marked as failure.").

+!handle_invalid_input(Actuator, Expected, Actual) <-
    .print("ERROR: Invalid input during benchmark — actuator=", Actuator,
           " expected=", Expected, " actual=", Actual).
