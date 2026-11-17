// Delay-learning program (Phase 3): measure how long each device takes to act,
// then meet goals with deadlines.
//
// The single BDI agent of a delay-learning run, in a "slow" lab (lab2_slow,
// lab3_slow), whose simulator flow switches the blind on only after a delay.
// No Q-table is learned: QLearner is created only to obtain the lab's action
// list and each action's WoT type and value.
//   A. Probe. For every action with a WoT action type (the "on" and the "off"
//      action of every device): put the room into the profile's baseline
//      (probe_baseline: all devices off, high sun), read the simulator's tick
//      counter, send the action and poll until some zone's rank rises. The
//      number of ticks in between is one delay sample (DynamicsLearner keeps
//      the running mean and variance per action). The first trial also records
//      which zones rose to which rank (action_effect/3) and the energy the
//      device draws per tick while on (action_cost/2). An action without an
//      effect (every "off" action from an all-off baseline) gets this one
//      trial only; an action with an effect gets probes_per_actuator trials.
//   B. Write. learned_dynamics_<b>_<P>.ttl (ws:responseDelay per action with
//      enough samples) and dynamics_delays_<b>_<P>.csv. Nothing in the
//      project reads the Turtle file back; it is a record of what was measured.
//   C. Plan and act. For each tb_goal (zone, target rank, deadline in seconds)
//      the planner takes the devices that reached at least the target rank in
//      that zone, prefers those whose believed delay fits the deadline, then
//      the lowest measured energy, executes the chosen one from the baseline
//      and measures the real time to the target. One row per goal goes to
//      timebounded_results_<b>_<P>.csv.
// The two modes differ only in the planner's belief about delays: ql_true
// (use_dynamics_kg(true)) uses the delays measured in step A, ql_false assumes
// that every device acts at once. Despite the shared mode names, neither mode
// uses prior knowledge from the knowledge graph here: both measure everything
// in step A.
//
// Started by: task_dynamics.jcm through the Gradle task taskDynamics;
// run_phase3_dynamics.ps1 runs "gradlew taskDynamics -Pprofile=<slow lab>
// -Pmode=<ql_true|ql_false>" with -Pseconds.per.tick, -Pprobe.count,
// -Pprobe.settle.ms, -Pprobe.poll.ms and -Pprobe.max.wait.ticks from
// config/run_config.json (phase3). Includes lab_profiles.asl.
//
// Beliefs and properties that configure it (see also the parameter facts):
//   use_dynamics_kg, mode_label, mode_bool   Planner mode; the system property
//                        dynamics.mode (Gradle -Pmode) sets all three:
//                        "ql_false" -> false / "ql_false" / "false", any other
//                        non-empty value -> true / "ql_true" / "true".
//   active_profile(P)    Replaced by the system property active.profile.
//   seconds_per_tick, probe_settle_ms, probe_poll_ms, probe_max_wait_ticks,
//   probe_hold_ticks, probes_per_actuator
//                        Replaced by the system properties seconds.per.tick,
//                        probe.settle.ms, probe.poll.ms, probe.max.wait.ticks,
//                        probe.hold.ticks, probe.count (defaults as written).
//   probe_baseline/3, tb_goal/4   Facts in this file, per profile and per goal.
//
// Artifacts and operations:
//   LabEnvironment "lab": readLabStatusTimed (ranks and tick counter),
//     readEnergyCostTimed (energy counter and tick counter),
//     setLabStateFromMap, configureDiscretization, invokeAction.
//   QLearner "qlearner": configureQLearner, getNumActions,
//     getActionMetadataForLearner, actionToWoT.
//   DynamicsLearner "dynamics": initDynamics, recordDelaySample, printStats,
//     saveLearnedDynamics, saveDelayTable, getDelaySecondsForAction,
//     recordExploitResult, saveExploitResults.

/* ============================================================
 * Initial Beliefs
 * ============================================================ */

// Passed to configureQLearner only. The action list does not depend on it (it
// comes from the WoT mapping of the graph in both arms); with true QLearner
// also computes starting values, which this program never uses. It does not
// affect the planner.
use_stereotypes(true).

// Planner mode, set at start from dynamics.mode:
//   ql_true  -> believe the delays measured in the probe step;
//   ql_false -> believe that every device acts at once (delay 0).
use_dynamics_kg(true).
mode_label("ql_true").
mode_bool("true").

