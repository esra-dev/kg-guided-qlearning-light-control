"""The Phase-3 paired table carries no p- or q-values by default.

The Phase-3 registration makes all Phase-3 results descriptive. The default
output of phase3_dynamics.py must therefore have no column whose name starts
with ``p_`` or ``q_`` (and no multiple-testing family column). The legacy
option exists only so reproduce_phase3_v2.py can rebuild the archived table.
"""
import csv
from pathlib import Path

from analysis import phase3_dynamics as p3
from analysis import reproduce_phase3_v2 as repro

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE_RUN = ROOT / "results" / "phase3_v2_corrected" / "run_29926328852"
CORRECTED = ROOT / "results" / "_corrected_tables" / "phase3_v2_corrected" / \
    "phase3_compliance_paired.csv"


def _synthetic_cells() -> dict:
    def arm(overall, tight, energy):
        return {"n_replicas": len(overall), "n_goals_total": 6,
                "overall_compliance": overall, "tight_compliance": tight,
                "loose_compliance": [1.0] * len(overall),
                "total_tick_energy": energy,
                "total_energy_wallclock_legacy": energy,
                "mean_actual_delay": [30.0] * len(overall)}
    return {
        ("labA", "ql_true"): arm([1.0, 1.0, 0.9], [1.0, 1.0, 0.8], [3.0, 4.0, 3.5]),
        ("labA", "ql_false"): arm([0.5, 0.5, 0.5], [0.0, 0.0, 0.0], [0.0, 0.0, 0.0]),
    }


def _header(path: Path) -> list[str]:
    with path.open(encoding="utf-8", newline="") as handle:
        return next(csv.reader(handle))


def test_default_paired_table_has_no_p_or_q_columns(tmp_path):
    n = p3.write_compliance_paired(_synthetic_cells(), ["labA"], tmp_path, iters=200)
    assert n == 4
    header = _header(tmp_path / "phase3_compliance_paired.csv")
    assert not [c for c in header if c.startswith(("p_", "q_"))]
    assert "bh_family_m" not in header
    for column in ("ql_true_mean", "ql_false_mean", "mean_diff_true_minus_false",
                   "ci_lo", "ci_hi", "n_paired", "cliffs_delta"):
        assert column in header


def test_legacy_option_restores_archived_columns(tmp_path):
    p3.write_compliance_paired(_synthetic_cells(), ["labA"], tmp_path, iters=200,
                               legacy_archived_tests=True)
    header = _header(tmp_path / "phase3_compliance_paired.csv")
    assert header == _header(ARCHIVE_RUN / "analysis" / "out" / "phase3_compliance_paired.csv")


def test_default_output_matches_committed_corrected_table(tmp_path):
    rc = p3.main(["--root", str(ARCHIVE_RUN / "dynamics_root"), "--out", str(tmp_path),
                  "--config", str(ROOT / "config" / "run_config.json")])
    assert rc == 0
    rebuilt = tmp_path / "phase3_compliance_paired.csv"
    assert not [c for c in _header(rebuilt) if c.startswith(("p_", "q_"))]
    assert repro._canonical_csv_bytes(rebuilt) == repro._canonical_csv_bytes(CORRECTED)
