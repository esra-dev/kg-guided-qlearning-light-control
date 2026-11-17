#!/usr/bin/env python3
"""Which lab3 situations fail, in training and in the test (post-hoc, descriptive).

POST-HOC, DESCRIPTIVE. Specified after the results were known. Counts and
shares only; no p-values and no q-values.

What it computes (Phase 1, one lab, every run mode given)
---------------------------------------------------------
* Training: per run mode, arm (``ql_true`` / ``ql_false`` training files) and
  training ScenarioId, the number of episodes presented and the number of
  failed episodes (``GoalReached == 0``), summed over seeds, split into
  episodes 0..149 and 150..2999 (the same 150-episode boundary as
  ``training_can_fail``).
* Test: per run mode, arm (``ql_true``, ``ql_false``, ``rule_based``) and test
  starting situation, the number of test runs pooled over seeds, the share
  reaching the goal and the mean steps.
* Each scenario carries its start state from the scenario files (sunshine,
  which devices are on, the declared zone levels), whether it already meets
  the goal at the start, and for test starts whether the start state is one
  of the training start states (TRAINED) or not (UNSEEN), as in
  ``test_trained_vs_unseen``.

Run (from the repository root):
  py -3 analysis/posthoc/lab3_failure_anatomy.py \
      --roots results/phase1_v2_corrected/run_29848584965 ... results/phase1_v2_corrected/run_42834963612 \
      --seeds 1-20 --out <output folder>
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from analysis.posthoc import _common as cm  # noqa: E402
from analysis.posthoc import training_can_fail as tcf  # noqa: E402

ANALYSIS = "lab3_failure_anatomy"
TEST_ARMS = ("ql_true", "ql_false", "rule_based")
BOUNDARY = tcf.EARLY_WINDOW


def short(mode: str) -> str:
    return mode.replace("phase1_v2_", "")


def training_failures(episodes: tcf.TrainingEpisodes, boundary: int = BOUNDARY) -> dict:
    """{scenario_id: {presented_early, failed_early, presented_late, failed_late}}."""
    out: dict[str, dict[str, int]] = {}
    for index, (sid, goal) in enumerate(zip(episodes.scenario_ids, episodes.goals)):
        slot = out.setdefault(sid, {"presented_early": 0, "failed_early": 0,
                                    "presented_late": 0, "failed_late": 0})
        part = "early" if index < boundary else "late"
        slot[f"presented_{part}"] += 1
        slot[f"failed_{part}"] += int(goal == 0)
    return out


def run(roots: list[Path], seeds: tuple[int, ...], lab: str, scenario_dir: Path,
        out: Path) -> dict:
    by_mode = cm.group_roots_by_mode(roots)
    modes = sorted(by_mode)
    train_rows = cm.load_scenarios(scenario_dir / f"train_scenarios_{lab}.json")
    test_rows = cm.load_scenarios(scenario_dir / f"scenarios_{lab}.json")
    terminal = tcf.terminal_ids_from_state(lab, train_rows)
    classes = {c.test_id: c for c in cm.classify_test_starts(test_rows, train_rows)}

    train_totals: dict[tuple[str, str, str], dict[str, int]] = {}
    test_totals: dict[tuple[str, str, int], dict[str, float]] = {}
    for mode in modes:
        for seed in seeds:
            for arm in cm.ARMS_QL:
                cell = cm.cell_dir(by_mode[mode], seed, lab, cm.TRAINING_SUBDIR[arm])
                episodes = tcf.read_training_episodes(tcf.metrics_path(cell, arm, lab))
                for sid, counts in training_failures(episodes).items():
                    slot = train_totals.setdefault((mode, arm, sid), {
                        "presented_early": 0, "failed_early": 0,
                        "presented_late": 0, "failed_late": 0, "n_seeds": 0})
                    for key, value in counts.items():
                        slot[key] += value
                    slot["n_seeds"] += 1
            for arm in TEST_ARMS:
                cell = cm.cell_dir(by_mode[mode], seed, lab, arm)
                for row in cm.csv_rows(cell / f"benchmark_results_{arm}.csv"):
                    sid = int(row["ScenarioId"])
                    if sid not in classes:
                        raise ValueError(f"{cell}: ScenarioId {sid} not in the test file")
                    slot = test_totals.setdefault((mode, arm, sid),
                                                  {"n_runs": 0, "n_goal": 0, "steps": 0.0})
                    slot["n_runs"] += 1
                    slot["n_goal"] += int(row["GoalReached"] == "1")
                    slot["steps"] += float(row["Steps"])

    by_id_train = {str(r["id"]): r for r in train_rows}
    by_id_test = {int(r["id"]): r for r in test_rows}
    train_out = []
    for (mode, arm, sid), t in sorted(train_totals.items(),
                                      key=lambda kv: (kv[0][0], kv[0][1], int(kv[0][2]))):
        row = by_id_train[sid]
        train_out.append({
            "run_mode": mode, "arm": arm, "scenario_id": int(sid),
            "Sunshine": row.get("Sunshine"), "devices_on": cm.devices_on(row),
            "Z1Level": row.get("Z1Level"), "Z2Level": row.get("Z2Level"),
            "meets_goal_at_start": sid in terminal, "n_seeds": t["n_seeds"],
            "episodes_0_149": t["presented_early"], "failed_0_149": t["failed_early"],
            f"episodes_{BOUNDARY}_2999": t["presented_late"],
            f"failed_{BOUNDARY}_2999": t["failed_late"],
            "failed_total": t["failed_early"] + t["failed_late"],
        })
    test_out = []
    for (mode, arm, sid), t in sorted(test_totals.items()):
        row = by_id_test[sid]
        cls = classes[sid]
        test_out.append({
            "run_mode": mode, "arm": arm, "test_scenario_id": sid,
            "class": "trained" if cls.trained else "unseen",
            "Sunshine": row.get("Sunshine"), "devices_on": cm.devices_on(row),
            "Z1Level": row.get("Z1Level"), "Z2Level": row.get("Z2Level"),
            "meets_goal_at_start": cm.start_meets_goal(lab, row),
            "n_test_runs": t["n_runs"], "n_goal": t["n_goal"],
            "goal_share": t["n_goal"] / t["n_runs"], "mean_steps": t["steps"] / t["n_runs"],
        })

    out.mkdir(parents=True, exist_ok=True)
    cm.write_csv(out / "training_failures_by_scenario.csv", train_out, [
        "run_mode", "arm", "scenario_id", "Sunshine", "devices_on", "Z1Level", "Z2Level",
        "meets_goal_at_start", "n_seeds", "episodes_0_149", "failed_0_149",
        f"episodes_{BOUNDARY}_2999", f"failed_{BOUNDARY}_2999", "failed_total"])
    cm.write_csv(out / "test_goal_share_by_start.csv", test_out, [
        "run_mode", "arm", "test_scenario_id", "class", "Sunshine", "devices_on", "Z1Level",
        "Z2Level", "meets_goal_at_start", "n_test_runs", "n_goal", "goal_share", "mean_steps"])
    result = {"modes": modes, "train_rows": train_out, "test_rows": test_out,
              "train_scenarios": train_rows, "test_scenarios": test_rows,
              "terminal": terminal, "classes": classes}
    _write_summary(out / "summary.md", result, seeds, lab)
    return result


def _write_summary(path: Path, result: dict, seeds, lab: str) -> None:
    modes = result["modes"]
    train = {(r["run_mode"], r["arm"], r["scenario_id"]): r for r in result["train_rows"]}
    test = {(r["run_mode"], r["arm"], r["test_scenario_id"]): r for r in result["test_rows"]}
    lines = [
        f"# {lab}: which situations fail",
        "",
        f"Status: {cm.POSTHOC_LABEL}. Counts and shares only; no p-values or q-values.",
        f"Seeds {seeds[0]}..{seeds[-1]} ({len(seeds)}), summed or pooled over seeds.",
        "",
        f"## Training: failed episodes, episodes 0..{BOUNDARY - 1} + {BOUNDARY}..2999 "
        "(T = ql_true, F = ql_false)",
        "",
    ]
    table = []
    for row in result["train_scenarios"]:
        sid = int(row["id"])
        entry = {"id": sid, "sun": row.get("Sunshine"), "on": cm.devices_on(row),
                 "goal_at_start": "yes" if str(sid) in result["terminal"] else "no"}
        for mode in modes:
            t = train.get((mode, "ql_true", sid))
            f = train.get((mode, "ql_false", sid))
            entry[short(mode)] = (f"T {t['failed_0_149']}+{t[f'failed_{BOUNDARY}_2999']} / "
                                  f"F {f['failed_0_149']}+{f[f'failed_{BOUNDARY}_2999']}")
        table.append(entry)
    lines += cm.md_table(table, ["id", "sun", "on", "goal_at_start", *map(short, modes)])
    lines += ["", "## Test: share of test runs reaching the goal (T / F / R = rule_based)", ""]
    table = []
    for row in result["test_scenarios"]:
        sid = int(row["id"])
        cls = result["classes"][sid]
        entry = {"id": sid, "class": "trained" if cls.trained else "unseen",
                 "sun": row.get("Sunshine"), "on": cm.devices_on(row)}
        for mode in modes:
            parts = []
            for arm, tag in (("ql_true", "T"), ("ql_false", "F"), ("rule_based", "R")):
                parts.append(f"{tag} {cm.num(test[(mode, arm, sid)]['goal_share'], 2)}")
            entry[short(mode)] = " ".join(parts)
        table.append(entry)
    lines += cm.md_table(table, ["id", "class", "sun", "on", *map(short, modes)])
    lines += ["", "Full tables: training_failures_by_scenario.csv, test_goal_share_by_start.csv."]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--roots", nargs="+", type=Path, required=True)
    parser.add_argument("--seeds", default="1-20")
    parser.add_argument("--lab", default="lab3")
    parser.add_argument("--scenario-dir", type=Path, default=Path("benchmark"))
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    run(args.roots, cm.parse_seeds(args.seeds), args.lab, args.scenario_dir, args.out)
    print(f"{ANALYSIS}: -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