// Lab profiles (shared with the other agent programs).
{ include("lab_profiles.asl") }

// Probe parameters; each is replaced at start by a system property (see
// @load_probe_params).
seconds_per_tick(5.0).        // seconds that one simulator tick stands for
probe_settle_ms(400).         // wait after setting a baseline (ms)
probe_poll_ms(50).            // wait between status reads (ms), one 50 ms tick
probe_max_wait_ticks(60).     // number of reads before a trial gives up
probe_hold_ticks(16).         // number of reads in the first trial (device kept on)
probes_per_actuator(8).       // delay trials per action that has an effect
action_delay_ms(65).          // not read by any plan of this program

// Probe baselines per profile: all devices off, sun 900 (high) so that the
// blind has an effect. The keys are simulator state fields; LabEnvironment
// checks them against the Thing Description before sending.
probe_baseline("lab2_slow",
               ["Z1Light","Z2Light","Z1Blinds","Z2Blinds","Sunshine"],
               [false,false,false,false,900]).
probe_baseline("lab3_slow",
               ["Z1Light","Z2Light","Z1Blinds","Z2Blinds","Spotlight","Sunshine"],
               [false,false,false,false,false,900]).

// Deadline goals: tb_goal(Id, Zone, TargetRank, DeadlineSec), Zone counted from
// 1. The same list is in config/run_config.json (phase3.timebounded_goals);
// the two must be kept equal by hand. With the baseline's high sun the blind
// does light the zone, so only its delay decides: with the slow labs' blind
// delay of 12 ticks (phase3.blind_delay_ticks) at 5 s per tick = 60 s, a
// deadline below 60 s can be met only by a lamp.
tb_goal("g1", 1, 3, 15.0).
tb_goal("g2", 1, 3, 45.0).
tb_goal("g3", 1, 3, 90.0).
tb_goal("g4", 1, 3, 300.0).
tb_goal("g5", 2, 3, 15.0).
tb_goal("g6", 2, 3, 300.0).

/* ============================================================
 * Rules
 * ============================================================ */

// True if some position in the second rank list is greater than the same
// position in the first, i.e. some zone's rank rose.
list_increased([B|_], [N|_]) :- N > B.
list_increased([_|BR], [_|NR]) :- list_increased(BR, NR).

// Delay (seconds) the planner believes an action has, per mode:
//   true  -> the measured mean, learned_delay_sec/2 (-1 if the action got no
//            delay sample);
//   false -> 0 for every action.
believed_delay(A, true,  D) :- learned_delay_sec(A, D).
believed_delay(_, false, 0).

// Feasibility flag for the planner sort key: 0 = fits the deadline, 1 = not.
key_infeas(B, Deadline, 0) :- B <= Deadline.
key_infeas(B, Deadline, 1) :- B >  Deadline.

/* ============================================================
 * Initial Goal
 * ============================================================ */

!apply_profile_then_start_dynamics.

/* ============================================================
 * Profile bridge (the initial goal), as in the other programs:
 * overrides, probe parameters, profile fields into beliefs,
 * zone_goal, then !start_dynamics.
 * ============================================================ */
@apply_profile_then_start_dynamics
+!apply_profile_then_start_dynamics <-
    !apply_runtime_overrides_dyn;
    !load_probe_params;
    !profile_print;
    !profile_td(TdUrl);                     +lab_td(TdUrl);
    !profile_light_bounds(LightBounds);     +light_rank_bounds(LightBounds);
    !profile_sunshine_bounds(SunBounds);    +sunshine_rank_bounds(SunBounds);
    !profile_sunshine_prob(SunProb);        +sunshine_satisfaction_prob(SunProb);
    !profile_zone_targets(ZoneTargetList);
    !derive_zone_goal(ZoneTargetList, []);
    !start_dynamics.

/* ============================================================
 * Run-time overrides, read through tools.jia.system_prop:
 *   active.profile  replaces active_profile/1 when set;
 *   dynamics.mode   when set, replaces use_dynamics_kg, mode_label
 *                   and mode_bool ("ql_false" -> plain planner,
 *                   any other value -> planner with measured delays).
 * If the plan fails, the beliefs as written are kept.
 * ============================================================ */
