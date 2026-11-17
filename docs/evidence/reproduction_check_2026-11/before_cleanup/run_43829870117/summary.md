# Comparison of run 43829870117 with the archive (phase4)

- archives (repository-relative): `results/phase4_v2_ladder/run_43494914019`
- result data files compared: 480 (csv, json, jsonl, ttl; logs are not compared)
- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout or re-download of a Linux-written archive does not count as a difference
- verdict: NO real data difference (0 files with a real data difference or missing from the run)

## Totals

- IDENTICAL: 416
- DIFFERENT-EXPECTED-FIELDS-ONLY: 32
- MISSING-IN-ARCHIVE: 32

## Per file kind

| kind | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|
| bench-step-log | 24 | 0 | 0 |
| benchmark-results | 0 | 24 | 0 |
| coverage | 40 | 0 | 0 |
| first-goal | 40 | 0 | 0 |
| iv-stats | 40 | 0 | 0 |
| learned-ttl | 40 | 0 | 0 |
| qtable | 184 | 0 | 32 |
| receipt | 0 | 8 | 0 |
| trace | 8 | 0 | 0 |
| training-metrics | 40 | 0 | 0 |

## Per cell

| cell | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|
| bench-lab4-ql_false-seed-1 | 23 | 2 | 0 |
| bench-lab4-ql_false-seed-2 | 23 | 2 | 0 |
| bench-lab4-ql_true-seed-1 | 23 | 2 | 0 |
| bench-lab4-ql_true-seed-2 | 23 | 2 | 0 |
| bench-lab4-rule_based-seed-1 | 22 | 2 | 0 |
| bench-lab4-rule_based-seed-2 | 22 | 2 | 0 |
| bench-lab5-ql_false-seed-1 | 23 | 2 | 0 |
| bench-lab5-ql_false-seed-2 | 23 | 2 | 0 |
| bench-lab5-ql_true-seed-1 | 23 | 2 | 0 |
| bench-lab5-ql_true-seed-2 | 23 | 2 | 0 |
| bench-lab5-rule_based-seed-1 | 22 | 2 | 0 |
| bench-lab5-rule_based-seed-2 | 22 | 2 | 0 |
| train-lab4-stereo-false-seed-1 | 18 | 1 | 4 |
| train-lab4-stereo-false-seed-2 | 18 | 1 | 4 |
| train-lab4-stereo-true-seed-1 | 18 | 1 | 4 |
| train-lab4-stereo-true-seed-2 | 18 | 1 | 4 |
| train-lab5-stereo-false-seed-1 | 18 | 1 | 4 |
| train-lab5-stereo-false-seed-2 | 18 | 1 | 4 |
| train-lab5-stereo-true-seed-1 | 18 | 1 | 4 |
| train-lab5-stereo-true-seed-2 | 18 | 1 | 4 |

## One example per kind of difference

- DIFFERENT-EXPECTED-FIELDS-ONLY / benchmark-results: `benchmark/results/lab4/ql_false/benchmark_results_ql_false.csv` (bench-lab4-ql_false-seed-1) rows=80; differing columns (rows differing): LegacyWallClockTotalEnergyCost(39); e.g. LegacyWallClockTotalEnergyCost run=12.00 archive=10.00
- DIFFERENT-EXPECTED-FIELDS-ONLY / receipt: `benchmark/results/lab4/training_stereo_false/TRAINING_OK.json` (train-lab4-stereo-false-seed-1) receipt timestamp only
- MISSING-IN-ARCHIVE / qtable: `qtable_initial_stereotypes_false_lab4_trust.csv` (train-lab4-stereo-false-seed-1) 
