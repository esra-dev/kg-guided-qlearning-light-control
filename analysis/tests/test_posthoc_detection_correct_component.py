"""Guards analysis/posthoc/detection_correct_component.py (post-hoc, descriptive).

Synthetic Phase-2 archives only. Covers blame parsing, the strict detection rule
(a detection counts only when the injected component is blamed; censoring when
only healthy components are blamed), agreement of the script's broken-component
table with the registered ground truth, and the cell, seed and contrast rows.
"""

import csv
import json

import pytest

from analysis import phase2_structonly_v3_family as structonly
from analysis.posthoc import detection_correct_component as dcc

URI = "http://example.org/was#"
HEADER = ["DefectComponent", "DetectEpisode", "ReconvergeEpisode", "RecoveryEpisodes",
          "SecondaryDetectEpisode", "RecoveredGoalRate", "NominalGoal", "BestEffortRank",
          "RankShortfall", "DegradedMode", "BlacklistEvents", "DetectorVersion",
          "ProtocolVersion"]


def test_parse_blames_orders_by_episode_and_strips_uri():
    blames = dcc.parse_blames(f"{URI}SetZ1Blinds@5;{URI}SetZ2Blinds@0")
    assert blames == [("SetZ2Blinds", 0), ("SetZ1Blinds", 5)]
    assert dcc.parse_blames("") == []
    assert dcc.parse_blames(None) == []


def test_strict_rule_with_wrong_first_blame():
    out = dcc.strict_outcome([("SetZ2Blinds", 0), ("SetZ1Blinds", 5)], ("SetZ1Blinds",), 138)
    assert out["first_blame"] == "SetZ2Blinds"
    assert out["first_blame_healthy"] and not out["first_blame_broken"]
    assert out["strict_detected"] and out["strict_detect_episode"] == 5.0
    assert not out["strict_censored"]


def test_strict_rule_censors_when_only_healthy_devices_are_blamed():
    out = dcc.strict_outcome([("SetZ2Blinds", 3)], ("SetZ1Blinds",), 138)
    assert not out["strict_detected"]
    assert out["strict_detect_episode"] == 139.0 and out["strict_censored"]
    none = dcc.strict_outcome([], ("SetZ1Blinds",), 138)
    assert not none["any_blame"] and none["strict_detect_episode"] == 139.0


def test_broken_components_agree_with_registered_ground_truth():
    for cell, injected in structonly.INJECTED_COMPONENTS.items():
        assert dcc.BROKEN_COMPONENTS[cell] == injected


def _record(path, defect, detect, events):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        w = csv.writer(handle, lineterminator="\n")
        w.writerow(HEADER)
        w.writerow([defect, detect, 10, 10, -1, "0.9", 3, 3, 0, 0, events,
                    "fault-detector-v2", "phase2-v2"])


def _build(tmp_path, profile="lab3_f1bdead"):
    root = tmp_path / "run_a"
    cases = {
        # (bool arm, seed): (DefectComponent, DetectEpisode, BlacklistEvents)
        ("true", 1): (f"{URI}SetZ1Blinds", 4, f"{URI}SetZ1Blinds@4"),
        ("true", 2): ("none", -1, ""),
        ("false", 1): (f"{URI}SetZ2Blinds", 0, f"{URI}SetZ2Blinds@0;{URI}SetZ1Blinds@5"),
        ("false", 2): (f"{URI}SetZ2Blinds", 7, f"{URI}SetZ2Blinds@7"),
    }
    for (bool_name, seed), (defect, detect, events) in cases.items():
        folder = root / "recovery_root" / f"seed{seed}"
        _record(folder / f"recovery_stereotypes_{bool_name}_{profile}.csv", defect, detect, events)
        (folder / f"ADAPT_OK_stereotypes_{bool_name}_{profile}.json").write_text(
            json.dumps({"adapt_episodes_effective": 100}), encoding="utf-8")
    return root


def test_run_counts_record_and_strict(tmp_path):
    root = _build(tmp_path)
    result = dcc.run({"x": [root]}, (1, 2), tmp_path / "out", iters=100)
    cells = {r["arm"]: r for r in result["cell_rows"]}
    t, f = cells["ql_true"], cells["ql_false"]
    assert (t["n_detected_record"], t["n_detected_strict"], t["n_censored_strict"]) == (1, 1, 1)
    assert t["mean_record_detect_episode"] == pytest.approx((4 + 101) / 2)
    assert (f["n_detected_record"], f["n_first_blame_broken"], f["n_first_blame_healthy"]) == (2, 0, 2)
    assert f["n_detected_strict"] == 1 and f["n_censored_strict"] == 1
    assert f["mean_record_detect_episode"] == pytest.approx((0 + 7) / 2)
    assert f["mean_strict_detect_episode"] == pytest.approx((5 + 101) / 2)
    assert f["n_strict_differs_from_record"] == 2
    seeds = {(r["arm"], r["seed"]): r for r in result["seed_rows"]}
    assert seeds[("ql_false", 1)]["first_blame"] == "SetZ2Blinds"
    assert seeds[("ql_false", 1)]["strict_detect_episode"] == 5.0
    contrasts = {r["detection_rule"]: r for r in result["contrast_rows"]}
    assert contrasts["record"]["mean_paired_difference"] == pytest.approx(((4 - 0) + (101 - 7)) / 2)
    assert contrasts["strict"]["mean_paired_difference"] == pytest.approx(((4 - 5) + (101 - 101)) / 2)


def test_unknown_fault_lab_is_an_error(tmp_path):
    root = _build(tmp_path, profile="lab9_f1dead")
    with pytest.raises(ValueError, match="broken-component"):
        dcc.run({"x": [root]}, (1, 2), tmp_path / "out", iters=10)


def test_parse_archives_groups_by_label():
    groups = dcc.parse_archives(["a=p1", "a=p2", "b=p3"])
    assert {k: [str(p) for p in v] for k, v in groups.items()} == {"a": ["p1", "p2"], "b": ["p3"]}
    with pytest.raises(ValueError):
        dcc.parse_archives(["nolabel"])