@apply_runtime_overrides_dyn
+!apply_runtime_overrides_dyn <-
    tools.jia.system_prop("active.profile", "", OverrideProfile);
    if (OverrideProfile \== "") {
        ?active_profile(OldP);
        -active_profile(OldP);
        +active_profile(OverrideProfile);
        .print("[RuntimeOverride] active_profile: ", OldP, " -> ", OverrideProfile)
    };
    tools.jia.system_prop("dynamics.mode", "", ModeOverride);
    if (ModeOverride \== "") {
        ?use_dynamics_kg(OldK); -use_dynamics_kg(OldK);
        -mode_label(_); -mode_bool(_);
        if (ModeOverride == "ql_false") {
            +use_dynamics_kg(false); +mode_label("ql_false"); +mode_bool("false");
            .print("[RuntimeOverride] dynamics planner -> assumes zero delay (ql_false)")
        } else {
            +use_dynamics_kg(true);  +mode_label("ql_true");  +mode_bool("true");
            .print("[RuntimeOverride] dynamics planner -> uses the learned delays (ql_true)")
        }
    }.
@apply_runtime_overrides_dyn_fail
-!apply_runtime_overrides_dyn <-
    .print("[RuntimeOverride] WARNING: tools.jia.system_prop failed; using defaults.").

// Probe parameters from system properties (tools.jia.system_prop_num). An
// absent property gives the default written in the call, which equals the
// fact above. If the plan fails, the facts above are kept.
@load_probe_params
+!load_probe_params <-
    tools.jia.system_prop_num("seconds.per.tick",     5.0, SPT); -+seconds_per_tick(SPT);
    tools.jia.system_prop_num("probe.settle.ms",      400, S);   -+probe_settle_ms(S);
    tools.jia.system_prop_num("probe.poll.ms",        50,  P);   -+probe_poll_ms(P);
    tools.jia.system_prop_num("probe.max.wait.ticks", 60,  MW);  -+probe_max_wait_ticks(MW);
    tools.jia.system_prop_num("probe.hold.ticks",     16,  H);   -+probe_hold_ticks(H);
    tools.jia.system_prop_num("probe.count",          8,   C);   -+probes_per_actuator(C);
    ?seconds_per_tick(SPTv); ?probes_per_actuator(Cv);
    .print("[Dynamics] params: secondsPerTick=", SPTv, " probesPerActuator=", Cv).
@load_probe_params_fail
-!load_probe_params <-
    .print("[Dynamics] WARNING: system_prop_num failed; using default probe params.").

// Build zone_goal/1 (target ranks in the order of the profile's zone_targets)
// for configureQLearner; the planner itself uses tb_goal/4.
@derive_zone_goal_done
+!derive_zone_goal([], Acc) <- .reverse(Acc, Goal); +zone_goal(Goal).
@derive_zone_goal_step
+!derive_zone_goal([target(_, T)|R], Acc) <- !derive_zone_goal(R, [T|Acc]).

/* ============================================================
 * Start and whole run: create the artifacts, list the actions to
 * probe, probe, write the delay files, turn the measured delays
 * into beliefs, plan and act for every deadline goal, write the
 * results, stop. If the start beliefs are missing, the second
 * plan prints an error and stops.
 * ============================================================ */
