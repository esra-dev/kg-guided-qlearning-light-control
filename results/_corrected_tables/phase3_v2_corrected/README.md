# Corrected table: Phase-3 paired comparison (`phase3_v2_corrected`)

## What is corrected

`phase3_compliance_paired.csv` without the p-value, q-value and
multiple-testing columns.

The Phase-3 correction registration (`docs/phase3_correction_registration_2026-07-22.md`,
section 3) makes the Phase-3 results descriptive: the compliance contrast is a
worked demonstration with descriptive intervals, not a hypothesis test, and no
p- or q-value may be attached to any Phase-3 energy quantity. The archived
table
`results/phase3_v2_corrected/run_29926328852/analysis/out/phase3_compliance_paired.csv`
nevertheless carries `p_bootstrap`, `p_wilcoxon`, `q_bootstrap_bh` and
`bh_family_m` for the compliance measures and for `total_tick_energy`. Those
columns are not results and must not be cited.

This table keeps every descriptive column with the same values: the arm means,
the paired difference `ql_true - ql_false`, its paired-bootstrap 95% interval
(`ci_lo`, `ci_hi`), the direction flags and Cliff's delta. Values may differ
from the archived ones in the last floating-point digit only (order of
summation). The other two Phase-3 tables (`phase3_delay_accuracy.csv`,
`phase3_compliance_ci.csv`) contain no tests and are unchanged; use the
archived copies.

## Command

From the repository root (the Phase-3 archive data is tracked in git):

    py -3 analysis/phase3_dynamics.py --root results/phase3_v2_corrected/run_29926328852/dynamics_root --out <temporary folder>

then copy `phase3_compliance_paired.csv` from the temporary folder here. The
test `analysis/tests/test_phase3_registration_conformance.py` rebuilds the
table and compares it with this copy.

The archived table itself is unchanged and still reproduced by
`analysis/reproduce_phase3_v2.py`, which runs the script with
`--legacy-archived-tests` for that purpose only.
