# Training success on episodes that can fail

Status: post-hoc, descriptive. No p-values or q-values are computed.
Seeds: 1..20 (20). Labs: lab1, lab2, lab3. Run modes read: phase1_v2_baseline, phase1_v2_brick_only, phase1_v2_kg_only, phase1_v2_pbrs_only, phase1_v2_redundancy_only.
Bootstrap: 10000 resamples of the seed differences, percentile 95% interval, random.Random seeded per (contrast, lab, window) from base seed 20261116.

## Terminal-at-start scenarios (from the start state, verified in every file)

- lab1: ScenarioIds 2, 4, 6; 1500 of 3000 episodes per file are terminal-at-start.
- lab2: ScenarioIds 2, 6, 10, 16; 1091 of 3000 episodes per file are terminal-at-start.
- lab3: ScenarioIds 2, 6, 10, 16; 1200 of 3000 episodes per file are terminal-at-start.

Scenario schedule identical across all arms and run modes for every (lab, seed): yes.
Plain-arm training files byte-identical across run modes: 58 of 60 (lab, seed) pairs; differing pairs: lab2/seed7 (phase1_v2_pbrs_only), lab2/seed8 (phase1_v2_pbrs_only).

## Paired contrasts (mean paired difference [95% bootstrap interval]; seeds +/0/-)

### success_share_can_fail

| contrast | lab | window | mean | ci | signs |
|---|---|---|---|---|---|
| full_knowledge_minus_plain | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_plain | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_plain | lab2 | all | 0.0058 | [0.0042, 0.0078] | 20/0/0 |
| full_knowledge_minus_plain | lab2 | first150 | 0.0766 | [0.0536, 0.1026] | 20/0/0 |
| full_knowledge_minus_plain | lab3 | all | -0.0038 | [-0.0058, -0.0018] | 5/1/14 |
| full_knowledge_minus_plain | lab3 | first150 | -0.0767 | [-0.1011, -0.0517] | 1/2/17 |
| rule_minus_plain | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| rule_minus_plain | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| rule_minus_plain | lab2 | all | 0.0041 | [0.0024, 0.0060] | 17/0/3 |
| rule_minus_plain | lab2 | first150 | 0.0484 | [0.0240, 0.0750] | 15/1/4 |
| rule_minus_plain | lab3 | all | 0.0040 | [0.0026, 0.0054] | 18/0/2 |
| rule_minus_plain | lab3 | first150 | 0.0322 | [0.0156, 0.0483] | 16/1/3 |
| structure_only_minus_plain | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_plain | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_plain | lab2 | all | 0.0058 | [0.0042, 0.0078] | 20/0/0 |
| structure_only_minus_plain | lab2 | first150 | 0.0766 | [0.0542, 0.1026] | 20/0/0 |
| structure_only_minus_plain | lab3 | all | -0.0102 | [-0.0155, -0.0059] | 2/0/18 |
| structure_only_minus_plain | lab3 | first150 | -0.0833 | [-0.1094, -0.0556] | 2/1/17 |
| full_knowledge_minus_structure_only | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab2 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab2 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab3 | all | 0.0064 | [0.0026, 0.0111] | 14/1/5 |
| full_knowledge_minus_structure_only | lab3 | first150 | 0.0067 | [-0.0161, 0.0272] | 11/2/7 |
| full_knowledge_minus_rule | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_rule | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_rule | lab2 | all | 0.0018 | [0.0012, 0.0023] | 16/4/0 |
| full_knowledge_minus_rule | lab2 | first150 | 0.0281 | [0.0182, 0.0391] | 16/4/0 |
| full_knowledge_minus_rule | lab3 | all | -0.0078 | [-0.0092, -0.0066] | 0/0/20 |
| full_knowledge_minus_rule | lab3 | first150 | -0.1089 | [-0.1250, -0.0933] | 0/0/20 |
| structure_only_minus_rule | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_rule | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_rule | lab2 | all | 0.0018 | [0.0012, 0.0023] | 16/4/0 |
| structure_only_minus_rule | lab2 | first150 | 0.0281 | [0.0182, 0.0391] | 16/4/0 |
| structure_only_minus_rule | lab3 | all | -0.0143 | [-0.0188, -0.0103] | 0/0/20 |
| structure_only_minus_rule | lab3 | first150 | -0.1156 | [-0.1356, -0.0956] | 0/0/20 |

