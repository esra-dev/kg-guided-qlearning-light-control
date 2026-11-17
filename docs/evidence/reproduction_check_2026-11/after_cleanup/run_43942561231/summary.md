# Comparison of run 43942561231 with the archive (phase2)

- archives (repository-relative): `results/phase2_v2_corrected/run_30001857104`; `results/phase2_v2_corrected/run_30001878310`; `lab2_f1bdead=results/phase2_v2_corrected/run_30001857104`; `lab3_f1dead=results/phase2_v2_corrected/run_30001878310`; `lab3_f2dead_lowsun=results/phase2_v3_lowsun/run_43099277363`
- result data files compared: 152 (csv, json, jsonl, ttl; logs are not compared)
- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout or re-download of a Linux-written archive does not count as a difference
- verdict: NO real data difference (0 files with a real data difference or missing from the run)

## Totals

- IDENTICAL: 112
- DIFFERENT-EXPECTED-FIELDS-ONLY: 8
- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY: 20
- MISSING-IN-ARCHIVE: 12

## Per file kind

| kind | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|---|
| adapt-metrics | 12 | 0 | 0 | 0 |
| coverage | 8 | 0 | 0 | 0 |
| first-goal | 8 | 0 | 0 | 0 |
| iv-stats | 8 | 0 | 0 | 0 |
| learned-ttl | 0 | 8 | 0 | 0 |
| qtable | 56 | 0 | 0 | 12 |
| receipt | 0 | 0 | 20 | 0 |
| recovery | 12 | 0 | 0 | 0 |
| training-metrics | 8 | 0 | 0 | 0 |

## Per cell

| cell | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY | MISSING-IN-ARCHIVE |
|---|---|---|---|---|
| adapt-lab2_f1bdead-ql_false-seed-1 | 2 | 0 | 1 | 1 |
| adapt-lab2_f1bdead-ql_false-seed-2 | 2 | 0 | 1 | 1 |
| adapt-lab2_f1bdead-ql_true-seed-1 | 2 | 0 | 1 | 1 |
| adapt-lab2_f1bdead-ql_true-seed-2 | 2 | 0 | 1 | 1 |
| adapt-lab3_f1dead-ql_false-seed-1 | 2 | 0 | 1 | 1 |
| adapt-lab3_f1dead-ql_false-seed-2 | 2 | 0 | 1 | 1 |
| adapt-lab3_f1dead-ql_true-seed-1 | 2 | 0 | 1 | 1 |
| adapt-lab3_f1dead-ql_true-seed-2 | 2 | 0 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_false-seed-1 | 2 | 0 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_false-seed-2 | 2 | 0 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_true-seed-1 | 2 | 0 | 1 | 1 |
| adapt-lab3_f2dead_lowsun-ql_true-seed-2 | 2 | 0 | 1 | 1 |
| clean-lab2-stereo-false-seed-1 | 11 | 1 | 1 | 0 |
| clean-lab2-stereo-false-seed-2 | 11 | 1 | 1 | 0 |
| clean-lab2-stereo-true-seed-1 | 11 | 1 | 1 | 0 |
| clean-lab2-stereo-true-seed-2 | 11 | 1 | 1 | 0 |
| clean-lab3-stereo-false-seed-1 | 11 | 1 | 1 | 0 |
| clean-lab3-stereo-false-seed-2 | 11 | 1 | 1 | 0 |
| clean-lab3-stereo-true-seed-1 | 11 | 1 | 1 | 0 |
| clean-lab3-stereo-true-seed-2 | 11 | 1 | 1 | 0 |

## One example per kind of difference

- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY / receipt: `ADAPT_OK_stereotypes_false_lab2_f1bdead.json` (adapt-lab2_f1bdead-ql_false-seed-1) same values in all 17 shared keys; 27 added keys: effective_settings.adaptive_trust,effective_settings.arm_mixed_into_seed,effective_settings.base_seed,effective_settings.energy_prior_weight,effective_settings.knowledge_layer,effective_settings.paired_arm_rng,effective_settings.prior_decay,effective_settings.prior_decay_episodes,effective_settings.prior_decay_floor,effective_settings.prior_fade_visits,effective_settings.prior_scale,effective_settings.protocol_version,effective_settings.reward_clip,effective_settings.reward_shaping,effective_settings.run_seed,effective_settings.training_budget_
- MISSING-IN-ARCHIVE / qtable: `qtable_adapted_stereotypes_false_lab2_f1bdead.csv` (adapt-lab2_f1bdead-ql_false-seed-1) 
- DIFFERENT-EXPECTED-FIELDS-ONLY / learned-ttl: `benchmark/results/lab2/training_stereo_false/learned_stereotypes_false_lab2.ttl` (clean-lab2-stereo-false-seed-1) only the address in the '@prefix elem:' declaration differs; the file uses no elem: term, so its triples are the same
