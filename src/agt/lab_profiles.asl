// ============================================================
//  Lab profile registry
//
//  Not an agent program: a file of beliefs and accessor plans that the four
//  experiment programs include with { include("lab_profiles.asl") }:
//  illuminance_controller_agent_ql.asl (training), _bench.asl (test),
//  _adapt.asl (Phase-2 fault stage) and _dynamics.asl (Phase-3 slow
//  devices). A lab profile names, for one simulated room (a "lab", see
//  docs/LABS.md), everything that differs between labs: the device
//  description, the knowledge graph, the scenario files, the rank bounds,
//  the targets and the training budget. A Phase-2 fault profile reuses the
//  device description, knowledge graph and port of its healthy parent and
//  differs only in the simulator flow that the runner starts.
//
//  Field order of a profile (positional; every reader relies on it):
//    lab_profile(Name,                      name of the profile (string)
//      td(TdUrl),                           Web of Things Thing Description,
//                                           "classpath:interactions-<lab>.ttl"
//                                           in src/resources/; LabEnvironment
//                                           reads it; its base URL holds the
//                                           simulator port
//      ont(OntologyFiles),                  list of knowledge-graph files in
//                                           src/resources/ (always one
//                                           self-contained building_*.ttl),
//                                           loaded by StereotypeReasoner
//                                           through QLearner (and by
//                                           OntologyArtifact in the test
//                                           program)
//      scenarios(TestFile),                 test situations of the test
//                                           program (benchmark/scenarios_*.json)
//      train_scenarios(TrainFile),          start situations of training and
//                                           of the fault stage; the episodes
//                                           cycle through them in file order
//                                           (benchmark/train_scenarios_*.json)
//      sim_port(Port),                      simulator port; printed only (the
//                                           runners take ports from
//                                           config/run_config.json, the agent
//                                           from the Thing Description)
//      light_bounds([B1, B2, B3]),          rank bounds of a zone's light
//                                           level in lux: below B1 rank 0,
//                                           below B2 rank 1, below B3 rank 2,
//                                           otherwise rank 3
//                                           (LabEnvironment.configureDiscretization)
//      sunshine_bounds([B1, B2, B3]),       the same for the sun level, in lux
//      zone_targets([target(Z, R), ...]),   target rank R (0..3) of zone Z
//                                           (1-based); the goal is reached
//                                           when every zone's rank equals its
//                                           target (QLearner.isTerminal); the
//                                           fault stage may lower a target it
//                                           proves unreachable
//      sunshine_prob(P),                    a probability passed to
//                                           configureQLearner and stored by
//                                           StereotypeReasoner, which does not
//                                           use it: no effect
//      weakness_flags(Flags),               list of atoms w1..w6; read only by
//                                           the test program: with [] it skips
//                                           the per-step weakness
//                                           fingerprinting, otherwise it records
//                                           each listed kind when its
//                                           fingerprint fires (w2 = inverted
//                                           effect, w4 = missing effect); the
//                                           fault stage does not read it
//      qtable_suffix(Suffix),               text appended to the names of the
//                                           files a program writes, e.g.
//                                           qtable_final_stereotypes_<true|false><Suffix>.csv
//      training_params(Episodes, Decay)).   episode budget and the factor by
//                                           which the exploration rate is
//                                           multiplied after each episode
//                                           (lower limit 0.01). For training
//                                           the runner replaces Episodes (see
//                                           below); for a fault profile it is
//                                           the default re-learning budget
//                                           (-Padapt.episodes overrides it)
//
//  Selecting a profile: active_profile/1 below names the default. At start
//  every experiment program reads the Java system property active.profile
//  (internal action tools.jia.system_prop) and, when it is set, replaces
//  active_profile/1 with it. The Gradle tasks taskQl, taskAdapt and
//  taskDynamics set the property from -Pprofile=<name>; runFullSweep sets it
//  for each test cell from -Pprofiles. The accessor plans at the end of the
//  file return one field of the active profile.
//
//  Programs that read or rewrite this file with regular expressions (so its
//  comments must not contain the text these patterns look for):
//    - run_full_project.ps1 rewrites two things and restores the original
//      file afterwards, also on error:
//        (?m)(training_params\()\d+(,)     the episode number of every
//                                          profile, set to the run mode's
//                                          num_episodes from
//                                          config/run_config.json;
//        (?m)^active_profile\(".*?"\)\.    the default profile, set for each
//                                          training cell, in case the system
//                                          property is not read.
//    - run_phase2_adapt.ps1 reads the train_scenarios(...) file of a fault
//      profile and stops when it differs from the configuration key
//      phase2.train_scenarios_map; analysis/tests/test_lowsun_treatment_delivery.py
//      makes the same comparison for the low-sun profiles.
//    - The Gradle task preflight (build.gradle) reads td, ont, scenarios and
//      train_scenarios of each requested profile and checks that the files
//      exist.
//    - scripts/make_labs_doc.py reads every profile to generate docs/LABS.md.
// ============================================================

