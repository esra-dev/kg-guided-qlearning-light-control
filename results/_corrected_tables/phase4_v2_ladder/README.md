# Corrected tables: Phase-4 knowledge ladder (`phase4_v2_ladder`)

## What is corrected

The lab5 energy-compliance LEVELS that the ladder tables report: the
tabula-rasa means in the lab5 row of
`phase4_v2_knowledge_ladder_comparability.csv`.

In lab5, 900 of the 1,600 test runs per arm start with the goal already met,
so the agent takes no step and the step log has no row for them. The archived
tables under `results/phase4_v2_ladder/analysis/registered/` scored those runs
as if every device were off (power 0, always within budget). Here they are
scored from the start state the scenario file declares
(`benchmark/scenarios_lab5.json`), which is the configuration they end in.

| lab5 energy compliance | archived (all off) | corrected (declared start) |
|---|---|---|
| full knowledge `ql_true` (A3) | 0.753125 | 0.503125 |
| Brick structure only `ql_true` (A2) | 0.678125 | 0.428125 |
| plain Q-learning `ql_false` (A0) | 0.71 | 0.46 |
| hand-written controller `rule_based` | 1.0 | 0.75 |
| A3 effect (`ql_true - ql_false`) | +0.043125 | +0.043125 |
| A2 effect (`ql_true - ql_false`) | -0.031875 | -0.031875 |
| rung increment A3 - A2 | +0.075 | +0.075 |

Effects and rung increments are differences between arms that share the same
zero-step runs, so they, their intervals, the registered categories and the
JSON summary are the same under both scorings; tiny differences in the last
floating-point digit come from the order of arithmetic only. The archived
ladder tables do not print the `ql_true` levels; they are listed in
`lab5_compliance_levels.csv`.

## Files

- `phase4_v2_knowledge_ladder_family.csv`, `..._descriptives.csv`,
  `..._comparability.csv`, `phase4_v2_knowledge_ladder.json`: the registered
  ladder outputs, same columns as the archived ones, corrected scoring.
- `lab5_compliance_levels.csv`: per arm (A3 full knowledge, A2 Brick
  structure) and planner mode, the mean over the 20 seeds of the lab5 goal
  rate, energy compliance, steady power and over-budget rate under both
  scorings (`declared_start_state` = corrected, `all_off_legacy` = archived),
  with the number of zero-step test runs.

## Command

From the repository root (needs the ignored raw benchmark CSVs):

    py -3 analysis/phase4_v2_corrected_tables.py          # writes this folder and ../phase4_v2_corrected
    py -3 analysis/phase4_v2_corrected_tables.py --check  # rebuilds and compares

The archived tables are unchanged and still reproduced by
`analysis/reproduce_phase4_v2_ladder.py`, which uses the legacy scoring on
purpose.
