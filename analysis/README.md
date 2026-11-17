# Analysis scripts

This folder holds every Python program that turns the raw files of a run into a result
table, checks an archive, or rebuilds a stored table. The programs are grouped by job below.
Each group has one table; every row names what the script computes, which archive under
[`../results/`](../results/README.md) it works on, and who starts it.

What the archives contain is described in [`../results/README.md`](../results/README.md).
How the campaigns and registrations fit together is in
[`../docs/EXPERIMENTS.md`](../docs/EXPERIMENTS.md). How to re-check the stored numbers
step by step is in [`../docs/REPRODUCING.md`](../docs/REPRODUCING.md). The terms (arm,
layer, run mode, family) are defined in [`../docs/VOCABULARY.md`](../docs/VOCABULARY.md).
This page does not repeat them.

Conventions used in the tables:

- **Phase** names the experiment the script belongs to: Phase 1 (healthy rooms, learning
  speed), Phase 1b (follow-up with mechanism rooms), Phase 2 (broken devices), Phase 3
  (slow devices), Phase 4 (dependencies and energy).
- **Called by** is one of: a GitHub workflow (file under `.github/workflows/`), a
  reproducer (a `reproduce_*.py` script), another script (named), a test, or *by hand*
  (nothing calls it; the command line is in the script's docstring). For scripts run by
  hand the "Output" column names the file or archive that holds a result of an earlier run.
- Commands use `py -3` (the Windows launcher); on Linux and macOS write `python3`.
  All scripts are run from the repository root.
- The statistical analysis scripts need `numpy` and `scipy`; the figure script needs
  `matplotlib`. `check_pins.py` and the archive validators need only the standard library.

## Which script do I need to check number X?

Start from the campaign that produced the number, then use the matching row. The cheapest
check for every archive is the pins checker, which also runs the reproducer:

```
py -3 analysis/check_pins.py --only <campaign> --run-reproducers
```

| The number comes from | Campaign folder under `results/` | Rebuilt by |
|---|---|---|
| Phase 1, lab1 to lab3, knowledge arm against plain arm (the registered five-member family, the knowledge ladder, the controls) | `phase1_v2_corrected` | `reproduce_phase1_v2.py` |
| Phase 1b, the confirmatory follow-up (six-member family) | `phase1b_corrected` | `reproduce_phase1b.py` (needs local raw data) |
| Phase 1b, the re-run of the extended arm | `phase1b_extended_v3` | `reproduce_phase1b_extended_v3.py` (needs local raw data) |
| Phase 2, fault detection and recovery (recovery family, detection family) | `phase2_v2_corrected` | `reproduce_phase2_v2.py` |
| Phase 2, the two low-sun cells | `phase2_v3_lowsun` | `reproduce_phase2_lowsun_v3.py` |
| Phase 2, the structure-only (Brick layer) fault cells | `phase2_v3_structonly` | `reproduce_phase2_structonly_v3.py` |
| Phase 3, delay learning and deadline compliance | `phase3_v2_corrected` | `reproduce_phase3_v2.py` |
| Phase 4, energy and dependency labs (four-member family) | `phase4_v2_corrected` | `reproduce_phase4_v2.py` (needs local raw data) |
| Phase 4, the knowledge ladder | `phase4_v2_ladder` | `reproduce_phase4_v2_ladder.py` (needs local raw data) |
| Phase 4 tables after the lab5 zero-step correction | `_corrected_tables/phase4_v2_corrected`, `_corrected_tables/phase4_v2_ladder` | `phase4_v2_corrected_tables.py --check` (needs local raw data) |
| The Phase-3 paired table without the tests the registration does not allow | `_corrected_tables/phase3_v2_corrected` | `phase3_dynamics.py` (the command is in that folder's `README.md`) |
| Post-hoc tables (trained against unseen situations, training that can fail, and so on) | `posthoc/` | the scripts in `analysis/posthoc/` (see below) |
| The Phase-2 start-state Q-gap study | `exploratory/phase2b_qgap` | `reproduce_phase2_qgap.py` (needs the source artifacts; local only) |
| Truncated learning curves | `exploratory/phase1_truncated_learning_exploratory` | `phase1_truncated_learning.py` (needs local inputs) |

`check_pins.py` itself verifies, for each archive, the dispatch receipt of every run, key
values of the registered tables, and the SHA-256 of every tracked file against the archive's
`SHA256SUMS.csv`; the pins files are `../results/pins/<campaign>.json`. Archives marked
"needs local raw data" in the table print `needs local raw data; not run` when the raw files
are absent; see [`../docs/REPRODUCING.md`](../docs/REPRODUCING.md) for how to download
them. To see why a number is what it is (which seeds, which formula), open the registered
analysis script of the same campaign (table 1 below); the registration that froze it is
named in [`../docs/README.md`](../docs/README.md).

## 1. Registered family analyses

One script per campaign computes the registered tables: the per-seed paired differences
between the two arms, their bootstrap intervals, exact tests, and the Benjamini-Hochberg
adjustment within the registered family. Each is frozen after its registration; the
registration document is named in the script's docstring. They read the raw per-cell files of an
archive and write CSV or JSON tables into `<campaign>/analysis/registered/`.

| Script | Phase | Computes | Archive it reads | Called by |
|---|---|---|---|---|
| `phase1_v2_registered_family.py` | 1 | The five-member family: full-knowledge effect on area under the goal curve in lab2, the redundancy-only effect, their difference, the lab3 first-goal effect, the lab3 cycling effect; share carried by redundancy. Writes `phase1_v2_registered_family.csv` and `phase1_v2_decomposition.json`. | `phase1_v2_corrected` (full-knowledge and redundancy-only runs) | `reproduce_phase1_v2.py`; its helpers are reused by several other scripts |
| `phase1_v2_knowledge_ladder.py` | 1 | The knowledge ladder (tabula rasa, redundancy, Brick structure, full graph): effect of each arm and the increment between rungs. | `phase1_v2_corrected` (all runs, including the Brick-only run) | `reproduce_phase1_v2.py` |
| `phase1b_report.py` | 1b | The six-member confirmatory family and the supporting outcomes; also writes blinded pilot diagnostics when given the pilot runs. Writes `phase1b_registered_family.csv` and `phase1b_supporting.csv`. | `phase1b_corrected` | `reproduce_phase1b.py`; run by hand during the campaign (`../scripts/curate_phase1b_confirmatory.ps1` names it as the next step) |
| `phase1b_extended_v3_family.py` | 1b | The registered analysis of the re-run extended arm against the unchanged frozen-knowledge and baseline arms of the earlier campaign; reuses the member definitions of `phase1b_report.py`. | `phase1b_extended_v3` and `phase1b_corrected` | `reproduce_phase1b_extended_v3.py` |
| `phase2_v2_registered_family.py` | 2 | The two eight-member families: recovery episodes (Tier 1) and detection episode. | `phase2_v2_corrected` | `reproduce_phase2_v2.py`; its loader is imported by the other Phase-2 analyses |
| `phase2_lowsun_v3_family.py` | 2 | The same estimands for the two low-sun cells re-run with the low sun delivered through the scenario files. | `phase2_v3_lowsun` | `reproduce_phase2_lowsun_v3.py`; `../scripts/curate_phase2_lowsun_v3.ps1` names it as the next step |
| `phase2_structonly_v3_family.py` | 2 | Four fault cells re-run with the Brick layer only, in both arms, paired by seed with the same cell and arm of the runs of record: recovery, detection and wrong-blame families. | `phase2_v3_structonly` and `phase2_v2_corrected` | `reproduce_phase2_structonly_v3.py`; `../scripts/curate_phase2_structonly_v3.ps1` names it as the next step |
| `phase4_v2_registered_family.py` | 4 | The four-member family: redundant actions in lab4, lab4dual and lab4chain, energy compliance in lab5; also the ladder trend table. Takes `--zero-step-scoring` to choose between the corrected and the legacy scoring of lab5 runs that need no step. | `phase4_v2_corrected` | `reproduce_phase4_v2.py`; `phase4_v2_corrected_tables.py`; imported by several post-hoc scripts |
| `phase4_v2_knowledge_ladder.py` | 4 | The Phase-4 knowledge ladder: arm effects, rung increments and the comparability table. | `phase4_v2_ladder` and `phase4_v2_corrected` | `reproduce_phase4_v2_ladder.py`; `phase4_v2_corrected_tables.py` |

## 2. Per-run aggregation (inside the workflows)

These scripts run in the aggregate job of the phase workflows and produce the per-run tables
that are archived under `<campaign>/run_<id>/analysis/out/`. The registered analyses above
and some reproducers import their statistical helpers.

| Script | Phase | Computes | Output | Called by |
|---|---|---|---|---|
| `sweep_report.py` | 1, 4 | Per-profile summary tables with confidence intervals, paired tests, learning-speed tables and first-goal tables. It also holds the frozen statistical helpers (bootstrap, sign-flip test, rank-biserial correlation, Cliff's delta, Benjamini-Hochberg) that other scripts import. | `summary_table_ci.csv`, `paired_tests.csv`, `learning_speed_table.csv`, `learning_speed_tests.csv`, `first_goal_table.csv` and others in `run_<id>/analysis/out/` | `phase1.yml`, `phase4.yml` |
| `phase4_energy.py` | 4 | Energy-budget compliance and dependency measures that `sweep_report.py` does not compute. | `phase4_energy_ci.csv`, `phase4_energy_paired.csv` | `phase4.yml`; imported by the Phase-4 registered analyses |
| `phase2_recovery.py` | 2 | Aggregates the per-cell recovery files into a confidence-interval table and paired tests. | `phase2_recovery_ci.csv`, `phase2_recovery_paired.csv` | `phase2.yml` |
| `phase3_dynamics.py` | 3 | Delay-learning accuracy, deadline compliance with intervals, and the paired compliance table; the option `--legacy-archived-tests` restores the archived paired table for the reproducer. | `phase3_delay_accuracy.csv`, `phase3_compliance_ci.csv`, `phase3_compliance_paired.csv` | `phase3.yml`; `reproduce_phase3_v2.py` |
| `learning_curves.py` | 1, 4 | Per-profile reward learning curves with a 95 percent bootstrap band, as PNG and PGF. Descriptive only. | figures in `run_<id>/analysis/out/` | `phase1.yml`, `phase4.yml` |
| `phase1_archive_inventory.py` | all | Writes or verifies a deterministic SHA-256 inventory (`SHA256SUMS.csv`) of a folder. | `SHA256SUMS.csv` | `phase1.yml`, `phase4.yml`; the four `curate_*.ps1` scripts in `../scripts/`; several reproducers |
| `write_workflow_provenance.py` | all | Writes `workflow_inputs.json`, the run's dispatch receipt, naming the workflow file that really ran. | `workflow_inputs.json` | `phase1.yml`, `phase2.yml`, `phase3.yml`, `phase4.yml` |
| `exact_paired_stats.py` | 2, 3, 4, 1b | Library: dependency-free copy of the frozen Phase-1 exact tests, sign test, rank-biserial correlation and Benjamini-Hochberg q-values, kept as a copy so that the frozen Phase-1 files stay byte-identical. Pinned against the original by `tests/test_exact_paired_stats.py`. | none (library) | imported by the Phase-1b, Phase-2 and Phase-4 analyses and the post-hoc scripts |

## 3. Reproducers

A reproducer validates every run archive of one campaign with the archive validator, reruns
the registered analysis into a scratch folder, and compares the rebuilt tables with the
stored ones after canonical formatting of the numbers. Each prints the differences and exits
non-zero on any mismatch. They are the commands behind `check_pins.py --run-reproducers`;
the data each needs and the time it takes are in
[`../docs/REPRODUCING.md`](../docs/REPRODUCING.md).

| Script | Archive | Rebuilds | Called by |
|---|---|---|---|
| `reproduce_phase1_v2.py` | `phase1_v2_corrected` | Every Phase-1 table: registered family, knowledge ladder, controls and descriptives, protocol gate summary | `check_pins.py` (CI job `reproducers`); by hand |
| `reproduce_phase1b.py` | `phase1b_corrected` | `phase1b_registered_family.csv`, `phase1b_supporting.csv` | `check_pins.py` (local raw data); by hand |
| `reproduce_phase1b_extended_v3.py` | `phase1b_extended_v3` | The extended-arm tables; takes `--comparator` for the earlier campaign with its raw files | `check_pins.py` (local raw data); by hand |
| `reproduce_phase2_v2.py` | `phase2_v2_corrected` | Recovery and detection families | `check_pins.py` (CI job `reproducers`); by hand |
| `reproduce_phase2_lowsun_v3.py` | `phase2_v3_lowsun` | Low-sun tables, with the treatment-delivery gate enabled | `check_pins.py` (CI job `reproducers`); by hand |
| `reproduce_phase2_structonly_v3.py` | `phase2_v3_structonly` | Structure-only tables, with the knowledge-layer and low-sun gates enabled; takes `--record-base` for the runs of record | `check_pins.py` (CI job `reproducers`); by hand |
| `reproduce_phase3_v2.py` | `phase3_v2_corrected` | The Phase-3 tables | `check_pins.py` (CI job `reproducers`); by hand |
| `reproduce_phase4_v2.py` | `phase4_v2_corrected` | The Phase-4 family tables (both seed halves together) | `check_pins.py` (local raw data); by hand |
| `reproduce_phase4_v2_ladder.py` | `phase4_v2_ladder` | The ladder tables; takes `--full-root` for the full-knowledge campaign | `check_pins.py` (local raw data); by hand |
| `compare_run_with_archive.py` | any archive run folder | Nothing: compares the downloaded artifacts of a new GitHub run file by file with an archived run (identical, only allowed fields differ, receipt with added keys, not in archive, real difference); Phase 3 by counts and choices | by hand; used for [`../docs/evidence/reproduction_check_2026-11/`](../docs/evidence/reproduction_check_2026-11/README.md) |
| `reproduce_phase2_qgap.py` | `exploratory/phase2b_qgap` | The Q-gap output set, byte for byte; every input path is a required argument | by hand (its pins file records that it is local only) |
| `phase1_v2_compile_controls.py` | `phase1_v2_corrected` | The controls-and-descriptives table: copies the archived contrast of each run mode into one table, no statistic recomputed | `reproduce_phase1_v2.py` |
| `phase1_v2_protocol_summary.py` | `phase1_v2_corrected` | The protocol gate summary: checks the completion gates of every run archive from the `TRAINING_OK.json` manifests and first-goal files | `reproduce_phase1_v2.py` |
| `phase4_v2_corrected_tables.py` | `phase4_v2_corrected`, `phase4_v2_ladder` | The Phase-4 tables with the corrected lab5 zero-step scoring, written to `../results/_corrected_tables/` (`--check` rebuilds and compares) | by hand (the folder's `README.md` gives the command) |

`phase1_v2_compile_controls.py` and `phase1_v2_protocol_summary.py` are helpers of
`reproduce_phase1_v2.py`. `phase4_v2_corrected_tables.py` is not part of `check_pins.py`:
the archived tables use the old scoring, and this script writes the corrected versions
next to them.

## 4. Archive validators

Validators only read. They list every gate violation and exit 1, else 0. The analyses and
reproducers call them first and stop on any error.

| Script | Phase | Checks | Called by |
|---|---|---|---|
| `validate_phase1_v2_archive.py` | 1 (and 4 through a wrapper) | Per seed and lab: protocol-v2 fields in `TRAINING_OK.json`, the declared scenario cycle in the metrics, identical first-goal files and schedule hash between the two arms, the benchmark schema. | `phase1.yml` (aggregate job); `reproduce_phase1_v2.py`; imported by `validate_phase1b_archive.py` and `validate_phase4_v2_archive.py` |
| `validate_phase4_v2_archive.py` | 4 | The same checks with the Phase-4 lab set (`--labs`, `--seeds`). | `phase4.yml`; `reproduce_phase4_v2.py`, `reproduce_phase4_v2_ladder.py` |
| `validate_phase1b_archive.py` | 1b | The Phase-1b run archives, with stages `pilot` and `confirmatory`, and the optional treatment-delivery gates. | `reproduce_phase1b.py`, `reproduce_phase1b_extended_v3.py`; `../scripts/curate_phase1b_confirmatory.ps1`, `../scripts/curate_phase1b_extended_v3.ps1` |
| `validate_phase1b_dispatch.py` | 1b | Argument check of a Phase-1b dispatch before the job matrix is built: all profiles, exactly one frozen seed block, no duplicates. Reads and writes no files. | `phase1.yml` (step "Phase-1b exact registered-dispatch gate") |
| `validate_phase2_v2_archive.py` | 2 | The fault-stage files under `recovery_root/seed<N>/`: gate manifests, schedule identity, detector and protocol stamps, parent provenance; optional gates for treatment delivery and knowledge layer. | `reproduce_phase2_*.py`; the family analyses; `../scripts/curate_phase2_lowsun_v3.ps1`, `../scripts/curate_phase2_structonly_v3.ps1` |
| `validate_phase3_v2_archive.py` | 3 | Per replica: `DYNAMICS_OK` manifests with the tick-based energy stamp and the time-bounded files. | `reproduce_phase3_v2.py` |
| `validate_phase2_qgap_inputs.py` | 2 (exploratory) | Fails closed on the inputs of the Q-gap study: commit, pinned source blobs, archive and ZIP hashes, against `phase2_qgap_spec.json`. | `phase2_qgap_core.py`, `phase2_qgap_retrospective.py`, `reproduce_phase2_qgap.py` |
| `validate_phase1_scenarios.py` | 1, 1b, 4 | The scenario files against the simulator physics: unique integer ids, declared zone levels against the recomputed settled levels, and the formula fragments in each simulator flow and in the dashboard's physics. | CI job `validate-resources` (`ci.yml`); `build_lowsun_scenarios.py` and a post-hoc script import it |
| `check_pins.py` | all | Every archive against its pins file `../results/pins/<campaign>.json`: receipts, table values, SHA-256 inventories; with `--run-reproducers` also runs the reproducers. | `ci.yml` (jobs `validate-resources`, and `reproducers` with `--run-reproducers`); by hand |

## 5. Proofs of delivered settings and cross-run identity

Two questions decide whether a control arm is valid: was the knowledge layer it claims
really delivered, and are the cells that two runs should share really identical? These
scripts answer them. The campaign manifests list their outputs under each run's
`verification/` folder. They are helpers written for one campaign each and run by hand.

| Script | Phase | Proves | Output | Called by |
|---|---|---|---|---|
| `phase1_v2_brick_layer_proof.py` | 1 | The Elementary layer was off in the Brick-only arm: compares the initial Q-tables of its knowledge arm with those of the full-knowledge run. | `elementary_layer_off_proof.json` in `phase1_v2_corrected/run_42834963612/verification/` | by hand |
| `phase1_v2_cross_run_identity.py` | 1 | Two Phase-1 archives share byte-identical files where they should: compares the SHA-256 inventories by cell kind, file kind and lab, and names the columns in which result files differ. | `cross_run_identity_vs_run_29848584965.json` in `phase1_v2_corrected/run_42834963612/verification/` | by hand |
| `phase4_v2_ladder_layer_proof.py` | 4 | Plug invariance of the initial Q-tables in the three plug labs when no power gate is bound (redundancy and Brick layers); with `--reference-roots` also compares the tables with the full-knowledge ones. | `gate_invariance_proof.json` and `initial_table_difference_vs_full_kg.json` in each ladder run's `verification/` | by hand |
| `phase4_v2_ladder_cross_run_identity.py` | 4 | The tabula-rasa training cells and the plain-arm and rule-based test cells of a control run are identical to the full-knowledge campaign, file by file. | `cross_run_identity_vs_phase4_v2_corrected.json` in each ladder run's `verification/` | by hand |
| `build_lowsun_scenarios.py` | 2 | Not a proof but its input: derives the pinned-low-sun scenario files from their clean parents (same ids and order, sun fixed at 100 lux, declared levels recomputed with the clean physics). `--check` compares the committed files with a rebuild. | `../benchmark/train_scenarios_*_lowsun.json` | by hand; tested by `tests/test_lowsun_treatment_delivery.py` |
| `generate_phase1b_certificates.py` | 1b | Exhaustive reachability certificates: for every Phase-1b scenario it enumerates the whole reachable state graph at the frozen horizon, and writes one self-hashed JSON certificate per scenario plus `INDEX.csv`. | `../config/reachability_certificates/` | by hand |
| `phase1b_models.py` | 1b | Library: the frozen Phase-1b physics of each lab as a deterministic transition model, with route metadata derived from theory. | none (library) | `generate_phase1b_certificates.py` |
| `transition_checker.py` | 1b, 2 | Library: the generic exhaustive checker (solvability within the horizon, shortest path length, shortest routes, union of first actions) that produces a hashable certificate. | none (library) | `generate_phase1b_certificates.py`, `phase1b_models.py`, `phase2_qgap_core.py` |
| `phase1b_flow_smoke.py` | 1b | Live smoke test of the seven Phase-1b Node-RED flows: `/health`, reset, set-state, action and status endpoints, and one deterministic physics path per lab. Needs the flows running. | JSON evidence, for example `../docs/evidence/phase1b_live_flow_smoke_2026-07-27.json` | `../scripts/Run-Phase1bFlowSmoke.ps1` (CI job `smoke-training` runs that script) |
| `elementary_term_conformance.py` | all | Which `elem:` terms used in the knowledge graphs and reasoner sources are declared in the published Elementary ontology (the ontology file is passed with `--ontology`). | `../docs/evidence/elementary_term_conformance.csv` | by hand; the result is described in [`../docs/VOCABULARY.md`](../docs/VOCABULARY.md) |

## 6. Post-hoc and exploratory analyses

Every analysis here was specified after the results were known. They are descriptive:
means, shares, paired per-seed differences with bootstrap intervals, and sign counts. None
computes a p-value or q-value, and none changes a registered verdict. The five post-hoc
scripts in `posthoc/` write the tables in [`../results/posthoc/`](../results/posthoc/README.md),
which lists the exact command of each.

| Script | Phase | Computes | Output | Called by |
|---|---|---|---|---|
| `posthoc/test_trained_vs_unseen.py` | 1, 4 | Test results split by whether a test starting situation also appears among the training situations. | `results/posthoc/test_trained_vs_unseen/` | by hand |
| `posthoc/training_can_fail.py` | 1 | Training success and steps on episodes whose starting situation does not already meet the goal, over all episodes and over the first 150; re-runnable on fresh seeds. | `results/posthoc/training_can_fail/` | by hand; imported by `lab3_failure_anatomy.py` |
| `posthoc/detection_correct_component.py` | 2 | Fault detection counted only when the first blamed device is really broken. | `results/posthoc/detection_correct_component/` | by hand |
| `posthoc/lab3_failure_anatomy.py` | 1 | Which lab3 starting situations fail, in training and in the test, per arm. | `results/posthoc/lab3_failure_anatomy/` | by hand |
| `posthoc/phase4_steps_split.py` | 4 | The Phase-4 step count split into steps without effect and switch changes; checks on every test run that the two add up to the steps. | `results/posthoc/phase4_steps_split/` | by hand |
| `posthoc/check_against_accepted.py` | 1, 2, 4 | One row per check of the post-hoc tables against the accepted registered tables; computes no statistic of its own. | `results/posthoc/sanity_checks.csv`, `sanity_checks.md` | by hand |
| `posthoc/_common.py` | - | Library: shared paired summary (mean, median, bootstrap interval, sign counts), cell lookup and CSV reading for the scripts above. | none (library) | the six scripts above |
| `posthoc_robustness_summary.py` | 1, 1b, 2, 4 | Benjamini-Yekutieli re-adjustment of every registered family, sign counts of the registered members, and the reference contrasts of Phase 1. | `../docs/evidence/registered_multiplicity_sensitivity.csv`, `registered_sign_counts.csv`, `phase1_v2_posthoc_reference_contrasts.csv`; described in [`../docs/reports/posthoc_robustness_2026-11-13.md`](../docs/reports/posthoc_robustness_2026-11-13.md) | by hand |
| `phase1_truncated_learning.py` | 1, 1b | Learning curves cut after fewer episodes, per pre-specified budget, binding each metric file to the committed SHA-256 inventories. Reads `phase1_truncated_learning_spec.json`; the Phase-1b inputs are downloaded by `../scripts/download_phase1b_truncation_inputs.ps1`. | `../results/exploratory/phase1_truncated_learning_exploratory/analysis/` | by hand; imported by `posthoc/_common.py` |
| `phase2_qgap_retrospective.py` | 2 | The start-state Q-gap study: how far the learned start-state values favour an action that the fault made unusable over the first action of a shortest surviving route. Exact arithmetic for all comparisons. Reads `phase2_qgap_spec.json`. | `../results/exploratory/phase2b_qgap/analysis/` | by hand; rebuilt by `reproduce_phase2_qgap.py` |
| `phase2_qgap_core.py` | 2 | Library: the deterministic core of the Q-gap study (fault effects, state encoding, route search). | none (library) | `phase2_qgap_retrospective.py`, `reproduce_phase2_qgap.py` |
| `phase1b_power.py` | 1b | The frozen power procedure of the pilot stage: simulates the six-member family at the registered smallest effects of interest to pick the smallest adequate N from 20, 30 and 40, from allow-listed pilot diagnostics only. Described in [`../docs/registrations/PHASE1B_POWER_PROTOCOL.md`](../docs/registrations/PHASE1B_POWER_PROTOCOL.md). | `../results/exploratory/phase1b_pilot/power_report.txt` | by hand |

## 7. Other files in this folder

| File | What it is |
|---|---|
| `__init__.py`, `posthoc/__init__.py` | Package markers so that the scripts can import each other. |
| `phase1_truncated_learning_spec.json` | The frozen specification read by `phase1_truncated_learning.py` and `../scripts/download_phase1b_truncation_inputs.ps1`: profiles, contrasts, budgets, endpoints, bootstrap settings and the runs with their artifact ids. |
| `phase2_qgap_spec.json` | The frozen specification of the Q-gap study, read by `validate_phase2_qgap_inputs.py` and the retrospective. |
| `tests/` | The Python tests (`py -3 -m pytest -q -p no:cacheprovider analysis/tests`). They cover the statistical copy, the pins checker, the archive validators, the registered analyses, the post-hoc scripts, the workflow and runner hygiene, the knowledge-provenance table, the runner allow-lists, and the dashboard data contracts. CI runs them in job `validate-resources`. |