/* ============================================================
 * Default profile. The programs replace it when the system property
 * active.profile is set (see the header).
 * ============================================================ */
active_profile("lab1").

/* ============================================================
 * Registered profiles
 *
 * The light formulas below are copied from the simulator flows
 * (simulator/simulator_flow_<profile>.json, listed per lab in
 * docs/LABS.md). Sun is the sun level in lux; it is set by the start
 * situation of the episode (0, 100, 400 or 900 lux unless stated
 * otherwise) and does not change during the episode. All labs use the
 * light rank bounds 50 / 100 / 300 lux, so rank 3 means 300 lux or more.
 * ============================================================ */

// ── Phase 1: healthy rooms (configuration key profiles_to_run) ─────────────
//   Each profile loads only its own self-contained building_*.ttl. lab1 to
//   lab3 are also the healthy parents that are trained before Phase 2.

//   lab1: one zone, one lamp (port 1892). Z1 = 25 + (Z1Light ? 400 : 0).
//   State [Z1Level, Z1Light]: 8 states.
lab_profile("lab1",
            td("classpath:interactions-lab1.ttl"),
            ont(["building_1_trivial.ttl"]),
            scenarios("benchmark/scenarios_lab1.json"),
            train_scenarios("benchmark/train_scenarios_lab1.json"),
            sim_port(1892),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab1"),
            training_params(1000, 0.9920)).

//   lab2: two independent zones, each with a lamp and a blind (port 1893).
//   Zi = 25 + (ZiLight ? 400 : 0) + (ZiBlinds ? 0.50 * Sun : 0).
//   State [Z1Level, Z2Level, Z1Light, Z2Light, Z1Blinds, Z2Blinds,
//   Sunshine]: 1024 states.
lab_profile("lab2",
            td("classpath:interactions-lab2.ttl"),
            ont(["building_2_intermediate.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1893),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab2"),
            training_params(2000, 0.9960)).

//   lab3: two coupled zones and a shared spotlight (port 1894).
//   Z1 = 25 + (Z1Light ? 400 : 0) + (Z2Light ? 100 : 0)
//           + (Z1Blinds ? 0.50 * Sun : 0) + (Z2Blinds ? 0.30 * Sun : 0)
//           + (Spotlight ? 150 : 0), and Z2 symmetrically.
//   State adds Spotlight to the lab2 layout: 2048 states.
lab_profile("lab3",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab3"),
            training_params(3000, 0.9970)).

// ── Phase 4: dependencies and energy (configuration key phase4.phase4_profiles)
//   Two zones with lamps, blinds and a shared spotlight like lab3, but with
//   their own cross-zone numbers: a lamp adds 150 lux and an open blind
//   0.40 * Sun to the other zone (lab3: 100 and 0.30). The knowledge graph
//   states the power dependencies (ws:powerGates) and, in lab5, the energy
//   costs (ws:energyCost); the plain arm learns them from experience only.

//   lab4: the Z1 lamp gives light only when Z1Light and the plug PlugZ1 are
//   both on (port 1897). State adds PlugZ1 to the lab3 layout: 4096 states.
lab_profile("lab4",
            td("classpath:interactions-lab4.ttl"),
            ont(["building_4_smartplug.ttl"]),
            scenarios("benchmark/scenarios_lab4.json"),
            train_scenarios("benchmark/train_scenarios_lab4.json"),
            sim_port(1897),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab4"),
            training_params(3000, 0.9970)).

//   lab4dual: each zone's lamp is behind its own plug (PlugZ1, PlugZ2;
//   port 1901). 8192 states. Same budget as lab4, so the number of gated
//   lamps (lab3: 0, lab4: 1, lab4dual: 2) is the only difference.
lab_profile("lab4dual",
            td("classpath:interactions-lab4dual.ttl"),
            ont(["building_8_dualplug.ttl"]),
            scenarios("benchmark/scenarios_lab4dual.json"),
            train_scenarios("benchmark/train_scenarios_lab4dual.json"),
            sim_port(1901),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab4dual"),
            training_params(3000, 0.9970)).

//   lab4chain: a chain of two dependencies (port 1902): the Z1 lamp gives
//   light only when Z1Light, PlugZ1 and MasterSwitch are all on; the graph
//   states MasterSwitch -> PlugZ1 -> CeilingLight_Z1. 8192 states. Same
//   budget as lab4 and lab4dual.
lab_profile("lab4chain",
            td("classpath:interactions-lab4chain.ttl"),
            ont(["building_9_chainplug.ttl"]),
            scenarios("benchmark/scenarios_lab4chain.json"),
            train_scenarios("benchmark/train_scenarios_lab4chain.json"),
            sim_port(1902),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab4chain"),
            training_params(3000, 0.9970)).

// ── Phase 1b: mechanism rooms (configuration key phase1b.phase1b_profiles) ──
//   labrel0, labrel4, labrel8, labrel16 (ports 1904 to 1907): one zone, one
//   lamp, Z1 = 25 + (Z1Light ? 400 : 0), no sun (every start situation sets
//   0). labrelK adds K switchable devices (fan, heater, speaker and others)
//   that have no effect on the light; each has a complete stereotype whose
//   dependent variable is not illuminance. The devices have no state entry,
//   so every rung has the same 8 states and only the number of actions
//   grows; goal, light formula, schedule and exploration decay are the same.
lab_profile("labrel0",
            td("classpath:interactions-labrel0.ttl"),
            ont(["building_10_labrel0.ttl"]),
            scenarios("benchmark/scenarios_labrel0.json"),
            train_scenarios("benchmark/train_scenarios_labrel0.json"),
            sim_port(1904),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.0),
            weakness_flags([]),
            qtable_suffix("_labrel0"),
            training_params(3000, 0.9970)).

