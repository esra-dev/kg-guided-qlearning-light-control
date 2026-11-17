# Configuration

This folder holds the settings and the reference data that the runners, the build and the
analysis scripts read. Nothing here is code. The words (arm, layer, run mode, profile,
family) are defined in [`../docs/VOCABULARY.md`](../docs/VOCABULARY.md); the campaigns that
use the settings are described in [`../docs/EXPERIMENTS.md`](../docs/EXPERIMENTS.md); the
rooms are described in [`../docs/LABS.md`](../docs/LABS.md). This page does not repeat them.
It says which key does what, and which program reads it.

| Path | What it is | Read by |
|---|---|---|
| `run_config.json` | Every setting of a run: the run modes (`profiles`), the learning settings, the ports and flow files of the simulators, and one block per phase. Starts with a UTF-8 byte-order mark (the PowerShell runners need it; in Python read it with `encoding="utf-8-sig"`). | the three runners, `build.gradle`, many analysis scripts (below) |
| `run_config.schema.json` | A JSON Schema of the run modes and the shared blocks. | nothing in the repository; see section 7 |
| `golden_registry/` | The reviewed lists of actions that the agent discovers for each room. | Gradle task `verifyActionRegistry` (section 6) |
| `reachability_certificates/` | One proof file per Phase-1b test situation that the goal can be reached. | written and checked by `analysis/generate_phase1b_certificates.py` (section 8) |
| `knowledge_provenance.csv` | Which layer of knowledge each knowledge-graph term belongs to. | `analysis/tests/test_knowledge_provenance.py` (section 9) |

## 1. How `run_config.json` is read

- **`run_full_project.ps1`** (Phase 1, Phase 1b, Phase 4) loads one run mode by name
  through [`../scripts/Read-RunConfig.ps1`](../scripts/Read-RunConfig.ps1) (`-RunMode`).
  The script checks that the nine required keys of the mode exist and that the numeric ones are not negative,
  merges the mode's `learning_overrides` over the shared `learning` block, and returns the
  result. The runner turns the settings into Gradle `-P` properties and into edits of the
  agent programs (section 3).
- **`run_phase2_adapt.ps1`** reads the `phase2` block directly and resolves its learning
  settings through `Read-RunConfig.ps1 -AdaptSettingsProfile` (section 4).
- **`run_phase3_dynamics.ps1`** reads the `phase3` block directly.
- **`build.gradle`** (task `preflight`) reads `simulator_port_map` to find the port of each
  room it checks. It forwards the `-P` properties that the runners build to the agent's
  JVM through an allow-list; a property that is not on the list is dropped.
- **Python** reads blocks for the analysis: `phase2` (`analysis/phase2_recovery.py`,
  `validate_phase2_v2_archive.py`, `validate_phase2_qgap_inputs.py`), `phase3`
  (`phase3_dynamics.py`, `validate_phase3_v2_archive.py`), `phase4` (`phase4_energy.py`,
  `phase4_v2_registered_family.py`), `phase1b` (`scripts/make_labs_doc.py`), the run modes (`validate_phase1b_archive.py` reads the
  `phase1b_v2_extended` settings), and the maps of rooms (`scripts/make_labs_doc.py`).
- The Java classes do not open the file. `LabEnvironment` only receives the `http_client`
  numbers, and `QLearner` and `StereotypeReasoner` the learning numbers, as Java system
  properties that the runners pass in.

## 2. Key groups

A key that nothing reads is marked **unread**; the list is repeated in section 7.

