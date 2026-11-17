# Result archives

This folder holds the archived data of every accepted campaign and of three exploratory
analyses. Each archive is the downloaded output of one or more GitHub Actions runs,
arranged in one folder per run (`run_<run id>/`), with checksums, manifests and the
registered result tables. How the campaigns are organised is explained in
[../docs/EXPERIMENTS.md](../docs/EXPERIMENTS.md); how to re-check the archives in
[../docs/REPRODUCING.md](../docs/REPRODUCING.md).

## Layout

| Path | Contents |
|---|---|
| `<campaign>/CAMPAIGN_MANIFEST.md` | The campaign in words: registration, runs, inputs, file counts, and for most campaigns the registered result. |
| `<campaign>/run_<id>/ARCHIVE_MANIFEST.md` | One run: dispatch, head commit, inputs, artifact identifier and digest, gates passed. The runs of `phase1b_corrected` have none; its campaign manifest covers them. |
| `<campaign>/run_<id>/analysis/out/` | The output of the workflow's aggregate job, including `workflow_inputs.json` (the run's dispatch receipt). |
| `<campaign>/run_<id>/benchmark/results_seed<N>/<lab>/` | Phases 1, 1b, 4: per lab and seed, the training folders `training_stereo_<arm>/` (with `TRAINING_OK.json`) and the test folders `ql_true/`, `ql_false/`, `rule_based/`. |
| `<campaign>/run_<id>/recovery_root/seed<N>/` | Phase 2: the fault-stage files and `ADAPT_OK_*.json` receipts per seed. |
| `<campaign>/run_<id>/dynamics_root/rep<N>/` | Phase 3: the delay-learning files and `DYNAMICS_OK_*.json` receipts per replica. |
| `<campaign>/run_<id>/_artifacts/clean/` | Phase 2: the parent-training receipts (`TRAINING_OK.json`) per parent lab, arm and seed. |
| `<campaign>/analysis/registered/` | The registered result tables, written by the campaign's registered analysis script. |
| `SHA256SUMS.csv` | SHA-256 inventory of the files of a run or campaign (in the run folder, its `analysis/out/`, or the campaign folder). |
| `pins/<campaign>.json` | The facts `analysis/check_pins.py` checks for each archive (schema at the top of that script). |

Large raw files (the Q-tables of every cell, some per-cell test files and logs) are listed
in the inventories but ignored by git, so they are not in the repository. Four archives
need them for their reproducer (marked *local* below). The raw files came from the runs'
consolidated workflow artifacts, which GitHub keeps for 90 days: those of the July runs
(`phase1b_corrected`, `phase4_v2_corrected` and the exploratory archives) have expired,
those of `phase1b_extended_v3` and `phase4_v2_ladder` expire on 2027-02-10 and 2027-02-11.
Because the reproducers of the two November archives also read the July raw files, a
reader cannot run any of the four reproducers; what can be checked is the tracked tables,
summary files and inventories. Details per run are in
[../docs/REPRODUCING.md](../docs/REPRODUCING.md), "The raw data of four archives".

The archive files are byte-frozen: `.gitattributes` disables every line-ending conversion
under `results/` (except for the pins files and this README). The paths written inside the archive manifests (`CAMPAIGN_MANIFEST.md`,
`ARCHIVE_MANIFEST.md`, the Q-gap source manifest) refer to the repository layout at the git
tag `record-v2.5.0-pre-cleanup`, in which every archive was a top-level folder (for example
`phase2_v2_corrected/` instead of `results/phase2_v2_corrected/`). Documents they name under
`docs/` are now in `docs/registrations/`, `docs/notices/`, `docs/reports/` or
`docs/evidence/`, or exist only at that tag; [../docs/README.md](../docs/README.md) gives
the mapping. In the archived `workflow_inputs.json` files the field `workflow` reads
`.github/workflows/phase1.yml` for every phase; the pins file of each archive names the
workflow that really dispatched it (`workflow_actual`).

## Accepted campaigns

Run ids, commits and inputs below are those pinned in `pins/<campaign>.json`. Registration
and dispatch documents are in `../docs/registrations/`, reports in `../docs/reports/`.

### `phase1_v2_corrected`: Phase 1, healthy rooms, protocol v2

- **What.** lab1, lab2, lab3; seeds 1–20; knowledge arm against plain arm in four run
  modes, plus the Brick-structure-only control arm.
- **Runs.** `29848584965` (`phase1_v2_kg_only`), `29848587274` (`phase1_v2_redundancy_only`),
  `29848589682` (`phase1_v2_baseline`), `29848592010` (`phase1_v2_pbrs_only`), all at
  commit `d3442385`; `42834963612` (`phase1_v2_brick_only`) at commit `140906f2`. Workflow
  `phase1.yml`.
- **Registration.** `thesis_methods_phase1_registration.md`,
  `phase1_correction_registration_2026-07-21.md`, `phase1_correction_registration_2026-07-21a.md`,
  `phase1_correction_analysis_deviation_2026-07-22.md`; Brick-only arm:
  `phase1_brick_only_registration_2026-11-10.md`.
