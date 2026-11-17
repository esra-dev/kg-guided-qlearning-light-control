# Test results on trained versus unseen starting situations

Status: post-hoc, descriptive. No p-values or q-values are computed.
Seeds 1..20 (20); bootstrap 10000 resamples, base seed 20261116.

## Starting situations

| lab | n_test_starts | n_trained | n_unseen | unseen_ids |
|---|---|---|---|---|
| lab1 | 8 | 6 | 2 | 5;8 |
| lab2 | 16 | 11 | 5 | 3;8;11;14;15 |
| lab3 | 16 | 10 | 6 | 3;4;8;12;14;15 |
| lab4 | 16 | 10 | 6 | 3;4;8;12;14;15 |
| lab4dual | 16 | 10 | 6 | 3;4;8;12;14;15 |
| lab4chain | 16 | 10 | 6 | 3;4;8;12;14;15 |
| lab5 | 16 | 10 | 6 | 3;4;8;12;14;15 |

## goal_share: ql_true - ql_false (mean [95% bootstrap]; seeds +/0/-)

| run_mode | lab | subset | ql_true | ql_false | difference | ci | signs |
|---|---|---|---|---|---|---|---|
| phase1_v2_baseline | lab1 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab1 | unseen | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab2 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab2 | unseen | 0.8440 | 0.8440 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab3 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab3 | unseen | 0.9367 | 0.9367 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab1 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab1 | unseen | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab2 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab2 | unseen | 1.0000 | 0.8440 | 0.1560 | [0.0880, 0.2340] | 14/6/0 |
| phase1_v2_brick_only | lab3 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab3 | unseen | 0.8600 | 0.9367 | -0.0767 | [-0.1217, -0.0300] | 4/3/13 |
| phase1_v2_kg_only | lab1 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_kg_only | lab1 | unseen | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_kg_only | lab2 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_kg_only | lab2 | unseen | 1.0000 | 0.8440 | 0.1560 | [0.0900, 0.2340] | 14/6/0 |
| phase1_v2_kg_only | lab3 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_kg_only | lab3 | unseen | 0.9000 | 0.9367 | -0.0367 | [-0.0900, 0.0200] | 6/5/9 |
| phase1_v2_pbrs_only | lab1 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab1 | unseen | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab2 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab2 | unseen | 0.8180 | 0.8180 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab3 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab3 | unseen | 0.9367 | 0.9367 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab1 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab1 | unseen | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab2 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab2 | unseen | 0.9600 | 0.8440 | 0.1160 | [0.0320, 0.2000] | 13/4/3 |
| phase1_v2_redundancy_only | lab3 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab3 | unseen | 0.9917 | 0.9367 | 0.0550 | [0.0150, 0.0950] | 9/10/1 |
| phase4_v2 | lab4 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2 | lab4 | unseen | 1.0000 | 0.9500 | 0.0500 | [0.0150, 0.0933] | 7/13/0 |
| phase4_v2 | lab4dual | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2 | lab4dual | unseen | 1.0000 | 0.9567 | 0.0433 | [0.0200, 0.0717] | 11/9/0 |
| phase4_v2 | lab4chain | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2 | lab4chain | unseen | 1.0000 | 0.9600 | 0.0400 | [0.0233, 0.0600] | 13/7/0 |
| phase4_v2 | lab5 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2 | lab5 | unseen | 0.9500 | 0.9733 | -0.0233 | [-0.0717, 0.0250] | 3/11/6 |
| phase4_v2_brick_only | lab4 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab4 | unseen | 1.0000 | 0.9500 | 0.0500 | [0.0167, 0.0917] | 7/13/0 |
| phase4_v2_brick_only | lab4dual | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab4dual | unseen | 1.0000 | 0.9567 | 0.0433 | [0.0200, 0.0700] | 11/9/0 |
| phase4_v2_brick_only | lab4chain | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab4chain | unseen | 1.0000 | 0.9600 | 0.0400 | [0.0233, 0.0600] | 13/7/0 |
| phase4_v2_brick_only | lab5 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab5 | unseen | 0.9133 | 0.9733 | -0.0600 | [-0.1000, -0.0183] | 4/4/12 |
| phase4_v2_redundancy_only | lab4 | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_redundancy_only | lab4 | unseen | 0.9850 | 0.9500 | 0.0350 | [-0.0067, 0.0817] | 6/11/3 |
| phase4_v2_redundancy_only | lab4dual | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_redundancy_only | lab4dual | unseen | 0.9883 | 0.9567 | 0.0317 | [0.0083, 0.0600] | 9/8/3 |
| phase4_v2_redundancy_only | lab4chain | trained | 1.0000 | 1.0000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_redundancy_only | lab4chain | unseen | 0.9967 | 0.9600 | 0.0367 | [0.0200, 0.0550] | 12/8/0 |

## mean_steps: ql_true - ql_false (mean [95% bootstrap]; seeds +/0/-)

