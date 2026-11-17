# Post-hoc descriptive tables

The tables in this folder were computed from the archived campaigns after their
results were known. They are **post-hoc and descriptive**: means, paired per-seed
differences (`ql_true - ql_false` unless a table says otherwise), 95% bootstrap
intervals of the mean paired difference (10,000 resamples, fixed generator seed), and
sign counts. They contain no p-values and no q-values, and they belong to no
registration. They are not archives, so they have no pins file; `analysis/check_pins.py`
skips this folder.

| Folder | What it describes | Script |
|---|---|---|
| `test_trained_vs_unseen/` | Test results split by whether a test starting situation was also a training starting situation (Phase 1 and Phase 4). | `analysis/posthoc/test_trained_vs_unseen.py` |
| `training_can_fail/` | Training success and steps on episodes whose starting situation does not already meet the goal, over all 3,000 episodes and over the first 150 (Phase 1). | `analysis/posthoc/training_can_fail.py` |
| `detection_correct_component/` | Fault detection counted only when the first blame names a device that is really broken (Phase 2, three archives). | `analysis/posthoc/detection_correct_component.py` |
| `lab3_failure_anatomy/` | Failed lab3 training episodes and test runs per starting situation and arm (Phase 1). | `analysis/posthoc/lab3_failure_anatomy.py` |
| `phase4_steps_split/` | The Phase-4 measure split into steps without effect and switch changes; check that their sum equals the number of steps in every test run. | `analysis/posthoc/phase4_steps_split.py` |
| `sanity_checks.csv`, `sanity_checks.md` | Comparison of these tables with the accepted registered tables (every check reproduced). | `analysis/posthoc/check_against_accepted.py` |

Each folder has a `summary.md` that states the definitions used.

## How the tables were written

Run from the repository root. The Phase-4 commands read benchmark files that are part
of the bulky raw data and are not in the repository (see `results/README.md`); the
other commands work on tracked files.

```
P1="results/phase1_v2_corrected/run_29848584965 results/phase1_v2_corrected/run_29848587274 results/phase1_v2_corrected/run_29848589682 results/phase1_v2_corrected/run_29848592010 results/phase1_v2_corrected/run_42834963612"
P4="results/phase4_v2_corrected/run_29926341581 results/phase4_v2_corrected/run_29926354783 results/phase4_v2_ladder/run_43494914019 results/phase4_v2_ladder/run_43494933613 results/phase4_v2_ladder/run_43494955158 results/phase4_v2_ladder/run_43494975441"

python analysis/posthoc/test_trained_vs_unseen.py --phase1-roots $P1 --phase4-roots $P4 --seeds 1-20 --out results/posthoc/test_trained_vs_unseen
python analysis/posthoc/training_can_fail.py --roots $P1 --seeds 1-20 --out results/posthoc/training_can_fail
python analysis/posthoc/detection_correct_component.py \
  --archive v2_corrected=results/phase2_v2_corrected/run_30001857104 \
  --archive v2_corrected=results/phase2_v2_corrected/run_30001867521 \
  --archive v2_corrected=results/phase2_v2_corrected/run_30001878310 \
  --archive v2_corrected=results/phase2_v2_corrected/run_30001888910 \
  --archive structonly=results/phase2_v3_structonly/run_43590832041 \
  --archive lowsun=results/phase2_v3_lowsun/run_43099277363 \
  --seeds 1-20 --out results/posthoc/detection_correct_component
python analysis/posthoc/lab3_failure_anatomy.py --roots $P1 --seeds 1-20 --out results/posthoc/lab3_failure_anatomy
python analysis/posthoc/phase4_steps_split.py --roots $P4 --seeds 1-20 --out results/posthoc/phase4_steps_split
python analysis/posthoc/check_against_accepted.py --posthoc-out results/posthoc \
  --phase1-archive results/phase1_v2_corrected --phase2-corrected results/phase2_v2_corrected \
  --phase2-structonly results/phase2_v3_structonly --phase4-corrected results/phase4_v2_corrected \
  --phase4-ladder results/phase4_v2_ladder
```

The committed tables were produced before the archives moved under `results/`; the
`source` strings inside some tables therefore still name the earlier top-level folder
names (for example `phase1_v2_corrected/...`). The data are the same files.