lab_profile("labrel4",
            td("classpath:interactions-labrel4.ttl"),
            ont(["building_10_labrel4.ttl"]),
            scenarios("benchmark/scenarios_labrel4.json"),
            train_scenarios("benchmark/train_scenarios_labrel4.json"),
            sim_port(1905),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.0),
            weakness_flags([]),
            qtable_suffix("_labrel4"),
            training_params(3000, 0.9970)).

lab_profile("labrel8",
            td("classpath:interactions-labrel8.ttl"),
            ont(["building_10_labrel8.ttl"]),
            scenarios("benchmark/scenarios_labrel8.json"),
            train_scenarios("benchmark/train_scenarios_labrel8.json"),
            sim_port(1906),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.0),
            weakness_flags([]),
            qtable_suffix("_labrel8"),
            training_params(3000, 0.9970)).

lab_profile("labrel16",
            td("classpath:interactions-labrel16.ttl"),
            ont(["building_10_labrel16.ttl"]),
            scenarios("benchmark/scenarios_labrel16.json"),
            train_scenarios("benchmark/train_scenarios_labrel16.json"),
            sim_port(1907),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.0),
            weakness_flags([]),
            qtable_suffix("_labrel16"),
            training_params(3000, 0.9970)).

//   labrel8s (port 1908): the eight devices of labrel8, but each has a state
//   entry: 4 x 2^9 = 2048 states. Compared with labrel8 at the same number
//   of actions, it shows the cost of the larger table alone.
lab_profile("labrel8s",
            td("classpath:interactions-labrel8s.ttl"),
            ont(["building_10_labrel8s.ttl"]),
            scenarios("benchmark/scenarios_labrel8s.json"),
            train_scenarios("benchmark/train_scenarios_labrel8s.json"),
            sim_port(1908),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.0),
            weakness_flags([]),
            qtable_suffix("_labrel8s"),
            training_params(3000, 0.9970)).

//   labband (port 1912): the target is rank 2, a band of 100 to below
//   300 lux, so too much light misses the goal.
//   Z1 = 25 + (StrongLamp ? 400 : 0) + (WeakLamp ? 150 : 0) + daylight,
//   daylight = Z1Blinds ? 0.50 * Sun : 0, multiplied by 0.25 when the
//   Awning is out. The strong lamp, or an open blind at sun 900, overshoots;
//   the weak lamp, switching devices off and the awning correct it.
//   State [Z1Level, StrongLamp, WeakLamp, Z1Blinds, Awning, Sunshine]:
//   256 states.
lab_profile("labband",
            td("classpath:interactions-labband.ttl"),
            ont(["building_11_labband.ttl"]),
            scenarios("benchmark/scenarios_labband.json"),
            train_scenarios("benchmark/train_scenarios_labband.json"),
            sim_port(1912),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 2)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_labband"),
            training_params(3000, 0.9970)).

//   lab4chain3 (port 1913): one zone, no sun, a chain of three:
//   Z1 = 25 + (Z1Light && PlugZ1 && Breaker ? 400 : 0); the graph states
//   Breaker -> PlugZ1 -> lamp, the only way to the goal. Two further devices,
//   AuxA (incomplete stereotype) and AuxB (no stereotype), have a state
//   entry but no effect on the light. 128 states, 11 actions.
lab_profile("lab4chain3",
            td("classpath:interactions-lab4chain3.ttl"),
            ont(["building_12_chain3.ttl"]),
            scenarios("benchmark/scenarios_lab4chain3.json"),
            train_scenarios("benchmark/train_scenarios_lab4chain3.json"),
            sim_port(1913),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.0),
            weakness_flags([]),
            qtable_suffix("_lab4chain3"),
            training_params(3000, 0.9970)).