@start_dynamics
+!start_dynamics : active_profile(ActiveP)
        & use_stereotypes(UseStereotypes)
        & use_dynamics_kg(UseKg)
        & lab_td(TdUrl)
        & zone_goal(Goal)
        & sunshine_satisfaction_prob(SunProb)
        & light_rank_bounds(LightBounds)
        & sunshine_rank_bounds(SunshineBounds) <-

    .print("==========================================================");
    .print("   ILLUMINANCE CONTROLLER AGENT - PHASE 3 DYNAMICS");
    .print("   Profile:  ", ActiveP, "  (slow / delayed actuator)");
    .print("   Planner:  ", UseKg, "  (true=uses the learned delays, false=assumes zero delay)");
    .print("   Goal:     ", Goal);
    .print("==========================================================");

    // Room connection (the slow lab's flow); one timed read checks that the
    // simulator answers.
    makeArtifact("lab", "tools.LabEnvironment", [TdUrl], LabId);
    +lab_artifact(LabId);
    .print("[probe] Checking simulator HTTP reachability ...");
    readLabStatusTimed(_PZL, _PSR, _PTick)[artifact_id(LabId)];
    .print("[probe] Simulator reachable — proceeding.");
    configureDiscretization(LightBounds, SunshineBounds)[artifact_id(LabId)];

    // QLearner, used only for the action list and actionToWoT.
    makeArtifact("qlearner", "tools.QLearner", [], QlId);
    +qlearner_artifact(QlId);
    !profile_ont(OntPaths);
    configureQLearner(Goal, UseStereotypes, OntPaths, SunProb)[artifact_id(QlId)];

    // DynamicsLearner: running mean and variance of the delay per action.
    makeArtifact("dynamics", "tools.DynamicsLearner", [], DId);
    +dyn_artifact(DId);
    getNumActions(NumActions)[artifact_id(QlId)];
    getActionMetadataForLearner(ActUris, ActVals, ActLabels)[artifact_id(QlId)];
    ?seconds_per_tick(SPT);
    initDynamics(NumActions, ActUris, ActVals, ActLabels, SPT)[artifact_id(DId)];
    +action_meta(ActUris, ActVals, ActLabels);
    !assert_action_labels(0, NumActions, ActLabels);
    .print("[Dynamics] action space size = ", NumActions);

    // Every action with a WoT action type, "on" and "off" alike (the printed
    // text says "ON-actuator", but "off" actions are in the list too).
    !collect_probe_actions(0, NumActions, ActUris, ActVals, [], ProbeList);
    .print("[Dynamics] probe targets (ON-actuator action ids) = ", ProbeList);

    // Step A: probe.
    !probe_all(ProbeList);
    printStats[artifact_id(DId)];

    // Step B: the measured delays as a separate Turtle file (ws:responseDelay)
    // and as a CSV table. The lab's knowledge graph is not changed, and no
    // program or class reads the Turtle file back.
    ?mode_bool(Bool);
    .concat("learned_dynamics_", Bool, "_", ActiveP, ".ttl", DynTtl);
    saveLearnedDynamics(DynTtl)[artifact_id(DId)];
    .concat("dynamics_delays_", Bool, "_", ActiveP, ".csv", DelayCsv);
    saveDelayTable(DelayCsv)[artifact_id(DId)];
    .print("[Dynamics] wrote ", DynTtl, " and ", DelayCsv);

    // The planner reads the delays from beliefs, learned_delay_sec/2.
    !materialise_delays(ProbeList);

    // Step C: one planning and execution per deadline goal, in fact order.
    .findall(tb_goal(Id, Z, T, D), tb_goal(Id, Z, T, D), Goals);
    !exploit_all(Goals);
    .concat("timebounded_results_", Bool, "_", ActiveP, ".csv", ResCsv);
    saveExploitResults(ResCsv)[artifact_id(DId)];
    .print("[Dynamics] wrote ", ResCsv);

    .print("==========================================================");
    .print("   PHASE 3 DYNAMICS COMPLETE for ", ActiveP, " (", Bool, ")");
    .print("==========================================================");
    !dynamics_finish.

@start_dynamics_missing
+!start_dynamics <-
    .print("[Dynamics] FATAL: prerequisites missing (profile/td/goal beliefs). Aborting.");
    !dynamics_finish.

/* ============================================================
 * Action list helpers
 * ============================================================ */

// Add action_label_b(Idx, Label) for every action (used in prints and in the
// chosen_label column of the results).
@assert_action_labels_done
+!assert_action_labels(I, N, _) : I >= N <- true.
@assert_action_labels_step
+!assert_action_labels(I, N, Labels) : I < N <-
    .nth(I, Labels, L);
    +action_label_b(I, L);
    !assert_action_labels(I + 1, N, Labels).

// Label of an action id, or "action_<id>" if none is known.
@action_label_known
+!action_label(A, L) : action_label_b(A, L) <- true.
@action_label_unknown
+!action_label(A, L) <- .concat("action_", A, L).

// Collect, in index order, every action whose WoT action type is neither ""
// nor "none" (so not "do nothing"). Both "on" and "off" actions are probed; an
// "off" action has no effect from the all-off baseline and so never becomes a
// planner candidate. This avoids decoding the action's boolean value here.
@collect_probe_actions_done
+!collect_probe_actions(I, N, _, _, Acc, Out) : I >= N <- .reverse(Acc, Out).
@collect_probe_actions_step
+!collect_probe_actions(I, N, Uris, Vals, Acc, Out) : I < N <-
    .nth(I, Uris, U);
    if (U \== "" & U \== "none") {
        !collect_probe_actions(I + 1, N, Uris, Vals, [I|Acc], Out)
    } else {
        !collect_probe_actions(I + 1, N, Uris, Vals, Acc, Out)
    }.

