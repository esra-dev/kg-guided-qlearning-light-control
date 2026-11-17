# Comparison of run 43942761735 with the archive (phase4)

- archives (repository-relative): `results/phase4_v2_ladder/run_43494914019`
- result data files compared: 480 (csv, json, jsonl, ttl; logs are not compared)
- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout or re-download of a Linux-written archive does not count as a difference
- verdict: NO real data difference (0 files with a real data difference or missing from the run)

## Totals

- IDENTICAL: 376
- DIFFERENT-EXPECTED-FIELDS-ONLY: 64
- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY: 8
- MISSING-IN-ARCHIVE: 32

## Per file kind

| kind | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|---|
| bench-step-log | 24 | 0 | 0 | 0 |
| benchmark-results | 0 | 24 | 0 | 0 |
| coverage | 40 | 0 | 0 | 0 |
| first-goal | 40 | 0 | 0 | 0 |
| iv-stats | 40 | 0 | 0 | 0 |
| learned-ttl | 0 | 40 | 0 | 0 |
| qtable | 184 | 0 | 0 | 32 |
| receipt | 0 | 0 | 8 | 0 |
| trace | 8 | 0 | 0 | 0 |
| training-metrics | 40 | 0 | 0 | 0 |

## Per cell

| cell | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|---|
| bench-lab4-ql_false-seed-1 | 21 | 4 | 0 | 0 |
| bench-lab4-ql_false-seed-2 | 21 | 4 | 0 | 0 |
| bench-lab4-ql_true-seed-1 | 21 | 4 | 0 | 0 |
| bench-lab4-ql_true-seed-2 | 21 | 4 | 0 | 0 |
| bench-lab4-rule_based-seed-1 | 20 | 4 | 0 | 0 |
| bench-lab4-rule_based-seed-2 | 20 | 4 | 0 | 0 |
| bench-lab5-ql_false-seed-1 | 21 | 4 | 0 | 0 |
| bench-lab5-ql_false-seed-2 | 21 | 4 | 0 | 0 |
| bench-lab5-ql_true-seed-1 | 21 | 4 | 0 | 0 |
| bench-lab5-ql_true-seed-2 | 21 | 4 | 0 | 0 |
| bench-lab5-rule_based-seed-1 | 20 | 4 | 0 | 0 |
| bench-lab5-rule_based-seed-2 | 20 | 4 | 0 | 0 |
| train-lab4-stereo-false-seed-1 | 16 | 2 | 1 | 4 |
| train-lab4-stereo-false-seed-2 | 16 | 2 | 1 | 4 |
| train-lab4-stereo-true-seed-1 | 16 | 2 | 1 | 4 |
| train-lab4-stereo-true-seed-2 | 16 | 2 | 1 | 4 |
| train-lab5-stereo-false-seed-1 | 16 | 2 | 1 | 4 |
| train-lab5-stereo-false-seed-2 | 16 | 2 | 1 | 4 |
| train-lab5-stereo-true-seed-1 | 16 | 2 | 1 | 4 |
| train-lab5-stereo-true-seed-2 | 16 | 2 | 1 | 4 |

## One example per kind of difference

- DIFFERENT-EXPECTED-FIELDS-ONLY / benchmark-results: `benchmark/results/lab4/ql_false/benchmark_results_ql_false.csv` (bench-lab4-ql_false-seed-1) rows=80; differing columns (rows differing): LegacyWallClockTotalEnergyCost(35); e.g. LegacyWallClockTotalEnergyCost run=9.00 archive=10.00
- DIFFERENT-EXPECTED-FIELDS-ONLY / learned-ttl: `benchmark/results/lab4/ql_false/learned_stereotypes_false_lab4.ttl` (bench-lab4-ql_false-seed-1) only the address in the '@prefix elem:' declaration differs; the file uses no elem: term, so its triples are the same
- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY / receipt: `benchmark/results/lab4/training_stereo_false/TRAINING_OK.json` (train-lab4-stereo-false-seed-1) same values in all 22 shared keys; timestamp differs; 9 added keys: knowledge_layer_delivered,knowledge_layer_requested,prior_decay_episodes_effective,prior_decay_mode_effective,reward_clip_effective,simulator_flow,simulator_profile_reported,stereo_energy_prior_weight_effective,stereo_energy_prior_weight_requested
- MISSING-IN-ARCHIVE / qtable: `qtable_initial_stereotypes_false_lab4_trust.csv` (train-lab4-stereo-false-seed-1) 
