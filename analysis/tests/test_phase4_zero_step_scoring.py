"""Scoring of lab5 test runs that need no step (goal met at the start).

Such a run has a results row but no step-log row. Under the default scoring
its final device state is the start state the scenario declares; under the
legacy scoring (kept only to reproduce archived tables) it counts as all
devices off. These tests use small synthetic CSVs.
"""
import csv
import json
from pathlib import Path

import pytest

from analysis import phase4_energy as pe

WEIGHTS = {"Z1Eff": 1.0, "Z1Ineff": 4.0, "Z2Eff": 1.0, "Z2Ineff": 4.0, "Spotlight": 2.0}
SCENARIOS = [
    {"comment": "header entry without id"},
    # Starts with both inefficient lamps on (power 8 > budget 2), goal met.
    {"id": 1, "Z1Eff": False, "Z1Ineff": True, "Z2Eff": False, "Z2Ineff": True,
     "Spotlight": False, "energyBudget": 2},
    # Starts with both efficient lamps on (power 2 = budget), goal met.
    {"id": 2, "Z1Eff": True, "Z1Ineff": False, "Z2Eff": True, "Z2Ineff": False,
     "Spotlight": False, "energyBudget": 2},
    # Starts all off; the agent acts.
    {"id": 3, "Z1Eff": False, "Z1Ineff": False, "Z2Eff": False, "Z2Ineff": False,
     "Spotlight": False, "energyBudget": 2},
]


def _state(**on) -> str:
    return ";".join(f"{name}={'true' if on.get(name) else 'false'}" for name in WEIGHTS)


def _cell(root: Path, name: str, acted: list[tuple[int, str]], goals: dict[int, int]):
    """Write one replica: step rows only for `acted`, results rows for `goals`."""
    cell = root / name
    cell.mkdir(parents=True)
    with (cell / "steps.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["ScenarioId", "RunId", "Step", "ActuatorState"])
        for sid, state in acted:
            writer.writerow([sid, 1, 1, state])
    with (cell / "results.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["ScenarioId", "RunId", "GoalReached"])
        for sid, goal in goals.items():
            writer.writerow([sid, 1, goal])
    return cell / "steps.csv", cell / "results.csv"


@pytest.fixture()
def scenario_file(tmp_path):
    path = tmp_path / "scenarios_lab5.json"
    path.write_text(json.dumps(SCENARIOS), encoding="utf-8")
    return path


def _score(paths, scenario_file, scoring):
    budgets = pe.load_scenario_budgets(scenario_file, 2)
    starts = pe.load_scenario_start_states(scenario_file, WEIGHTS)
    return pe.replica_scalars(paths[0], paths[1], WEIGHTS, budgets, 2, has_budget=True,
                              start_states=starts, zero_step_scoring=scoring)


def test_start_states_read_from_scenario_file(scenario_file):
    starts = pe.load_scenario_start_states(scenario_file, WEIGHTS)
    assert set(starts) == {1, 2, 3}
    assert pe.steady_power(starts[1], WEIGHTS) == 8.0
    assert pe.steady_power(starts[2], WEIGHTS) == 2.0


def test_zero_step_run_above_budget(tmp_path, scenario_file):
    paths = _cell(tmp_path, "arm", acted=[], goals={1: 1})
    corrected = _score(paths, scenario_file, pe.ZERO_STEP_DECLARED_START_STATE)
    legacy = _score(paths, scenario_file, pe.ZERO_STEP_ALL_OFF_LEGACY)
    assert corrected["energy_compliance"] == 0.0
    assert corrected["mean_steady_power"] == 8.0
    assert corrected["over_budget_rate"] == 1.0
    assert legacy["energy_compliance"] == 1.0
    assert legacy["mean_steady_power"] == 0.0


def test_default_is_declared_start_state(tmp_path, scenario_file):
    paths = _cell(tmp_path, "arm", acted=[], goals={1: 1})
    budgets = pe.load_scenario_budgets(scenario_file, 2)
    starts = pe.load_scenario_start_states(scenario_file, WEIGHTS)
    scalars = pe.replica_scalars(paths[0], paths[1], WEIGHTS, budgets, 2,
                                 has_budget=True, start_states=starts)
    assert scalars["energy_compliance"] == 0.0


def test_missing_start_state_is_an_error_not_all_off(tmp_path, scenario_file):
    paths = _cell(tmp_path, "arm", acted=[], goals={1: 1})
    budgets = pe.load_scenario_budgets(scenario_file, 2)
    with pytest.raises(ValueError):
        pe.replica_scalars(paths[0], paths[1], WEIGHTS, budgets, 2, has_budget=True)
    with pytest.raises(ValueError):
        _score(paths, scenario_file, "something_else")


def test_runs_with_steps_are_scored_the_same_under_both(tmp_path, scenario_file):
    paths = _cell(tmp_path, "arm", acted=[(3, _state(Z1Ineff=True))], goals={3: 1})
    corrected = _score(paths, scenario_file, pe.ZERO_STEP_DECLARED_START_STATE)
    legacy = _score(paths, scenario_file, pe.ZERO_STEP_ALL_OFF_LEGACY)
    assert corrected == legacy
    assert corrected["energy_compliance"] == 0.0


def test_paired_difference_identical_when_arms_share_zero_step_runs(tmp_path, scenario_file):
    # Both arms share the zero-step runs of scenarios 1 and 2; they differ only
    # in what they do in scenario 3 (cheap lamp vs inefficient lamp).
    goals = {1: 1, 2: 1, 3: 1}
    good = _cell(tmp_path, "good", acted=[(3, _state(Z1Eff=True, Z2Eff=True))], goals=goals)
    bad = _cell(tmp_path, "bad", acted=[(3, _state(Z1Ineff=True, Z2Eff=True))], goals=goals)
    diffs = {}
    for scoring in pe.ZERO_STEP_SCORINGS:
        a = _score(good, scenario_file, scoring)
        b = _score(bad, scenario_file, scoring)
        diffs[scoring] = {m: a[m] - b[m] for m in ("energy_compliance", "mean_steady_power",
                                                    "goal_rate")}
    assert diffs[pe.ZERO_STEP_DECLARED_START_STATE] == pytest.approx(
        diffs[pe.ZERO_STEP_ALL_OFF_LEGACY], abs=1e-12)
    assert diffs[pe.ZERO_STEP_DECLARED_START_STATE]["energy_compliance"] == pytest.approx(1 / 3)
    # The levels themselves differ: scenario 1 starts above budget.
    assert _score(good, scenario_file, pe.ZERO_STEP_DECLARED_START_STATE)["energy_compliance"] \
        == pytest.approx(2 / 3)
    assert _score(good, scenario_file, pe.ZERO_STEP_ALL_OFF_LEGACY)["energy_compliance"] == 1.0
