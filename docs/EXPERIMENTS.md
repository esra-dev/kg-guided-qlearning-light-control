# Experiments

This document explains how the experiments are organised: the phases, the arms and run
modes, seeds and pairing, the GitHub Actions workflows that run them, the files and
receipts they produce, and how a campaign is registered, archived and pinned. How the
parts of the program work is in [ARCHITECTURE.md](ARCHITECTURE.md); the rooms are in
[LABS.md](LABS.md); how to re-check the stored results is in
[REPRODUCING.md](REPRODUCING.md). This document contains no results.

## 1. Words

| Word | Meaning |
|---|---|
| Step | One action of the agent and the room's answer. |
| Episode | One attempt from a start situation; at most 20 steps (`max_steps_per_episode`). |
| Training | 3,000 episodes with learning on (`num_episodes` of the run mode). |
| Test run | One attempt of a trained agent from a test situation with exploration off; each test situation is played `bench_runs` (5) times. |
| Arm | One side of a comparison: `ql_true` (with the knowledge-based starting values and preference) or `ql_false` (without). In the test, `rule_based` runs beside them. |
| Seed | The number `run.seed` that fixes the agent's random choices (section 4). |
| Cell | One combination of lab, arm (or test contestant) and seed. One cell is one job on GitHub Actions. |
| Run | One execution of a workflow on GitHub Actions, identified by its run id. |
| Campaign | The runs that together answer one registered study, archived together under `results/<campaign>/`. |
| Run mode | A named bundle of settings in `config/run_config.json` (`profiles`), chosen with `-RunMode` or the workflow input `run_mode`. |

## 2. The phases

| Phase | Question | Labs | Runner | Workflow |
|---|---|---|---|---|
| 1 | In healthy rooms, does the knowledge make learning faster? | lab1, lab2, lab3 | `run_full_project.ps1` | `phase1.yml` |
| 1b | Which kind of knowledge matters, in rooms built to isolate one mechanism each? | labrel0, labrel4, labrel8, labrel16, labrel8s, labband, lab4chain3 | `run_full_project.ps1` | `phase1.yml` |
| 2 | When a device breaks, does the agent detect it, take it out of use and learn again? | the fault variants of lab1, lab2, lab3, labmon, labmon2 | `run_full_project.ps1` (parents), then `run_phase2_adapt.ps1` | `phase2.yml` |
| 3 | Can the agent learn a response delay and use it to meet deadlines? | lab2_slow, lab3_slow | `run_phase3_dynamics.ps1` | `phase3.yml` |
| 4 | Does knowledge of hidden dependencies (plugs) and energy costs help? | lab4, lab4dual, lab4chain, lab5 | `run_full_project.ps1` | `phase4.yml` |

The labs and the configuration key that assigns them to each phase are listed in
[LABS.md](LABS.md). The registered hypotheses, outcome measures and analyses of each
campaign are in its registration in `docs/registrations/` (index in [README.md](README.md)).

### What a cell does in each phase

- **Phase 1, 1b and 4.** A training cell trains one arm in one lab with one seed and
  writes its Q-tables and metrics. A test cell loads the two trained tables of its lab and
  seed and plays the test situations as `ql_true`, `ql_false` or `rule_based`.
- **Phase 2.** First, healthy *parent* labs are trained exactly like Phase-1 training cells
  (run mode `phase1_v2_kg_only`). Then each fault cell loads the parent table of its arm
  and seed into the faulty lab and runs the fault stage described in
  [ARCHITECTURE.md](ARCHITECTURE.md), section 6.
- **Phase 3.** No table is trained. A cell measures the delays of the devices and plans the
  six deadline goals; `ql_true` plans with the measured delays, `ql_false` without. Cells
  are repeated as *replicas* instead of seeds, because the only variation is timing.

## 3. Arms and run modes

The plain arm `ql_false` is the same in every run mode: zero starting values, no preference.
What the knowledge arm `ql_true` receives depends on the run mode. The run modes used by
the accepted campaigns (all with protocol version `phase1-v2`, 3,000 training episodes,
20 steps per episode, 5 test runs per situation, reward limit 200):