| Key | Meaning | Read by |
|---|---|---|
| `$schema` | Link to `run_config.schema.json`. | **unread** (no program validates the file) |
| `description` | One sentence on what the file is. | nobody (text) |
| `profiles` | The run modes, keyed by name (section 3). | `Read-RunConfig.ps1` |
| `train_scenarios_alias` | Maps four Phase-2 variant labs (`labmon_infoonly`, `labmon_nostereo`, `labmon2_infoonly`, `labmon2_nostereo`) to the base lab whose training scenario file they share. | `run_full_project.ps1` (records the scenario schedule of a profile) |
| `profiles_to_run` | The Phase-1 labs: `lab1`, `lab2`, `lab3`. | `scripts/make_labs_doc.py`, a test (`test_runner_allowlists.py`); `Read-RunConfig.ps1` passes it on, but the runner keeps its own list |
| `stereo_modes`, `bench_modes` | `["true","false"]` and `["rule_based","ql_false","ql_true"]`. | **unread**: `Read-RunConfig.ps1` passes them on and no caller uses them; the runners write the same lists in code |
| `simulator_port_map` | Port of the simulator of each clean lab (1892 and up; one port per lab, shared by labs that share a flow). | `build.gradle` (`preflight`), `scripts/make_labs_doc.py`. `run_full_project.ps1` keeps an equal table of its own (`$Simulators`) |
| `simulator_flow_map` | Flow file (in [`../simulator/`](../simulator/README.md)) of each clean lab. | `scripts/make_labs_doc.py`; the Phase-1 runner has its own table |
| `qtable_suffix_map` | File-name suffix of the learned files of each lab (for example `_lab2`, giving `qtable_final_stereotypes_true_lab2.csv`). | `Read-RunConfig.ps1` passes it on; the Phase-1 runner has its own table; the archive scripts use the `phase2` copy |
| `expected_state_vec_dim` | Length of the state vector of each lab. | `scripts/make_labs_doc.py` (printed in `LABS.md`); not checked at run time |
| `http_client` | Timeouts and retries of the agent's HTTP calls to the simulator: `connect_timeout_ms`, `response_timeout_ms`, `max_retries`, `backoff_base_ms`. | `run_full_project.ps1` (passed as `-Psim.http.*`; `LabEnvironment` reads them) |
| `learning` | Learning settings shared by all run modes (section 3). | `run_full_project.ps1` |
| `phase1b` | The Phase-1b labs and arms (section 5). | `scripts/make_labs_doc.py`; `phase1b_profiles` also names the labs of the campaign |
| `phase2` | The fault stage (section 4). | `run_phase2_adapt.ps1`, analysis scripts |
| `phase3` | The slow-device stage (section 5). | `run_phase3_dynamics.ps1`, analysis scripts |
| `phase4` | The dependency and energy labs (section 5). | `analysis/phase4_energy.py` and the Phase-4 analysis |
| `dry_run` | `false`. | **unread**: `Read-RunConfig.ps1` returns it and `run_full_project.ps1` never looks at it. The runner's `-DryRun` switch is separate. |

## 3. Run modes (`profiles`) and the `learning` block

A **run mode** is one entry of `profiles`. It fixes the size of a training run and, with
`learning_overrides`, which knowledge the knowledge arm (`ql_true`) is given. The plain
arm (`ql_false`) is the same in every mode. The meaning of the knowledge settings is in
[`../docs/EXPERIMENTS.md`](../docs/EXPERIMENTS.md) (section 3, the table of run modes) and
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) (section 5.4). Choose a mode with
`run_full_project.ps1 -RunMode <name>`; the Phase-1, Phase-1b and Phase-4 workflows pass the
same name.

The nine keys that every mode must have, and where the runner puts them:

| Key | Meaning | Where it ends up |
|---|---|---|
| `tick` | Seconds between two updates of the simulator, as a string. | written into the `repeat` field of every flow file before the simulators start (the file keeps the new value) |
| `num_episodes` | Training episodes. | `num_episodes(...)` of the training program and the episode budget in `lab_profiles.asl` (both restored after the run) |
| `max_steps_per_episode` | Longest training episode. | `max_steps_per_episode(...)` of the training program |
| `action_delay_ms` | Pause after an action during training; larger than the tick. | `action_delay_ms(...)` of the training program |
| `exec_delay_ms_ql`, `exec_max_steps_ql` | Pause and step limit of the greedy run of the trained policy at the end of training. | `exec_delay_ms(...)`, `exec_max_steps(...)` of the training program |
| `bench_runs` | Repeats of each test situation. | `bench_runs(...)` of the test program |
| `exec_max_steps_bench`, `exec_delay_ms_bench` | Step limit and pause in the test. | `exec_max_steps(...)`, `exec_delay_ms(...)` of the test program |

Optional keys: `note` (text), `protocol_version` (`phase1-v2`; passed as
`-Pphase1.protocolVersion`; makes the loaders of the scenario files strict), `metric_schema`
(`phase1-benchmark-v2`; no runner reads it, a test checks its value), `policy_energy_weights` (passed as
`-Pphase1.policyEnergyWeights`; the weights of the energy measure in the test files) and
`learning_overrides`.

