"""Guards analysis/posthoc/training_can_fail.py (post-hoc, descriptive).

Synthetic Phase-1 archives only. Covers the terminal-at-start definition derived
from the start state, the 150-episode window boundary, the strict training
reader, the paired contrasts, and the stops raised when the arms use different
schedules or a terminal-at-start scenario fails.
"""

import csv
import json

import pytest

from analysis.posthoc import training_can_fail as tcf

LAB1_TRAIN = [
    {"comment": "synthetic", "id": 1, "Z1Level": 25, "Z1Light": False, "Sunshine": 0,
     "description": "dark"},
    {"id": 2, "Z1Level": 425, "Z1Light": True, "Sunshine": 100, "description": "lit"},
]
MODES = ("phase1_v2_kg_only", "phase1_v2_brick_only", "phase1_v2_redundancy_only")


def _schedule(n=tcf.HORIZON):
    return [1 if i % 2 == 0 else 2 for i in range(n)]


def _write_metrics(path, steps, goals, wasted_pen, wasted_ne, scenarios):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        w = csv.writer(handle, lineterminator="\n")
        w.writerow(["Episode", "Steps", "RewardZ1", "RewardZ2", "GoalReached", "Epsilon",
                    "WastedByPenalty", "WastedByNoEffect", "ScenarioId"])
        for i, (s, g, wp, wn, sid) in enumerate(zip(steps, goals, wasted_pen, wasted_ne,
                                                     scenarios)):
            w.writerow([i, s, "1.00", "0.00", g, "0.3", wp, wn, sid])
        handle.write("\n# Summary\n# TotalGoalCount,0\n")


def _arm_series(fail_at=(), slow=False):
    sched = _schedule()
    steps, goals, wp, wn = [], [], [], []
    for i, sid in enumerate(sched):
        if sid == 2:
            steps.append(0); goals.append(1); wp.append(0); wn.append(0)
        elif i in fail_at:
            steps.append(10); goals.append(0); wp.append(3); wn.append(2)
        else:
            steps.append(2 if slow else 1); goals.append(1); wp.append(0); wn.append(0)
    return steps, goals, wp, wn, sched


def _build(tmp_path, series, seeds=(1, 2), lab="lab1"):
    """series[(mode, arm)] -> callable(seed) returning _arm_series output."""
    roots = []
    for mode in MODES:
        root = tmp_path / f"run_{mode}"
        roots.append(root)
        for seed in seeds:
            for arm, tag in (("ql_true", "true"), ("ql_false", "false")):
                cell = root / "benchmark" / f"results_seed{seed}" / lab / f"training_stereo_{tag}"
                steps, goals, wp, wn, sched = series[(mode, arm)](seed)
                _write_metrics(cell / f"metrics_stereotypes_{tag}_{lab}.csv",
                               steps, goals, wp, wn, sched)
                (cell / "TRAINING_OK.json").write_text(json.dumps({
                    "status": "ok", "run_mode": mode, "run_seed": seed, "profile": lab,
                    "stereotype": tag, "fixed_horizon_episodes": tcf.HORIZON}),
                    encoding="utf-8")
    sdir = tmp_path / "scenarios"
    sdir.mkdir()
    (sdir / f"train_scenarios_{lab}.json").write_text(json.dumps(LAB1_TRAIN), encoding="utf-8")
    return roots, sdir


def _default_series():
    return {
        (m, a): (lambda seed, a=a: _arm_series(fail_at=(0, 148, 150) if a == "ql_false" else ()))
        for m in MODES for a in ("ql_true", "ql_false")
    }


# ── unit-level definitions ─────────────────────────────────────────────────

def test_terminal_ids_from_state_lab1_and_lab2():
    assert tcf.terminal_ids_from_state("lab1", LAB1_TRAIN) == {"2"}
    lab2 = [
        {"id": 1, "Z1Level": 425, "Z2Level": 425, "Z1Light": True, "Z2Light": True,
         "Z1Blinds": False, "Z2Blinds": False, "Sunshine": 0},
        {"id": 2, "Z1Level": 425, "Z2Level": 25, "Z1Light": True, "Z2Light": False,
         "Z1Blinds": False, "Z2Blinds": False, "Sunshine": 0},
        {"id": 3, "Z1Level": 475, "Z2Level": 475, "Z1Light": False, "Z2Light": False,
         "Z1Blinds": True, "Z2Blinds": True, "Sunshine": 900},
    ]
    assert tcf.terminal_ids_from_state("lab2", lab2) == {"1", "3"}


def test_declared_levels_must_match_physics():
    bad = [{"id": 1, "Z1Level": 300, "Z1Light": False, "Sunshine": 0}]
    with pytest.raises(ValueError, match="physics"):
        tcf.terminal_ids_from_state("lab1", bad)


def test_window_boundary_149_in_150_out():
    steps, goals, wp, wn, sched = _arm_series(fail_at=(148, 150))
    ep = tcf.TrainingEpisodes(tuple(steps), tuple(goals),
                              tuple(a + b for a, b in zip(wp, wn)), tuple(map(str, sched)))
    early = tcf.window_measures(ep, {"2"}, 0, tcf.EARLY_WINDOW)
    full = tcf.window_measures(ep, {"2"}, 0, tcf.HORIZON)
    assert early["n_episodes"] == 150
    assert early["n_can_fail"] == 75 and early["n_terminal_at_start"] == 75
    assert early["n_failed_can_fail"] == 1          # episode 148 only; 150 is outside
    assert full["n_failed_can_fail"] == 2
    assert early["success_share_can_fail"] == pytest.approx(74 / 75)
    assert early["mean_steps_can_fail"] == pytest.approx((10 + 74 * 1) / 75)
    assert early["mean_steps_successful_can_fail"] == pytest.approx(1.0)
    assert early["mean_wasted_can_fail"] == pytest.approx(5 / 75)
    assert early["overall_success_share"] == pytest.approx(149 / 150)