- **Report.** `phase1_results_v2.md`; Brick-only arm: `phase1_brick_only_report_2026-11-13.md`.
- **Registered tables.** `analysis/registered/phase1_v2_registered_family.csv`,
  `phase1_v2_knowledge_ladder_family.csv` and related files.
- **Reproducer.** `analysis/reproduce_phase1_v2.py` (tracked data).

### `phase1b_corrected`: Phase 1b, mechanism rooms

- **What.** labrel0, labrel4, labrel8, labrel16, labrel8s, labband, lab4chain3; seeds 1–20;
  four run modes, each in two seed halves.
- **Runs.** `30199243656` / `30199247467` (`phase1b_v2_baseline`), `30199251189` /
  `30199254246` (`phase1b_v2_redundancy_only`), `30199257204` / `30199260042`
  (`phase1b_v2_kg_frozen`), `30199263213` / `30199266427` (`phase1b_v2_extended`), seeds
  1–10 / 11–20, commit `c8e14a4b`, workflow `phase1.yml`.
- **Registration.** `phase1b_registration_2026-07-26.md`, `PHASE1B_POWER_PROTOCOL.md`;
  dispatch record `phase1b_confirmatory_dispatch_record_2026-07-26.md`.
- **Report.** The campaign manifest. The two runs of `phase1b_v2_extended` did not receive
  their three extra settings (see `phase1b_extended_rerun_registration_2026-11-11.md`);
  their effect on the extended-arm comparisons is re-measured in `phase1b_extended_v3`.
- **Registered tables.** `analysis/registered/phase1b_registered_family.csv`.
- **Reproducer.** `analysis/reproduce_phase1b.py` (*local*: about 650 MB of per-cell files).

### `phase1b_extended_v3`: Phase 1b, re-run of the extended arm

- **What.** The `phase1b_v2_extended` arm again, with its settings delivered and checked,
  compared with the frozen arm of `phase1b_corrected`.
- **Runs.** `43099281565` (seeds 1–10), `43099286057` (seeds 11–20), commit `77e7791f`,
  workflow `phase1.yml`.
- **Registration.** `phase1b_extended_rerun_registration_2026-11-11.md`; dispatch record
  `phase1b_extended_rerun_dispatch_record_2026-11-11.md`.
- **Report.** The campaign manifest.
- **Registered tables.** `analysis/registered/phase1b_extended_v3_registered_family.csv`.
- **Reproducer.** `analysis/reproduce_phase1b_extended_v3.py` (*local*: per-cell files of
  this archive and of `phase1b_corrected`).

### `phase2_v2_corrected`: Phase 2, broken devices, protocol v2

- **What.** 23 fault labs in two groups; seeds 1–20; full knowledge layer.
- **Runs.** Group A (lab1, lab2 and labmon family): `30001857104` (seeds 1–10),
  `30001867521` (seeds 11–20); group B (lab3 family and `labmon2_nostereo_f2dead_lowsun`):
  `30001878310` (seeds 1–10), `30001888910` (seeds 11–20); commit `19f4f3ff`, workflow
  `phase2.yml`.
- **Registration.** `phase2_correction_registration_2026-07-22.md`; dispatch record
  `phase2_v2_dispatch_record_2026-07-22.md`.
- **Report.** The campaign manifest. The two low-sun cells of this campaign did not receive
  the low sun (see `phase2_lowsun_rerun_registration_2026-11-11.md`); `phase2_v3_lowsun`
  re-runs them.
- **Registered tables.** `analysis/registered/phase2_v2_registered_family.csv`,
  `phase2_v2_detection_family.csv`.
- **Reproducer.** `analysis/reproduce_phase2_v2.py` (tracked data).

### `phase2_v3_lowsun`: Phase 2, re-run of the low-sun cells

- **What.** `lab3_f2dead_lowsun` and `labmon2_f2dead_lowsun` with the low sun delivered
  through their own training schedules; seeds 1–20.
- **Run.** `43099277363`, commit `77e7791f`, workflow `phase2.yml`.
- **Registration.** `phase2_lowsun_rerun_registration_2026-11-11.md`; dispatch record
  `phase2_lowsun_rerun_dispatch_record_2026-11-11.md`.
- **Report.** The campaign manifest.
- **Registered tables.** `analysis/registered/phase2_lowsun_v3_recovery_family.csv`,
  `phase2_lowsun_v3_detection_family.csv`.
- **Reproducer.** `analysis/reproduce_phase2_lowsun_v3.py` (tracked data).

### `phase2_v3_structonly`: Phase 2, structure-only fault stage

- **What.** Four fault labs (`lab2_f1bdead`, `lab3_f1bdead`, `lab3_f2dead_lowsun`,
  `labmon2_f2dead_lowsun`) with the `brick` knowledge layer in both arms of the fault
  stage; seeds 1–20; compared with the full-layer runs of `phase2_v2_corrected` and
  `phase2_v3_lowsun`.
- **Run.** `43590832041`, commit `b5bf2146`, workflow `phase2.yml`.
- **Registration.** `phase2_structonly_registration_2026-11-14.md`; dispatch record
  `phase2_structonly_dispatch_record_2026-11-14.md`.
