# Corrected tables: Phase-4 registered family (`phase4_v2_corrected`)

## What is corrected

The lab5 energy-compliance LEVELS (`mean_ql_true`, `mean_ql_false` of the
`lab5,energy_compliance` row).

In lab5, 900 of the 1,600 test runs per arm (20 seeds x 80 runs) start with
the goal already met, so the agent takes no step and the step log has no row
for them. The archived table
`results/phase4_v2_corrected/analysis/registered/phase4_v2_registered_family.csv`
scored those runs as if every device were off (power 0, always within the
energy budget). 400 of them start above the budget, so the archived levels
are too high. Here they are scored from the start state the scenario file
(`benchmark/scenarios_lab5.json`) declares, which is the device configuration
they actually end in.

| lab5 energy compliance | archived (all off) | corrected (declared start) |
|---|---|---|
| full knowledge (`ql_true`) | 0.753125 | 0.503125 |
| plain Q-learning (`ql_false`) | 0.71 | 0.46 |
| hand-written controller (`rule_based`) | 1.0 | 0.75 |
| paired difference `ql_true - ql_false` | +0.043125 | +0.043125 |

The paired difference, its interval, the p- and q-values and the rank-biserial
correlation are the same under both scorings (both arms share the same
zero-step runs, so the correction shifts both arms by the same amount per
seed). The three dependency-lab rows and `phase4_v2_ladder_trend.csv` do not
use the energy scoring and are unchanged; tiny differences in the last
floating-point digit come from the order of arithmetic only.

## Files

- `phase4_v2_registered_family.csv`, `phase4_v2_ladder_trend.csv`: the
  registered tables, same columns as the archived ones, corrected scoring.
- `lab5_compliance_levels.csv`: per planner mode, the mean over the 20 seeds
  of the lab5 goal rate, energy compliance, steady power and over-budget rate
  under both scorings (`declared_start_state` = corrected,
  `all_off_legacy` = archived), with the number of zero-step test runs.

## Command

From the repository root (needs the ignored raw benchmark CSVs of the
archive):

    py -3 analysis/phase4_v2_corrected_tables.py          # writes this folder and ../phase4_v2_ladder
    py -3 analysis/phase4_v2_corrected_tables.py --check  # rebuilds and compares

The archived table itself is unchanged and still reproduced by
`analysis/reproduce_phase4_v2.py`, which uses the legacy scoring on purpose.