The 13 run modes of the file:

| Run mode | Size | What `learning_overrides` change, compared with the shared `learning` block |
|---|---|---|
| `dev` | 50 episodes, 2 test repeats, 65 ms pauses | nothing: a quick local check that uses the shared block as it is (reward shaping `pbrs`, adaptive trust on) |
| `phase1_v2_kg_only` | 3000 episodes, 20 steps, 5 test repeats, 100 ms test pauses | reward shaping `none`, adaptive trust off: full knowledge only |
| `phase1_v2_redundancy_only` | as above | as `kg_only`, plus `stereo_redundancy_only: true` |
| `phase1_v2_brick_only` | as above | as `kg_only`, plus `stereo_knowledge_layer: brick` |
| `phase1_v2_baseline` | as above | knowledge strength 0 (`stereo_prior_scale`, `stereo_init_bonus`), shaping `none`, trust off |
| `phase1_v2_pbrs_only` | as above | as `baseline`, but shaping `pbrs` |
| `phase1b_v2_baseline` | as above, plus `policy_energy_weights` | as `phase1_v2_baseline` |
| `phase1b_v2_redundancy_only` | as above | as `phase1_v2_redundancy_only` |
| `phase1b_v2_kg_frozen` | as above | as `phase1_v2_kg_only` |
| `phase1b_v2_extended` | as above | as `kg_frozen`, plus `stereo_irrelevant_dv_prior` 2.0, `stereo_band_mirror_init` 5.0, `stereo_band_mirror_prior` 2.0 |
| `phase4_v2` | as above, with the Phase-4 `policy_energy_weights` | as `kg_only`, plus `stereo_energy_prior_weight` 2.0 |
| `phase4_v2_redundancy_only` | as `phase4_v2` | as `phase4_v2`, plus `stereo_redundancy_only: true` |
| `phase4_v2_brick_only` | as `phase4_v2` | as `phase4_v2`, plus `stereo_knowledge_layer: brick` |

The `learning` block holds the defaults; a mode's `learning_overrides` win. Each key is
turned into a Gradle property by `run_full_project.ps1` and read by `QLearner` or
`StereotypeReasoner` as a system property:

| `learning` key | Gradle property | Meaning |
|---|---|---|
| `reward_clip` | `reward.clip` | Largest absolute reward per step (200 in the file; the Java default is 50) |
| `stereo_prior_scale` | `stereo.priorScale` | Strength of the soft preference among actions (0 switches it off) |
| `stereo_prior_decay_episodes`, `stereo_prior_decay_floor` | `stereo.priorDecayEpisodes`, `stereo.priorDecayFloor` | Length and end value of the fade of the preference |
| `stereo_prior_redundant`, `stereo_prior_iv_unsat` | `stereo.priorRedundant`, `stereo.priorIVUnsat` | Size (1.0 and 5.0) of the preference against an action that is already in the commanded state, and against switching on an action whose independent variable is not satisfied (for example blinds up with no sun) |
| `stereo_redundancy_only` | `stereo.redundancyOnly` | Knowledge layer `redundancy` (only the redundant-action rule) |
| `stereo_knowledge_layer` | `stereo.knowledgeLayer` | `full`, `brick` or `redundancy`; the layer overrides `stereo_redundancy_only` |
| `stereo_init_bonus` | `stereo.initBonus` | Optimistic starting value, per rank of remaining gap, for an action that the graph says should help (15.0) |
| `cross_zone_bonus` | `stereo.crossZoneBonus` | Exploration bonus for actions in the other zone (0 = off) |
| `stereo_energy_prior_weight` | `stereo.energyPriorWeight` | Weight of the energy preference (0 = off) |
| `stereo_irrelevant_dv_prior`, `stereo_band_mirror_init`, `stereo_band_mirror_prior` | `stereo.irrelevantDvPrior`, `stereo.bandMirrorInit`, `stereo.bandMirrorPrior` | The three extra settings of the Phase-1b extended arm (0 = off) |
| `reward_shaping` | `reward.shaping` | `none` or `pbrs` (potential-based shaping) |
| `adaptive_trust`, `adaptive_trust_min_samples`, `adaptive_trust_floor` | `stereo.adaptiveTrust`, `stereo.adaptiveTrust.minSamples`, `stereo.adaptiveTrust.floor` | Scales the preference for an action by how often the graph's predicted direction of change matched the observed one, once the action has been seen `min_samples` times, never below `floor` |