//   lab5 (Phase 4, port 1898): two lamps per zone, an efficient one (Z1Eff,
//   Z2Eff, energy 1 per tick) and an inefficient one (Z1Ineff, Z2Ineff,
//   4 per tick); a zone gets +400 lux when either of its lamps is on (not
//   both added), plus the lab4 cross-zone terms, blinds and spotlight
//   (energy 2 per tick). Energy is not part of the reward. The knowledge
//   graph states the costs (ws:energyCost); the knowledge arm turns them
//   into a preference for the cheaper action when the run mode sets an
//   energy weight (stereo_energy_prior_weight 2.0 in the Phase-4 run modes,
//   0 by default). The test situations carry an energy budget of 2 that the
//   Phase-4 analysis checks. 8192 states.
lab_profile("lab5",
            td("classpath:interactions-lab5.ttl"),
            ont(["building_5_energy.ttl"]),
            scenarios("benchmark/scenarios_lab5.json"),
            train_scenarios("benchmark/train_scenarios_lab5.json"),
            sim_port(1898),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab5"),
            training_params(3000, 0.9970)).

// ── Phase 2 parents: the monitor labs (configuration key phase2.parent_profile)
//   labmon (port 1899): one zone with a task lamp and a computer monitor
//   whose light is a side effect. Z1 = 25 + (Z1Light ? 400 : 0)
//   + (Z1Monitor ? 260 : 0); no blinds, the sun has no effect. The lamp
//   alone gives 425 lux (rank 3), the monitor alone 285 lux (rank 2).
//   State [Z1Level, Z1Light, Z1Monitor]: 16 states.
lab_profile("labmon",
            td("classpath:interactions-labmon.ttl"),
            ont(["building_6_monitor.ttl"]),
            scenarios("benchmark/scenarios_labmon.json"),
            train_scenarios("benchmark/train_scenarios_labmon.json"),
            sim_port(1899),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_labmon"),
            training_params(1500, 0.9950)).

//   labmon2 (port 1900): two independent zones, each with a task lamp, a
//   monitor and a blind; no spotlight, no cross-zone light.
//   Zi = 25 + (ZiLight ? 400 : 0) + (ZiMonitor ? 200 : 0)
//   + (ZiBlinds ? 0.50 * Sun : 0). State [Z1Level, Z2Level, Z1Light,
//   Z2Light, Z1Monitor, Z2Monitor, Z1Blinds, Z2Blinds, Sunshine]:
//   4096 states.
lab_profile("labmon2",
            td("classpath:interactions-labmon2.ttl"),
            ont(["building_7_dualmonitor.ttl"]),
            scenarios("benchmark/scenarios_labmon2.json"),
            train_scenarios("benchmark/train_scenarios_labmon2.json"),
            sim_port(1900),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_labmon2"),
            training_params(4000, 0.9975)).

//   Variants of labmon and labmon2 with the same flows, ports, Thing
//   Descriptions and scenario files, but a knowledge graph that does not
//   say that the monitor gives light:
//     *_infoonly  the monitor's stereotype has the dependent variable
//                 "displayed information" only;
//     *_nostereo  the monitor has no stereotype.
//   The monitor's actions are still in the action list (the WoT mapping
//   defines it in both arms), but the graph gives the knowledge arm nothing
//   that favours them; both arms learn the monitor's light from the reward.
//   With labmon / labmon2 they form a comparison of a complete, an
//   incomplete and a missing description.
lab_profile("labmon_infoonly",
            td("classpath:interactions-labmon.ttl"),
            ont(["building_6_monitor_infoonly.ttl"]),
            scenarios("benchmark/scenarios_labmon.json"),
            train_scenarios("benchmark/train_scenarios_labmon.json"),
            sim_port(1899),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_labmon_infoonly"),
            training_params(1500, 0.9950)).

lab_profile("labmon_nostereo",
            td("classpath:interactions-labmon.ttl"),
            ont(["building_6_monitor_nostereo.ttl"]),
            scenarios("benchmark/scenarios_labmon.json"),
            train_scenarios("benchmark/train_scenarios_labmon.json"),
            sim_port(1899),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_labmon_nostereo"),
            training_params(1500, 0.9950)).

lab_profile("labmon2_infoonly",
            td("classpath:interactions-labmon2.ttl"),
            ont(["building_7_dualmonitor_infoonly.ttl"]),
            scenarios("benchmark/scenarios_labmon2.json"),
            train_scenarios("benchmark/train_scenarios_labmon2.json"),
            sim_port(1900),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_labmon2_infoonly"),
            training_params(4000, 0.9975)).

lab_profile("labmon2_nostereo",
            td("classpath:interactions-labmon2.ttl"),
            ont(["building_7_dualmonitor_nostereo.ttl"]),
            scenarios("benchmark/scenarios_labmon2.json"),
            train_scenarios("benchmark/train_scenarios_labmon2.json"),
            sim_port(1900),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_labmon2_nostereo"),
            training_params(4000, 0.9975)).

