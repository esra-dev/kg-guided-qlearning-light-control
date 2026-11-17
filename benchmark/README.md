# Scenario files

This folder holds the start situations of the experiments: the situations the agent is
trained on and the situations it is tested on. Each file is a JSON list of situations. A
situation says where the room starts (which switches are on, how bright it is, how strong
the sun is). The rooms themselves are described in [`../docs/LABS.md`](../docs/LABS.md); the
way training and tests use the situations is described in
[`../docs/EXPERIMENTS.md`](../docs/EXPERIMENTS.md). This page describes the files.

## 1. Two kinds of file

| Pattern | Kind | Used by |
|---|---|---|
| `scenarios_<lab>.json` | **Test situations** (the "benchmark"). The test program puts the room into each situation, lets each arm (`rule_based`, `ql_false`, `ql_true`) act for up to the step limit, and records whether the goal is reached. | the test program `illuminance_controller_agent_bench.asl` |
| `train_scenarios_<lab>.json` | **Training situations**. The training program and the fault-stage program start episode `n` in situation number `n mod (number of situations)` of the file, in file order. | `illuminance_controller_agent_ql.asl`, `illuminance_controller_agent_adapt.asl` |

The training situations of a lab are, in every lab but one, a subset of its test
situations with the same `id` and the same start state. The test file therefore holds the
training situations and, in addition, situations the agent never trains on; it is not a
held-out set. The comment in the first entry of a file describes the split; some of these
comments call the file a "held-out benchmark", a wording that is older than this description
and was left in place so that the files stay byte-identical to those the archived runs used. The exception is `labmon`: its two test situations (ids 5 and 8) are not
among its six training situations (ids 1, 2, 3, 4, 6, 7).

## 2. Format

A file is a JSON array. Every element is one object with these fields:

| Field | Type | Meaning |
|---|---|---|
| `id` | integer | The identifier of the situation. Unique in the file; need not be consecutive. It appears in the result files as `scenario_id`. Required: the loader (`ScenarioCatalog`) refuses a file with a missing, repeated or non-integer `id`, an element that is not an object, or no element at all (run modes with `protocol_version: phase1-v2`; other modes skip an element without an `id`). |
| `Z1Level`, `Z2Level`, ... | number | The light level of each zone in lux at the start. |
| `Z1Light`, `Z2Light`, `Z1Blinds`, `Z2Blinds`, `Spotlight`, `PlugZ1`, `Z1Monitor`, ... | boolean | The state of each switch the lab has. The set of switches depends on the lab; the names are the status fields of the lab's device description. |
| `Sunshine` | number | The sun level in lux (0, 100, 400 or 900 in the files that have it). Absent in the two `labmon` files. |
| `description` | text | What the situation is and what a good agent does in it. Not sent to the simulator. |
| `comment`, `comment2` | text | Only on the first element of a file: what the file is, the physics of its lab, which situations are held out. Not sent to the simulator. |
| `energyBudget` | number | Only in the two `lab5` files: the highest steady-state power (the sum of the weights of the switches that are on, see `phase4.power_formula` in [`../config/`](../config/README.md)) that still counts as energy-compliant. Read by `analysis/phase4_energy.py`; not sent to the simulator. |

Which switch fields a file has is fixed by its lab (for example the `lab4*` files add
`PlugZ1`, `lab4chain` adds `MasterSwitch`, `lab5` adds `Z1Eff`, `Z1Ineff`, `Z2Eff`,
`Z2Ineff`, `labmon2` adds `Z1Monitor` and `Z2Monitor`, `labband` adds `StrongLamp`,
`WeakLamp` and `Awning`, `labrel8s` adds eight further devices). Every key that is not
`id`, `description`, `comment` or `energyBudget` is sent to the simulator, and the agent's
input check refuses a key that is not a status field of the lab's device description.

## 3. How a situation is applied

1. The agent program calls `setScenarioLabStateByPosition` (training and fault stage: file
   position `n mod count`) or `setScenarioLabState` (test: a given `id`) of the
   `LabEnvironment` artifact.
2. The artifact loads the file with `ScenarioCatalog`, takes the object, drops the keys
   `id`, `description`, `comment` and `energyBudget`, and sends the rest as one JSON object to
   the simulator's `POST /was/rl/setState` (see [`../simulator/README.md`](../simulator/README.md)).
3. The flow sets the named switches, levels and sun, keeps the sun fixed for the rest of
   the episode, and sets its energy counters to 0 (all lab flows do this). On its next tick (every 0.05 s) it
   recomputes the zone levels from the switches and the sun, so the levels in the file
   describe the start but are replaced by the physics of the lab.
4. The agent waits until the simulator has ticked (250 ms in training and fault stage, 300 ms
   in the test) and only then reads the state, so the start state it records is the settled
   one.

If a lab's profile names no training file, the training program starts from a random
situation of the default file `train_scenarios.json` instead (and counts it as a fallback).
Every lab of the accepted campaigns has its own file. The slow-device program does not use
these files: it sets its own probe start state.

## 4. Which lab uses which file