| Run mode | What `ql_true` gets | Used by |
|---|---|---|
| `phase1_v2_kg_only` | full knowledge: starting values and preference from all layers | Phase 1; Phase 2 parent training |
| `phase1_v2_redundancy_only` | only the redundant-action rule (`stereo_redundancy_only: true`) | Phase 1 |
| `phase1_v2_brick_only` | redundant-action rule plus Brick structure (`stereo_knowledge_layer: brick`) | Phase 1 |
| `phase1_v2_baseline` | nothing: knowledge strength 0 (`stereo_prior_scale: 0`, `stereo_init_bonus: 0`); a check that the comparison itself has no bias | Phase 1 |
| `phase1_v2_pbrs_only` | nothing, as above, but both arms get potential-based reward shaping (`reward_shaping: pbrs`); a check of the shaping wiring | Phase 1 |
| `phase1b_v2_baseline`, `phase1b_v2_redundancy_only`, `phase1b_v2_kg_frozen` | nothing; the redundant-action rule; full knowledge exactly as in `phase1_v2_kg_only` | Phase 1b |
| `phase1b_v2_extended` | full knowledge plus three extra settings about irrelevant devices and the direction of effects (`stereo_irrelevant_dv_prior`, `stereo_band_mirror_init`, `stereo_band_mirror_prior`) | Phase 1b |
| `phase4_v2` | full knowledge plus the energy preference (`stereo_energy_prior_weight: 2.0`) | Phase 4 |
| `phase4_v2_redundancy_only`, `phase4_v2_brick_only` | as `phase4_v2`, cut down to the redundant-action rule, or to the rule plus Brick structure | Phase 4 |

The ladder `redundancy` → `brick` → `full` is explained in
[ARCHITECTURE.md](ARCHITECTURE.md), section 5.4. The Phase-4 run modes and the Phase-1b
modes also set `policy_energy_weights`, the weights of the deterministic energy measure
`PolicyEnergyCost` in the test files.

Phase 2 has two further switches for the fault stage, given to `run_phase2_adapt.ps1`
and to the workflow `phase2.yml`:

- `-KnowledgeLayer` / input `knowledge_layer` (`full`, `brick`, `redundancy`): the
  knowledge layer of the whole fault stage, in both arms (detector and re-learning). The
  parent training always uses its run mode's own layer.
- `-SettingsProfile` / input `settings_profile` (`record`, `paired`, `aligned`; default
  `record`): the named learning settings of the fault stage in
  `config/run_config.json` (`phase2.adapt_settings`). `record` is what the archived
  campaigns used (reward limit 50, automatic fade, arm-specific random stream); `paired`
  gives both arms the same random stream; `aligned` additionally uses the training reward
  limit of 200.

`config/run_config.json` also contains the run mode `dev`, the default of
`run_full_project.ps1 -RunMode`; no accepted campaign uses it. The run modes of the time
before the corrected protocol (`paper`, `paper_h40`, `paper_h60`, `phase1`,
`phase1_baseline`, `phase1_kg_only` and its variants `_ib5`, `_e750`, `_e3000`,
`phase1_pbrs_only`, `phase1_full`, `phase1_kg_xzone`, `phase1_redundancy_only`, `phase4`)
have been removed; they are kept at the git tag `record-v2.5.0-pre-cleanup`.

## 4. Seeds and pairing

- **Seed.** `-RunSeed N` (workflow input `seeds`) becomes the property `run.seed`. The
  learner's random generator is used for the exploration draw and for breaking ties
  between actions of equal value. When the agent program configures the learner,
  `QLearner.computeActionSeed` seeds it with the base 42 combined with a hash of
  `run.seed` and, unless the arms are paired, with an arm constant.
- **Training: same random stream in both arms.** With protocol version `phase1-v2` (all
  accepted training run modes) the arm is not mixed into the seed, so `ql_true` and
  `ql_false` of the same seed start from the same random stream. The streams can drift
  apart once the arms make different choices. The receipt records this as
  `paired_rng_version: common-seed-v1`. Different seeds give different streams.
- **Test: one fixed seed.** The test program (`illuminance_controller_agent_bench.asl`)
  calls `setSeed(42)` right after configuring the learner, so every test run, of both
  arms and of every seed, uses a generator seeded with 42. Exploration is off in the test,
  so this generator only breaks ties. Test runs of different seeds differ because they
  load the tables trained with those seeds, not because of `run.seed`.
- **Same start situations in the same order.** Training takes the start situations of the
  lab's training file in file order, one per episode, cycling through the file; the test
  takes every test situation `bench_runs` times. The receipt records the ordered list of
  situation ids and its SHA-256 hash (`ordered_scenario_ids`, `scenario_schedule_sha256`).
  Situations are addressed by their position in the file, and a position outside the
  file stops the run. A random start (`setRandomLabState`) is used only for a lab profile
  without a training file; every lab of the accepted campaigns has one.
