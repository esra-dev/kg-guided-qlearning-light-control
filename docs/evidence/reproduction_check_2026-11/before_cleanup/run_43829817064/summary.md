# Comparison of run 43829817064 with the archive (phase1)

- archives (repository-relative): `results/phase1_v2_corrected/run_42834963612`
- result data files compared: 480 (csv, json, jsonl, ttl; logs are not compared)
- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout or re-download of a Linux-written archive does not count as a difference
- verdict: NO real data difference (0 files with a real data difference or missing from the run)

## Totals

- IDENTICAL: 416
- DIFFERENT-EXPECTED-FIELDS-ONLY: 24
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
| learned-ttl | 40 | 0 | 0 | 0 |
| qtable | 184 | 0 | 0 | 32 |
| receipt | 0 | 0 | 8 | 0 |
| trace | 8 | 0 | 0 | 0 |
| training-metrics | 40 | 0 | 0 | 0 |

## Per cell

| cell | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|---|
| bench-lab2-ql_false-seed-1 | 23 | 2 | 0 | 0 |
| bench-lab2-ql_false-seed-2 | 23 | 2 | 0 | 0 |
| bench-lab2-ql_true-seed-1 | 23 | 2 | 0 | 0 |
| bench-lab2-ql_true-seed-2 | 23 | 2 | 0 | 0 |
| bench-lab2-rule_based-seed-1 | 22 | 2 | 0 | 0 |
| bench-lab2-rule_based-seed-2 | 22 | 2 | 0 | 0 |
| bench-lab3-ql_false-seed-1 | 23 | 2 | 0 | 0 |
| bench-lab3-ql_false-seed-2 | 23 | 2 | 0 | 0 |
| bench-lab3-ql_true-seed-1 | 23 | 2 | 0 | 0 |
| bench-lab3-ql_true-seed-2 | 23 | 2 | 0 | 0 |
| bench-lab3-rule_based-seed-1 | 22 | 2 | 0 | 0 |
| bench-lab3-rule_based-seed-2 | 22 | 2 | 0 | 0 |
| train-lab2-stereo-false-seed-1 | 18 | 0 | 1 | 4 |
| train-lab2-stereo-false-seed-2 | 18 | 0 | 1 | 4 |
| train-lab2-stereo-true-seed-1 | 18 | 0 | 1 | 4 |
| train-lab2-stereo-true-seed-2 | 18 | 0 | 1 | 4 |
| train-lab3-stereo-false-seed-1 | 18 | 0 | 1 | 4 |
| train-lab3-stereo-false-seed-2 | 18 | 0 | 1 | 4 |
| train-lab3-stereo-true-seed-1 | 18 | 0 | 1 | 4 |
| train-lab3-stereo-true-seed-2 | 18 | 0 | 1 | 4 |

## One example per kind of difference

- DIFFERENT-EXPECTED-FIELDS-ONLY / benchmark-results: `benchmark/results/lab2/ql_false/benchmark_results_ql_false.csv` (bench-lab2-ql_false-seed-1) rows=80; differing columns (rows differing): LegacyWallClockTotalEnergyCost(48); e.g. LegacyWallClockTotalEnergyCost run=8.00 archive=9.00
- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY / receipt: `benchmark/results/lab2/training_stereo_false/TRAINING_OK.json` (train-lab2-stereo-false-seed-1) same values in all 15 shared keys; timestamp differs; 7 added keys: stereo_effective_props.stereo.bandMirrorInit,stereo_effective_props.stereo.bandMirrorPrior,stereo_effective_props.stereo.irrelevantDvPrior,stereo_effective_props.stereo.redundancyOnly,stereo_requested_props.stereo.bandMirrorInit,stereo_requested_props.stereo.bandMirrorPrior,stereo_requested_props.stereo.irrelevantDvPrior
- MISSING-IN-ARCHIVE / qtable: `qtable_initial_stereotypes_false_lab2_trust.csv` (train-lab2-stereo-false-seed-1) 
