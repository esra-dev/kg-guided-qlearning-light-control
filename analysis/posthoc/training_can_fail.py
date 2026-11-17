#!/usr/bin/env python3
"""Training success and steps on episodes that can fail (post-hoc, descriptive).

POST-HOC, DESCRIPTIVE. This analysis was specified after the Phase-1 results
were known. It computes means, paired per-seed differences, 95% bootstrap
intervals of the mean paired difference and sign counts. It computes no
p-values and no q-values. It is written so that it can be re-run unchanged on
a replication with fresh seeds (``--seeds``).

Definitions
-----------
* Terminal-at-start scenario: a training starting situation that already
  meets the goal. It is derived from the start state alone: every target zone
  of the lab (lab1: Z1; lab2/lab3: Z1 and Z2) has a declared start level of at
  least 300 lux (rank 3 under light bounds [50, 100, 300]). The declared levels
  are first checked against the lab physics
  (``validate_phase1_scenarios.expected_levels``). The derived set is then
  verified in every training file read: every episode of a terminal-at-start
  scenario has ``Steps == 0`` and ``GoalReached == 1``, and no other scenario
  has that for all of its episodes. Any violation stops the run.
* Can-fail episode: an episode whose scenario is not terminal-at-start.
* Windows: ALL episodes (index 0..horizon-1) and the FIRST 150 episodes
  (index 0..149). The 150-episode window is fixed by the earlier exploratory
  study and is not a parameter.

Measures per (run mode, lab, arm, seed, window)
-----------------------------------------------
n_can_fail; n_failed_can_fail (GoalReached == 0); success_share_can_fail;
mean_steps_can_fail (failed episodes keep their logged step count);
mean_steps_successful_can_fail (successful can-fail episodes only);
mean_wasted_can_fail (WastedByPenalty + WastedByNoEffect, summed as logged);
overall_success_share (all episodes of the window, terminal ones included).
For the ALL window the overall share is cross-checked against the registered
``auc_goal`` loader (``sweep_report._load_episode_metrics`` +
``_auc_normalised``) and must agree exactly.

Paired per-seed contrasts (left - right, same seed)
---------------------------------------------------
full knowledge - plain    kg_only run:          ql_true - ql_false
rule - plain              redundancy_only run:  ql_true - ql_false
structure-only - plain    brick_only run:       ql_true - ql_false
full knowledge - structure-only   kg_only ql_true - brick_only ql_true
full knowledge - rule             kg_only ql_true - redundancy_only ql_true
structure-only - rule             brick_only ql_true - redundancy_only ql_true
A contrast is skipped (and listed in the summary) when one of its run modes
is not among the inputs. Other run modes (baseline, pbrs_only) are described
per cell but enter no contrast.

Checks written to the output: the plain arm's training files are compared
byte for byte (SHA-256) across all run modes per (lab, seed); the ScenarioId
column is compared across every arm and run mode per (lab, seed). The two arms
of one run must share the schedule, otherwise the run stops.

Run (from the repository root):
  py -3 analysis/posthoc/training_can_fail.py \
      --roots results/phase1_v2_corrected/run_29848584965 results/phase1_v2_corrected/run_29848587274 \
              results/phase1_v2_corrected/run_29848589682 results/phase1_v2_corrected/run_29848592010 \
              results/phase1_v2_corrected/run_42834963612 \
      --seeds 1-20 --out <output folder>
The run mode of each root is read from its TRAINING_OK.json files; several
roots may share a mode (seeds split across runs), but each (mode, seed, lab,
arm) must resolve to exactly one file.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from analysis import phase1_truncated_learning as ptl  # noqa: E402
from analysis import sweep_report as sr  # noqa: E402
from analysis import validate_phase1_scenarios as vps  # noqa: E402
from analysis.posthoc import _common as cm  # noqa: E402

ANALYSIS = "training_can_fail"
HORIZON = 3000
EARLY_WINDOW = 150
WINDOWS = (("all", 0, HORIZON), ("first150", 0, EARLY_WINDOW))
DEFAULT_LABS = ("lab1", "lab2", "lab3")

MODE_KG = "phase1_v2_kg_only"
MODE_RULE = "phase1_v2_redundancy_only"
MODE_STRUCT = "phase1_v2_brick_only"
CONTRASTS = (
    ("full_knowledge_minus_plain", (MODE_KG, "ql_true"), (MODE_KG, "ql_false")),
    ("rule_minus_plain", (MODE_RULE, "ql_true"), (MODE_RULE, "ql_false")),
    ("structure_only_minus_plain", (MODE_STRUCT, "ql_true"), (MODE_STRUCT, "ql_false")),
    ("full_knowledge_minus_structure_only", (MODE_KG, "ql_true"), (MODE_STRUCT, "ql_true")),
    ("full_knowledge_minus_rule", (MODE_KG, "ql_true"), (MODE_RULE, "ql_true")),
    ("structure_only_minus_rule", (MODE_STRUCT, "ql_true"), (MODE_RULE, "ql_true")),
)
MEASURES = (
    "n_episodes", "n_terminal_at_start", "n_can_fail", "n_failed_can_fail",
    "success_share_can_fail", "mean_steps_can_fail", "n_successful_can_fail",
    "mean_steps_successful_can_fail", "mean_wasted_can_fail", "overall_success_share",
)
CONTRAST_MEASURES = (
    "success_share_can_fail", "n_failed_can_fail", "mean_steps_can_fail",
    "mean_steps_successful_can_fail", "mean_wasted_can_fail", "overall_success_share",
)
REQUIRED_COLUMNS = ("Episode", "Steps", "GoalReached", "WastedByPenalty",
                    "WastedByNoEffect", "ScenarioId")


# ── reading ────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class TrainingEpisodes:
    steps: tuple[int, ...]
    goals: tuple[int, ...]
    wasted: tuple[int, ...]
    scenario_ids: tuple[str, ...]


def metrics_path(cell: Path, arm: str, lab: str) -> Path:
    tag = "true" if arm == "ql_true" else "false"
    return cell / f"metrics_stereotypes_{tag}_{lab}.csv"


def read_training_episodes(path: Path, horizon: int = HORIZON) -> TrainingEpisodes:
    """Strict reader for one training metrics file (``#`` footer ignored).

    Episodes must be contiguous from 0 and exactly ``horizon`` long. Goals and
    ScenarioIds are cross-checked against the registered strict reader
    ``phase1_truncated_learning.read_episode_metrics``.
    """
    import csv

    steps: list[int] = []
    goals: list[int] = []
    wasted: list[int] = []
    scenarios: list[str] = []
    with Path(path).open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = set(REQUIRED_COLUMNS).difference(reader.fieldnames or ())
        if missing:
            raise ValueError(f"{path}: missing columns {sorted(missing)}")
        for expected, row in enumerate(reader):
            episode_text = str(row.get("Episode", "")).strip()
            if episode_text.startswith("#"):
                break
            if int(episode_text) != expected:
                raise ValueError(f"{path}: expected Episode={expected}, got {episode_text}")
            goal = int(row["GoalReached"])
            if goal not in (0, 1):
                raise ValueError(f"{path}: GoalReached must be 0/1, got {goal}")
            steps.append(int(row["Steps"]))
            goals.append(goal)
            wasted.append(int(row["WastedByPenalty"]) + int(row["WastedByNoEffect"]))
            scenarios.append(str(row["ScenarioId"]).strip())
    if len(goals) != horizon:
        raise ValueError(f"{path}: expected {horizon} episodes, got {len(goals)}")
    reference = ptl.read_episode_metrics(Path(path), horizon)
    if reference.goals != tuple(goals) or reference.scenario_ids != tuple(scenarios):
        raise ValueError(f"{path}: disagrees with the registered strict reader")
    return TrainingEpisodes(tuple(steps), tuple(goals), tuple(wasted), tuple(scenarios))


def registered_auc_goal(path: Path) -> float:
    goals, _rewards = sr._load_episode_metrics(Path(path))
    return sr._auc_normalised(goals)


# ── terminal-at-start ──────────────────────────────────────────────────────

def check_declared_levels(lab: str, rows: list[dict]) -> None:
    for row in rows:
        for key, expected in vps.expected_levels(lab, row).items():
            if abs(float(row[key]) - expected) > 1e-9:
                raise ValueError(f"{lab} scenario {row['id']}: {key}={row[key]} "
                                 f"but physics gives {expected}")


def terminal_ids_from_state(lab: str, train_rows: list[dict]) -> set[str]:
    """ScenarioIds whose declared start state already meets the goal."""
    check_declared_levels(lab, train_rows)
    return {str(row["id"]) for row in train_rows if cm.start_meets_goal(lab, row)}


def verify_terminal_in_data(episodes: TrainingEpisodes, terminal: set[str],
                            known_ids: set[str]) -> list[str]:
    """Problems found when testing the terminal-at-start set against one file."""
    problems: list[str] = []
    per_scenario: dict[str, bool] = {}
    for sid, steps, goal in zip(episodes.scenario_ids, episodes.steps, episodes.goals):
        if sid not in known_ids:
            problems.append(f"ScenarioId {sid} is not in the training scenario file")
            known_ids = known_ids | {sid}
        zero_goal = steps == 0 and goal == 1
        per_scenario[sid] = per_scenario.get(sid, True) and zero_goal
    for sid, all_zero_goal in sorted(per_scenario.items(), key=lambda kv: int(kv[0])):
        if sid in terminal and not all_zero_goal:
            problems.append(f"terminal-at-start scenario {sid} has an episode with "
                            "Steps != 0 or GoalReached != 1")
        if sid not in terminal and all_zero_goal:
            problems.append(f"scenario {sid} is not terminal-at-start but every episode "
                            "has Steps == 0 and GoalReached == 1")
    return problems


# ── measures ───────────────────────────────────────────────────────────────

def window_measures(episodes: TrainingEpisodes, terminal: set[str],
                    start: int, stop: int) -> dict:
    """Measures over episode indices start..stop-1 (can-fail = not terminal)."""
    if not 0 <= start < stop <= len(episodes.goals):
        raise ValueError(f"window {start}..{stop - 1} outside the file")
    idx = range(start, stop)
    can_fail = [i for i in idx if episodes.scenario_ids[i] not in terminal]
    failed = [i for i in can_fail if episodes.goals[i] == 0]
    success = [i for i in can_fail if episodes.goals[i] == 1]
    n_cf = len(can_fail)
    return {
        "n_episodes": stop - start,
        "n_terminal_at_start": (stop - start) - n_cf,
        "n_can_fail": n_cf,
        "n_failed_can_fail": len(failed),
        "success_share_can_fail": len(success) / n_cf if n_cf else float("nan"),
        "mean_steps_can_fail": cm.mean(episodes.steps[i] for i in can_fail),
        "n_successful_can_fail": len(success),
        "mean_steps_successful_can_fail": cm.mean(episodes.steps[i] for i in success),
        "mean_wasted_can_fail": cm.mean(episodes.wasted[i] for i in can_fail),
        "overall_success_share": sum(episodes.goals[i] for i in idx) / (stop - start),
    }


# ── driver ─────────────────────────────────────────────────────────────────

def run(roots: list[Path], seeds: tuple[int, ...], labs: tuple[str, ...],
        scenario_dir: Path, out: Path, iters: int = cm.BOOTSTRAP_ITERS) -> dict:
    by_mode = cm.group_roots_by_mode(roots)
    modes = sorted(by_mode)

    terminal_rows, verification_rows, schedule_rows, identity_rows = [], [], [], []
    cell_rows: list[dict] = []
    values: dict[tuple, dict[int, float]] = {}   # (mode, arm, lab, window, measure) -> seed -> v
    problems: list[str] = []
    terminal_sets: dict[str, set[str]] = {}

    for lab in labs:
        train_rows = cm.load_scenarios(scenario_dir / f"train_scenarios_{lab}.json")
        terminal = terminal_ids_from_state(lab, train_rows)
        terminal_sets[lab] = terminal
        known = {str(r["id"]) for r in train_rows}
        episode_counts: dict[str, int] = {}
        files_seen = 0
        for seed in seeds:
            sequences: dict[str, tuple[str, ...]] = {}
            plain_hashes: dict[str, str] = {}
            for mode in modes:
                for arm in cm.ARMS_QL:
                    cell = cm.cell_dir(by_mode[mode], seed, lab, cm.TRAINING_SUBDIR[arm])
                    path = metrics_path(cell, arm, lab)
                    marker = cm.read_json(cell / "TRAINING_OK.json")
                    if (int(marker.get("fixed_horizon_episodes", HORIZON)) != HORIZON
                            or int(marker.get("run_seed", seed)) != seed
                            or str(marker.get("profile", lab)) != lab
                            or str(marker.get("run_mode", mode)) != mode):
                        raise ValueError(f"{cell}: TRAINING_OK.json does not match "
                                         f"{mode}/{lab}/seed{seed}/horizon {HORIZON}")
                    episodes = read_training_episodes(path)
                    files_seen += 1
                    sequences[f"{mode}/{arm}"] = episodes.scenario_ids
                    if arm == "ql_false":
                        plain_hashes[mode] = ptl._sha256(path)
                    found = verify_terminal_in_data(episodes, terminal, known)
                    problems.extend(f"{path}: {p}" for p in found)
                    verification_rows.append({
                        "lab": lab, "seed": seed, "run_mode": mode, "arm": arm,
                        "n_terminal_at_start_episodes": sum(
                            1 for s in episodes.scenario_ids if s in terminal),
                        "problems": " | ".join(found), "ok": not found,
                    })
                    if not episode_counts:
                        for s in episodes.scenario_ids:
                            episode_counts[s] = episode_counts.get(s, 0) + 1
                    auc = registered_auc_goal(path)
                    for window, start, stop in WINDOWS:
                        measures = window_measures(episodes, terminal, start, stop)
                        if window == "all" and abs(measures["overall_success_share"] - auc) > 1e-12:
                            raise ValueError(f"{path}: overall share differs from registered auc_goal")
                        row = {"run_mode": mode, "lab": lab, "arm": arm, "seed": seed,
                               "window": window, **measures,
                               "registered_auc_goal_all_episodes": auc}
                        cell_rows.append(row)
                        for measure in MEASURES:
                            values.setdefault((mode, arm, lab, window, measure), {})[seed] = \
                                float(measures[measure])
            for mode in modes:
                if sequences[f"{mode}/ql_true"] != sequences[f"{mode}/ql_false"]:
                    raise ValueError(f"{lab}/seed{seed}/{mode}: the two arms do not share "
                                     "the scenario schedule")
            distinct = len(set(sequences.values()))
            schedule_rows.append({"lab": lab, "seed": seed, "n_files": len(sequences),
                                  "n_distinct_scenario_sequences": distinct,
                                  "identical_across_arms_and_modes": distinct == 1})
            hashes = sorted(set(plain_hashes.values()))
            reference = plain_hashes.get(MODE_KG, next(iter(plain_hashes.values())))
            identity_rows.append({
                "lab": lab, "seed": seed, "n_modes": len(plain_hashes),
                "n_distinct_sha256": len(hashes),
                "byte_identical_across_modes": len(hashes) == 1,
                "modes_differing_from_reference": ";".join(
                    m for m in modes if plain_hashes[m] != reference),
                "reference_mode": MODE_KG if MODE_KG in plain_hashes else modes[0],
            })
        for row in train_rows:
            sid = str(row["id"])
            terminal_rows.append({
                "lab": lab, "scenario_id": row["id"], "Sunshine": row.get("Sunshine"),
                "devices_on": cm.devices_on(row),
                "Z1Level": row.get("Z1Level"), "Z2Level": row.get("Z2Level", ""),
                "terminal_at_start_from_state": sid in terminal,
                "episodes_per_file": episode_counts.get(sid, 0),
                "files_verified": files_seen,
            })

    if problems:
        raise ValueError("terminal-at-start verification failed:\n" + "\n".join(problems))

    # ── contrasts ───────────────────────────────────────────────────────
    contrast_rows, contrast_seed_rows, skipped = [], [], []
    for name, left, right in CONTRASTS:
        if left[0] not in by_mode or right[0] not in by_mode:
            skipped.append(name)
            continue
        for lab in labs:
            for window, _s, _e in WINDOWS:
                for measure in CONTRAST_MEASURES:
                    lv = values[(left[0], left[1], lab, window, measure)]
                    rv = values[(right[0], right[1], lab, window, measure)]
                    # One resampling stream per (contrast, lab, window), shared by
                    # all measures.
                    summary = cm.paired_summary(lv, rv, seeds, ANALYSIS, name, lab, window,
                                                iters=iters)
                    contrast_rows.append({
                        "contrast": name, "left": "/".join(left), "right": "/".join(right),
                        "lab": lab, "window": window, "measure": measure, **summary})
                    for seed in seeds:
                        contrast_seed_rows.append({
                            "contrast": name, "lab": lab, "window": window,
                            "measure": measure, "seed": seed, "left_value": lv[seed],
                            "right_value": rv[seed], "paired_difference": lv[seed] - rv[seed]})

    # ── cell means ──────────────────────────────────────────────────────
    mean_rows = []
    for (mode, arm, lab, window, measure), per_seed in sorted(values.items()):
        defined = [v for v in per_seed.values() if not cm.is_nan(v)]
        mean_rows.append({
            "run_mode": mode, "lab": lab, "arm": arm, "window": window, "measure": measure,
            "n_seeds": len(per_seed), "n_seeds_defined": len(defined),
            "mean_over_seeds": cm.mean(defined),
            "min_over_seeds": min(defined) if defined else float("nan"),
            "max_over_seeds": max(defined) if defined else float("nan"),
        })

    out.mkdir(parents=True, exist_ok=True)
    cm.write_csv(out / "terminal_at_start.csv", terminal_rows, [
        "lab", "scenario_id", "Sunshine", "devices_on", "Z1Level", "Z2Level",
        "terminal_at_start_from_state", "episodes_per_file", "files_verified"])
    cm.write_csv(out / "terminal_verification_per_file.csv", verification_rows, [
        "lab", "seed", "run_mode", "arm", "n_terminal_at_start_episodes", "ok", "problems"])
    cm.write_csv(out / "schedule_check.csv", schedule_rows, [
        "lab", "seed", "n_files", "n_distinct_scenario_sequences",
        "identical_across_arms_and_modes"])
    cm.write_csv(out / "plain_arm_identity.csv", identity_rows, [
        "lab", "seed", "n_modes", "n_distinct_sha256", "byte_identical_across_modes",
        "reference_mode", "modes_differing_from_reference"])
    cm.write_csv(out / "cells_per_seed.csv", cell_rows, [
        "run_mode", "lab", "arm", "seed", "window", *MEASURES,
        "registered_auc_goal_all_episodes"])
    cm.write_csv(out / "cell_means.csv", mean_rows, [
        "run_mode", "lab", "arm", "window", "measure", "n_seeds", "n_seeds_defined",
        "mean_over_seeds", "min_over_seeds", "max_over_seeds"])
    cm.write_csv(out / "contrasts_per_seed.csv", contrast_seed_rows, [
        "contrast", "lab", "window", "measure", "seed", "left_value", "right_value",
        "paired_difference"])
    cm.write_csv(out / "contrasts.csv", contrast_rows, [
        "contrast", "left", "right", "lab", "window", "measure", *cm.PAIRED_FIELDS])

    result = {
        "modes": modes, "terminal_sets": {k: sorted(v, key=int) for k, v in terminal_sets.items()},
        "skipped_contrasts": skipped, "schedule_rows": schedule_rows,
        "identity_rows": identity_rows, "contrast_rows": contrast_rows,
        "mean_rows": mean_rows, "terminal_rows": terminal_rows,
    }
    _write_summary(out / "summary.md", result, seeds, labs, iters)
    return result


def _write_summary(path: Path, result: dict, seeds, labs, iters: int) -> None:
    lines = [
        "# Training success on episodes that can fail",
        "",
        f"Status: {cm.POSTHOC_LABEL}. No p-values or q-values are computed.",
        f"Seeds: {seeds[0]}..{seeds[-1]} ({len(seeds)}). Labs: {', '.join(labs)}. "
        f"Run modes read: {', '.join(result['modes'])}.",
        f"Bootstrap: {iters} resamples of the seed differences, percentile 95% interval, "
        f"random.Random seeded per (contrast, lab, window) from base seed "
        f"{cm.BOOTSTRAP_BASE_SEED}.",
        "",
        "## Terminal-at-start scenarios (from the start state, verified in every file)",
        "",
    ]
    for lab in labs:
        ids = result["terminal_sets"][lab]
        per_file = sum(r["episodes_per_file"] for r in result["terminal_rows"]
                       if r["lab"] == lab and r["terminal_at_start_from_state"])
        lines.append(f"- {lab}: ScenarioIds {', '.join(ids) or 'none'}; "
                     f"{per_file} of {HORIZON} episodes per file are terminal-at-start.")
    sched_ok = all(r["identical_across_arms_and_modes"] for r in result["schedule_rows"])
    ident = result["identity_rows"]
    lines += [
        "",
        f"Scenario schedule identical across all arms and run modes for every (lab, seed): "
        f"{'yes' if sched_ok else 'no (see schedule_check.csv)'}.",
        f"Plain-arm training files byte-identical across run modes: "
        f"{sum(r['byte_identical_across_modes'] for r in ident)} of {len(ident)} (lab, seed) "
        "pairs; differing pairs: "
        + (", ".join(f"{r['lab']}/seed{r['seed']} ({r['modes_differing_from_reference']})"
                     for r in ident if not r["byte_identical_across_modes"]) or "none") + ".",
        "",
        "## Paired contrasts (mean paired difference [95% bootstrap interval]; seeds +/0/-)",
        "",
    ]
    if result["skipped_contrasts"]:
        lines.append("Skipped (run mode not among the inputs): "
                     + ", ".join(result["skipped_contrasts"]) + ".")
        lines.append("")
    for measure in ("success_share_can_fail", "mean_steps_can_fail", "overall_success_share"):
        lines.append(f"### {measure}")
        lines.append("")
        table = []
        for row in result["contrast_rows"]:
            if row["measure"] != measure:
                continue
            table.append({
                "contrast": row["contrast"], "lab": row["lab"], "window": row["window"],
                "mean": row["mean_paired_difference"],
                "ci": cm.interval(row["bootstrap_ci_lo_95"], row["bootstrap_ci_hi_95"]),
                "signs": f"{row['n_seeds_left_higher']}/{row['n_seeds_tied']}/"
                         f"{row['n_seeds_left_lower']}",
            })
        lines += cm.md_table(table, ["contrast", "lab", "window", "mean", "ci", "signs"])
        lines.append("")
    lines.append("Full tables: contrasts.csv, cells_per_seed.csv, cell_means.csv.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--roots", nargs="+", type=Path, required=True,
                        help="Phase-1 run archive roots (each holding benchmark/results_seed<N>/)")
    parser.add_argument("--seeds", default="1-20", help="seed list, e.g. 1-20 or 21-40")
    parser.add_argument("--labs", nargs="+", default=list(DEFAULT_LABS))
    parser.add_argument("--scenario-dir", type=Path, default=Path("benchmark"),
                        help="folder holding train_scenarios_<lab>.json")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    result = run(args.roots, cm.parse_seeds(args.seeds), tuple(args.labs),
                 args.scenario_dir, args.out)
    print(f"{ANALYSIS}: {len(result['contrast_rows'])} contrast rows, modes "
          f"{', '.join(result['modes'])} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
