# Comparison of run 43942632190 with the archive (phase2)

- archives (repository-relative): `results/phase2_v3_structonly/run_43590832041`
- result data files compared: 68 (csv, json, jsonl, ttl; logs are not compared)
- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout or re-download of a Linux-written archive does not count as a difference
- verdict: NO real data difference (0 files with a real data difference or missing from the run)

## Totals

- IDENTICAL: 56
- DIFFERENT-EXPECTED-FIELDS-ONLY: 4
- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY: 8

## Per file kind

| kind | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY |
|---|---|---|---|
| adapt-metrics | 4 | 0 | 0 |
| coverage | 4 | 0 | 0 |
| first-goal | 4 | 0 | 0 |
| iv-stats | 4 | 0 | 0 |
| learned-ttl | 0 | 4 | 0 |
| qtable | 32 | 0 | 0 |
| receipt | 0 | 0 | 8 |
| recovery | 4 | 0 | 0 |
| training-metrics | 4 | 0 | 0 |

## Per cell

| cell | IDENTICAL | DIFFERENT-EXPECTED-FIELDS-ONLY | DIFFERENT-RECEIPT-ADDED-KEYS-ONLY |
|---|---|---|---|
| adapt-lab2_f1bdead-ql_false-seed-1 | 3 | 0 | 1 |
| adapt-lab2_f1bdead-ql_false-seed-2 | 3 | 0 | 1 |
| adapt-lab2_f1bdead-ql_true-seed-1 | 3 | 0 | 1 |
| adapt-lab2_f1bdead-ql_true-seed-2 | 3 | 0 | 1 |
| clean-lab2-stereo-false-seed-1 | 11 | 1 | 1 |
| clean-lab2-stereo-false-seed-2 | 11 | 1 | 1 |
| clean-lab2-stereo-true-seed-1 | 11 | 1 | 1 |
| clean-lab2-stereo-true-seed-2 | 11 | 1 | 1 |

## One example per kind of difference

- DIFFERENT-RECEIPT-ADDED-KEYS-ONLY / receipt: `ADAPT_OK_stereotypes_false_lab2_f1bdead.json` (adapt-lab2_f1bdead-ql_false-seed-1) same values in all 22 shared keys; 22 added keys: effective_settings.adaptive_trust,effective_settings.arm_mixed_into_seed,effective_settings.base_seed,effective_settings.energy_prior_weight,effective_settings.knowledge_layer,effective_settings.paired_arm_rng,effective_settings.prior_decay,effective_settings.prior_decay_episodes,effective_settings.prior_decay_floor,effective_settings.prior_fade_visits,effective_settings.prior_scale,effective_settings.protocol_version,effective_settings.reward_clip,effective_settings.reward_shaping,effective_settings.run_seed,effective_settings.training_budget_
- DIFFERENT-EXPECTED-FIELDS-ONLY / learned-ttl: `benchmark/results/lab2/training_stereo_false/learned_stereotypes_false_lab2.ttl` (clean-lab2-stereo-false-seed-1) only the address in the '@prefix elem:' declaration differs; the file uses no elem: term, so its triples are the same