- **Report.** `phase2_structonly_report_2026-11-15.md`.
- **Registered tables.** `analysis/registered/phase2_structonly_v3_wrongblame_family.csv`,
  `phase2_structonly_v3_detection_family.csv`, `phase2_structonly_v3_recovery_family.csv`.
- **Reproducer.** `analysis/reproduce_phase2_structonly_v3.py` (tracked data).

### `phase3_v2_corrected`: Phase 3, slow devices

- **What.** lab2_slow and lab3_slow; both planner modes; replicas 1–10.
- **Run.** `29926328852`, commit `90e53f8b`, workflow `phase3.yml`.
- **Registration.** `phase3_correction_registration_2026-07-22.md`; dispatch record
  `phase34_v2_dispatch_record_2026-07-22.md`.
- **Report.** The campaign manifest.
- **Tables.** `run_29926328852/analysis/out/phase3_delay_accuracy.csv`,
  `phase3_compliance_ci.csv`, `phase3_compliance_paired.csv`.
- **Reproducer.** `analysis/reproduce_phase3_v2.py` (tracked data).

### `phase4_v2_corrected`: Phase 4, dependencies and energy

- **What.** lab4, lab4dual, lab4chain, lab5; seeds 1–20; run mode `phase4_v2`.
- **Runs.** `29926341581` (seeds 1–10), `29926354783` (seeds 11–20), commit `90e53f8b`,
  workflow `phase4.yml`.
- **Registration.** `phase4_correction_registration_2026-07-22.md`; dispatch record
  `phase34_v2_dispatch_record_2026-07-22.md`.
- **Report.** The campaign manifest.
- **Registered tables.** `analysis/registered/phase4_v2_registered_family.csv`,
  `phase4_v2_ladder_trend.csv`.
- **Reproducer.** `analysis/reproduce_phase4_v2.py` (*local*: about 0.7 GB of per-cell
  files).

### `phase4_v2_ladder`: Phase 4, knowledge ladder

- **What.** The structure-only arm (`phase4_v2_brick_only`, all four labs) and the
  redundancy-rule arm (`phase4_v2_redundancy_only`, lab4, lab4dual, lab4chain); seeds 1–20;
  compared with the full-knowledge runs of `phase4_v2_corrected`.
- **Runs.** `43494914019` / `43494933613` (`phase4_v2_brick_only`, seeds 1–10 / 11–20),
  `43494955158` / `43494975441` (`phase4_v2_redundancy_only`, seeds 1–10 / 11–20), commit
  `ee656178`, workflow `phase4.yml`.
- **Registration.** `phase4_knowledge_ladder_registration_2026-11-13.md`.
- **Report.** `phase4_knowledge_ladder_report_2026-11-14.md`.
- **Registered tables.** `analysis/registered/phase4_v2_knowledge_ladder_family.csv` and
  related files.
- **Reproducer.** `analysis/reproduce_phase4_v2_ladder.py` (*local*: per-cell files of this
  archive and of `phase4_v2_corrected`, about 1.9 GB together).

## Exploratory archives (`exploratory/`)

Exploratory work: a pilot and two analyses of existing data. Their registrations say what
had been seen before they were written; they carry no confirmatory weight and have no
reproducer that runs in CI.

| Archive | What | Protocol | Why no reproducer runs |
|---|---|---|---|
| `exploratory/phase1b_pilot` | The Phase-1b pilot runs on separate seeds (runs `30171111962`, `30171112970`, `30171113750`, `30171114580`): markers, diagnostics and the inputs of the power analysis. | `PHASE1B_POWER_PROTOCOL.md`; dispatch record `phase1b_pilot_dispatch_record_2026-07-25.md` | The raw pilot artifacts (about 4.2 GB) are not in the repository. |
| `exploratory/phase2b_qgap` | A retrospective study of the trained tables of the Phase-2 runs; results in `PHASE2_QGAP_RETROSPECTIVE_RESULTS.md` inside the archive. | `PHASE2_QGAP_RETROSPECTIVE.md` | `analysis/reproduce_phase2_qgap.py` needs the extracted source artifacts (about 2.1 GB). |
| `exploratory/phase1_truncated_learning_exploratory` | Learning curves of Phase 1 and Phase 1b cut after fewer episodes. | `phase1_truncated_learning_exploratory_2026-08-12.md` (also the report) | `analysis/phase1_truncated_learning.py` needs inputs downloaded with `scripts/download_phase1b_truncation_inputs.ps1`. |

`docs/reports/posthoc_robustness_2026-11-13.md` is an unregistered, descriptive summary
across campaigns; its tables are in `docs/evidence/`.

## Corrected tables (`_corrected_tables/`)

`_corrected_tables/<campaign>/` holds tables that the corrected analysis code produces from
an unchanged archive: for `phase4_v2_corrected` and `phase4_v2_ladder`, the lab5 compliance
levels with test runs that need no step scored from their declared start state; for
`phase3_v2_corrected`, the paired table without the test statistics that the Phase-3
registration does not allow. Each folder has a `README.md` with the reason and the command
that rebuilds it. The folder is derived output, not an archive, and has no pins file.
