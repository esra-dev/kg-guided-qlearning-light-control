"""Guards analysis/posthoc/phase4_steps_split.py (post-hoc, descriptive).

Synthetic Phase-4 archives only. Covers the row-level identity
Steps == WastedSteps + ActuatorCyclingCount (and its violation report), the
per-cell measures including the share of zero-step runs, and the paired contrasts.
"""

import csv
import json

import pytest

from analysis.posthoc import phase4_steps_split as pss


def _row(sid, steps, wasted, cycling):
    return {"ScenarioId": str(sid), "RunId": "1", "Steps": str(steps),
            "WastedSteps": str(wasted), "ActuatorCyclingCount": str(cycling)}


def test_identity_violations_flags_only_mismatching_rows():
    rows = [_row(1, 3, 1, 2), _row(2, 0, 0, 0), _row(3, 4, 1, 2)]
    bad = pss.identity_violations(rows)
    assert len(bad) == 1 and bad[0]["ScenarioId"] == "3"


def test_cell_measures_split_and_zero_share():
    m = pss.cell_measures([_row(1, 3, 1, 2), _row(2, 0, 0, 0), _row(3, 1, 0, 1),
                           _row(4, 0, 0, 0)])
    assert m["mean_steps"] == pytest.approx(1.0)
    assert m["mean_wasted_steps"] == pytest.approx(0.25)
    assert m["mean_actuator_cycling"] == pytest.approx(0.75)
    assert m["mean_wasted_plus_cycling"] == pytest.approx(1.0)
    assert m["share_zero_steps"] == pytest.approx(0.5)


def _bench(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        w = csv.writer(handle, lineterminator="\n")
        w.writerow(["ScenarioId", "RunId", "AgentType", "MetricSchema", "GoalReached", "Steps",
                    "WastedSteps", "ActuatorCyclingCount"])
        for sid, steps, wasted, cycling in rows:
            w.writerow([sid, 1, "x", "phase1-benchmark-v2", 1, steps, wasted, cycling])


def _build(tmp_path, violate=False):
    roots = []
    for half, seeds in (("a", (1,)), ("b", (2,))):   # seeds split across two runs
        root = tmp_path / f"run_{half}"
        roots.append(root)
        for seed in seeds:
            lab = root / "benchmark" / f"results_seed{seed}" / "lab4"
            marker = lab / "training_stereo_true" / "TRAINING_OK.json"
            marker.parent.mkdir(parents=True)
            marker.write_text(json.dumps({"run_mode": "phase4_v2"}), encoding="utf-8")
            _bench(lab / "ql_true" / "benchmark_results_ql_true.csv",
                   [(1, 1, 0, 1), (2, 0, 0, 0)])
            _bench(lab / "ql_false" / "benchmark_results_ql_false.csv",
                   [(1, 3, 2, 1), (2, 0, 0, 0) if not violate else (2, 2, 0, 0)])
            _bench(lab / "rule_based" / "benchmark_results_rule_based.csv",
                   [(1, 1, 0, 1), (2, 0, 0, 0)])
    return roots


def test_run_end_to_end(tmp_path):
    """Runs the script on a two-seed synthetic archive and checks the paired contrasts."""
    result = pss.run(_build(tmp_path), (1, 2), tmp_path / "out", iters=100)
    assert result["rows_checked"] == 12
    assert result["violations"] == []
    rows = {r["measure"]: r for r in result["contrast_rows"]}
    assert rows["mean_steps"]["mean_paired_difference"] == pytest.approx(0.5 - 1.5)
    assert rows["mean_wasted_steps"]["mean_paired_difference"] == pytest.approx(-1.0)
    assert rows["mean_actuator_cycling"]["mean_paired_difference"] == pytest.approx(0.0)
    assert rows["share_zero_steps"]["mean_paired_difference"] == pytest.approx(0.0)
    # same resampling stream for every measure of one (mode, lab)
    assert (rows["mean_steps"]["bootstrap_ci_lo_95"]
            == rows["mean_wasted_plus_cycling"]["bootstrap_ci_lo_95"])


def test_run_reports_identity_violations(tmp_path):
    result = pss.run(_build(tmp_path, violate=True), (1, 2), tmp_path / "out", iters=10)
    assert len(result["violations"]) == 2
    assert {v["seed"] for v in result["violations"]} == {1, 2}
    assert (tmp_path / "out" / "identity_violations.csv").is_file()
