# Comparison of run 43829843668 with the archive (phase2)

- archives (repository-relative): `results/phase2_v3_structonly/run_43590832041`
- result data files compared: 68 (csv, json, jsonl, ttl; logs are not compared)
- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout or re-download of a Linux-written archive does not count as a difference
- verdict: NO real data difference (0 files with a real data difference or missing from the run)

## Totals

- IDENTICAL: 64
- DIFFERENT-EXPECTED-FIELDS-ONLY: 4

## Per file kind

| kind | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY |
|---|---|---|
| adapt-metrics | 4 | 0 |
| coverage | 4 | 0 |
| first-goal | 4 | 0 |
| iv-stats | 4 | 0 |
| learned-ttl | 4 | 0 |
| qtable | 32 | 0 |
| receipt | 4 | 4 |
| recovery | 4 | 0 |
| training-metrics | 4 | 0 |

## Per cell

| cell | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY |
|---|---|---|
| adapt-lab2_f1bdead-ql_false-seed-1 | 4 | 0 |
| adapt-lab2_f1bdead-ql_false-seed-2 | 4 | 0 |
| adapt-lab2_f1bdead-ql_true-seed-1 | 4 | 0 |
| adapt-lab2_f1bdead-ql_true-seed-2 | 4 | 0 |
| clean-lab2-stereo-false-seed-1 | 12 | 1 |
| clean-lab2-stereo-false-seed-2 | 12 | 1 |
| clean-lab2-stereo-true-seed-1 | 12 | 1 |
| clean-lab2-stereo-true-seed-2 | 12 | 1 |

## One example per kind of difference

- DIFFERENT-EXPECTED-FIELDS-ONLY / receipt: `benchmark/results/lab2/training_stereo_false/TRAINING_OK.json` (clean-lab2-stereo-false-seed-1) receipt timestamp only