- **Seeds of the accepted campaigns.** 1 to 20 (Phase 3: replicas 1 to 10).
- **Fault stage.** The fault runner does not pass the protocol version, so in the fault
  stage the arm constant is mixed into the seed and the two arms of a seed draw from
  different random streams; they also start from different parent tables. "Same seed"
  there means the same seed number, the same fault and the same order of start
  situations.
  The settings profiles `paired` and `aligned` remove the arm constant
  (`phase2.pairedArmRng`).

## 5. The workflows

All four experiment workflows are started by hand (`workflow_dispatch`); none runs on a
schedule. Each has a `setup` job (compiles once, turns the inputs into matrices), one or
two matrix jobs of cells, and an `aggregate` job that collects all cell outputs, runs the
standard analysis, writes `analysis/out/workflow_inputs.json` (the run's dispatch
receipt) and uploads everything as one consolidated artifact.

| Workflow | Inputs (default) | Matrix jobs |
|---|---|---|
| `phase1.yml` | `profiles` (`lab1,lab2,lab3`), `seeds` (1..20), `run_mode` (`phase1_v2_kg_only`), `publish_results` (true), `phase1b_stage` (empty; `pilot` or `confirmatory` for Phase-1b modes) | `train`: lab × arm × seed; `bench`: lab × {`rule_based`, `ql_false`, `ql_true`} × seed |
| `phase2.yml` | `adapt_profiles` (15 fault labs), `seeds` (1..10), `run_mode` (`phase1_v2_kg_only`, for the parents), `adapt_episodes` (0 = profile budget), `knowledge_layer` (`full`), `publish_results` (true) | `train_clean`: parent lab × arm × seed; `adapt`: fault lab × arm × seed |
| `phase3.yml` | `dynamics_profiles` (`lab2_slow,lab3_slow`), `replicas` (1..10), `probes` (0 = 8 per device), `publish_results` (true) | `dynamics`: lab × mode × replica |
| `phase4.yml` | `profiles` (`lab4,lab4dual,lab4chain,lab5`), `seeds` (1..10), `run_mode` (`phase4_v2`), `publish_results` (true) | `train` and `bench` as in `phase1.yml` |

`phase2.yml` also has the input `settings_profile` (default `record`), see section 3.

Every cell job checks out the repository at the dispatched commit, installs Java 21,
Node.js 20 and Node-RED 4, and calls the runner with one lab, one arm and one seed, for
example (from `phase1.yml`):

```powershell
./run_full_project.ps1 -RunMode $RUN_MODE -OnlyProfiles <lab> -OnlyStereo <true|false> -RunSeed <seed> -SkipBenchmark -SkipPreflight
./run_full_project.ps1 -RunMode $RUN_MODE -OnlyProfiles <lab> -OnlyModes <mode> -RunSeed <seed> -SkipTraining -SkipPreflight
```

A Phase-2 fault cell first runs `./simulator/generate_faulty_flows.ps1` and then
`./run_phase2_adapt.ps1 -AdaptProfiles <lab> -Modes <mode> -Episodes <n> -RunSeed <seed> -KnowledgeLayer <layer>`;
a Phase-3 cell runs
`./run_phase3_dynamics.ps1 -DynamicsProfiles <lab> -Modes <mode> -Probes <n> -Replica <r>`.

Limits and timing:

- **Parallel jobs.** Each matrix job runs at most 10 cells at the same time
  (`max-parallel: 10`), except the Phase-3 matrix, which has no limit.
- **256 jobs per matrix.** GitHub Actions refuses a matrix with more than 256 jobs; the
  workflow then fails before any cell starts. A Phase-1b campaign has 7 labs × 2 arms ×
  20 seeds = 280 training cells, so every Phase-1b mode was dispatched as two runs with
  seeds 1–10 and 11–20 (amendment A1 of
  `docs/registrations/phase1b_confirmatory_dispatch_record_2026-07-26.md`). The corrected
  Phase-2 and Phase-4 campaigns of July were also dispatched in seed halves.
- **Time-outs.** 180 minutes per training or fault cell, 90 minutes per test cell,
  30 minutes per Phase-3 cell. The workflow comments give about 50 minutes for one
  3,000-episode training cell on a GitHub runner.
- **Run time.** One example from the dispatch records: the Phase-2 low-sun run
  `43099277363` (80 parent-training cells and 80 fault cells) was dispatched on
  2026-11-11 at 20:59 UTC and completed on 2026-11-12 at 07:36 UTC
  (`docs/registrations/phase2_lowsun_rerun_dispatch_record_2026-11-11.md`).