def test_verify_terminal_in_data_flags_both_directions():
    ep = tcf.TrainingEpisodes((0, 1, 0, 0), (1, 1, 1, 1), (0, 0, 0, 0), ("2", "2", "1", "1"))
    problems = tcf.verify_terminal_in_data(ep, {"2"}, {"1", "2"})
    assert any("terminal-at-start scenario 2" in p for p in problems)
    assert any("scenario 1 is not terminal-at-start" in p for p in problems)
    ok = tcf.TrainingEpisodes((0, 1), (1, 1), (0, 0), ("2", "1"))
    assert tcf.verify_terminal_in_data(ok, {"2"}, {"1", "2"}) == []


def test_reader_rejects_short_file(tmp_path):
    path = tmp_path / "m.csv"
    _write_metrics(path, [0], [1], [0], [0], [2])
    with pytest.raises(ValueError, match="expected"):
        tcf.read_training_episodes(path)


# ── end to end ─────────────────────────────────────────────────────────────

def test_run_end_to_end(tmp_path):
    """Runs the script on a two-seed synthetic archive and checks contrasts and output files."""
    roots, sdir = _build(tmp_path, _default_series())
    out = tmp_path / "out"
    result = tcf.run(roots, (1, 2), ("lab1",), sdir, out, iters=200)
    assert result["terminal_sets"] == {"lab1": ["2"]}
    assert result["skipped_contrasts"] == []
    rows = {(r["contrast"], r["window"], r["measure"]): r for r in result["contrast_rows"]}
    early = rows[("full_knowledge_minus_plain", "first150", "n_failed_can_fail")]
    assert early["mean_paired_difference"] == pytest.approx(-2.0)
    assert early["n_seeds_left_lower"] == 2
    full = rows[("full_knowledge_minus_plain", "all", "success_share_can_fail")]
    assert full["mean_paired_difference"] == pytest.approx(3 / 1500)
    overall = rows[("full_knowledge_minus_plain", "all", "overall_success_share")]
    assert overall["mean_paired_difference"] == pytest.approx(3 / 3000)
    across = rows[("full_knowledge_minus_rule", "all", "success_share_can_fail")]
    assert across["mean_paired_difference"] == 0
    assert all(r["byte_identical_across_modes"] for r in result["identity_rows"])
    assert all(r["identical_across_arms_and_modes"] for r in result["schedule_rows"])
    for name in ("contrasts.csv", "cells_per_seed.csv", "terminal_at_start.csv",
                 "plain_arm_identity.csv", "summary.md"):
        assert (out / name).is_file()
    assert "post-hoc, descriptive" in (out / "summary.md").read_text(encoding="utf-8")


def test_run_skips_contrasts_without_their_mode(tmp_path):
    roots, sdir = _build(tmp_path, _default_series())
    result = tcf.run([r for r in roots if "redundancy" not in r.name], (1, 2), ("lab1",),
                     sdir, tmp_path / "out", iters=50)
    assert set(result["skipped_contrasts"]) == {
        "rule_minus_plain", "full_knowledge_minus_rule", "structure_only_minus_rule"}


def test_run_detects_plain_arm_difference(tmp_path):
    series = _default_series()
    series[("phase1_v2_brick_only", "ql_false")] = lambda seed: _arm_series(
        fail_at=(0, 148, 150), slow=(seed == 2))
    roots, sdir = _build(tmp_path, series)
    result = tcf.run(roots, (1, 2), ("lab1",), sdir, tmp_path / "out", iters=50)
    ident = {r["seed"]: r for r in result["identity_rows"]}
    assert ident[1]["byte_identical_across_modes"]
    assert not ident[2]["byte_identical_across_modes"]
    assert ident[2]["modes_differing_from_reference"] == "phase1_v2_brick_only"


def test_run_stops_when_arms_use_different_schedules(tmp_path):
    series = _default_series()

    def shifted(seed):
        steps, goals, wp, wn, sched = _arm_series()
        sched = [2 if s == 1 else 1 for s in sched]
        steps = [0 if s == 2 else 1 for s in sched]
        return steps, goals, wp, wn, sched

    series[("phase1_v2_kg_only", "ql_true")] = shifted
    roots, sdir = _build(tmp_path, series)
    with pytest.raises(ValueError, match="schedule"):
        tcf.run(roots, (1, 2), ("lab1",), sdir, tmp_path / "out", iters=50)


def test_run_stops_on_terminal_violation(tmp_path):
    series = _default_series()

    def broken(seed):
        steps, goals, wp, wn, sched = _arm_series()
        steps[1] = 3   # episode 1 is terminal-at-start scenario 2
        return steps, goals, wp, wn, sched

    series[("phase1_v2_kg_only", "ql_false")] = broken
    roots, sdir = _build(tmp_path, series)
    with pytest.raises(ValueError, match="terminal-at-start verification failed"):
        tcf.run(roots, (1, 2), ("lab1",), sdir, tmp_path / "out", iters=50)