### mean_steps_can_fail

| contrast | lab | window | mean | ci | signs |
|---|---|---|---|---|---|
| full_knowledge_minus_plain | lab1 | all | -0.0006 | [-0.0009, -0.0002] | 1/10/9 |
| full_knowledge_minus_plain | lab1 | first150 | -0.0120 | [-0.0193, -0.0053] | 1/9/10 |
| full_knowledge_minus_plain | lab2 | all | -0.3109 | [-0.4319, -0.1888] | 2/0/18 |
| full_knowledge_minus_plain | lab2 | first150 | -2.2109 | [-2.7115, -1.7432] | 0/0/20 |
| full_knowledge_minus_plain | lab3 | all | 0.3902 | [0.2644, 0.5192] | 19/0/1 |
| full_knowledge_minus_plain | lab3 | first150 | 2.0572 | [1.5206, 2.6050] | 19/0/1 |
| rule_minus_plain | lab1 | all | -0.0003 | [-0.0007, 0.0000] | 1/14/5 |
| rule_minus_plain | lab1 | first150 | -0.0087 | [-0.0173, -0.0013] | 1/13/6 |
| rule_minus_plain | lab2 | all | -0.0318 | [-0.2258, 0.1591] | 10/0/10 |
| rule_minus_plain | lab2 | first150 | -1.0172 | [-1.6344, -0.4479] | 4/0/16 |
| rule_minus_plain | lab3 | all | -0.0308 | [-0.1833, 0.1306] | 7/0/13 |
| rule_minus_plain | lab3 | first150 | -0.8361 | [-1.2700, -0.4100] | 4/0/16 |
| structure_only_minus_plain | lab1 | all | -0.0006 | [-0.0009, -0.0002] | 1/10/9 |
| structure_only_minus_plain | lab1 | first150 | -0.0120 | [-0.0193, -0.0053] | 1/9/10 |
| structure_only_minus_plain | lab2 | all | -0.2498 | [-0.4231, -0.0838] | 5/0/15 |
| structure_only_minus_plain | lab2 | first150 | -2.1172 | [-2.5859, -1.6656] | 0/0/20 |
| structure_only_minus_plain | lab3 | all | 0.6869 | [0.5349, 0.8399] | 19/0/1 |
| structure_only_minus_plain | lab3 | first150 | 2.5611 | [1.9600, 3.1161] | 18/0/2 |
| full_knowledge_minus_structure_only | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab2 | all | -0.0611 | [-0.1917, 0.0779] | 7/0/13 |
| full_knowledge_minus_structure_only | lab2 | first150 | -0.0938 | [-0.2484, 0.0651] | 7/1/12 |
| full_knowledge_minus_structure_only | lab3 | all | -0.2967 | [-0.4176, -0.1739] | 4/0/16 |
| full_knowledge_minus_structure_only | lab3 | first150 | -0.5039 | [-0.9050, -0.0828] | 5/0/15 |
| full_knowledge_minus_rule | lab1 | all | -0.0002 | [-0.0006, 0.0001] | 3/12/5 |
| full_knowledge_minus_rule | lab1 | first150 | -0.0033 | [-0.0087, 0.0013] | 3/12/5 |
| full_knowledge_minus_rule | lab2 | all | -0.2791 | [-0.4653, -0.0883] | 7/0/13 |
| full_knowledge_minus_rule | lab2 | first150 | -1.1938 | [-1.4734, -0.9323] | 0/0/20 |
| full_knowledge_minus_rule | lab3 | all | 0.4210 | [0.2820, 0.5577] | 17/0/3 |
| full_knowledge_minus_rule | lab3 | first150 | 2.8933 | [2.4833, 3.2956] | 20/0/0 |
| structure_only_minus_rule | lab1 | all | -0.0002 | [-0.0006, 0.0001] | 3/12/5 |
| structure_only_minus_rule | lab1 | first150 | -0.0033 | [-0.0087, 0.0013] | 3/12/5 |
| structure_only_minus_rule | lab2 | all | -0.2180 | [-0.3867, -0.0540] | 7/0/13 |
| structure_only_minus_rule | lab2 | first150 | -1.1000 | [-1.4031, -0.8151] | 0/0/20 |
| structure_only_minus_rule | lab3 | all | 0.7177 | [0.5392, 0.9099] | 19/0/1 |
| structure_only_minus_rule | lab3 | first150 | 3.3972 | [2.9394, 3.8678] | 20/0/0 |