- **Retention.** Cell and consolidated artifacts are kept for 90 days, the Phase-2 parent
  tables (`clean-*` artifacts) for 30 days (`retention-days` in the workflows).

### Starting a campaign

Campaigns are started from the command line with the GitHub CLI, from a branch that
contains the registered code. Two examples taken from the dispatch records:

```powershell
# Phase 2 structure-only campaign (docs/registrations/phase2_structonly_dispatch_record_2026-11-14.md)
gh workflow run phase2.yml --ref phase2-structonly-2026-11 -f adapt_profiles=lab2_f1bdead,lab3_f1bdead,lab3_f2dead_lowsun,labmon2_f2dead_lowsun -f seeds=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20 -f run_mode=phase1_v2_kg_only -f adapt_episodes=0 -f knowledge_layer=brick -f publish_results=true

# Phase 4, first seed half (docs/registrations/phase34_v2_dispatch_record_2026-07-22.md)
gh workflow run phase4.yml --ref phase234-correction-2026-07-22 -f profiles=lab4,lab4dual,lab4chain,lab5 -f seeds=1,2,3,4,5,6,7,8,9,10 -f run_mode=phase4_v2 -f publish_results=true
```

Each command is one line, so it works the same in PowerShell and in a Unix shell.

Replace the `--ref` value by the branch to run. `gh run list --workflow phase2.yml` shows
the run id, and `gh run rerun <run id> --failed` re-runs only failed jobs under the same
run id (used once for a dependency-download failure, see
`docs/registrations/phase1b_extended_rerun_dispatch_record_2026-11-11.md`). The input
names must match the workflow exactly; for example `phase3.yml` expects
`dynamics_profiles`, not `profiles`.

## 6. Where results go

1. Every cell uploads its files as a workflow artifact.
2. The `aggregate` job sorts them (for example `benchmark/results_seed<N>/<lab>/<mode>/`
   in Phases 1, 1b and 4, `recovery_root/seed<N>/` in Phase 2, `dynamics_root/rep<N>/` in
   Phase 3), runs the standard analysis into `analysis/out/` and uploads the consolidated
   artifact (`phase1-consolidated`, `phase2-consolidated`, ...).
3. With `publish_results=true` it also pushes a copy of the summary files to the branch
   `results`. Phases 1 and 4 do this through `scripts/version_artifacts.ps1`, which also
   creates a tag `results-<time>-<run mode>-<commit>`; Phases 2 and 3 copy their output
   into `phase2/<run id>-<time>/` or `phase3/<run id>-<time>/` on that branch without a tag.
   After the push the workflow checks that the tag exists on the remote and fails if it
   does not.
4. The consolidated artifact is downloaded and turned into an archive under
   `results/<campaign>/run_<run id>/` (section 8).

### Files of a cell

| File | Written by | Contents |
|---|---|---|
| `metrics_stereotypes_<arm>_<lab>.csv` | training | one row per episode: steps, reward per zone, goal reached, epsilon, penalised and no-effect steps, situation id |
| `qtable_initial_...`, `qtable_final_stereotypes_<arm>_<lab>[_zone<N>, _visits, _trust].csv` | training | the tables before and after training, per zone, with visit counts |
| `first_goal_stereotypes_<arm>_<lab>.csv` | training | per training situation: at which presentation the goal was first reached |
| `coverage_...csv`, `iv_stats_...json`, `learned_stereotypes_...ttl` | training | visit counts per state and action; the learner's own counts of when blinds and gated lamps worked; observed effects as Turtle |
| `benchmark_results_<mode>.csv` | test | one row per test run: `GoalReached`, `Steps`, `WastedSteps`, `ActuatorCyclingCount`, `CumIlluminanceDeviation`, `PolicyEnergyCost`, `LegacyWallClockTotalEnergyCost` (a timing-dependent diagnostic) and further columns |
| `bench_step_log_<mode>.csv` | test | one row per step: ranks before and after, action, switch positions |
| `recovery_stereotypes_<arm>_<lab>.csv` | fault stage | blamed device, `DetectEpisode`, `ReconvergeEpisode`, `RecoveryEpisodes`, goal rate of the closing check, original and lowered target, all blacklist events |
| `metrics_adapted_...csv`, `qtable_adapted_...csv` | fault stage | episode records and final table of the fault stage |
| `dynamics_delays_<arm>_<lab>.csv`, `timebounded_results_<arm>_<lab>.csv`, `learned_dynamics_<arm>_<lab>.ttl` | Phase 3 | measured delay per device; one row per deadline goal (chosen device, believed delay, time needed, deadline met, energy); delays as Turtle |