// ── Phase 3: slow devices (configuration key phase3.dynamics_profiles) ────
//   The blinds take effect only 12 simulator ticks after the command
//   (60 s of model time at phase3.seconds_per_tick = 5); lamps and
//   spotlight react at once. The dynamics program measures each device's
//   delay and plans against time-bounded goals; it writes the learned delays
//   to learned_dynamics_*.ttl. The profiles use their own ports, Thing
//   Descriptions and graphs, and the scenario files of their healthy parents.

//   lab2_slow (port 1895): the lab2 formula with delayed blinds.
lab_profile("lab2_slow",
            td("classpath:interactions-lab2_slow.ttl"),
            ont(["building_2_slow.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1895),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab2_slow"),
            training_params(2000, 0.9960)).

//   lab3_slow (port 1896): two coupled zones with spotlight and delayed
//   blinds, but with the cross-zone numbers of the Phase-4 labs (lamp +150,
//   blind 0.40 * Sun), not those of lab3 (+100, 0.30).
lab_profile("lab3_slow",
            td("classpath:interactions-lab3_slow.ttl"),
            ont(["building_3_slow.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1896),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([]),
            qtable_suffix("_lab3_slow"),
            training_params(3000, 0.9970)).

// ── Phase 2: rooms with broken devices (configuration key phase2.adapt_profiles)
//   Each fault profile has the device description, knowledge graph, port,
//   bounds and targets of its healthy parent; only the simulator flow
//   differs (simulator/simulator_flow_<profile>.json, written by
//   simulator/generate_faulty_flows.ps1). A dead device has no effect on
//   the light; an inverted device lowers the light by the amount it should
//   add. adapt_source/2 at the end of the file names the parent whose
//   trained Q-table the fault stage loads. The weakness flags (w4 dead,
//   w2 inverted) are not read by the fault stage, which has its own fault
//   detector. Where the original target cannot be reached, the fault stage
//   lowers it to the nearest reachable rank (QLearner, effective goal).

//   lab1_f1dead: the only lamp is dead. Z1 stays at 25 lux; no device is
//   left to reach the goal.
lab_profile("lab1_f1dead",
            td("classpath:interactions-lab1.ttl"),
            ont(["building_1_trivial.ttl"]),
            scenarios("benchmark/scenarios_lab1.json"),
            train_scenarios("benchmark/train_scenarios_lab1.json"),
            sim_port(1892),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab1_f1dead"),
            training_params(1000, 0.9920)).

//   lab2_f1dead: the Z1 lamp is dead. Z1 = 25 + 0.50 * Sun with the blind
//   open, so Z1 reaches rank 3 only at sun 900; zone 2 is unchanged.
lab_profile("lab2_f1dead",
            td("classpath:interactions-lab2.ttl"),
            ont(["building_2_intermediate.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1893),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab2_f1dead"),
            training_params(2000, 0.9960)).

//   lab2_f1inv: the Z1 lamp is inverted (-400 lux in Z1).
lab_profile("lab2_f1inv",
            td("classpath:interactions-lab2.ttl"),
            ont(["building_2_intermediate.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1893),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w2]),
            qtable_suffix("_lab2_f1inv"),
            training_params(2000, 0.9960)).

//   lab3_f1dead: the Z1 lamp is dead (no light in Z1, no spill into Z2).
//   Without sun, Z1 reaches at most 25 + 100 (Z2 lamp) + 150 (spotlight)
//   = 275 lux (rank 2); rank 3 needs sun 100 or more and an open blind.
lab_profile("lab3_f1dead",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab3_f1dead"),
            training_params(4000, 0.9970)).

//   lab3_f1inv: the Z1 lamp is inverted (-400 lux in Z1, -100 in Z2).
//   Because the Z2 lamp and the spotlight raise Z1, switching the lamp on
//   visibly lowers the rank instead of only leaving it unchanged.
lab_profile("lab3_f1inv",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w2]),
            qtable_suffix("_lab3_f1inv"),
            training_params(4000, 0.9970)).

//   Several devices broken at once: the fault stage detects and removes
//   them one after the other. Only lamps are broken here.

//   lab2_f2dead: both lamps are dead. Each zone gets 25 + 0.50 * Sun with
//   its blind open: rank 3 only at sun 900.
lab_profile("lab2_f2dead",
            td("classpath:interactions-lab2.ttl"),
            ont(["building_2_intermediate.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1893),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab2_f2dead"),
            training_params(2000, 0.9960)).

//   lab2_f2inv: both lamps are inverted (-400 lux each in its own zone).
lab_profile("lab2_f2inv",
            td("classpath:interactions-lab2.ttl"),
            ont(["building_2_intermediate.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1893),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w2]),
            qtable_suffix("_lab2_f2inv"),
            training_params(2000, 0.9960)).

//   lab3_f2dead: both lamps are dead. With the spotlight and both blinds a
//   zone gets 175 + 0.80 * Sun: rank 3 at sun 400 and 900, rank 2 below.
lab_profile("lab3_f2dead",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab3_f2dead"),
            training_params(4000, 0.9970)).

//   lab3_f2inv: both lamps are inverted (-400 own zone, -100 other zone);
//   the spotlight and the blinds remain.
lab_profile("lab3_f2inv",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w2]),
            qtable_suffix("_lab3_f2inv"),
            training_params(4000, 0.9970)).

//   Further fault cells: the Z2 lamp of lab3 (mirror images of lab3_f1dead
//   and lab3_f1inv), and dead or inverted blinds. A blind's effect is
//   0.50 * Sun in its own zone (and 0.30 * Sun in the other zone of lab3),
//   so its fault can be seen only when there is sun; the lamps still reach
//   rank 3 in both zones without sun.

//   lab3_f1dead_z2: the Z2 lamp is dead. Without sun, Z2 reaches at most
//   25 + 100 (Z1 lamp) + 150 (spotlight) = 275 lux (rank 2); rank 3 needs
//   sun 100 or more and an open blind.
lab_profile("lab3_f1dead_z2",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab3_f1dead_z2"),
            training_params(4000, 0.9970)).

//   lab3_f1inv_z2: the Z2 lamp is inverted (-400 lux in Z2, -100 in Z1).
lab_profile("lab3_f1inv_z2",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w2]),
            qtable_suffix("_lab3_f1inv_z2"),
            training_params(4000, 0.9970)).

//   lab3_f1bdead: the Z1 blind is dead (0.50 * Sun in Z1 and 0.30 * Sun in
//   Z2 are zero).
lab_profile("lab3_f1bdead",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab3_f1bdead"),
            training_params(4000, 0.9970)).

//   lab3_f1binv: the Z1 blind is inverted (-0.50 * Sun in Z1, -0.30 * Sun in
//   Z2). From a lit zone, opening it lowers the rank; from rank 0 the rank
//   cannot fall further, so the response looks like a dead device.
lab_profile("lab3_f1binv",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w2]),
            qtable_suffix("_lab3_f1binv"),
            training_params(4000, 0.9970)).

//   lab2_f1bdead: the Z1 blind is dead (0.50 * Sun in Z1 is zero).
lab_profile("lab2_f1bdead",
            td("classpath:interactions-lab2.ttl"),
            ont(["building_2_intermediate.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1893),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab2_f1bdead"),
            training_params(2000, 0.9960)).

//   lab2_f1binv: the Z1 blind is inverted (-0.50 * Sun in Z1).
lab_profile("lab2_f1binv",
            td("classpath:interactions-lab2.ttl"),
            ont(["building_2_intermediate.ttl"]),
            scenarios("benchmark/scenarios_lab2.json"),
            train_scenarios("benchmark/train_scenarios_lab2.json"),
            sim_port(1893),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w2]),
            qtable_suffix("_lab2_f1binv"),
            training_params(2000, 0.9960)).

