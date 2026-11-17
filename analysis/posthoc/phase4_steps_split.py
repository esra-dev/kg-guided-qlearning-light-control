#!/usr/bin/env python3
"""Phase-4 test steps split into wasted steps and actuator cycling (post-hoc, descriptive).

POST-HOC, DESCRIPTIVE. Specified after the results were known. Computes
means, shares, paired per-seed differences, 95% bootstrap intervals of the
mean paired difference and sign counts; no p-values and no q-values.

What it does
------------
1. Checks on EVERY benchmark row (test run) of every (lab, run mode, arm,
   seed) cell read that ``Steps == WastedSteps + ActuatorCyclingCount`` and
   reports the number of rows checked and every violation.
2. Per (lab, run mode, arm, seed): the mean per test run of ``Steps``,
   ``WastedSteps``, ``ActuatorCyclingCount`` and of their sum
   ``WastedSteps + ActuatorCyclingCount`` (the registered Phase-4 estimand
   ``avg_redundant``; cross-checked against
   ``phase4_v2_registered_family._avg_redundant``), and the share of test runs
   with ``Steps == 0``.
3. Paired per-seed differences ``ql_true - ql_false`` for each of these
   measures, per (lab, run mode). ``rule_based`` cells are described but
   enter no contrast.

Run (from the repository root; needs the Phase-4 raw files that git ignores, present locally):
  py -3 analysis/posthoc/phase4_steps_split.py \
      --roots results/phase4_v2_corrected/run_29926341581 results/phase4_v2_corrected/run_29926354783 \
              results/phase4_v2_ladder/run_43494914019 ... \
      --seeds 1-20 --out <output folder>
The run mode of each root is read from its TRAINING_OK.json files.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from analysis import phase4_v2_registered_family as p4  # noqa: E402
from analysis.posthoc import _common as cm  # noqa: E402

ANALYSIS = "phase4_steps_split"
LABS = ("lab4", "lab4dual", "lab4chain", "lab5")
ARMS = ("ql_true", "ql_false", "rule_based")
MEASURES = ("mean_steps", "mean_wasted_steps", "mean_actuator_cycling",
            "mean_wasted_plus_cycling", "share_zero_steps")


def identity_violations(rows: list[dict]) -> list[dict]:
    """Rows where Steps != WastedSteps + ActuatorCyclingCount."""
    bad = []
    for row in rows:
        steps = int(row["Steps"])
        wasted = int(row["WastedSteps"])
        cycling = int(row["ActuatorCyclingCount"])
        if steps != wasted + cycling:
            bad.append({"ScenarioId": row.get("ScenarioId"), "RunId": row.get("RunId"),
                        "Steps": steps, "WastedSteps": wasted,
                        "ActuatorCyclingCount": cycling})
    return bad


def cell_measures(rows: list[dict]) -> dict:
    if not rows:
        raise ValueError("no benchmark rows")
    n = len(rows)
    steps = [int(r["Steps"]) for r in rows]
    wasted = [int(r["WastedSteps"]) for r in rows]
    cycling = [int(r["ActuatorCyclingCount"]) for r in rows]
    return {
        "n_test_runs": n,
        "mean_steps": sum(steps) / n,
        "mean_wasted_steps": sum(wasted) / n,
        "mean_actuator_cycling": sum(cycling) / n,
        "mean_wasted_plus_cycling": (sum(wasted) + sum(cycling)) / n,
        "share_zero_steps": sum(1 for s in steps if s == 0) / n,
    }


def run(roots: list[Path], seeds: tuple[int, ...], out: Path,
        iters: int = cm.BOOTSTRAP_ITERS) -> dict:
    by_mode = cm.group_roots_by_mode(roots)
    cell_rows, violation_rows, check_rows, contrast_rows, seed_rows = [], [], [], [], []
    values: dict[tuple, dict[int, float]] = {}
    rows_checked = 0
    for mode in sorted(by_mode):
        labs = [lab for lab in LABS if lab in cm.labs_in(by_mode[mode])]
        for lab in labs:
            mode_rows = 0
            mode_bad = 0
            for arm in ARMS:
                for seed in seeds:
                    cell = cm.cell_dir(by_mode[mode], seed, lab, arm)
                    rows = cm.csv_rows(cell / f"benchmark_results_{arm}.csv")
                    bad = identity_violations(rows)
                    rows_checked += len(rows)
                    mode_rows += len(rows)
                    mode_bad += len(bad)
                    for item in bad:
                        violation_rows.append({"run_mode": mode, "lab": lab, "arm": arm,
                                               "seed": seed, **item})
                    measures = cell_measures(rows)
                    if arm in cm.ARMS_QL:
                        registered = p4._avg_redundant(cell, arm)
                        if abs(registered - measures["mean_wasted_plus_cycling"]) > 1e-12:
                            raise ValueError(f"{cell}: sum differs from the registered avg_redundant")
                    cell_rows.append({"run_mode": mode, "lab": lab, "arm": arm, "seed": seed,
                                      **measures, "identity_violations": len(bad)})
                    for measure in MEASURES:
                        values.setdefault((mode, lab, arm, measure), {})[seed] = measures[measure]
            check_rows.append({"run_mode": mode, "lab": lab, "rows_checked": mode_rows,
                               "violations": mode_bad})
            for measure in MEASURES:
                lv = values[(mode, lab, "ql_true", measure)]
                rv = values[(mode, lab, "ql_false", measure)]
                # One resampling stream per (mode, lab), shared by all measures.
                summary = cm.paired_summary(lv, rv, seeds, ANALYSIS, mode, lab, iters=iters)
                contrast_rows.append({"run_mode": mode, "lab": lab, "measure": measure,
                                      "contrast": "ql_true_minus_ql_false", **summary})
                for seed in seeds:
                    seed_rows.append({"run_mode": mode, "lab": lab, "measure": measure,
                                      "seed": seed, "ql_true": lv[seed], "ql_false": rv[seed],
                                      "paired_difference": lv[seed] - rv[seed]})

    mean_rows = [{"run_mode": mode, "lab": lab, "arm": arm, "measure": measure,
                  "n_seeds": len(per_seed), "mean_over_seeds": cm.mean(per_seed.values())}
                 for (mode, lab, arm, measure), per_seed in sorted(values.items())]

    out.mkdir(parents=True, exist_ok=True)
    cm.write_csv(out / "identity_check.csv", check_rows,
                 ["run_mode", "lab", "rows_checked", "violations"])
    cm.write_csv(out / "identity_violations.csv", violation_rows,
                 ["run_mode", "lab", "arm", "seed", "ScenarioId", "RunId", "Steps",
                  "WastedSteps", "ActuatorCyclingCount"])
    cm.write_csv(out / "cells_per_seed.csv", cell_rows,
                 ["run_mode", "lab", "arm", "seed", "n_test_runs", *MEASURES,
                  "identity_violations"])
    cm.write_csv(out / "cell_means.csv", mean_rows,
                 ["run_mode", "lab", "arm", "measure", "n_seeds", "mean_over_seeds"])
    cm.write_csv(out / "contrasts.csv", contrast_rows,
                 ["run_mode", "lab", "measure", "contrast", *cm.PAIRED_FIELDS])
    cm.write_csv(out / "contrasts_per_seed.csv", seed_rows,
                 ["run_mode", "lab", "measure", "seed", "ql_true", "ql_false",
                  "paired_difference"])
    result = {"rows_checked": rows_checked, "violations": violation_rows,
              "check_rows": check_rows, "contrast_rows": contrast_rows,
              "mean_rows": mean_rows}
    _write_summary(out / "summary.md", result, seeds, iters)
    return result


def _write_summary(path: Path, result: dict, seeds, iters: int) -> None:
    means = {(r["run_mode"], r["lab"], r["arm"], r["measure"]): r["mean_over_seeds"]
             for r in result["mean_rows"]}
    lines = [
        "# Phase-4 test steps split into wasted steps and actuator cycling",
        "",
        f"Status: {cm.POSTHOC_LABEL}. No p-values or q-values are computed.",
        f"Seeds {seeds[0]}..{seeds[-1]} ({len(seeds)}); bootstrap {iters} resamples, "
        f"base seed {cm.BOOTSTRAP_BASE_SEED}.",
        "",
        f"Identity Steps == WastedSteps + ActuatorCyclingCount: {result['rows_checked']} test-run "
        f"rows checked, {len(result['violations'])} violations.",
        "",
        "## ql_true - ql_false per measure (mean [95% bootstrap]; seeds +/0/-)",
        "",
    ]
    table = []
    for row in result["contrast_rows"]:
        key = (row["run_mode"], row["lab"])
        table.append({
            "run_mode": row["run_mode"], "lab": row["lab"], "measure": row["measure"],
            "ql_true": means[key + ("ql_true", row["measure"])],
            "ql_false": means[key + ("ql_false", row["measure"])],
            "difference": row["mean_paired_difference"],
            "ci": cm.interval(row["bootstrap_ci_lo_95"], row["bootstrap_ci_hi_95"]),
            "signs": f"{row['n_seeds_left_higher']}/{row['n_seeds_tied']}/"
                     f"{row['n_seeds_left_lower']}",
        })
    lines += cm.md_table(table, ["run_mode", "lab", "measure", "ql_true", "ql_false",
                                 "difference", "ci", "signs"])
    lines += ["", "Full tables: contrasts.csv, cells_per_seed.csv, cell_means.csv, "
                  "identity_check.csv."]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--roots", nargs="+", type=Path, required=True)
    parser.add_argument("--seeds", default="1-20")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    result = run(args.roots, cm.parse_seeds(args.seeds), args.out)
    print(f"{ANALYSIS}: {result['rows_checked']} rows checked, "
          f"{len(result['violations'])} violations -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