| run_mode | lab | subset | ql_true | ql_false | difference | ci | signs |
|---|---|---|---|---|---|---|---|
| phase1_v2_baseline | lab1 | trained | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab1 | unseen | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab2 | trained | 1.3409 | 1.3409 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab2 | unseen | 4.9220 | 4.9220 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab3 | trained | 1.2400 | 1.2400 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_baseline | lab3 | unseen | 2.1983 | 2.1983 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab1 | trained | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab1 | unseen | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_brick_only | lab2 | trained | 1.2727 | 1.3409 | -0.0682 | [-0.1727, 0.0318] | 6/6/8 |
| phase1_v2_brick_only | lab2 | unseen | 0.8780 | 4.9220 | -4.0440 | [-5.4560, -2.8000] | 0/0/20 |
| phase1_v2_brick_only | lab3 | trained | 1.5150 | 1.2400 | 0.2750 | [0.2000, 0.3450] | 19/0/1 |
| phase1_v2_brick_only | lab3 | unseen | 3.6233 | 2.1983 | 1.4250 | [0.5867, 2.2283] | 14/0/6 |
| phase1_v2_kg_only | lab1 | trained | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_kg_only | lab1 | unseen | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_kg_only | lab2 | trained | 1.2364 | 1.3409 | -0.1045 | [-0.1773, -0.0318] | 3/5/12 |
| phase1_v2_kg_only | lab2 | unseen | 0.8840 | 4.9220 | -4.0380 | [-5.3920, -2.7980] | 0/0/20 |
| phase1_v2_kg_only | lab3 | trained | 1.4150 | 1.2400 | 0.1750 | [0.1000, 0.2500] | 15/4/1 |
| phase1_v2_kg_only | lab3 | unseen | 2.7933 | 2.1983 | 0.5950 | [-0.5000, 1.6383] | 11/1/8 |
| phase1_v2_pbrs_only | lab1 | trained | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab1 | unseen | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab2 | trained | 1.3500 | 1.3500 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab2 | unseen | 5.3960 | 5.3960 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab3 | trained | 1.2400 | 1.2400 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_pbrs_only | lab3 | unseen | 2.1983 | 2.1983 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab1 | trained | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab1 | unseen | 0.5000 | 0.5000 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase1_v2_redundancy_only | lab2 | trained | 1.3727 | 1.3409 | 0.0318 | [-0.0818, 0.1500] | 11/0/9 |
| phase1_v2_redundancy_only | lab2 | unseen | 2.2300 | 4.9220 | -2.6920 | [-4.3040, -1.0540] | 4/0/16 |
| phase1_v2_redundancy_only | lab3 | trained | 1.2750 | 1.2400 | 0.0350 | [-0.0550, 0.1250] | 7/7/6 |
| phase1_v2_redundancy_only | lab3 | unseen | 1.1650 | 2.1983 | -1.0333 | [-1.8917, -0.2133] | 9/1/10 |
| phase4_v2 | lab4 | trained | 1.0100 | 1.0600 | -0.0500 | [-0.1000, 0.0000] | 3/7/10 |
| phase4_v2 | lab4 | unseen | 0.6833 | 2.4533 | -1.7700 | [-2.4933, -1.1333] | 1/0/19 |
| phase4_v2 | lab4dual | trained | 1.2050 | 1.2250 | -0.0200 | [-0.0800, 0.0350] | 8/5/7 |
| phase4_v2 | lab4dual | unseen | 1.0100 | 2.9633 | -1.9533 | [-2.5133, -1.4333] | 0/1/19 |
| phase4_v2 | lab4chain | trained | 1.0950 | 1.0750 | 0.0200 | [-0.0300, 0.0700] | 10/3/7 |
| phase4_v2 | lab4chain | unseen | 0.6667 | 2.6583 | -1.9917 | [-2.3617, -1.6350] | 0/0/20 |
| phase4_v2 | lab5 | trained | 1.0300 | 1.0550 | -0.0250 | [-0.0600, 0.0050] | 2/13/5 |
| phase4_v2 | lab5 | unseen | 1.4167 | 0.9017 | 0.5150 | [-0.2917, 1.2900] | 15/0/5 |
| phase4_v2_brick_only | lab4 | trained | 1.0750 | 1.0600 | 0.0150 | [-0.0400, 0.0650] | 9/5/6 |
| phase4_v2_brick_only | lab4 | unseen | 0.6833 | 2.4533 | -1.7700 | [-2.4850, -1.1417] | 1/0/19 |
| phase4_v2_brick_only | lab4dual | trained | 1.2250 | 1.2250 | 0.0000 | [-0.0550, 0.0500] | 8/6/6 |
| phase4_v2_brick_only | lab4dual | unseen | 1.1200 | 2.9633 | -1.8433 | [-2.3633, -1.3450] | 0/1/19 |
| phase4_v2_brick_only | lab4chain | trained | 1.1100 | 1.0750 | 0.0350 | [-0.0150, 0.0900] | 9/4/7 |
| phase4_v2_brick_only | lab4chain | unseen | 0.6833 | 2.6583 | -1.9750 | [-2.3550, -1.6017] | 0/0/20 |
| phase4_v2_brick_only | lab5 | trained | 1.1200 | 1.0550 | 0.0650 | [0.0150, 0.1150] | 10/7/3 |
| phase4_v2_brick_only | lab5 | unseen | 2.1883 | 0.9017 | 1.2867 | [0.5517, 1.9733] | 16/0/4 |
| phase4_v2_redundancy_only | lab4 | trained | 1.0900 | 1.0600 | 0.0300 | [-0.0250, 0.0850] | 8/8/4 |
| phase4_v2_redundancy_only | lab4 | unseen | 1.3633 | 2.4533 | -1.0900 | [-1.9067, -0.3500] | 3/0/17 |
| phase4_v2_redundancy_only | lab4dual | trained | 1.2900 | 1.2250 | 0.0650 | [-0.0150, 0.1400] | 13/1/6 |
| phase4_v2_redundancy_only | lab4dual | unseen | 2.0750 | 2.9633 | -0.8883 | [-1.5850, -0.2633] | 6/0/14 |
| phase4_v2_redundancy_only | lab4chain | trained | 1.0700 | 1.0750 | -0.0050 | [-0.0650, 0.0600] | 7/3/10 |
| phase4_v2_redundancy_only | lab4chain | unseen | 1.4967 | 2.6583 | -1.1617 | [-1.6183, -0.7000] | 4/0/16 |

Full tables: contrasts.csv, cells_per_seed.csv, cell_means.csv.