//   lab3_f2bdead: both blinds are dead. Each zone still reaches rank 3 with
//   its own lamp (425 lux or more) without sun. Registered as an
//   exploratory cell in docs/registrations/pre_registration.md, section
//   9.11, outside the families of sections 9.5 and 9.6.
lab_profile("lab3_f2bdead",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab3_f2bdead"),
            training_params(4000, 0.9970)).

//   labmon_f1dead: the task lamp of labmon is dead. Only the monitor is
//   left: 25 + 260 = 285 lux, rank 2, so rank 3 cannot be reached in any
//   situation.
lab_profile("labmon_f1dead",
            td("classpath:interactions-labmon.ttl"),
            ont(["building_6_monitor.ttl"]),
            scenarios("benchmark/scenarios_labmon.json"),
            train_scenarios("benchmark/train_scenarios_labmon.json"),
            sim_port(1899),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_labmon_f1dead"),
            training_params(1500, 0.9950)).

//   lab3_f2dead_lowsun: both lamps of lab3 are dead and every start
//   situation has sun 100 (train_scenarios_lab3_lowsun.json: the parent's
//   ids, order and start states with Sunshine = 100). A zone then reaches
//   at most 25 + 150 (spotlight) + 50 (own blind) + 30 (other blind)
//   = 255 lux, rank 2, in both zones. The test situations are lab3's. The
//   flow's reset node also sets sun 100, but no program calls the reset
//   endpoint; the low sun reaches the agent only through the scenario file.
lab_profile("lab3_f2dead_lowsun",
            td("classpath:interactions-lab3.ttl"),
            ont(["building_3_complex.ttl"]),
            scenarios("benchmark/scenarios_lab3.json"),
            train_scenarios("benchmark/train_scenarios_lab3_lowsun.json"),
            sim_port(1894),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_lab3_f2dead_lowsun"),
            training_params(4000, 0.9970)).