/* ============================================================
 * Step A, probing. Per action: one characterising trial; if it
 * showed an effect, probes_per_actuator - 1 further delay trials.
 * ============================================================ */
@probe_all_done
+!probe_all([]) <- .print("[Probe] all actuators probed.").
@probe_all_step
+!probe_all([A|Rest]) <-
    !action_label(A, L);
    .print("[Probe] actuator action ", A, " (", L, ") ...");
    ?probes_per_actuator(K);
    !characterise(A);            // 1 delay sample + effect zones + energy cost
    // Only an action that raised some zone gets the further delay trials;
    // this leaves out the "off" actions without decoding their value.
    if (action_effect(A, _, _)) {
        !delay_only(A, K - 1)
    } else {
        .print("[Probe]   action ", A, " produced no effect — inert (single pass).")
    };
    !probe_all(Rest).

// Characterising trial: set the baseline, wait probe_settle_ms, read ranks,
// tick and energy counter, send the action, then read probe_hold_ticks times
// (probe_poll_ms apart) while the device stays on. Records the first-rise
// delay in ticks (if a rise was seen), action_cost(A, Power) and
// action_effect/3 for every zone whose rank rose between the baseline and the
// final read.
@characterise
+!characterise(A) <-
    ?lab_artifact(LabId); ?qlearner_artifact(QlId); ?dyn_artifact(DId);
    ?active_profile(P); ?probe_baseline(P, BKeys, BVals);
    ?probe_settle_ms(SettleMs); ?probe_hold_ticks(HoldTicks);
    setLabStateFromMap(BKeys, BVals)[artifact_id(LabId)];
    .wait(SettleMs);
    readLabStatusTimed(ZL0, _SR0, Tick0)[artifact_id(LabId)];
    readEnergyCostTimed(E0, ET0)[artifact_id(LabId)];
    actionToWoT(A, WotType, WotValue)[artifact_id(QlId)];
    invokeAction(WotType, WotValue)[artifact_id(LabId)];
    !hold_and_measure(A, ZL0, Tick0, HoldTicks, -1, Delay);
    // Power = energy difference / tick difference over the hold, taken from two
    // reads that each return the energy counter and the tick counter together.
    // It counts simulator ticks, not wall-clock time, so the value does not
    // depend on how fast the run went (0.0 if no tick passed).
    readEnergyCostTimed(E1, ET1)[artifact_id(LabId)];
    readLabStatusTimed(ZLf, _SRf, _Tf)[artifact_id(LabId)];
    if (Delay >= 0) { recordDelaySample(A, Delay)[artifact_id(DId)] };
    if (ET1 > ET0) { Power = (E1 - E0) / (ET1 - ET0) } else { Power = 0.0 };
    +action_cost(A, Power);
    !record_effect_zones(A, 1, ZL0, ZLf);
    .print("[Probe]   action ", A, " first-effect delay=", Delay,
           " ticks, holding power=", Power, " per tick").

// PollsLeft more reads; the tick difference of the first read in which a rank
// rose is kept (-1 if none). The reads continue after the rise.
@hold_and_measure_done
+!hold_and_measure(_, _, _, 0, DelaySoFar, DelaySoFar) <- true.
@hold_and_measure_step
+!hold_and_measure(A, ZL0, Tick0, PollsLeft, DelaySoFar, Out) : PollsLeft > 0 <-
    ?probe_poll_ms(PollMs);
    .wait(PollMs);
    ?lab_artifact(LabId);
    readLabStatusTimed(ZLn, _SR, Tickn)[artifact_id(LabId)];
    if (DelaySoFar < 0 & list_increased(ZL0, ZLn)) {
        !hold_and_measure(A, ZL0, Tick0, PollsLeft - 1, Tickn - Tick0, Out)
    } else {
        !hold_and_measure(A, ZL0, Tick0, PollsLeft - 1, DelaySoFar, Out)
    }.

// Add action_effect(A, Zone, FinalRank) (Zone counted from 1) for each zone
// whose rank rose.
@record_effect_zones_done
+!record_effect_zones(_, _, [], []) <- true.
@record_effect_zones_step
+!record_effect_zones(A, Z, [B|BR], [F|FR]) <-
    if (F > B) { +action_effect(A, Z, F) };
    !record_effect_zones(A, Z + 1, BR, FR).

