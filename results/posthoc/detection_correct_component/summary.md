# Phase-2 detection counted only when a truly broken device is blamed

Status: post-hoc, descriptive. No p-values or q-values are computed.
Seeds 1..20 (20); bootstrap 10000 resamples, base seed 20261116.

## Per fault lab and arm

Columns: detected = seeds with any blame (record); first ok / first healthy = seeds whose first blame names a broken / healthy device; strict = seeds with a blame of a broken device; mean episodes are censored at adapt_episodes_effective + 1.

| archive | fault_lab | arm | n | detected | first_ok | first_healthy | strict | record_mean | strict_mean | seeds_changed |
|---|---|---|---|---|---|---|---|---|---|---|
| v2_corrected | lab1_f1dead | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab1_f1dead | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab2_f1bdead | ql_true | 20 | 20 | 20 | 0 | 20 | 6.00 | 6.00 | 0 |
| v2_corrected | lab2_f1bdead | ql_false | 20 | 20 | 20 | 0 | 20 | 6.00 | 6.00 | 0 |
| v2_corrected | lab2_f1binv | ql_true | 20 | 20 | 20 | 0 | 20 | 6.00 | 6.00 | 0 |
| v2_corrected | lab2_f1binv | ql_false | 20 | 20 | 20 | 0 | 20 | 6.00 | 6.00 | 0 |
| v2_corrected | lab2_f1dead | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab2_f1dead | ql_false | 20 | 20 | 20 | 0 | 20 | 0.15 | 0.15 | 0 |
| v2_corrected | lab2_f1inv | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab2_f1inv | ql_false | 20 | 20 | 20 | 0 | 20 | 0.15 | 0.15 | 0 |
| v2_corrected | lab2_f2dead | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab2_f2dead | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab2_f2inv | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab2_f2inv | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab3_f1bdead | ql_true | 20 | 20 | 20 | 0 | 20 | 5.00 | 5.00 | 0 |
| v2_corrected | lab3_f1bdead | ql_false | 20 | 20 | 20 | 0 | 20 | 5.00 | 5.00 | 0 |
| v2_corrected | lab3_f1binv | ql_true | 20 | 20 | 20 | 0 | 20 | 5.00 | 5.00 | 0 |
| v2_corrected | lab3_f1binv | ql_false | 20 | 20 | 20 | 0 | 20 | 5.00 | 5.00 | 0 |
| v2_corrected | lab3_f1dead | ql_true | 20 | 0 | 0 | 0 | 0 | 4001.00 | 4001.00 | 0 |
| v2_corrected | lab3_f1dead | ql_false | 20 | 11 | 11 | 0 | 11 | 1804.05 | 1804.05 | 0 |
| v2_corrected | lab3_f1dead_z2 | ql_true | 20 | 0 | 0 | 0 | 0 | 4001.00 | 4001.00 | 0 |
| v2_corrected | lab3_f1dead_z2 | ql_false | 20 | 20 | 20 | 0 | 20 | 3.15 | 3.15 | 0 |
| v2_corrected | lab3_f1inv | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab3_f1inv | ql_false | 20 | 20 | 20 | 0 | 20 | 0.05 | 0.05 | 0 |
| v2_corrected | lab3_f1inv_z2 | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab3_f1inv_z2 | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab3_f2bdead | ql_true | 20 | 20 | 20 | 0 | 20 | 5.00 | 5.00 | 0 |
| v2_corrected | lab3_f2bdead | ql_false | 20 | 20 | 20 | 0 | 20 | 5.00 | 5.00 | 0 |
| v2_corrected | lab3_f2dead | ql_true | 20 | 0 | 0 | 0 | 0 | 4001.00 | 4001.00 | 0 |
| v2_corrected | lab3_f2dead | ql_false | 20 | 20 | 20 | 0 | 20 | 1.55 | 1.55 | 0 |
| v2_corrected | lab3_f2dead_lowsun | ql_true | 20 | 0 | 0 | 0 | 0 | 4001.00 | 4001.00 | 0 |
| v2_corrected | lab3_f2dead_lowsun | ql_false | 20 | 20 | 20 | 0 | 20 | 1.55 | 1.55 | 0 |
| v2_corrected | lab3_f2inv | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | lab3_f2inv | ql_false | 20 | 20 | 20 | 0 | 20 | 0.05 | 0.05 | 0 |
| v2_corrected | labmon2_f2dead_lowsun | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon2_f2dead_lowsun | ql_false | 20 | 20 | 20 | 0 | 20 | 0.20 | 0.20 | 0 |
| v2_corrected | labmon2_infoonly_f2dead_lowsun | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon2_infoonly_f2dead_lowsun | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon2_nostereo_f2dead_lowsun | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon2_nostereo_f2dead_lowsun | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon_f1dead | ql_true | 20 | 13 | 13 | 0 | 13 | 525.35 | 525.35 | 0 |
| v2_corrected | labmon_f1dead | ql_false | 20 | 10 | 10 | 0 | 10 | 750.50 | 750.50 | 0 |
| v2_corrected | labmon_infoonly_f1dead | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon_infoonly_f1dead | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon_nostereo_f1dead | ql_true | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| v2_corrected | labmon_nostereo_f1dead | ql_false | 20 | 20 | 20 | 0 | 20 | 0.00 | 0.00 | 0 |
| structonly | lab2_f1bdead | ql_true | 20 | 18 | 18 | 0 | 18 | 204.10 | 204.10 | 0 |
| structonly | lab2_f1bdead | ql_false | 20 | 20 | 20 | 0 | 20 | 4.60 | 4.60 | 0 |
| structonly | lab3_f1bdead | ql_true | 20 | 0 | 0 | 0 | 0 | 4001.00 | 4001.00 | 0 |
| structonly | lab3_f1bdead | ql_false | 20 | 9 | 8 | 1 | 9 | 2202.40 | 2202.65 | 1 |
| structonly | lab3_f2dead_lowsun | ql_true | 20 | 0 | 0 | 0 | 0 | 4001.00 | 4001.00 | 0 |
| structonly | lab3_f2dead_lowsun | ql_false | 20 | 11 | 11 | 0 | 11 | 1800.45 | 1800.45 | 0 |
| structonly | labmon2_f2dead_lowsun | ql_true | 20 | 14 | 14 | 0 | 14 | 1200.30 | 1200.30 | 0 |
| structonly | labmon2_f2dead_lowsun | ql_false | 20 | 20 | 20 | 0 | 20 | 0.35 | 0.35 | 0 |
| lowsun | lab3_f2dead_lowsun | ql_true | 20 | 0 | 0 | 0 | 0 | 4001.00 | 4001.00 | 0 |
| lowsun | lab3_f2dead_lowsun | ql_false | 20 | 11 | 11 | 0 | 11 | 1800.45 | 1800.45 | 0 |
| lowsun | labmon2_f2dead_lowsun | ql_true | 20 | 14 | 14 | 0 | 14 | 1200.30 | 1200.30 | 0 |
| lowsun | labmon2_f2dead_lowsun | ql_false | 20 | 20 | 20 | 0 | 20 | 0.35 | 0.35 | 0 |