Here `<arm>` is `true` or `false` and `<lab>` the lab's suffix from
`qtable_suffix_map` in `config/run_config.json`.

### Receipts

A runner writes a receipt only for a cell that completed and passed its checks (one
exception: a delay cell whose learned blind delay fails the tolerance check writes its
receipt with `status` `delay_check_failed` and ends the run with an error). The archive
validators reject an archive in which a receipt is missing or wrong. Since the hand-in
fixes, every receipt also names the flow file the runner started (`simulator_flow`) and
the lab name that the simulator reported in its `/health` answer
(`simulator_profile_reported`); the archived receipts were written before and do not have
these keys.

- **`TRAINING_OK.json`** (`benchmark/results/<lab>/training_stereo_<arm>/`, written by
  `run_full_project.ps1` after every training cell): `status`, `profile`, `stereotype`,
  `run_seed`, `run_mode`, `protocol_version`, `ordered_scenario_ids`,
  `scenario_schedule_sha256`, `scenario_file`, `fixed_horizon_episodes`,
  `paired_rng_version`, `metric_schema`, `policy_energy_weights`,
  `stereo_requested_props` and `stereo_effective_props` (the Phase-1b settings as requested
  and as reported by the JVM), `scenario_fallback_count`, `timestamp`, and the list of
  `artifacts`. A test cell (`-SkipTraining`) refuses to start unless both training
  receipts of its lab exist with `status` `ok` and, for protocol-v2 run modes, the
  expected seed, horizon, pairing and schema.
  It also records the knowledge layer as requested and as delivered
  (`knowledge_layer_requested`, `knowledge_layer_delivered`), the reward limit and fade
  length read back from the JVM log (`reward_clip_effective`,
  `prior_decay_mode_effective`, `prior_decay_episodes_effective`) and the energy
  preference weight as requested and delivered; `scenario_fallback_count` is the number of
  random starts the JVM reported. A test cell writes no receipt of its own.
- **`ADAPT_OK_stereotypes_<arm><suffix>.json`** (root, written by `run_phase2_adapt.ps1`):
  `status` (`ok`, or `cold_start` when no parent table was found), `protocol_version`
  (`phase2-v2`), `detector_version` (`fault-detector-v2`), `metric_schema`,
  `settled_start_state`, `scenario_fallback_count`, `profile`, `parent_profile`, `mode`,
  `run_seed`, `knowledge_layer` and `knowledge_layer_delivered` (read from the JVM log),
  `adapt_episodes_effective`, `adapt_episodes_override`, `train_scenarios_file`,
  `ordered_scenario_ids`, `scenario_schedule_sha256`, `scenario_file_sha256`,
  `scenario_sunshine_values`, `lowsun_pin_delivered` (for the low-sun labs),
  `parent_qtable_files` and `parent_qtable_sha256`.
  It also records `settings_profile`, the `effective_settings` read back from the JVM log,
  `paired_arm_rng`, the exact parent files and their checksum
  (`parent_qtable_exact_files`, `parent_qtable_exact_sha256`; the older field
  `parent_qtable_sha256` hashes every file that matches a name pattern and can include
  other labs' tables), and the flow and the lab name the simulator reported. The runner
  reads the knowledge layer and the learning settings back from the JVM log; a mismatch
  with the requested values stops the cell before the receipt is written
  (`Knowledge-layer treatment NOT verified ...` or
  `Settings verification FAILED ...`).
- **`DYNAMICS_OK_<arm>_<lab>.json`** (root, written by `run_phase3_dynamics.ps1`):
  `status`, `protocol_version` (`phase3-v2`), `energy_meter` (`tick-v1`), `profile`,
  `mode`, `replica`, `blind_delay_ticks`, `seconds_per_tick`, `probes_override`.
  Before it starts a slow flow, the runner reads the true blind delay from the flow file
  (`var DELAY = <ticks>;`) and stops if it differs from `phase3.blind_delay_ticks` in
  `config/run_config.json` (`Blind delay mismatch ...`); `blind_delay_ticks` in the
  receipt is this value, `blind_delay_ticks_source` the flow file and
  `blind_delay_ticks_config` the configured value. The receipt also lists the learned
  delay of every device (`learned_delay_ticks`) and the result of the tolerance check
  (`blind_delay_tolerance_ticks`, from `phase3.blind_delay_tolerance_ticks`, 1.0 tick;
  `blind_delay_check` `pass` or `fail`; `blind_delay_max_abs_error_ticks`): every learned
  blind delay must lie within the tolerance of the true delay. The archived receipts
  were written before this check and do not have these keys.
- **`analysis/out/workflow_inputs.json`** (written by `analysis/write_workflow_provenance.py`
  in every aggregate job): `workflow`, `run_id`, `head_sha`, `ref`, `run_mode`,
  `profiles`, `seeds`, `publish_results` and, for Phase 2, `knowledge_layer`. In the
  archived campaigns the `workflow` field says `.github/workflows/phase1.yml` for every
  phase; the pins files record the workflow that really dispatched each run
  (`workflow_actual`).
  New runs record the real workflow.

## 7. Analysis in the aggregate job

| Workflow | Scripts |
|---|---|
| `phase1.yml` | `analysis/validate_phase1_v2_archive.py` (gate), `analysis/sweep_report.py`, `analysis/learning_curves.py`, `analysis/phase1_archive_inventory.py` (SHA-256 inventory) |
| `phase2.yml` | `analysis/phase2_recovery.py` |
| `phase3.yml` | `analysis/phase3_dynamics.py` |
| `phase4.yml` | `analysis/sweep_report.py`, `analysis/phase4_energy.py`, `analysis/learning_curves.py` |

`analysis/phase3_dynamics.py` reports Phase 3 descriptively (means, differences and
descriptive intervals, no p- or q-values), as its registration requires. The Phase-4
aggregate also validates the archive and writes its SHA-256 inventory, and
`analysis/phase4_energy.py` scores a lab5 test run that needs no step from its declared
start state.

These outputs are the workflow's standard summary. The registered result of a campaign is
computed afterwards, from the archive, by the campaign's registered analysis script (for
example `analysis/phase2_structonly_v3_family.py`) and written to
`results/<campaign>/analysis/registered/`.