After every training cell the runner reads the settings that the JVM reports in its log
(line `STEREO_EFFECTIVE_PROPS`) and checks them against what it requested, so a setting that
the build silently dropped is caught. The result is recorded in `TRAINING_OK.json`.

The runner also knows the optional `-RunSeed` option; it is not a key of the file.

## 4. Phase 2: the `phase2` block and the fault-stage settings profiles

| Key | Meaning |
|---|---|
| `description` | Text. |
| `adapt_profiles` | The 23 labs with a broken device, in the order the runner takes them. Each name is `<parent lab>_<fault>`: `f1` one device, `f2` two devices; `dead` gives no light, `inv` gives the opposite effect, a `b` after `f1` or `f2` means the blinds; `_z2` the second zone; `_lowsun` a sun pinned at 100 lux; `labmon_infoonly` and the like are the monitor variants. The physics of each is in `LABS.md`. |
| `adapt_modes` | `["ql_true","ql_false"]`. **Unread**: the runner takes the arms from its `-Modes` option. |
| `smoke_episodes` | Fault-stage episodes when the runner is started with `-Smoke` (200). |
| `parent_profile` | The healthy lab each fault lab comes from. Used for messages by the runner and for checks by the analysis scripts. |
| `train_scenarios_map` | The training scenario file of each fault lab (see [`../benchmark/README.md`](../benchmark/README.md)). Used by the archive validators. |
| `simulator_port_map`, `simulator_flow_map` | Port (that of the parent) and faulty flow file of each fault lab. The runner starts that flow. |
| `qtable_suffix_map` | Suffix of the learned files of each fault lab. |
| `clean_source_suffix` | Suffix of the healthy lab whose Q-table the fault stage loads as its starting point (`qtable_final_stereotypes_<bool><suffix>`). |
| `expected_state_vec_dim` | State-vector length of each fault lab (printed in `LABS.md`). |
| `adapt_settings` | The named learning settings of the fault stage (below). |

`adapt_settings` has a `default` (`record`) and three profiles. A profile has `properties`
(Gradle properties passed to the fault-stage JVM exactly as written, so every value is a
string) and `expected` (the values the JVM must report; the runner compares them after each
cell and writes no `ADAPT_OK` receipt on a mismatch). `Read-RunConfig.ps1` refuses a profile
that sets `profile`, `mode`, `adapt.episodes`, `run.seed`, `stereo.knowledgeLayer` or
`stereo.priorDecayEpisodes`, or whose `prior_decay` is not `auto` (the fade of the preference
is a quarter of the fault-stage budget). The shared `learning` block is not used here.

| Profile | What it sets |
|---|---|
| `record` | reward limit 50, shaping `none`, preference strength 1.0, trust off, each arm has its own random stream; these are the settings that every archived fault-stage run used |
| `paired` | as `record`, but both arms use the same random stream (`phase2.pairedArmRng`) |
| `aligned` | as `paired`, with the reward limit of healthy training (200) |

Select with `run_phase2_adapt.ps1 -SettingsProfile` or the input `settings_profile` of
`phase2.yml`.

## 5. Phases 1b, 3 and 4

**`phase1b`**: `phase1b_profiles` lists the seven labs (`labrel0`, `labrel4`, `labrel8`,
`labrel16`, `labrel8s`, `labband`, `lab4chain3`); `scripts/make_labs_doc.py` reads it.
`phase1b_modes` lists the four run modes of the campaign and is **unread**; `bench_modes`
repeats the test arms and is **unread**; `note` is text. The labs to run and the mode are
chosen by the inputs of `phase1.yml`.

**`phase3`** (read by `run_phase3_dynamics.ps1`):