## Seeds whose first blame names a healthy device (1)

| archive | fault_lab | arm | seed | blames | record_detect_episode | strict_detect_episode |
|---|---|---|---|---|---|---|
| structonly | lab3_f1bdead | ql_false | 6 | SetZ2Blinds@0;SetZ1Blinds@5 | 0 | 5 |

## ql_true - ql_false detection episode, record vs strict (mean [95% bootstrap]; seeds +/0/-)

| archive | fault_lab | rule | difference | ci | signs |
|---|---|---|---|---|---|
| v2_corrected | lab1_f1dead | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab1_f1dead | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f1bdead | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f1bdead | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f1binv | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f1binv | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f1dead | record | -0.15 | [-0.45, 0.00] | 0/19/1 |
| v2_corrected | lab2_f1dead | strict | -0.15 | [-0.45, 0.00] | 0/19/1 |
| v2_corrected | lab2_f1inv | record | -0.15 | [-0.45, 0.00] | 0/19/1 |
| v2_corrected | lab2_f1inv | strict | -0.15 | [-0.45, 0.00] | 0/19/1 |
| v2_corrected | lab2_f2dead | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f2dead | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f2inv | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab2_f2inv | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f1bdead | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f1bdead | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f1binv | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f1binv | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f1dead | record | 2196.95 | [1397.20, 2997.00] | 11/9/0 |
| v2_corrected | lab3_f1dead | strict | 2196.95 | [1397.20, 2997.00] | 11/9/0 |
| v2_corrected | lab3_f1dead_z2 | record | 3997.85 | [3995.65, 3999.75] | 20/0/0 |
| v2_corrected | lab3_f1dead_z2 | strict | 3997.85 | [3995.65, 3999.75] | 20/0/0 |
| v2_corrected | lab3_f1inv | record | -0.05 | [-0.15, 0.00] | 0/19/1 |
| v2_corrected | lab3_f1inv | strict | -0.05 | [-0.15, 0.00] | 0/19/1 |
| v2_corrected | lab3_f1inv_z2 | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f1inv_z2 | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f2bdead | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f2bdead | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | lab3_f2dead | record | 3999.45 | [3997.75, 4000.60] | 20/0/0 |
| v2_corrected | lab3_f2dead | strict | 3999.45 | [3997.75, 4000.60] | 20/0/0 |
| v2_corrected | lab3_f2dead_lowsun | record | 3999.45 | [3997.75, 4000.60] | 20/0/0 |
| v2_corrected | lab3_f2dead_lowsun | strict | 3999.45 | [3997.75, 4000.60] | 20/0/0 |
| v2_corrected | lab3_f2inv | record | -0.05 | [-0.15, 0.00] | 0/19/1 |
| v2_corrected | lab3_f2inv | strict | -0.05 | [-0.15, 0.00] | 0/19/1 |
| v2_corrected | labmon2_f2dead_lowsun | record | -0.20 | [-0.50, 0.00] | 0/18/2 |
| v2_corrected | labmon2_f2dead_lowsun | strict | -0.20 | [-0.50, 0.00] | 0/18/2 |
| v2_corrected | labmon2_infoonly_f2dead_lowsun | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | labmon2_infoonly_f2dead_lowsun | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | labmon2_nostereo_f2dead_lowsun | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | labmon2_nostereo_f2dead_lowsun | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | labmon_f1dead | record | -225.15 | [-675.45, 300.20] | 4/9/7 |
| v2_corrected | labmon_f1dead | strict | -225.15 | [-675.45, 300.20] | 4/9/7 |
| v2_corrected | labmon_infoonly_f1dead | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | labmon_infoonly_f1dead | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | labmon_nostereo_f1dead | record | 0.00 | [0.00, 0.00] | 0/20/0 |
| v2_corrected | labmon_nostereo_f1dead | strict | 0.00 | [0.00, 0.00] | 0/20/0 |
| structonly | lab2_f1bdead | record | 199.50 | [-0.20, 498.35] | 10/6/4 |
| structonly | lab2_f1bdead | strict | 199.50 | [-0.20, 498.35] | 10/6/4 |
| structonly | lab3_f1bdead | record | 1798.60 | [998.85, 2598.60] | 9/11/0 |
| structonly | lab3_f1bdead | strict | 1798.35 | [998.85, 2598.15] | 9/11/0 |
| structonly | lab3_f2dead_lowsun | record | 2200.55 | [1400.35, 3000.75] | 11/9/0 |
| structonly | lab3_f2dead_lowsun | strict | 2200.55 | [1400.35, 3000.75] | 11/9/0 |
| structonly | labmon2_f2dead_lowsun | record | 1199.95 | [399.90, 2000.20] | 6/12/2 |
| structonly | labmon2_f2dead_lowsun | strict | 1199.95 | [399.90, 2000.20] | 6/12/2 |
| lowsun | lab3_f2dead_lowsun | record | 2200.55 | [1400.35, 3000.75] | 11/9/0 |
| lowsun | lab3_f2dead_lowsun | strict | 2200.55 | [1400.35, 3000.75] | 11/9/0 |
| lowsun | labmon2_f2dead_lowsun | record | 1199.95 | [399.90, 2000.20] | 6/12/2 |
| lowsun | labmon2_f2dead_lowsun | strict | 1199.95 | [399.90, 2000.20] | 6/12/2 |

Full tables: cells.csv, per_seed.csv, contrasts.csv.