//   labmon2_f2dead_lowsun: both task lamps of labmon2 are dead and every
//   start situation has sun 100 (train_scenarios_labmon2_lowsun.json, built
//   like the lab3 file). A zone then reaches at most 25 + 200 (monitor)
//   + 50 (blind) = 275 lux, rank 2; without the monitor a blind gives only
//   75 lux (rank 1). The test situations are labmon2's; the reset node
//   remark of lab3_f2dead_lowsun applies here too.
lab_profile("labmon2_f2dead_lowsun",
            td("classpath:interactions-labmon2.ttl"),
            ont(["building_7_dualmonitor.ttl"]),
            scenarios("benchmark/scenarios_labmon2.json"),
            train_scenarios("benchmark/train_scenarios_labmon2_lowsun.json"),
            sim_port(1900),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_labmon2_f2dead_lowsun"),
            training_params(4000, 0.9970)).

//   The same two faults with the knowledge graphs of the monitor variants
//   (*_infoonly, *_nostereo). Each loads the Q-table trained on the matching
//   healthy variant (adapt_source/2). The monitor is the device that matters
//   after the fault, and the graph does not say that it gives light.

//   labmon_infoonly_f1dead: the flow of labmon_f1dead; the monitor's
//   stereotype describes displayed information only. Best reachable: rank 2.
lab_profile("labmon_infoonly_f1dead",
            td("classpath:interactions-labmon.ttl"),
            ont(["building_6_monitor_infoonly.ttl"]),
            scenarios("benchmark/scenarios_labmon.json"),
            train_scenarios("benchmark/train_scenarios_labmon.json"),
            sim_port(1899),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_labmon_infoonly_f1dead"),
            training_params(1500, 0.9950)).

//   labmon_nostereo_f1dead: the flow of labmon_f1dead; the monitor has no
//   stereotype. Best reachable: rank 2.
lab_profile("labmon_nostereo_f1dead",
            td("classpath:interactions-labmon.ttl"),
            ont(["building_6_monitor_nostereo.ttl"]),
            scenarios("benchmark/scenarios_labmon.json"),
            train_scenarios("benchmark/train_scenarios_labmon.json"),
            sim_port(1899),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_labmon_nostereo_f1dead"),
            training_params(1500, 0.9950)).

//   labmon2_infoonly_f2dead_lowsun: the flow and scenario file of
//   labmon2_f2dead_lowsun; monitor stereotypes describe displayed
//   information only. Best reachable: rank 2 in both zones.
lab_profile("labmon2_infoonly_f2dead_lowsun",
            td("classpath:interactions-labmon2.ttl"),
            ont(["building_7_dualmonitor_infoonly.ttl"]),
            scenarios("benchmark/scenarios_labmon2.json"),
            train_scenarios("benchmark/train_scenarios_labmon2_lowsun.json"),
            sim_port(1900),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_labmon2_infoonly_f2dead_lowsun"),
            training_params(4000, 0.9970)).

//   labmon2_nostereo_f2dead_lowsun: the flow and scenario file of
//   labmon2_f2dead_lowsun; the monitors have no stereotype. Best
//   reachable: rank 2 in both zones.
lab_profile("labmon2_nostereo_f2dead_lowsun",
            td("classpath:interactions-labmon2.ttl"),
            ont(["building_7_dualmonitor_nostereo.ttl"]),
            scenarios("benchmark/scenarios_labmon2.json"),
            train_scenarios("benchmark/train_scenarios_labmon2_lowsun.json"),
            sim_port(1900),
            light_bounds([50, 100, 300]),
            sunshine_bounds([50, 200, 600]),
            zone_targets([target(1, 3), target(2, 3)]),
            sunshine_prob(0.75),
            weakness_flags([w4]),
            qtable_suffix("_labmon2_nostereo_f2dead_lowsun"),
            training_params(4000, 0.9970)).

/* ============================================================
 * adapt_source/2: for each fault profile, the qtable_suffix of the
 * healthy profile whose trained Q-table the fault stage loads
 * (qtable_final_stereotypes_<true|false><Suffix>.csv). Read only by
 * illuminance_controller_agent_adapt.asl, which stops when the active
 * profile has no entry.
 * ============================================================ */
