"""Guards analysis/posthoc/lab3_failure_anatomy.py (post-hoc, descriptive).

Synthetic Phase-1 archives only. Covers the split of training failures at the
150-episode boundary and the end-to-end training and test tables (trained versus
unseen classification, goal shares, summary file).
"""

import csv
import json

import pytest

from analysis.posthoc import lab3_failure_anatomy as lfa
from analysis.posthoc import training_can_fail as tcf

OFF = {"Z1Light": False, "Z2Light": False, "Z1Blinds": False, "Z2Blinds": False,
       "Spotlight": False}
TRAIN = [
    {"id": 1, "Z1Level": 25, "Z2Level": 25, **OFF, "Sunshine": 0},
    {"id": 2, "Z1Level": 525, "Z2Level": 525, **{**OFF, "Z1Light": True, "Z2Light": True},
     "Sunshine": 0},
]
TEST = TRAIN + [
    {"id": 3, "Z1Level": 125, "Z2Level": 425, **{**OFF, "Z2Light": True}, "Sunshine": 0},
]


def test_training_failures_split_at_149_150():
    n = tcf.HORIZON
    sched = [str(1 + (i % 2)) for i in range(n)]
    goals = [1] * n
    goals[148] = 0   # scenario 1, early
    goals[150] = 0   # scenario 1, late
    goals[151] = 0   # scenario 2, late
    ep = tcf.TrainingEpisodes(tuple([1] * n), tuple(goals), tuple([0] * n), tuple(sched))
    out = lfa.training_failures(ep)
    assert out["1"]["failed_early"] == 1 and out["1"]["failed_late"] == 1
    assert out["2"]["failed_early"] == 0 and out["2"]["failed_late"] == 1
    assert out["1"]["presented_early"] == 75 and out["1"]["presented_late"] == 1425


def _metrics(path, fail_at):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        w = csv.writer(handle, lineterminator="\n")
        w.writerow(["Episode", "Steps", "RewardZ1", "RewardZ2", "GoalReached", "Epsilon",
                    "WastedByPenalty", "WastedByNoEffect", "ScenarioId"])
        for i in range(tcf.HORIZON):
            sid = 1 + (i % 2)
            if sid == 2:
                w.writerow([i, 0, "0", "0", 1, "0.1", 0, 0, 2])
            else:
                w.writerow([i, 4, "1", "1", 0 if i in fail_at else 1, "0.1", 0, 0, 1])


def _bench(path, goals_by_id):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        w = csv.writer(handle, lineterminator="\n")
        w.writerow(["ScenarioId", "RunId", "GoalReached", "Steps"])
        for sid, goals in goals_by_id.items():
            for run, goal in enumerate(goals, start=1):
                w.writerow([sid, run, goal, 2])


def test_run_end_to_end(tmp_path):
    root = tmp_path / "run"
    for seed in (1, 2):
        lab = root / "benchmark" / f"results_seed{seed}" / "lab3"
        for arm, tag, fails in (("ql_true", "true", (0,)), ("ql_false", "false", (0, 200))):
            cell = lab / f"training_stereo_{tag}"
            _metrics(cell / f"metrics_stereotypes_{tag}_lab3.csv", fails)
            (cell / "TRAINING_OK.json").write_text(json.dumps({"run_mode": "phase1_v2_kg_only"}),
                                                   encoding="utf-8")
        _bench(lab / "ql_true" / "benchmark_results_ql_true.csv", {1: [1, 1], 2: [1, 1], 3: [0, 1]})
        _bench(lab / "ql_false" / "benchmark_results_ql_false.csv", {1: [1, 1], 2: [1, 1], 3: [1, 1]})
        _bench(lab / "rule_based" / "benchmark_results_rule_based.csv",
               {1: [1, 1], 2: [1, 1], 3: [1, 1]})
    sdir = tmp_path / "scen"
    sdir.mkdir()
    (sdir / "train_scenarios_lab3.json").write_text(json.dumps(TRAIN), encoding="utf-8")
    (sdir / "scenarios_lab3.json").write_text(json.dumps(TEST), encoding="utf-8")
    result = lfa.run([root], (1, 2), "lab3", sdir, tmp_path / "out")
    train = {(r["arm"], r["scenario_id"]): r for r in result["train_rows"]}
    assert train[("ql_true", 1)]["failed_0_149"] == 2      # two seeds
    assert train[("ql_false", 1)]["failed_150_2999"] == 2
    assert train[("ql_false", 2)]["failed_total"] == 0
    assert train[("ql_false", 2)]["meets_goal_at_start"] is True
    test = {(r["arm"], r["test_scenario_id"]): r for r in result["test_rows"]}
    assert test[("ql_true", 3)]["goal_share"] == pytest.approx(0.5)
    assert test[("ql_true", 3)]["class"] == "unseen"
    assert test[("ql_true", 3)]["devices_on"] == "Z2Light"
    assert test[("ql_false", 2)]["class"] == "trained"
    assert "post-hoc, descriptive" in (tmp_path / "out" / "summary.md").read_text(encoding="utf-8")
