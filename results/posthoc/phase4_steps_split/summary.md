# Phase-4 test steps split into wasted steps and actuator cycling

Status: post-hoc, descriptive. No p-values or q-values are computed.
Seeds 1..20 (20); bootstrap 10000 resamples, base seed 20261116.

Identity Steps == WastedSteps + ActuatorCyclingCount: 52800 test-run rows checked, 0 violations.

## ql_true - ql_false per measure (mean [95% bootstrap]; seeds +/0/-)

| run_mode | lab | measure | ql_true | ql_false | difference | ci | signs |
|---|---|---|---|---|---|---|---|
| phase4_v2 | lab4 | mean_steps | 0.8875 | 1.5825 | -0.6950 | [-0.9894, -0.4437] | 1/0/19 |
| phase4_v2 | lab4 | mean_wasted_steps | 0.0000 | 0.5519 | -0.5519 | [-0.8369, -0.3187] | 0/1/19 |
| phase4_v2 | lab4 | mean_actuator_cycling | 0.8875 | 1.0306 | -0.1431 | [-0.2150, -0.0781] | 3/0/17 |
| phase4_v2 | lab4 | mean_wasted_plus_cycling | 0.8875 | 1.5825 | -0.6950 | [-0.9894, -0.4437] | 1/0/19 |
| phase4_v2 | lab4 | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2 | lab4dual | mean_steps | 1.1319 | 1.8769 | -0.7450 | [-0.9706, -0.5338] | 1/0/19 |
| phase4_v2 | lab4dual | mean_wasted_steps | 0.0000 | 0.5356 | -0.5356 | [-0.7213, -0.3731] | 0/1/19 |
| phase4_v2 | lab4dual | mean_actuator_cycling | 1.1319 | 1.3413 | -0.2094 | [-0.2913, -0.1313] | 1/1/18 |
| phase4_v2 | lab4dual | mean_wasted_plus_cycling | 1.1319 | 1.8769 | -0.7450 | [-0.9706, -0.5338] | 1/0/19 |
| phase4_v2 | lab4dual | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2 | lab4chain | mean_steps | 0.9344 | 1.6687 | -0.7344 | [-0.8869, -0.5887] | 0/0/20 |
| phase4_v2 | lab4chain | mean_wasted_steps | 0.0000 | 0.5288 | -0.5288 | [-0.6675, -0.4088] | 0/0/20 |
| phase4_v2 | lab4chain | mean_actuator_cycling | 0.9344 | 1.1400 | -0.2056 | [-0.2831, -0.1337] | 2/0/18 |
| phase4_v2 | lab4chain | mean_wasted_plus_cycling | 0.9344 | 1.6687 | -0.7344 | [-0.8869, -0.5887] | 0/0/20 |
| phase4_v2 | lab4chain | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2 | lab5 | mean_steps | 1.1750 | 0.9975 | 0.1775 | [-0.1212, 0.4662] | 14/1/5 |
| phase4_v2 | lab5 | mean_wasted_steps | 0.4000 | 0.2388 | 0.1613 | [-0.1806, 0.4987] | 11/3/6 |
| phase4_v2 | lab5 | mean_actuator_cycling | 0.7750 | 0.7588 | 0.0162 | [-0.0500, 0.0750] | 11/2/7 |
| phase4_v2 | lab5 | mean_wasted_plus_cycling | 1.1750 | 0.9975 | 0.1775 | [-0.1212, 0.4662] | 14/1/5 |
| phase4_v2 | lab5 | share_zero_steps | 0.5625 | 0.5625 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab4 | mean_steps | 0.9281 | 1.5825 | -0.6544 | [-0.9544, -0.3987] | 1/0/19 |
| phase4_v2_brick_only | lab4 | mean_wasted_steps | 0.0000 | 0.5519 | -0.5519 | [-0.8450, -0.3150] | 0/1/19 |
| phase4_v2_brick_only | lab4 | mean_actuator_cycling | 0.9281 | 1.0306 | -0.1025 | [-0.1706, -0.0350] | 4/0/16 |
| phase4_v2_brick_only | lab4 | mean_wasted_plus_cycling | 0.9281 | 1.5825 | -0.6544 | [-0.9544, -0.3987] | 1/0/19 |
| phase4_v2_brick_only | lab4 | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab4dual | mean_steps | 1.1856 | 1.8769 | -0.6912 | [-0.9037, -0.4944] | 1/0/19 |
| phase4_v2_brick_only | lab4dual | mean_wasted_steps | 0.0000 | 0.5356 | -0.5356 | [-0.7219, -0.3744] | 0/1/19 |
| phase4_v2_brick_only | lab4dual | mean_actuator_cycling | 1.1856 | 1.3413 | -0.1556 | [-0.2375, -0.0763] | 3/1/16 |
| phase4_v2_brick_only | lab4dual | mean_wasted_plus_cycling | 1.1856 | 1.8769 | -0.6912 | [-0.9037, -0.4944] | 1/0/19 |
| phase4_v2_brick_only | lab4dual | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab4chain | mean_steps | 0.9500 | 1.6687 | -0.7188 | [-0.8725, -0.5637] | 1/0/19 |
| phase4_v2_brick_only | lab4chain | mean_wasted_steps | 0.0000 | 0.5288 | -0.5288 | [-0.6669, -0.4050] | 0/0/20 |
| phase4_v2_brick_only | lab4chain | mean_actuator_cycling | 0.9500 | 1.1400 | -0.1900 | [-0.2700, -0.1131] | 3/0/17 |
| phase4_v2_brick_only | lab4chain | mean_wasted_plus_cycling | 0.9500 | 1.6687 | -0.7188 | [-0.8725, -0.5637] | 1/0/19 |
| phase4_v2_brick_only | lab4chain | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_brick_only | lab5 | mean_steps | 1.5206 | 0.9975 | 0.5231 | [0.2450, 0.7712] | 17/0/3 |
| phase4_v2_brick_only | lab5 | mean_wasted_steps | 0.0663 | 0.2388 | -0.1725 | [-0.3750, -0.0112] | 9/4/7 |
| phase4_v2_brick_only | lab5 | mean_actuator_cycling | 1.4544 | 0.7588 | 0.6956 | [0.5194, 0.8644] | 19/0/1 |
| phase4_v2_brick_only | lab5 | mean_wasted_plus_cycling | 1.5206 | 0.9975 | 0.5231 | [0.2450, 0.7712] | 17/0/3 |
| phase4_v2_brick_only | lab5 | share_zero_steps | 0.5625 | 0.5625 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_redundancy_only | lab4 | mean_steps | 1.1925 | 1.5825 | -0.3900 | [-0.7137, -0.1000] | 4/0/16 |
| phase4_v2_redundancy_only | lab4 | mean_wasted_steps | 0.0169 | 0.5519 | -0.5350 | [-0.8238, -0.3050] | 1/0/19 |
| phase4_v2_redundancy_only | lab4 | mean_actuator_cycling | 1.1756 | 1.0306 | 0.1450 | [0.0150, 0.2938] | 13/0/7 |
| phase4_v2_redundancy_only | lab4 | mean_wasted_plus_cycling | 1.1925 | 1.5825 | -0.3900 | [-0.7137, -0.1000] | 4/0/16 |
| phase4_v2_redundancy_only | lab4 | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_redundancy_only | lab4dual | mean_steps | 1.5844 | 1.8769 | -0.2925 | [-0.5775, -0.0394] | 6/1/13 |
| phase4_v2_redundancy_only | lab4dual | mean_wasted_steps | 0.0563 | 0.5356 | -0.4794 | [-0.6775, -0.3112] | 1/0/19 |
| phase4_v2_redundancy_only | lab4dual | mean_actuator_cycling | 1.5281 | 1.3413 | 0.1869 | [0.0575, 0.3162] | 13/0/7 |
| phase4_v2_redundancy_only | lab4dual | mean_wasted_plus_cycling | 1.5844 | 1.8769 | -0.2925 | [-0.5775, -0.0394] | 6/1/13 |
| phase4_v2_redundancy_only | lab4dual | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| phase4_v2_redundancy_only | lab4chain | mean_steps | 1.2300 | 1.6687 | -0.4388 | [-0.6206, -0.2500] | 3/0/17 |
| phase4_v2_redundancy_only | lab4chain | mean_wasted_steps | 0.0506 | 0.5288 | -0.4781 | [-0.6150, -0.3550] | 0/0/20 |
| phase4_v2_redundancy_only | lab4chain | mean_actuator_cycling | 1.1794 | 1.1400 | 0.0394 | [-0.0875, 0.1731] | 9/1/10 |
| phase4_v2_redundancy_only | lab4chain | mean_wasted_plus_cycling | 1.2300 | 1.6687 | -0.4388 | [-0.6206, -0.2500] | 3/0/17 |
| phase4_v2_redundancy_only | lab4chain | share_zero_steps | 0.4375 | 0.4375 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |

Full tables: contrasts.csv, cells_per_seed.csv, cell_means.csv, identity_check.csv.
