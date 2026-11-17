"""Guards analysis/posthoc/test_trained_vs_unseen.py (post-hoc, descriptive).

Synthetic archives only. Covers the classification of test scenarios as trained
or unseen (text fields and chosen extra fields ignored), the per-subset paired
contrasts, and the error raised for a benchmark scenario missing from the test
scenario file. The module name starts with test_ but is a script, not a test.
"""

import csv
import json

import pytest

from analysis.posthoc import _common as cm
from analysis.posthoc import test_trained_vs_unseen as ttu

TRAIN = [
    {"comment": "train", "id": 1, "Z1Level": 25, "Z1Light": False, "Sunshine": 0,
     "description": "a"},
    {"id": 2, "Z1Level": 425, "Z1Light": True, "Sunshine": 0, "description": "b"},
]
TEST = [
    {"comment": "test", "id": 1, "Z1Level": 25, "Z1Light": False, "Sunshine": 0,
     "description": "same state as training 1"},
    {"id": 3, "Z1Level": 425, "Z1Light": True, "Sunshine": 0,
     "description": "same state as training 2, different id"},
    {"id": 4, "Z1Level": 25, "Z1Light": False, "Sunshine": 900, "description": "unseen sun"},
]


def test_classifier_ignores_id_comment_description():
    classes = cm.classify_test_starts(TEST, TRAIN)
    assert [(c.test_id, c.trained, c.matching_training_id) for c in classes] == [
        (1, True, 1), (3, True, 2), (4, False, None)]


def test_classifier_can_ignore_extra_fields():
    train = [{"id": 1, "Z1Level": 25, "energyBudget": 2}]
    test = [{"id": 1, "Z1Level": 25, "energyBudget": 4}]
    assert not cm.classify_test_starts(test, train)[0].trained
    assert cm.classify_test_starts(test, train, cm.TEXT_FIELDS | {"energyBudget"})[0].trained


def _bench(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        w = csv.writer(handle, lineterminator="\n")
        w.writerow(["ScenarioId", "RunId", "GoalReached", "Steps", "CumIlluminanceDeviation",
                    "WastedSteps", "ActuatorCyclingCount"])
        for sid, run, goal, steps in rows:
            w.writerow([sid, run, goal, steps, "0.00", 0, steps])


def _build(tmp_path, seeds=(1, 2)):
    root = tmp_path / "run"
    for seed in seeds:
        lab = root / "benchmark" / f"results_seed{seed}" / "lab1"
        marker = lab / "training_stereo_true" / "TRAINING_OK.json"
        marker.parent.mkdir(parents=True)
        marker.write_text(json.dumps({"run_mode": "phase1_v2_kg_only"}), encoding="utf-8")
        # two runs per start; ql_false fails the unseen start once and is slower there
        _bench(lab / "ql_true" / "benchmark_results_ql_true.csv",
               [(1, 1, 1, 1), (3, 1, 1, 0), (4, 1, 1, 1),
                (1, 2, 1, 1), (3, 2, 1, 0), (4, 2, 1, 1)])
        _bench(lab / "ql_false" / "benchmark_results_ql_false.csv",
               [(1, 1, 1, 1), (3, 1, 1, 0), (4, 1, 0, 5),
                (1, 2, 1, 1), (3, 2, 1, 0), (4, 2, 1, seed)])
        _bench(lab / "rule_based" / "benchmark_results_rule_based.csv",
               [(1, 1, 1, 1), (3, 1, 1, 0), (4, 1, 1, 1),
                (1, 2, 1, 1), (3, 2, 1, 0), (4, 2, 1, 1)])
    sdir = tmp_path / "scen"
    sdir.mkdir()
    (sdir / "train_scenarios_lab1.json").write_text(json.dumps(TRAIN), encoding="utf-8")
    (sdir / "scenarios_lab1.json").write_text(json.dumps(TEST), encoding="utf-8")
    return root, sdir


def test_run_splits_subsets_and_pairs_by_seed(tmp_path):
    root, sdir = _build(tmp_path)
    result = ttu.run({"phase1": [root], "phase4": []}, (1, 2), sdir, tmp_path / "out", iters=100)
    counts = result["count_rows"][0]
    assert (counts["n_trained"], counts["n_unseen"], counts["unseen_ids"]) == (2, 1, "4")
    rows = {(r["subset"], r["measure"]): r for r in result["contrast_rows"]}
    assert rows[("trained", "goal_share")]["mean_paired_difference"] == 0
    assert rows[("unseen", "goal_share")]["mean_paired_difference"] == pytest.approx(0.5)
    # unseen steps: ql_true 1.0; ql_false (5 + seed) / 2 -> 3.0 and 3.5
    assert rows[("unseen", "mean_steps")]["mean_paired_difference"] == pytest.approx(-2.25)
    assert rows[("unseen", "mean_steps")]["n_seeds_left_lower"] == 2
    assert rows[("all", "goal_share")]["mean_paired_difference"] == pytest.approx(1 / 6)
    text = (tmp_path / "out" / "summary.md").read_text(encoding="utf-8")
    assert "post-hoc, descriptive" in text


def test_unknown_benchmark_scenario_is_an_error(tmp_path):
    root, sdir = _build(tmp_path)
    (sdir / "scenarios_lab1.json").write_text(json.dumps(TEST[:2]), encoding="utf-8")
    with pytest.raises(ValueError, match="not in the test scenario file"):
        ttu.run({"phase1": [root]}, (1, 2), sdir, tmp_path / "out", iters=10)