// Further delay trials: baseline, wait, read, send, then read until a rank
// rises (at most probe_max_wait_ticks reads); a trial without a rise adds no
// sample.
@delay_only_done
+!delay_only(_, K) : K <= 0 <- true.
@delay_only_step
+!delay_only(A, K) : K > 0 <-
    ?lab_artifact(LabId); ?qlearner_artifact(QlId); ?dyn_artifact(DId);
    ?active_profile(P); ?probe_baseline(P, BKeys, BVals);
    ?probe_settle_ms(SettleMs); ?probe_max_wait_ticks(MaxW);
    setLabStateFromMap(BKeys, BVals)[artifact_id(LabId)];
    .wait(SettleMs);
    readLabStatusTimed(ZL0, _SR0, Tick0)[artifact_id(LabId)];
    actionToWoT(A, WotType, WotValue)[artifact_id(QlId)];
    invokeAction(WotType, WotValue)[artifact_id(LabId)];
    !measure_delay(A, ZL0, Tick0, MaxW, 0, Delay);
    if (Delay >= 0) { recordDelaySample(A, Delay)[artifact_id(DId)] };
    !delay_only(A, K - 1).

// Read until some zone's rank rises (Out = tick difference) or MaxW reads have
// been made (Out = -1).
@measure_delay_hit
+!measure_delay(A, ZL0, Tick0, MaxW, Waited, Out) : Waited < MaxW <-
    ?probe_poll_ms(PollMs);
    .wait(PollMs);
    ?lab_artifact(LabId);
    readLabStatusTimed(ZLn, _SR, Tickn)[artifact_id(LabId)];
    if (list_increased(ZL0, ZLn)) {
        Out = Tickn - Tick0
    } else {
        !measure_delay(A, ZL0, Tick0, MaxW, Waited + 1, Out)
    }.
@measure_delay_timeout
+!measure_delay(_, _, _, MaxW, Waited, -1) : Waited >= MaxW <- true.

// learned_delay_sec(A, Sec) for every probed action: mean delay in ticks times
// seconds_per_tick, or -1.0 if the action got no delay sample. Added in both
// modes; only the ql_true planner reads it.
@materialise_delays_done
+!materialise_delays([]) <- true.
@materialise_delays_step
+!materialise_delays([A|Rest]) <-
    ?dyn_artifact(DId);
    getDelaySecondsForAction(A, Sec)[artifact_id(DId)];
    +learned_delay_sec(A, Sec);
    !materialise_delays(Rest).

/* ============================================================
 * Step C, deadline goals. For each goal: choose a device, execute
 * it from the baseline, measure, and add one result row
 * (recordExploitResult). A goal without any candidate is skipped
 * and gets no row.
 * ============================================================ */
@exploit_all_done
+!exploit_all([]) <- .print("[Exploit] all time-bounded goals evaluated.").
@exploit_all_step
+!exploit_all([tb_goal(Id, Zone, Target, Deadline)|Rest]) <-
    !exploit_goal(Id, Zone, Target, Deadline);
    !exploit_all(Rest).

@exploit_goal
+!exploit_goal(Id, Zone, Target, Deadline) <-
    !choose_action(Zone, Target, Deadline, ChosenA, Believed, Cost);
    if (ChosenA >= 0) {
        !execute_and_measure(ChosenA, Zone, Target, Deadline,
                             ActualSec, TickEnergy, TickSpan, LegacyEnergy, Met);
        ?dyn_artifact(DId);
        getDelaySecondsForAction(ChosenA, LearnedSec)[artifact_id(DId)];
        ?active_profile(P); ?mode_label(Mode);
        !action_label(ChosenA, Label);
        recordExploitResult(P, Mode, Id, Zone, Target, Deadline, Label,
                            Believed, LearnedSec, ActualSec,
                            TickEnergy, TickSpan, LegacyEnergy, Met)[artifact_id(DId)];
        .print("[Exploit] ", Id, " zone ", Zone, " >=", Target, " in ", Deadline,
               "s -> ", Label, " (believed ", Believed, "s) actual=", ActualSec,
               "s tick_energy=", TickEnergy, " over ", TickSpan,
               " ticks, met=", Met)
    } else {
        .print("[Exploit] ", Id, " — no feasible actuator; skipped.")
    }.