The profile of a lab in [`../src/agt/lab_profiles.asl`](../src/agt/lab_profiles.asl) names
its files; the table is read from there (and from `phase2.train_scenarios_map` in
[`../config/run_config.json`](../config/README.md), which names the training file of each
fault lab and agrees with it).

| Test file (situations) | Training file (situations) | Labs that use the pair |
|---|---|---|
| `scenarios_lab1.json` (8) | `train_scenarios_lab1.json` (6) | `lab1`, `lab1_f1dead` |
| `scenarios_lab2.json` (16) | `train_scenarios_lab2.json` (11) | `lab2`, `lab2_slow`, and the fault labs `lab2_f1dead`, `lab2_f1inv`, `lab2_f2dead`, `lab2_f2inv`, `lab2_f1bdead`, `lab2_f1binv` |
| `scenarios_lab3.json` (16) | `train_scenarios_lab3.json` (10) | `lab3`, `lab3_slow`, and the fault labs `lab3_f1dead`, `lab3_f1inv`, `lab3_f2dead`, `lab3_f2inv`, `lab3_f1dead_z2`, `lab3_f1inv_z2`, `lab3_f1bdead`, `lab3_f1binv`, `lab3_f2bdead` |
| `scenarios_lab3.json` (16) | `train_scenarios_lab3_lowsun.json` (10) | `lab3_f2dead_lowsun` (test file of `lab3`, training file with the sun pinned, section 5) |
| `scenarios_lab4.json` (16) | `train_scenarios_lab4.json` (10) | `lab4` |
| `scenarios_lab4dual.json` (16) | `train_scenarios_lab4dual.json` (10) | `lab4dual` |
| `scenarios_lab4chain.json` (16) | `train_scenarios_lab4chain.json` (10) | `lab4chain` |
| `scenarios_lab4chain3.json` (8) | `train_scenarios_lab4chain3.json` (6) | `lab4chain3` |
| `scenarios_lab5.json` (16) | `train_scenarios_lab5.json` (10) | `lab5` |
| `scenarios_labmon.json` (2) | `train_scenarios_labmon.json` (6) | `labmon`, `labmon_infoonly`, `labmon_nostereo`, and the fault labs `labmon_f1dead`, `labmon_infoonly_f1dead`, `labmon_nostereo_f1dead` |
| `scenarios_labmon2.json` (16) | `train_scenarios_labmon2.json` (10) | `labmon2`, `labmon2_infoonly`, `labmon2_nostereo` |
| `scenarios_labmon2.json` (16) | `train_scenarios_labmon2_lowsun.json` (10) | `labmon2_f2dead_lowsun`, `labmon2_infoonly_f2dead_lowsun`, `labmon2_nostereo_f2dead_lowsun` |
| `scenarios_labrel0.json`, `_labrel4`, `_labrel8`, `_labrel16`, `_labrel8s` (2 each) | `train_scenarios_labrel0.json` and so on (2 each, the same two situations) | `labrel0`, `labrel4`, `labrel8`, `labrel16`, `labrel8s` |
| `scenarios_labband.json` (16) | `train_scenarios_labband.json` (10) | `labband` |

Files that no lab profile names:

| File | What it is | Who uses it |
|---|---|---|
| `scenarios_labrel.json`, `train_scenarios_labrel.json` | The shared master copy of the two `labrel` situations. The files of `labrel0`, `labrel4`, `labrel8` and `labrel16` are byte-copies of it (`labrel8s` has its own, with eight more device fields). | `analysis/phase1b_models.py` (the model of the reachability certificates, see [`../config/README.md`](../config/README.md)) and `analysis/tests/test_phase1b_scenario_identity.py` (which checks that the four copies equal the master) |
| `train_scenarios.json` | A 14-situation schedule with the fields of `lab3` (two zones, spotlight). | the training program's random fallback (section 3) |

## 5. The low-sun variants

`train_scenarios_lab3_lowsun.json` and `train_scenarios_labmon2_lowsun.json` are the training
files of the Phase-2 low-sun fault labs (`lab3_f2dead_lowsun`, `labmon2_f2dead_lowsun` and the
two `labmon2` variants). In such a lab both task lamps are broken and the sun is low, so the
goal cannot be reached and the agent has to find the best it can do. Each file is derived
from its parent (`train_scenarios_lab3.json`, `train_scenarios_labmon2.json`) by
[`../analysis/build_lowsun_scenarios.py`](../analysis/build_lowsun_scenarios.py): the same
`id`s in the same order, the same switch states, but `Sunshine` is 100 in every situation and
the zone levels are recomputed with the healthy physics of the parent lab. All ten situations
of each file have `Sunshine` 100. The pin has to be in the file because the training program
starts every episode from the file; the low-sun flows (`simulator_flow_lab3_f2dead_lowsun.json`,
`simulator_flow_labmon2_f2dead_lowsun.json`) also fix the sun at 100 in their episode reset,
but the file is what sets it. `py -3 analysis/build_lowsun_scenarios.py --check` checks that the committed
files equal what the script derives. The test files are the unchanged parent test files.
