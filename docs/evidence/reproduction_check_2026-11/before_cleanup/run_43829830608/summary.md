# Comparison of run 43829830608 with the archive (phase2)

- archives (repository-relative): `results/phase2_v2_corrected/run_30001857104`; `results/phase2_v2_corrected/run_30001878310`; `results/phase2_v3_lowsun/run_43099277363`; `lab2_f1bdead=results/phase2_v2_corrected/run_30001857104`; `lab3_f1dead=results/phase2_v2_corrected/run_30001878310`; `lab3_f2dead_lowsun=results/phase2_v3_lowsun/run_43099277363`
- result data files compared: 152 (csv, json, jsonl, ttl; logs are not compared)
- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout or re-download of a Linux-written archive does not count as a difference
- verdict: NO real data difference (0 files with a real data difference or missing from the run)

## Totals

- IDENTICAL: 120
- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY: 20
- MISSING-IN-ARCHIVE: 12

## Per file kind

| kind | IDENTICAL | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|
| adapt-metrics | 12 | 0 | 0 |
| coverage | 8 | 0 | 0 |
| first-goal | 8 | 0 | 0 |
| iv-stats | 8 | 0 | 0 |
| learned-ttl | 8 | 0 | 0 |
| qtable | 56 | 0 | 12 |
| receipt | 0 | 20 | 0 |
| recovery | 12 | 0 | 0 |
| training-metrics | 8 | 0 | 0 |

## Per cell

| cell | IDENTICAL | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|
| adapt-lab2_f1bdead-ql_false-seed-1 | 2 | 1 | 1 |
| adapt-lab2_f1bdead-ql_false-seed-2 | 2 | 1 | 1 |
| adapt-lab2_f1bdead-ql_true-seed-1 | 2 | 1 | 1 |
| adapt-lab2_f1bdead-ql_true-seed-2 | 2 | 1 | 1 |
| adapt-lab3_f1dead-ql_false-seed-1 | 2 | 1 | 1 |
| adapt-lab3_f1dead-ql_false-seed-2 | 2 | 1 | 1 |
| adapt-lab3_f1dead-ql_true-seed-1 | 2 | 1 | 1 |
| adapt-lab3_f1dead-ql_true-seed-2 | 2 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_false-seed-1 | 2 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_false-seed-2 | 2 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_true-seed-1 | 2 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_true-seed-2 | 2 | 1 | 1 |
| clean-lab2-stereo-false-seed-1 | 12 | 1 | 0 |
| clean-lab2-stereo-false-seed-2 | 12 | 1 | 0 |
| clean-lab2-stereo-true-seed-1 | 12 | 1 | 0 |
| clean-lab2-stereo-true-seed-2 | 12 | 1 | 0 |
| clean-lab3-stereo-false-seed-1 | 12 | 1 | 0 |
| clean-lab3-stereo-false-seed-2 | 12 | 1 | 0 |
| clean-lab3-stereo-true-seed-1 | 12 | 1 | 0 |
| clean-lab3-stereo-true-seed-2 | 12 | 1 | 0 |

## One example per kind of difference

- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY / receipt: `ADAPT_OK_stereotypes_false_lab2_f1bdead.json` (adapt-lab2_f1bdead-ql_false-seed-1) same values in all 17 shared keys; 5 added keys: knowledge_layer,knowledge_layer_delivered,lowsun_pin_delivered,scenario_file_sha256,scenario_sunshine_values
- MISSING-IN-ARCHIVE / qtable: `qtable_adapted_stereotypes_false_lab2_f1bdead.csv` (adapt-lab2_f1bdead-ql_false-seed-1) 