// Candidates: actions whose characterising trial raised Zone to at least Target.
// Sort key key(Infeasible, Cost, BelievedDelay, ActionId), smallest first
// (.min): devices whose believed delay fits the deadline (Infeasible = 0)
// before the others, then the lowest measured power, then the shortest
// believed delay, then the lowest action index. If no candidate fits, the
// cheapest one that does not fit is still chosen. Without candidates
// ChosenA = -1.
@choose_action
+!choose_action(Zone, Target, Deadline, ChosenA, Believed, Cost) <-
    ?use_dynamics_kg(UseKg);
    .findall(key(Infeas, C, B, A),
             ( action_effect(A, Zone, FR) & FR >= Target
             & action_cost(A, C)
             & believed_delay(A, UseKg, B)
             & key_infeas(B, Deadline, Infeas) ),
             Keys);
    !pick_key(Keys, Zone, Target, ChosenA, Believed, Cost).
@pick_key_ok
+!pick_key(Keys, _, _, ChosenA, Believed, Cost) : Keys \== [] <-
    .min(Keys, key(_Infeas, Cost, Believed, ChosenA)).
@pick_key_empty
+!pick_key([], Zone, Target, -1, 0, 0) <-
    .print("[Exploit] WARNING: no candidate actuator reaches zone ", Zone,
           " rank ", Target).

// Execute the chosen action from the baseline and measure the real time until
// Zone reaches Target: ActualSec = ticks * seconds_per_tick, or -1.0 if not
// reached within probe_max_wait_ticks reads; Met = 1 iff reached and
// ActualSec <= Deadline. TickEnergy is the energy difference between the two
// timed reads around the attempt and TickSpan their tick difference, both in
// simulator time. LegacyEnergy is the plain counter value at the end, whose
// meaning depends on elapsed real time; it is recorded only as a diagnostic.
@execute_and_measure
+!execute_and_measure(A, Zone, Target, Deadline, ActualSec, TickEnergy, TickSpan, LegacyEnergy, Met) <-
    ?lab_artifact(LabId); ?qlearner_artifact(QlId);
    ?active_profile(P); ?probe_baseline(P, BKeys, BVals);
    ?probe_settle_ms(SettleMs); ?probe_max_wait_ticks(MaxW); ?seconds_per_tick(SPT);
    setLabStateFromMap(BKeys, BVals)[artifact_id(LabId)];
    .wait(SettleMs);
    readLabStatusTimed(_ZL0, _SR0, Tick0)[artifact_id(LabId)];
    readEnergyCostTimed(E0, ET0)[artifact_id(LabId)];
    actionToWoT(A, WotType, WotValue)[artifact_id(QlId)];
    invokeAction(WotType, WotValue)[artifact_id(LabId)];
    !measure_target(Zone, Target, Tick0, MaxW, 0, ReachTicks);
    readEnergyCostTimed(E1, ET1)[artifact_id(LabId)];
    TickEnergy = E1 - E0;
    TickSpan = ET1 - ET0;
    LegacyEnergy = E1;
    if (ReachTicks >= 0) {
        ActualSec = ReachTicks * SPT;
        if (ActualSec <= Deadline) { Met = 1 } else { Met = 0 }
    } else {
        ActualSec = -1.0;
        Met = 0
    }.

// Read until zone Zone (counted from 1) has at least rank Target (Out = tick
// difference) or MaxW reads have been made (Out = -1).
@measure_target_hit
+!measure_target(Zone, Target, Tick0, MaxW, Waited, Out) : Waited < MaxW <-
    ?probe_poll_ms(PollMs);
    .wait(PollMs);
    ?lab_artifact(LabId);
    readLabStatusTimed(ZLn, _SR, Tickn)[artifact_id(LabId)];
    Idx = Zone - 1;
    .nth(Idx, ZLn, Rank);
    if (Rank >= Target) {
        Out = Tickn - Tick0
    } else {
        !measure_target(Zone, Target, Tick0, MaxW, Waited + 1, Out)
    }.
@measure_target_timeout
+!measure_target(_, _, _, MaxW, Waited, -1) : Waited >= MaxW <- true.

/* ============================================================
 * Shutdown: wait 200 ms, then stop the platform. Apart from the
 * two override plans this program has no failure plans: a failing
 * operation during the run ends the run's intention, so the
 * remaining files are not written and this plan is not reached.
 * ============================================================ */
@dynamics_finish
+!dynamics_finish <-
    .print("[Dynamics] run finished — stopping MAS.");
    .wait(200);
    .stopMAS.