### overall_success_share

| contrast | lab | window | mean | ci | signs |
|---|---|---|---|---|---|
| full_knowledge_minus_plain | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_plain | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_plain | lab2 | all | 0.0037 | [0.0027, 0.0049] | 20/0/0 |
| full_knowledge_minus_plain | lab2 | first150 | 0.0490 | [0.0343, 0.0657] | 20/0/0 |
| full_knowledge_minus_plain | lab3 | all | -0.0023 | [-0.0035, -0.0011] | 5/1/14 |
| full_knowledge_minus_plain | lab3 | first150 | -0.0460 | [-0.0607, -0.0310] | 1/2/17 |
| rule_minus_plain | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| rule_minus_plain | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| rule_minus_plain | lab2 | all | 0.0026 | [0.0015, 0.0038] | 17/0/3 |
| rule_minus_plain | lab2 | first150 | 0.0310 | [0.0153, 0.0480] | 15/1/4 |
| rule_minus_plain | lab3 | all | 0.0024 | [0.0016, 0.0032] | 18/0/2 |
| rule_minus_plain | lab3 | first150 | 0.0193 | [0.0093, 0.0290] | 16/1/3 |
| structure_only_minus_plain | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_plain | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_plain | lab2 | all | 0.0037 | [0.0027, 0.0050] | 20/0/0 |
| structure_only_minus_plain | lab2 | first150 | 0.0490 | [0.0347, 0.0657] | 20/0/0 |
| structure_only_minus_plain | lab3 | all | -0.0062 | [-0.0093, -0.0036] | 2/0/18 |
| structure_only_minus_plain | lab3 | first150 | -0.0500 | [-0.0657, -0.0333] | 2/1/17 |
| full_knowledge_minus_structure_only | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab2 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab2 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_structure_only | lab3 | all | 0.0039 | [0.0015, 0.0066] | 14/1/5 |
| full_knowledge_minus_structure_only | lab3 | first150 | 0.0040 | [-0.0097, 0.0163] | 11/2/7 |
| full_knowledge_minus_rule | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_rule | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| full_knowledge_minus_rule | lab2 | all | 0.0011 | [0.0008, 0.0015] | 16/4/0 |
| full_knowledge_minus_rule | lab2 | first150 | 0.0180 | [0.0117, 0.0250] | 16/4/0 |
| full_knowledge_minus_rule | lab3 | all | -0.0047 | [-0.0055, -0.0039] | 0/0/20 |
| full_knowledge_minus_rule | lab3 | first150 | -0.0653 | [-0.0750, -0.0560] | 0/0/20 |
| structure_only_minus_rule | lab1 | all | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_rule | lab1 | first150 | 0.0000 | [0.0000, 0.0000] | 0/20/0 |
| structure_only_minus_rule | lab2 | all | 0.0011 | [0.0008, 0.0015] | 16/4/0 |
| structure_only_minus_rule | lab2 | first150 | 0.0180 | [0.0117, 0.0250] | 16/4/0 |
| structure_only_minus_rule | lab3 | all | -0.0086 | [-0.0113, -0.0062] | 0/0/20 |
| structure_only_minus_rule | lab3 | first150 | -0.0693 | [-0.0813, -0.0573] | 0/0/20 |

Full tables: contrasts.csv, cells_per_seed.csv, cell_means.csv.