| Key | Meaning |
|---|---|
| `dynamics_profiles`, `dynamics_modes` | The two slow labs (`lab2_slow`, `lab3_slow`) and the arms, used when the runner gets no `-DynamicsProfiles` or `-Modes`. |
| `parent_profile`, `simulator_port_map`, `simulator_flow_map`, `qtable_suffix_map`, `expected_state_vec_dim` | As in `phase2`, for the two slow labs. |
| `seconds_per_tick` | Seconds that one simulator tick stands for (5.0); the runner passes it to the agent as `-Pseconds.per.tick`. |
| `blind_delay_ticks` | The blind delay in ticks (12). The runner reads the delay that the started flow really applies (`var DELAY = <ticks>;`) and stops when it differs. |
| `blind_delay_tolerance_ticks` | Largest accepted distance between a learned and the true blind delay (1.0); the runner checks every learned delay against it. |
| `smoke_probes` | Delay samples per device with `-Smoke` (3). |
| `probe.probes_per_actuator`, `probe.settle_ms`, `probe.poll_ms`, `probe.max_wait_ticks` | Samples per device (8), settling time (400 ms), polling interval (50 ms) and longest wait (60 polls) of the delay measurement. Passed to the agent as `-Pprobe.*`. |
| `probe.target_rank`, `probe.probe_sun_rank` | **Unread.** The dynamics agent pins the sun at 900 lux through its own `probe_baseline` facts. |
| `timebounded_goals` | The six goals, all with target rank 3: zone 1 with deadlines of 15, 45, 90 and 300 seconds, zone 2 with 15 and 300 seconds. **Unread**: the agent program holds the same list as `tb_goal/4` facts, and the two are kept equal by hand. |
| `timebounded_goals_note`, `blind_delay_tolerance_note`, `description` | Text. |

**`phase4`**:

| Key | Meaning | Read by |
|---|---|---|
| `phase4_profiles` | The four labs (`lab4`, `lab4dual`, `lab4chain`, `lab5`). | `analysis/phase4_energy.py` |
| `power_formula` | The power of a switch configuration as a weighted sum, e.g. `Z1Eff*1 + Z1Ineff*4 + ... + Spotlight*2`. | `phase4_energy.py`, `phase4_v2_registered_family.py` |
| `energy_budget_default` | Energy budget of a test situation that has none (2). | the same two scripts |
| `phase4_modes`, `run_mode` | `["rule_based","ql_false","ql_true"]` and `phase4_v2`. | **unread**: `phase4.yml` has its own defaults |
| `simulator_port_map`, `simulator_flow_map`, `qtable_suffix_map`, `expected_state_vec_dim` | As in `phase2`, for the four labs. | `scripts/make_labs_doc.py` |
| `description` | Text. | nobody |

## 6. Golden action registries (`golden_registry/`)

The agent builds its list of actions from the room's knowledge graph and the device
description, not from a fixed table (see [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)).
A change to a graph file or to the discovery code could silently add, drop or change an
action, and with it change what the archived experiments mean. The golden registries are the
reviewed list of what the discovery returns for each room.

- `registry_<set>.csv`: one file per set of graph files, named after a representative
  lab (`lab1` to `lab5`, `lab4dual`, `lab4chain`, `lab4chain3`, `lab2_slow`, `lab3_slow`,
  `labmon` and `labmon2` with their `_infoonly` and `_nostereo` variants, `labrel0` to
  `labrel16`, `labrel8s`, `labband`). The set `custom` belongs to the older
  graph files `lab-ontology.ttl` and `wot-mappings.ttl` in `src/resources/`; no lab profile
  uses it, but its list is still checked.
- `brick_only/registry_<set>.csv`: eight files, the same discovery under the knowledge layer
  `brick` (`lab1`, `lab2`, `lab3`, `lab4`, `lab4dual`, `lab4chain`, `lab5`, `labmon2`).
- Format: two comment lines, a header, then one row per action, sorted by key. The key is
  the simulator command with `|ON` or `|OFF`, or `DO_NOTHING`. The columns are `label`,
  `wotStateType` (the status field the action sets), `svBit` (its position in the state
  vector), `expectedBit` (the value it sets), `zones`, `hasIV` / `ivIdx` / `ivMinRank` (the
  independent variable it depends on, its state-vector slot and lowest effective rank),
  `energyCost`, `kgSilent` (the graph says nothing about this action), `relevance`,
  `illumDirection`, `ivGates` (power or sun gates as `slot>=minimum`) and `actionIndex`.
  The index is for information only.

