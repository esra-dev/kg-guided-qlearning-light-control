#!/usr/bin/env python3
"""Write the Phase-4 tables with the corrected lab5 zero-step scoring.

Background: in lab5 a test run whose goal is already met at the start needs no
step, so the step log has no row for it. The archived Phase-4 tables scored
such a run as if every device were off (power 0, always within budget). The
correct reading is the start state the scenario file declares, which is what
``phase4_energy`` now does by default. The archived tables under
``results/phase4_v2_corrected/`` and ``results/phase4_v2_ladder/`` stay exactly
as they are (their reproducers use the legacy scoring); this script writes
the corrected versions next to them, under ``results/_corrected_tables/``:

  phase4_v2_corrected/
      phase4_v2_registered_family.csv, phase4_v2_ladder_trend.csv
      lab5_compliance_levels.csv
  phase4_v2_ladder/
      phase4_v2_knowledge_ladder_family.csv, ..._descriptives.csv,
      ..._comparability.csv, phase4_v2_knowledge_ladder.json
      lab5_compliance_levels.csv

``lab5_compliance_levels.csv`` lists, per arm and planner mode, the mean over
the 20 seeds of the per-seed lab5 rates under BOTH scorings, so the size of
the correction is visible in one place. Only the levels change; every paired
difference and rung increment is the same under both scorings.

The README.md files in those folders are written by hand and are not touched.

Usage (from the repository root; needs the ignored raw benchmark CSVs):
  python analysis/phase4_v2_corrected_tables.py            # write the tables
  python analysis/phase4_v2_corrected_tables.py --check    # rebuild in a temp
                                                           # folder and compare
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import tempfile
from pathlib import Path

try:
    from analysis import phase4_energy
    from analysis import phase4_v2_knowledge_ladder as ladder
    from analysis import phase4_v2_registered_family as family
    from analysis import reproduce_phase4_v2 as base
    from analysis import reproduce_phase4_v2_ladder as ladder_repro
except ImportError:  # executed as a script from the repo root
    import phase4_energy  # type: ignore
    import phase4_v2_knowledge_ladder as ladder  # type: ignore
    import phase4_v2_registered_family as family  # type: ignore
    import reproduce_phase4_v2 as base  # type: ignore
    import reproduce_phase4_v2_ladder as ladder_repro  # type: ignore


FULL_CAMPAIGN = "phase4_v2_corrected"
LADDER_CAMPAIGN = "phase4_v2_ladder"
MODES = ("ql_true", "ql_false", "rule_based")
LEVEL_METRICS = ("goal_rate", "energy_compliance", "mean_steady_power", "over_budget_rate")
LEVEL_FIELDS = ["arm", "mode", "zero_step_scoring", "n_seeds", "n_test_runs",
                "n_zero_step_runs"] + [f"{m}_mean" for m in LEVEL_METRICS]
FAMILY_TABLES = ("phase4_v2_registered_family.csv", "phase4_v2_ladder_trend.csv")
LEVELS_TABLE = "lab5_compliance_levels.csv"


def _run_dirs(campaign_root: Path) -> list[Path]:
    return sorted(p for p in campaign_root.glob("run_*") if p.is_dir())


def _ladder_arms(campaign_root: Path) -> dict[str, list[Path]]:
    arms: dict[str, list[Path]] = {"brick": [], "redundancy": []}
    for run_dir in _run_dirs(campaign_root):
        arm = ladder_repro.ARM_OF_MODE.get(ladder_repro._inputs(run_dir)["run_mode"])
        if arm is None:
            raise SystemExit(f"{run_dir}: unexpected run_mode")
        arms[arm].append(run_dir)
    return arms


def _zero_step_runs(cell: Path, mode: str) -> tuple[int, int]:
    """(test runs, test runs without any step row) of one lab5 cell."""
    states = phase4_energy._final_states(cell / f"bench_step_log_{mode}.csv")
    goals = phase4_energy._goal_flags(cell / f"benchmark_results_{mode}.csv")
    keys = set(states) | set(goals)
    return len(keys), len(keys - set(states))


def _level_rows(arm: str, roots: list[Path]) -> list[dict]:
    weights, budgets, default_budget = family._lab5_tools()
    start_states = family._lab5_start_states(weights)
    rows = []
    for mode in MODES:
        cells = [family._cell_dir(roots, seed, "lab5", mode) for seed in family.SEEDS]
        n_runs = n_zero = 0
        for cell in cells:
            runs, zero = _zero_step_runs(cell, mode)
            n_runs += runs
            n_zero += zero
        for scoring in phase4_energy.ZERO_STEP_SCORINGS:
            per_seed = []
            for cell in cells:
                scalars = phase4_energy.replica_scalars(
                    cell / f"bench_step_log_{mode}.csv",
                    cell / f"benchmark_results_{mode}.csv",
                    weights, budgets, default_budget, has_budget=True,
                    start_states=start_states, zero_step_scoring=scoring)
                if scalars is None:
                    raise SystemExit(f"{cell}: no lab5 rows")
                per_seed.append(scalars)
            row = {"arm": arm, "mode": mode, "zero_step_scoring": scoring,
                   "n_seeds": len(per_seed), "n_test_runs": n_runs,
                   "n_zero_step_runs": n_zero}
            for metric in LEVEL_METRICS:
                row[f"{metric}_mean"] = sum(s[metric] for s in per_seed) / len(per_seed)
            rows.append(row)
    return rows


def _write_levels(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=LEVEL_FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def build(results: Path, out: Path) -> None:
    """Write both corrected table sets under ``out``."""
    full_roots = _run_dirs(results / FULL_CAMPAIGN)
    arms = _ladder_arms(results / LADDER_CAMPAIGN)
    if not full_roots or not arms["brick"] or not arms["redundancy"]:
        raise SystemExit("missing archives: need the full-KG campaign and both ladder arms")

    full_out = out / FULL_CAMPAIGN
    family.run(full_roots, full_out)  # default scoring: declared start state
    _write_levels(full_out / LEVELS_TABLE, _level_rows("full_kg", full_roots))

    ladder_out = out / LADDER_CAMPAIGN
    ladder.run(full_roots, arms["brick"], arms["redundancy"], ladder_out)
    _write_levels(ladder_out / LEVELS_TABLE,
                  _level_rows("A3_full_kg", full_roots) + _level_rows("A2_brick", arms["brick"]))


def check(results: Path, committed: Path) -> int:
    """Rebuild into a temporary folder and compare with the committed tables."""
    with tempfile.TemporaryDirectory(prefix="phase4-corrected-tables-") as tmp:
        rebuilt = Path(tmp)
        build(results, rebuilt)
        for campaign, names in (
                (FULL_CAMPAIGN, FAMILY_TABLES + (LEVELS_TABLE,)),
                (LADDER_CAMPAIGN, ladder_repro.TABLES + (LEVELS_TABLE,))):
            for name in names:
                base._compare(committed / campaign / name, rebuilt / campaign / name)
        summary = ladder_repro.SUMMARY
        if json.loads((committed / LADDER_CAMPAIGN / summary).read_text(encoding="utf-8")) != \
                json.loads((rebuilt / LADDER_CAMPAIGN / summary).read_text(encoding="utf-8")):
            raise ValueError(f"summary differs: {committed / LADDER_CAMPAIGN / summary}")
    print("Corrected Phase-4 tables are reproduced.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", type=Path, default=Path("results"),
                        help="folder holding the Phase-4 archives (default: results)")
    parser.add_argument("--out", type=Path, default=Path("results/_corrected_tables"),
                        help="where the corrected tables go (default: results/_corrected_tables)")
    parser.add_argument("--check", action="store_true",
                        help="rebuild into a temporary folder and compare with --out")
    args = parser.parse_args()
    if args.check:
        return check(args.results, args.out)
    build(args.results, args.out)
    print(f"Wrote corrected Phase-4 tables under {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
