#!/usr/bin/env python3
"""Sanity checks of the post-hoc outputs against the archived accepted numbers.

POST-HOC, DESCRIPTIVE support script. It reads the output folders written by
the five post-hoc analyses and the archived analysis tables of the runs of
record, and writes one row per check (expected, observed, absolute
difference, pass) to ``sanity_checks.csv`` plus a markdown version. It
computes no statistics of its own.

Archived tables read (all read-only):
  <phase1 archive>/run_<id>/analysis/out/summary_table_ci.csv and
  learning_speed_table.csv (Phase-1 benchmark and training means per run);
  <phase2 corrected>/analysis/registered/phase2_v2_detection_family.csv;
  <phase2 structonly>/analysis/registered/phase2_structonly_v3_detection_family.csv;
  <phase4 corrected>/analysis/registered/phase4_v2_registered_family.csv;
  <phase4 ladder>/analysis/registered/phase4_v2_knowledge_ladder_descriptives.csv.

Run (from the repository root):
  py -3 analysis/posthoc/check_against_accepted.py --posthoc-out <folder with the five outputs> \
      --phase1-archive results/phase1_v2_corrected --phase2-corrected results/phase2_v2_corrected \
      --phase2-structonly results/phase2_v3_structonly \
      --phase4-corrected results/phase4_v2_corrected --phase4-ladder results/phase4_v2_ladder
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from analysis.posthoc import _common as cm  # noqa: E402

TOL = 1e-9
PHASE1_RUNS = {
    "29848584965": "phase1_v2_kg_only", "29848587274": "phase1_v2_redundancy_only",
    "29848589682": "phase1_v2_baseline", "29848592010": "phase1_v2_pbrs_only",
    "42834963612": "phase1_v2_brick_only",
}
LADDER_ROWS = {"A3_full_kg_effect": "phase4_v2", "A2_brick_effect": "phase4_v2_brick_only",
               "A1_redundancy_effect": "phase4_v2_redundancy_only"}


def _rows(path: Path) -> list[dict]:
    with Path(path).open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def run(args) -> list[dict]:
    out = args.posthoc_out
    checks: list[dict] = []

    def add(group, name, expected, observed, tol=TOL, source=""):
        if isinstance(expected, (int, float)) and isinstance(observed, (int, float)):
            diff = abs(float(expected) - float(observed))
            ok = diff <= tol
        else:
            diff, ok = "", expected == observed
        checks.append({"analysis": group, "check": name, "expected": expected,
                       "observed": observed, "abs_difference": diff, "tolerance": tol,
                       "pass": ok, "expected_source": source})

    # 1 ── trained vs unseen ──────────────────────────────────────────────
    a1 = out / "test_trained_vs_unseen"
    counts = {r["lab"]: r for r in _rows(a1 / "start_counts.csv")}
    for lab, (trained, total) in {"lab1": (6, 8), "lab2": (11, 16), "lab3": (10, 16),
                                  "lab4": (10, 16), "lab5": (10, 16)}.items():
        add("test_trained_vs_unseen", f"{lab} trained of total",
            f"{trained} of {total}",
            f"{counts[lab]['n_trained']} of {counts[lab]['n_test_starts']}", source="brief")
    means = {(r["run_mode"], r["lab"], r["arm"], r["subset"], r["measure"]):
             float(r["mean_over_seeds"]) for r in _rows(a1 / "cell_means.csv")}
    for run_id, mode in PHASE1_RUNS.items():
        table = args.phase1_archive / f"run_{run_id}" / "analysis" / "out" / "summary_table_ci.csv"
        for row in _rows(table):
            for ours, theirs in (("goal_share", "goal_rate_mean"), ("mean_steps", "avg_steps_mean")):
                add("test_trained_vs_unseen",
                    f"{mode} {row['profile']} {row['mode']} ALL-subset {ours}",
                    float(row[theirs]), means[(mode, row["profile"], row["mode"], "all", ours)],
                    source=str(table.relative_to(args.phase1_archive)))
    contrasts = {(r["run_mode"], r["lab"], r["subset"], r["measure"]): r
                 for r in _rows(a1 / "contrasts.csv")}
    ladder = args.phase4_ladder / "analysis" / "registered" / "phase4_v2_knowledge_ladder_descriptives.csv"
    for row in _rows(ladder):
        if row["row"] in LADDER_ROWS and row["metric"] in ("goal_rate", "avg_steps"):
            ours = "goal_share" if row["metric"] == "goal_rate" else "mean_steps"
            mode = LADDER_ROWS[row["row"]]
            add("test_trained_vs_unseen",
                f"{mode} {row['lab']} ALL-subset ql_true-ql_false {ours}",
                float(row["mean"]),
                float(contrasts[(mode, row["lab"], "all", ours)]["mean_paired_difference"]),
                source="phase4_v2_ladder/analysis/registered/" + ladder.name)

    # 2 ── training can fail ──────────────────────────────────────────────
    a2 = out / "training_can_fail"
    term = [r for r in _rows(a2 / "terminal_at_start.csv")
            if r["lab"] == "lab2" and r["terminal_at_start_from_state"] == "true"]
    add("training_can_fail", "lab2 terminal-at-start episodes per file", 1091,
        sum(int(r["episodes_per_file"]) for r in term), source="project handbook (brief)")
    tmeans = {(r["run_mode"], r["lab"], r["arm"], r["window"], r["measure"]):
              float(r["mean_over_seeds"]) for r in _rows(a2 / "cell_means.csv")}
    add("training_can_fail", "lab2 kg_only ql_true overall success share (4 dp)", "1.0000",
        f"{tmeans[('phase1_v2_kg_only', 'lab2', 'ql_true', 'all', 'overall_success_share')]:.4f}",
        source="brief")
    add("training_can_fail", "lab2 kg_only ql_false overall success share (4 dp)", "0.9963",
        f"{tmeans[('phase1_v2_kg_only', 'lab2', 'ql_false', 'all', 'overall_success_share')]:.4f}",
        source="brief")
    for run_id, mode in PHASE1_RUNS.items():
        table = args.phase1_archive / f"run_{run_id}" / "analysis" / "out" / "learning_speed_table.csv"
        for row in _rows(table):
            if row["metric"] != "auc_goal":
                continue
            add("training_can_fail", f"{mode} {row['profile']} {row['condition']} auc_goal",
                float(row["mean"]),
                tmeans[(mode, row["profile"], row["condition"], "all", "overall_success_share")],
                source=str(table.relative_to(args.phase1_archive)))

    # 3 ── detection ───────────────────────────────────────────────────────
    a3 = out / "detection_correct_component"
    cells = {(r["archive"], r["fault_lab"], r["arm"]): r for r in _rows(a3 / "cells.csv")}
    det = args.phase2_corrected / "analysis" / "registered" / "phase2_v2_detection_family.csv"
    for row in _rows(det):
        for arm in ("ql_true", "ql_false"):
            add("detection_correct_component",
                f"v2_corrected {row['registered_cell']} {arm} record mean DetectEpisode",
                float(row[f"mean_{arm}"]),
                float(cells[("v2_corrected", row["registered_cell"], arm)]["mean_record_detect_episode"]),
                source="phase2_v2_corrected/analysis/registered/" + det.name)
    sdet = args.phase2_structonly / "analysis" / "registered" / "phase2_structonly_v3_detection_family.csv"
    for row in _rows(sdet):
        add("detection_correct_component",
            f"structonly {row['registered_cell']} {row['arm']} record mean DetectEpisode",
            float(row["mean_structonly"]),
            float(cells[("structonly", row["registered_cell"], row["arm"])]["mean_record_detect_episode"]),
            source="phase2_v3_structonly/analysis/registered/" + sdet.name)
        if row["registered_cell"] in ("lab3_f2dead_lowsun", "labmon2_f2dead_lowsun"):
            add("detection_correct_component",
                f"lowsun {row['registered_cell']} {row['arm']} record mean DetectEpisode",
                float(row["mean_record"]),
                float(cells[("lowsun", row["registered_cell"], row["arm"])]["mean_record_detect_episode"]),
                source="phase2_v3_structonly/analysis/registered/" + sdet.name)
    seed6 = [r for r in _rows(a3 / "per_seed.csv")
             if (r["archive"], r["fault_lab"], r["arm"], r["seed"])
             == ("structonly", "lab3_f1bdead", "ql_false", "6")]
    add("detection_correct_component", "structonly lab3_f1bdead ql_false seed 6 first blame",
        "SetZ2Blinds", seed6[0]["first_blame"] if seed6 else "missing", source="brief")

    # 4 ── lab3 anatomy ────────────────────────────────────────────────────
    a4 = out / "lab3_failure_anatomy"
    failed: dict[tuple[str, str], int] = {}
    for row in _rows(a4 / "training_failures_by_scenario.csv"):
        key = (row["run_mode"], row["arm"])
        failed[key] = failed.get(key, 0) + int(row["failed_total"])
    pooled: dict[tuple[str, str], list[int]] = {}
    for row in _rows(a4 / "test_goal_share_by_start.csv"):
        slot = pooled.setdefault((row["run_mode"], row["arm"]), [0, 0])
        slot[0] += int(row["n_goal"])
        slot[1] += int(row["n_test_runs"])
    for run_id, mode in PHASE1_RUNS.items():
        base = args.phase1_archive / f"run_{run_id}" / "analysis" / "out"
        for row in _rows(base / "learning_speed_table.csv"):
            if row["profile"] == "lab3" and row["metric"] == "auc_goal":
                add("lab3_failure_anatomy", f"{mode} {row['condition']} failed training episodes",
                    round(20 * 3000 * (1 - float(row["mean"]))),
                    failed[(mode, row["condition"])], tol=0,
                    source="learning_speed_table.csv auc_goal x 60000")
        for row in _rows(base / "summary_table_ci.csv"):
            if row["profile"] == "lab3":
                g, n = pooled[(mode, row["mode"])]
                add("lab3_failure_anatomy", f"{mode} {row['mode']} pooled test goal share",
                    float(row["goal_rate_mean"]), g / n, source="summary_table_ci.csv")

    # 5 ── phase-4 steps split ─────────────────────────────────────────────
    a5 = out / "phase4_steps_split"
    p5 = {(r["run_mode"], r["lab"], r["measure"]): r for r in _rows(a5 / "contrasts.csv")}
    add("phase4_steps_split", "lab4 phase4_v2 mean paired difference of the sum (3 dp)", "-0.695",
        f"{float(p5[('phase4_v2', 'lab4', 'mean_wasted_plus_cycling')]['mean_paired_difference']):.3f}",
        source="brief")
    fam = args.phase4_corrected / "analysis" / "registered" / "phase4_v2_registered_family.csv"
    for row in _rows(fam):
        if row["metric"] != "avg_redundant":
            continue
        ours = p5[("phase4_v2", row["registered_cell"], "mean_wasted_plus_cycling")]
        for theirs, mine in (("mean_ql_true", "mean_left"), ("mean_ql_false", "mean_right"),
                             ("mean_paired_difference", "mean_paired_difference")):
            add("phase4_steps_split", f"phase4_v2 {row['registered_cell']} sum {theirs}",
                float(row[theirs]), float(ours[mine]),
                source="phase4_v2_corrected/analysis/registered/" + fam.name)
    for row in _rows(ladder):
        if row["row"] in LADDER_ROWS and row["metric"] == "avg_redundant":
            mode = LADDER_ROWS[row["row"]]
            add("phase4_steps_split", f"{mode} {row['lab']} sum ql_true-ql_false",
                float(row["mean"]),
                float(p5[(mode, row["lab"], "mean_wasted_plus_cycling")]["mean_paired_difference"]),
                source="phase4_v2_ladder/analysis/registered/" + ladder.name)
    return checks


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--posthoc-out", type=Path, required=True)
    parser.add_argument("--phase1-archive", type=Path, required=True)
    parser.add_argument("--phase2-corrected", type=Path, required=True)
    parser.add_argument("--phase2-structonly", type=Path, required=True)
    parser.add_argument("--phase4-corrected", type=Path, required=True)
    parser.add_argument("--phase4-ladder", type=Path, required=True)
    args = parser.parse_args(argv)
    checks = run(args)
    fields = ["analysis", "check", "expected", "observed", "abs_difference", "tolerance",
              "pass", "expected_source"]
    cm.write_csv(args.posthoc_out / "sanity_checks.csv", checks, fields)
    lines = ["# Sanity checks against archived accepted numbers", "",
             f"Status: {cm.POSTHOC_LABEL}. {sum(c['pass'] for c in checks)} of {len(checks)} "
             "checks pass.", ""]
    by_group: dict[str, list[dict]] = {}
    for c in checks:
        by_group.setdefault(c["analysis"], []).append(c)
    for group, items in by_group.items():
        lines.append(f"- {group}: {sum(c['pass'] for c in items)} of {len(items)} pass")
    failing = [c for c in checks if not c["pass"]]
    if failing:
        lines += ["", "## Failing checks", ""]
        lines += cm.md_table(failing, ["analysis", "check", "expected", "observed"], digits=6)
    (args.posthoc_out / "sanity_checks.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{sum(c['pass'] for c in checks)} of {len(checks)} checks pass")
    return 0 if not failing else 1


if __name__ == "__main__":
    raise SystemExit(main())