**Check:** `.\gradlew.bat verifyActionRegistry` runs `tools.RegistryGoldenCheck`. It
recomputes the registry of every set with the current code and compares it with the file:
the key sets must be equal, and every column except `actionIndex` must be equal. A moved
index is reported but allowed, because all stored per-action files are matched by label when
they are loaded. A difference exits with status 1. The task is part of `preflight`.
`.\gradlew.bat dumpActionRegistry` (class `tools.ActionRegistryDump`) rewrites the files;
run it only after an intended change, read the difference and commit it with the change.
`-PregistryGolden=<dir>` and `-PregistryOut=<dir>` select another folder.

## 7. The schema and the unread keys

`run_config.schema.json` describes the file: the nine required keys of a mode, their types,
the allowed `learning_overrides` keys, the three maps and `http_client`. It allows other
top-level keys, so the phase blocks are not checked. No program in the repository loads it,
so the file is a guide for an editor, not a gate. The one real check of the file is the set
of tests in `analysis/tests/` that read it (for example `test_phase1_v2_config.py`,
`test_phase2_adapt_settings.py`, `test_phase4_v2_ladder_config.py`).

Keys that no program reads:
`$schema`, `dry_run`, `stereo_modes`, `bench_modes`, `phase1b.phase1b_modes`,
`phase1b.bench_modes`, `phase2.adapt_modes`, `phase3.probe.target_rank`,
`phase3.probe.probe_sun_rank`, `phase3.timebounded_goals`, `phase4.phase4_modes`,
`phase4.run_mode`, and every `note` and `description`. The run-mode key `metric_schema` is read by tests only.
The top-level `simulator_port_map`, `simulator_flow_map` and `qtable_suffix_map` are not
read by `run_full_project.ps1`, which holds its own tables; `build.gradle` and
`scripts/make_labs_doc.py` read them, so they have to stay equal to the runner's.

## 8. Reachability certificates (`reachability_certificates/`)

Before a Phase-1b campaign, every test situation of its seven labs is checked by exhaustive
search: from the start state, over all sequences of switch commands up to the step limit of
20, is the goal reached, by which shortest routes, and does the intended mechanism lie on
every shortest route? `analysis/generate_phase1b_certificates.py` does this with the model
in `analysis/phase1b_models.py` and `analysis/transition_checker.py`, and writes:

- `<lab>/scenario_<id>.json`: the result (`solvable`, `shortest_path_length`, the shortest
  command sequences, the reachable states, `ambiguous`, `shortcuts`, and so on) and its own
  `certificate_sha256`;
- `INDEX.csv`: one row per certificate (`profile`, `scenario_id`, `certificate_sha256`,
  `verdict`, `reason`, `shortest_len`, `shortest_count`, `reachable_states`).

A certificate passes only if the goal is reachable, the route is not ambiguous, there is no
unintended shortcut, and the intended mechanism is on every shortest route. The folder holds
34 certificates (`labrel0`, `labrel4`, `labrel8`, `labrel16`, `labrel8s`: 2 each; `labband`:
16; `lab4chain3`: 8), all with verdict `PASS`. The script exits with 1 when one fails, which
blocks the campaign. It is started by hand; no workflow runs it. The SHA-256 of `INDEX.csv`
that the registration cites is in
[`../docs/registrations/phase1b_registration_2026-07-26.md`](../docs/registrations/phase1b_registration_2026-07-26.md).

## 9. Knowledge provenance (`knowledge_provenance.csv`)

One row per knowledge-graph term or derived classification, with the columns `term`,
`layer`, `layer_name`, `claim_wording`, `status` and `notes`. There are 22 rows. The layers
are 1 (paper-aligned stereotype), 2 (system topology), 3 (instance metadata), 4
(project-specific extension), 5 (learned enrichment) and `derived` (a classification
computed from the others, or a diagnostic). `claim_wording` is the phrase a result must use
when it attributes an effect to that layer. The binding rules and the reasons are in
[`../docs/KNOWLEDGE_PROVENANCE.md`](../docs/KNOWLEDGE_PROVENANCE.md).

`analysis/tests/test_knowledge_provenance.py` checks that the file is well formed (every
term once, a valid layer, a claim wording), that the terms of the Phase-1b extension are
classified, and that every tracked predicate used in a `building_*.ttl` file has a row. A
new term therefore needs its row in the same change.
