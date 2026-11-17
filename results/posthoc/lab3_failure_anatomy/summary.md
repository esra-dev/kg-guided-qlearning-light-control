# lab3: which situations fail

Status: post-hoc, descriptive. Counts and shares only; no p-values or q-values.
Seeds 1..20 (20), summed or pooled over seeds.

## Training: failed episodes, episodes 0..149 + 150..2999 (T = ql_true, F = ql_false)

| id | sun | on | goal_at_start | baseline | brick_only | kg_only | pbrs_only | redundancy_only |
|---|---|---|---|---|---|---|---|---|
| 1 | 0 | none | no | T 38+53 / F 38+53 | T 162+261 / F 38+53 | T 160+56 / F 38+53 | T 38+53 / F 38+53 | T 18+6 / F 38+53 |
| 2 | 0 | Z1Light;Z2Light | yes | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 |
| 5 | 100 | none | no | T 26+16 / F 26+16 | T 5+10 / F 26+16 | T 8+14 / F 26+16 | T 26+16 / F 26+16 | T 5+0 / F 26+16 |
| 6 | 100 | Z1Light;Z2Light | yes | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 |
| 7 | 100 | Z1Light | no | T 8+4 / F 8+4 | T 49+30 / F 8+4 | T 34+9 / F 8+4 | T 8+4 / F 8+4 | T 2+0 / F 8+4 |
| 9 | 400 | none | no | T 7+10 / F 7+10 | T 2+10 / F 7+10 | T 2+12 / F 7+10 | T 7+10 / F 7+10 | T 0+0 / F 7+10 |
| 10 | 400 | Z1Light;Z2Light | yes | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 |
| 11 | 400 | Z2Light | no | T 0+1 / F 0+1 | T 16+0 / F 0+1 | T 18+0 / F 0+1 | T 0+1 / F 0+1 | T 1+0 / F 0+1 |
| 13 | 900 | none | no | T 5+8 / F 5+8 | T 0+0 / F 5+8 | T 0+1 / F 5+8 | T 5+8 / F 5+8 | T 0+0 / F 5+8 |
| 16 | 900 | Z1Blinds;Z2Blinds | yes | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 | T 0+0 / F 0+0 |

## Test: share of test runs reaching the goal (T / F / R = rule_based)

| id | class | sun | on | baseline | brick_only | kg_only | pbrs_only | redundancy_only |
|---|---|---|---|---|---|---|---|---|
| 1 | trained | 0 | none | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 2 | trained | 0 | Z1Light;Z2Light | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 3 | unseen | 0 | Z2Light | T 0.78 F 0.78 R 1.00 | T 0.16 F 0.78 R 1.00 | T 0.40 F 0.78 R 1.00 | T 0.78 F 0.78 R 1.00 | T 0.95 F 0.78 R 1.00 |
| 4 | unseen | 0 | Z1Light;Z2Light;Spotlight | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 5 | trained | 100 | none | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 6 | trained | 100 | Z1Light;Z2Light | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 7 | trained | 100 | Z1Light | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 8 | unseen | 100 | Spotlight | T 0.99 F 0.99 R 1.00 | T 1.00 F 0.99 R 1.00 | T 1.00 F 0.99 R 1.00 | T 0.99 F 0.99 R 1.00 | T 1.00 F 0.99 R 1.00 |
| 9 | trained | 400 | none | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 10 | trained | 400 | Z1Light;Z2Light | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 11 | trained | 400 | Z2Light | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 12 | unseen | 400 | Z1Light;Z2Light;Spotlight | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 13 | trained | 900 | none | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 14 | unseen | 900 | Z1Light;Z2Light | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |
| 15 | unseen | 900 | Z2Light | T 0.85 F 0.85 R 1.00 | T 1.00 F 0.85 R 1.00 | T 1.00 F 0.85 R 1.00 | T 0.85 F 0.85 R 1.00 | T 1.00 F 0.85 R 1.00 |
| 16 | trained | 900 | Z1Blinds;Z2Blinds | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 | T 1.00 F 1.00 R 1.00 |

Full tables: training_failures_by_scenario.csv, test_goal_share_by_start.csv.