## 8. Registering, archiving and pinning a new campaign

The accepted campaigns followed these steps; a new one should too.

1. **Register.** Write a registration in `docs/registrations/` before any data exist: the
   question, labs, arms and run mode, seeds, the exact workflow inputs, the outcome
   measures, the analysis script and decision rules, and the commit at which the code is
   frozen. Commit it, and let CI pass on that commit.
2. **Dispatch.** Start the run(s) with `gh workflow run` from a commit that contains the
   registration, and write a dispatch record (`docs/registrations/<...>_dispatch_record_<date>.md`)
   with the run ids, the head commit and the inputs. Pre-data failures and re-runs are
   added to the record as amendments; a cell that produced data is never replaced.
3. **Archive.** Download the consolidated artifact (`gh run download <run id> --name <artifact>`)
   into `results/<campaign>/run_<run id>/`. The `scripts/curate_*.ps1` scripts do this for
   the later campaigns. Write the SHA-256 inventory with
   `py -3 analysis/phase1_archive_inventory.py --root <run folder> --out <run folder>/SHA256SUMS.csv --include "analysis/**" ...`,
   run the phase's archive validator (`analysis/validate_phase1_v2_archive.py`,
   `validate_phase1b_archive.py`, `validate_phase2_v2_archive.py`,
   `validate_phase3_v2_archive.py`, `validate_phase4_v2_archive.py`), and describe the run
   in `ARCHIVE_MANIFEST.md` and the campaign in `CAMPAIGN_MANIFEST.md`. Large raw files
   (Q-tables, per-cell logs) are ignored by git; only the summary tables, small per-cell
   files, receipts, manifests and inventories are committed.
4. **Analyse.** Run the registered analysis script on the archive; it writes the
   registered tables into `results/<campaign>/analysis/registered/`. Add a reproduction
   script `analysis/reproduce_<campaign>.py` that recomputes those tables and compares
   them byte for byte.
5. **Pin.** Add `results/pins/<campaign>.json` (schema `thesis-pins-v1`, described at the
   top of `analysis/check_pins.py`): the receipts of every run, the key values of the
   registered tables, the inventories and the reproducer command. `check_pins.py` fails if
   any folder under `results/` has no pins file.
6. **Report and merge.** Write the report (in the archive's manifest or in `docs/reports/`),
   open a pull request, and merge when CI passes (`ci.yml` runs the unit tests, the Python
   tests, `check_pins.py` and the reproducers on tracked data).