adapt_source("lab1_f1dead", "_lab1").
adapt_source("lab2_f1dead", "_lab2").
adapt_source("lab2_f1inv",  "_lab2").
adapt_source("lab3_f1dead", "_lab3").
adapt_source("lab3_f1inv",  "_lab3").
adapt_source("lab2_f2dead", "_lab2").
adapt_source("lab2_f2inv",  "_lab2").
adapt_source("lab3_f2dead", "_lab3").
adapt_source("lab3_f2inv",  "_lab3").
// Z2 lamp and blind faults.
adapt_source("lab3_f1dead_z2", "_lab3").
adapt_source("lab3_f1inv_z2",  "_lab3").
adapt_source("lab3_f1bdead",   "_lab3").
adapt_source("lab3_f1binv",    "_lab3").
adapt_source("lab2_f1bdead",   "_lab2").
adapt_source("lab2_f1binv",    "_lab2").
// Both blinds dead (exploratory cell).
adapt_source("lab3_f2bdead",   "_lab3").
// Monitor lab, dead task lamp.
adapt_source("labmon_f1dead",  "_labmon").
// Both lamps dead with sun 100.
adapt_source("lab3_f2dead_lowsun", "_lab3").
adapt_source("labmon2_f2dead_lowsun", "_labmon2").
// Monitor variants: each fault cell loads the Q-table of the matching
// healthy variant (same graph, same action list).
adapt_source("labmon_infoonly_f1dead",         "_labmon_infoonly").
adapt_source("labmon_nostereo_f1dead",         "_labmon_nostereo").
adapt_source("labmon2_infoonly_f2dead_lowsun", "_labmon2_infoonly").
adapt_source("labmon2_nostereo_f2dead_lowsun", "_labmon2_nostereo").

/* ============================================================
 * Accessors: each plan unifies its argument with one field of the
 * lab_profile/13 record of the profile named by active_profile/1.
 * ============================================================ */

@profile_td
+!profile_td(TdUrl)
    : active_profile(P) & lab_profile(P, td(TdUrl), _, _, _, _, _, _, _, _, _, _, _) <- true.

@profile_ont
+!profile_ont(Ont)
    : active_profile(P) & lab_profile(P, _, ont(Ont), _, _, _, _, _, _, _, _, _, _) <- true.

@profile_scenarios
+!profile_scenarios(File)
    : active_profile(P) & lab_profile(P, _, _, scenarios(File), _, _, _, _, _, _, _, _, _) <- true.

@profile_train_scenarios
+!profile_train_scenarios(File)
    : active_profile(P) & lab_profile(P, _, _, _, train_scenarios(File), _, _, _, _, _, _, _, _) <- true.

@profile_sim_port
+!profile_sim_port(Port)
    : active_profile(P) & lab_profile(P, _, _, _, _, sim_port(Port), _, _, _, _, _, _, _) <- true.

@profile_light_bounds
+!profile_light_bounds(B)
    : active_profile(P) & lab_profile(P, _, _, _, _, _, light_bounds(B), _, _, _, _, _, _) <- true.

@profile_sunshine_bounds
+!profile_sunshine_bounds(B)
    : active_profile(P) & lab_profile(P, _, _, _, _, _, _, sunshine_bounds(B), _, _, _, _, _) <- true.

@profile_zone_targets
+!profile_zone_targets(T)
    : active_profile(P) & lab_profile(P, _, _, _, _, _, _, _, zone_targets(T), _, _, _, _) <- true.

@profile_sunshine_prob
+!profile_sunshine_prob(SP)
    : active_profile(P) & lab_profile(P, _, _, _, _, _, _, _, _, sunshine_prob(SP), _, _, _) <- true.

@profile_weakness_flags
+!profile_weakness_flags(F)
    : active_profile(P) & lab_profile(P, _, _, _, _, _, _, _, _, _, weakness_flags(F), _, _) <- true.

@profile_qtable_suffix
+!profile_qtable_suffix(S)
    : active_profile(P) & lab_profile(P, _, _, _, _, _, _, _, _, _, _, qtable_suffix(S), _) <- true.

@profile_training_params
+!profile_training_params(NumEps, EpDecay)
    : active_profile(P) & lab_profile(P, _, _, _, _, _, _, _, _, _, _, _, training_params(NumEps, EpDecay)) <- true.

/* ============================================================
 * Prints every field of the active profile; each program calls it
 * once at start.
 * ============================================================ */
@profile_print
+!profile_print
    : active_profile(P)
    & lab_profile(P, td(Td), ont(Ont), scenarios(Sc), train_scenarios(Tr),
                  sim_port(Port), light_bounds(LB), sunshine_bounds(SB),
                  zone_targets(ZT), sunshine_prob(SP), weakness_flags(WF),
                  qtable_suffix(QS), training_params(NumEps, EpDecay)) <-
    .print("[Profile] active=", P);
    .print("[Profile]   td=", Td);
    .print("[Profile]   ont=", Ont);
    .print("[Profile]   scenarios=", Sc, "  train_scenarios=", Tr);
    .print("[Profile]   sim_port=", Port);
    .print("[Profile]   light_bounds=", LB, "  sunshine_bounds=", SB);
    .print("[Profile]   zone_targets=", ZT, "  sunshine_prob=", SP);
    .print("[Profile]   weakness_flags=", WF, "  qtable_suffix=", QS);
    .print("[Profile]   training_params: num_episodes=", NumEps, " epsilonDecay=", EpDecay).
