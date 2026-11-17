# Phase-3 comparison of run 43829883553 with the archive (counts and choices, not bytes)

- archives (repository-relative): `results/phase3_v2_corrected/run_29926328852`
- Phase 3 measures real device delays, so bytes differ; the deadline flag and the chosen device are compared per goal
- goals compared: 48 in 8 cells
- deadline flag (`met`) identical: 48 of 48
- chosen device identical to the archive replica of the same number: 45 of 48
- chosen device different from that replica but chosen by the archive in another replica of the same goal: 3
- chosen device never chosen by the archive for that goal: 0
- learned-delay values equal to the archive replica (goal rows plus delay-table rows): 14 of 84 (measured values, reported not judged)
- line endings: not relevant here (values are read, not bytes)
- verdict: PASS

## Per cell

| cell | goals | same choice as archive replica | same deadline flag | deadlines met (run) | deadlines met (archive replica) | learned delay values equal |
|---|---|---|---|---|---|---|
| dynamics-lab2_slow-ql_false-rep-1 | 6 | 6 | 6 | 3 | 3 | 1 of 10 |
| dynamics-lab2_slow-ql_false-rep-2 | 6 | 6 | 6 | 3 | 3 | 5 of 10 |
| dynamics-lab2_slow-ql_true-rep-1 | 6 | 6 | 6 | 6 | 6 | 0 of 10 |
| dynamics-lab2_slow-ql_true-rep-2 | 6 | 6 | 6 | 6 | 6 | 3 of 10 |
| dynamics-lab3_slow-ql_false-rep-1 | 6 | 6 | 6 | 3 | 3 | 1 of 11 |
| dynamics-lab3_slow-ql_false-rep-2 | 6 | 6 | 6 | 3 | 3 | 1 of 11 |
| dynamics-lab3_slow-ql_true-rep-1 | 6 | 6 | 6 | 6 | 6 | 0 of 11 |
| dynamics-lab3_slow-ql_true-rep-2 | 6 | 3 | 6 | 6 | 6 | 3 of 11 |

## Goals whose choice differs from the archive replica of the same number

| cell | goal | run chose | archive replica chose | archive spread over all replicas |
|---|---|---|---|---|
| dynamics-lab3_slow-ql_true-rep-2 | g3 | SetZ1Blinds=ON | SetZ2Blinds=ON | SetZ1Blinds=ON x7; SetZ2Blinds=ON x3 |
| dynamics-lab3_slow-ql_true-rep-2 | g4 | SetZ1Blinds=ON | SetZ2Blinds=ON | SetZ1Blinds=ON x7; SetZ2Blinds=ON x3 |
| dynamics-lab3_slow-ql_true-rep-2 | g6 | SetZ1Blinds=ON | SetZ2Blinds=ON | SetZ1Blinds=ON x7; SetZ2Blinds=ON x3 |
