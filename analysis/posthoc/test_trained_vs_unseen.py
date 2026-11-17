#!/usr/bin/env python3
"""Test results on trained versus unseen starting situations (post-hoc, descriptive).

POST-HOC, DESCRIPTIVE. Specified after the results were known. Computes
shares, means, paired per-seed differences, 95% bootstrap intervals of the
mean paired difference and sign counts; no p-values and no q-values.

Classification
--------------
A test starting situation (``benchmark/scenarios_<lab>.json``) is TRAINED
when its start state equals the start state of some training situation
(``benchmark/train_scenarios_<lab>.json``), otherwise UNSEEN. The start state
is every field of the scenario entry except ``id``, ``comment``,
``description`` and any other text-valued field (levels, device switches,
sunshine and, for lab5, ``energyBudget``). The classification is also
repeated with ``energyBudget`` ignored and the two are reported side by side.

Measures
--------
Per (phase, run mode, lab, arm, seed) and subset (TRAINED, UNSEEN, ALL): the
number of test runs (benchmark rows), the share of runs with
``GoalReached == 1`` and the mean ``Steps`` per run. The ALL subset must
reproduce the registered benchmark reader (``sweep_report.summarise_benchmark``
``goal_rate`` and ``avg_steps``) exactly; the run stops otherwise. Paired
per-seed differences ``ql_true - ql_false`` are summarised per (phase, run
mode, lab, subset, measure). ``rule_based`` cells are described but enter no
contrast.

Run (from the repository root; needs the Phase-4 raw files that git ignores, present locally):
  py -3 analysis/posthoc/test_trained_vs_unseen.py \
      --phase1-roots results/phase1_v2_corrected/run_29848584965 ... \
      --phase4-roots results/phase4_v2_corrected/run_29926341581 ... \
      --seeds 1-20 --out <output folder>
The run mode of each root is read from its TRAINING_OK.json files.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from analysis import sweep_report as sr  # noqa: E402
from analysis.posthoc import _common as cm  # noqa: E402

ANALYSIS = "test_trained_vs_unseen"
PHASE_LABS = {
    "phase1": ("lab1", "lab2", "lab3"),
    "phase4": ("lab4", "lab4dual", "lab4chain", "lab5"),
}
ARMS = ("ql_true", "ql_false", "rule_based")
SUBSETS = ("trained", "unseen", "all")
MEASURES = ("goal_share", "mean_steps")


def classification_table(lab: str, scenario_dir: Path) -> list[dict]:
    test_rows = cm.load_scenarios(scenario_dir / f"scenarios_{lab}.json")
    train_rows = cm.load_scenarios(scenario_dir / f"train_scenarios_{lab}.json")
    primary = cm.classify_test_starts(test_rows, train_rows)
    alt = cm.classify_test_starts(test_rows, train_rows, cm.TEXT_FIELDS | {"energyBudget"})
    out = []
    for row, cls, cls_alt in zip(test_rows, primary, alt):
        out.append({
            "lab": lab, "test_scenario_id": cls.test_id,
            "class": "trained" if cls.trained else "unseen",
            "matching_training_id": cls.matching_training_id,
            "same_id_as_match": cls.matching_training_id == cls.test_id if cls.trained else "",
            "class_ignoring_energyBudget": "trained" if cls_alt.trained else "unseen",
            "Sunshine": row.get("Sunshine"), "devices_on": cm.devices_on(row),
            "Z1Level": row.get("Z1Level"), "Z2Level": row.get("Z2Level", ""),
        })
    return out


def subset_measures(rows: list[dict], classes: dict[int, str]) -> dict[str, dict]:
    """goal share and mean steps over benchmark rows, per subset."""
    buckets: dict[str, list[dict]] = {s: [] for s in SUBSETS}
    for row in rows:
        sid = int(row["ScenarioId"])
        if sid not in classes:
            raise ValueError(f"benchmark ScenarioId {sid} is not in the test scenario file")
        buckets[classes[sid]].append(row)
        buckets["all"].append(row)
    out = {}
    for subset, items in buckets.items():
        n = len(items)
        out[subset] = {
            "n_test_runs": n,
            "n_goal": sum(1 for r in items if r["GoalReached"] == "1"),
            "goal_share": (sum(1 for r in items if r["GoalReached"] == "1") / n) if n else float("nan"),
            "mean_steps": cm.mean(float(r["Steps"]) for r in items),
        }
    return out


def run(phase_roots: dict[str, list[Path]], seeds: tuple[int, ...], scenario_dir: Path,
        out: Path, iters: int = cm.BOOTSTRAP_ITERS) -> dict:
    class_rows: list[dict] = []
    classes: dict[str, dict[int, str]] = {}
    cell_rows, contrast_rows, contrast_seed_rows, count_rows = [], [], [], []
    values: dict[tuple, dict[int, float]] = {}

    for phase, roots in phase_roots.items():
        if not roots:
            continue
        by_mode = cm.group_roots_by_mode(roots)
        for mode in sorted(by_mode):
            labs = [lab for lab in PHASE_LABS[phase] if lab in cm.labs_in(by_mode[mode])]
            for lab in labs:
                if lab not in classes:
                    table = classification_table(lab, scenario_dir)
                    class_rows.extend(table)
                    classes[lab] = {r["test_scenario_id"]: r["class"] for r in table}
                    count_rows.append({
                        "lab": lab, "n_test_starts": len(table),
                        "n_trained": sum(r["class"] == "trained" for r in table),
                        "n_unseen": sum(r["class"] == "unseen" for r in table),
                        "trained_ids": ";".join(str(r["test_scenario_id"]) for r in table
                                                if r["class"] == "trained"),
                        "unseen_ids": ";".join(str(r["test_scenario_id"]) for r in table
                                               if r["class"] == "unseen"),
                        "classification_unchanged_ignoring_energyBudget": all(
                            r["class"] == r["class_ignoring_energyBudget"] for r in table),
                    })
                for arm in ARMS:
                    for seed in seeds:
                        cell = cm.cell_dir(by_mode[mode], seed, lab, arm)
                        path = cell / f"benchmark_results_{arm}.csv"
                        rows = cm.csv_rows(path)
                        measures = subset_measures(rows, classes[lab])
                        reference = sr.summarise_benchmark(path)
                        if (abs(measures["all"]["goal_share"] - reference["goal_rate"]) > 1e-12
                                or abs(measures["all"]["mean_steps"] - reference["avg_steps"]) > 1e-12):
                            raise ValueError(f"{path}: ALL subset differs from summarise_benchmark")
                        for subset in SUBSETS:
                            m = measures[subset]
                            cell_rows.append({"phase": phase, "run_mode": mode, "lab": lab,
                                              "arm": arm, "seed": seed, "subset": subset, **m})
                            for measure in MEASURES:
                                values.setdefault((phase, mode, lab, arm, subset, measure),
                                                  {})[seed] = float(m[measure])
                for subset in SUBSETS:
                    for measure in MEASURES:
                        lv = values[(phase, mode, lab, "ql_true", subset, measure)]
                        rv = values[(phase, mode, lab, "ql_false", subset, measure)]
                        # One resampling stream per (phase, mode, lab, subset).
                        summary = cm.paired_summary(lv, rv, seeds, ANALYSIS, phase, mode, lab,
                                                    subset, iters=iters)
                        contrast_rows.append({"phase": phase, "run_mode": mode, "lab": lab,
                                              "subset": subset, "measure": measure,
                                              "contrast": "ql_true_minus_ql_false", **summary})
                        for seed in seeds:
                            contrast_seed_rows.append({
                                "phase": phase, "run_mode": mode, "lab": lab, "subset": subset,
                                "measure": measure, "seed": seed, "ql_true": lv[seed],
                                "ql_false": rv[seed], "paired_difference": lv[seed] - rv[seed]})

    mean_rows = []
    for (phase, mode, lab, arm, subset, measure), per_seed in sorted(values.items()):
        mean_rows.append({"phase": phase, "run_mode": mode, "lab": lab, "arm": arm,
                          "subset": subset, "measure": measure, "n_seeds": len(per_seed),
                          "mean_over_seeds": cm.mean(per_seed.values())})

    out.mkdir(parents=True, exist_ok=True)
    cm.write_csv(out / "start_classification.csv", class_rows, [
        "lab", "test_scenario_id", "class", "matching_training_id", "same_id_as_match",
        "class_ignoring_energyBudget", "Sunshine", "devices_on", "Z1Level", "Z2Level"])
    cm.write_csv(out / "start_counts.csv", count_rows, [
        "lab", "n_test_starts", "n_trained", "n_unseen", "trained_ids", "unseen_ids",
        "classification_unchanged_ignoring_energyBudget"])
    cm.write_csv(out / "cells_per_seed.csv", cell_rows, [
        "phase", "run_mode", "lab", "arm", "seed", "subset", "n_test_runs", "n_goal",
        "goal_share", "mean_steps"])
    cm.write_csv(out / "cell_means.csv", mean_rows, [
        "phase", "run_mode", "lab", "arm", "subset", "measure", "n_seeds", "mean_over_seeds"])
    cm.write_csv(out / "contrasts.csv", contrast_rows, [
        "phase", "run_mode", "lab", "subset", "measure", "contrast", *cm.PAIRED_FIELDS])
    cm.write_csv(out / "contrasts_per_seed.csv", contrast_seed_rows, [
        "phase", "run_mode", "lab", "subset", "measure", "seed", "ql_true", "ql_false",
        "paired_difference"])
    result = {"count_rows": count_rows, "contrast_rows": contrast_rows, "mean_rows": mean_rows}
    _write_summary(out / "summary.md", result, seeds, iters)
    return result


def _write_summary(path: Path, result: dict, seeds, iters: int) -> None:
    lines = [
        "# Test results on trained versus unseen starting situations",
        "",
        f"Status: {cm.POSTHOC_LABEL}. No p-values or q-values are computed.",
        f"Seeds {seeds[0]}..{seeds[-1]} ({len(seeds)}); bootstrap {iters} resamples, "
        f"base seed {cm.BOOTSTRAP_BASE_SEED}.",
        "",
        "## Starting situations",
        "",
    ]
    lines += cm.md_table(result["count_rows"], ["lab", "n_test_starts", "n_trained", "n_unseen",
                                                "unseen_ids"])
    means = {(r["phase"], r["run_mode"], r["lab"], r["arm"], r["subset"], r["measure"]):
             r["mean_over_seeds"] for r in result["mean_rows"]}
    for measure in MEASURES:
        lines += ["", f"## {measure}: ql_true - ql_false (mean [95% bootstrap]; seeds +/0/-)", ""]
        table = []
        for row in result["contrast_rows"]:
            if row["measure"] != measure or row["subset"] == "all":
                continue
            key = (row["phase"], row["run_mode"], row["lab"])
            table.append({
                "run_mode": row["run_mode"], "lab": row["lab"], "subset": row["subset"],
                "ql_true": means[key + ("ql_true", row["subset"], measure)],
                "ql_false": means[key + ("ql_false", row["subset"], measure)],
                "difference": row["mean_paired_difference"],
                "ci": cm.interval(row["bootstrap_ci_lo_95"], row["bootstrap_ci_hi_95"]),
                "signs": f"{row['n_seeds_left_higher']}/{row['n_seeds_tied']}/"
                         f"{row['n_seeds_left_lower']}",
            })
        lines += cm.md_table(table, ["run_mode", "lab", "subset", "ql_true", "ql_false",
                                     "difference", "ci", "signs"])
    lines += ["", "Full tables: contrasts.csv, cells_per_seed.csv, cell_means.csv."]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--phase1-roots", nargs="*", type=Path, default=[])
    parser.add_argument("--phase4-roots", nargs="*", type=Path, default=[])
    parser.add_argument("--seeds", default="1-20")
    parser.add_argument("--scenario-dir", type=Path, default=Path("benchmark"))
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    result = run({"phase1": args.phase1_roots, "phase4": args.phase4_roots},
                 cm.parse_seeds(args.seeds), args.scenario_dir, args.out)
    print(f"{ANALYSIS}: {len(result['contrast_rows'])} contrast rows -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
