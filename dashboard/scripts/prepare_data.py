#!/usr/bin/env python3
"""Prepare the result data files for the demo dashboard.

Reads the stored result tables under results/ and writes five JSON files into
dashboard/public/data/: phase1.json .. phase4.json and overview.json (the
numbers on the overview page, derived from the four phase files). Stdlib only.
Run from anywhere:

    py -3 dashboard/scripts/prepare_data.py              # all five files
    py -3 dashboard/scripts/prepare_data.py --only phase4

The replay_lab*.json and training.json files in the same folder are NOT built
here. They are frozen illustration data (single runs of an early prototype)
whose inputs are no longer in the repository; this script never writes them.
"""
import csv
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "dashboard" / "public" / "data"

# Phase-1 confirmatory source: the protocol-v2 corrected campaign. Every value
# in dashboard/public/data/phase1.json is derived from a committed
# results/phase1_v2_corrected/ file; CI regenerates the JSON and fails on any byte
# difference, and analysis/tests/test_dashboard_contracts.py checks its schema
# independently. Protocol-v1 sources (run 29639767776 and the pooled-20
# reanalysis) are withdrawn and must never feed this builder again.
PHASE1_V2 = ROOT / "results" / "phase1_v2_corrected"
PHASE1_FAMILY_CSV = PHASE1_V2 / "analysis" / "registered" / "phase1_v2_registered_family.csv"
PHASE1_DECOMP_JSON = PHASE1_V2 / "analysis" / "registered" / "phase1_v2_decomposition.json"
PHASE1_GATES_JSON = PHASE1_V2 / "analysis" / "phase1_v2_protocol_gate_summary.json"
PHASE1_CONTROLS_CSV = PHASE1_V2 / "analysis" / "phase1_v2_controls_and_descriptives.csv"
PHASE1_ARM_C_OUT = PHASE1_V2 / "run_29848584965" / "analysis" / "out"
PHASE1_RUN_KEYS = {
    "phase1_v2_kg_only": "arm_c",
    "phase1_v2_redundancy_only": "redundancy_only",
    "phase1_v2_baseline": "baseline",
    "phase1_v2_pbrs_only": "pbrs_only",
}
PHASE1_FAMILY_NAMES = {
    "1_arm_c_lab2_auc_goal": "Arm-C lab2 auc_goal",
    "2_redundancy_lab2_auc_goal": "Redundancy lab2 auc_goal",
    "3_arm_c_minus_redundancy_lab2": "Arm C minus redundancy, lab2",
    "4_arm_c_lab3_mean_first_goal_presentations": "Arm-C lab3 first-goal presentations",
    "5_arm_c_lab3_avg_cycling": "Arm-C lab3 cycling",
}
PHASE1_DECOMP_CATEGORIES = {
    "more_than_two_thirds": "more than two thirds: redundancy reproduces most of arm C",
    "between_one_third_and_two_thirds":
        "between one third and two thirds: redundancy reproduces part of arm C",
    "less_than_one_third": "less than one third: redundancy does not reproduce arm C",
}
PHASE1_LAB_HEADLINES = {
    "lab1": "Saturated null control",
    "lab2": "Small learning benefit, mostly redundancy-derived",
    "lab3": "First-goal and cycling null; adverse training/energy descriptives",
}
PHASE1_BENCH_KEYS = {
    "lab2": (
        ("benchmark_goal_rate", "goal_rate"),
        ("benchmark_steps", "avg_steps"),
        ("benchmark_deviation", "avg_dev"),
        ("benchmark_policy_energy", "avg_policy_energy"),
        ("benchmark_cycling", "avg_cycling"),
    ),
    "lab3": (
        ("benchmark_goal_rate", "goal_rate"),
        ("benchmark_policy_energy", "avg_policy_energy"),
        ("benchmark_cycling", "avg_cycling"),
        ("benchmark_redundant", "avg_redundant"),
    ),
}
LEGACY_ENERGY_METRIC = "avg_legacy_wallclock_energy"


def read_csv_rows(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def build_phase1(out_dir=None):
    """Derive the protocol-v2 Phase-1 dashboard summary from the corrected
    archives. Fails loudly on any inconsistency instead of guessing."""
    out_dir = OUT if out_dir is None else out_dir
    gates = json.load(open(PHASE1_GATES_JSON, encoding="utf-8"))
    # The dashboard shows the four-arm campaign. The gate summary also lists
    # the knowledge-ladder control arm (Addendum 2026-11-10), which ran from a
    # later commit and is not displayed here, so it is left out of the
    # campaign-consistency checks below.
    run_rows = [row for row in gates["runs"] if row["run_mode"] in PHASE1_RUN_KEYS]
    head_shas = {row["head_sha"] for row in run_rows}
    if len(head_shas) != 1:
        raise SystemExit("phase1 gate summary: inconsistent head_sha across runs")

    runs = {}
    for mode, key in PHASE1_RUN_KEYS.items():
        matches = [row["run_id"] for row in run_rows if row["run_mode"] == mode]
        if len(matches) != 1:
            raise SystemExit(f"phase1 gate summary: expected exactly one {mode} run")
        runs[key] = matches[0]

    family = []
    for row in read_csv_rows(PHASE1_FAMILY_CSV):
        q = float(row["q_signflip_bh_m5"])
        family.append({
            "name": PHASE1_FAMILY_NAMES[row["registered_test"]],
            "mean": float(row["mean_paired_difference"]),
            "median": float(row["median_paired_difference"]),
            "ci_lo": float(row["ci_lo_bootstrap"]),
            "ci_hi": float(row["ci_hi_bootstrap"]),
            "p": float(row["p_signflip_two_sided"]),
            "q": q,
            "rank_biserial": float(row["paired_rank_biserial"]),
            "verdict": "supported" if q <= 0.05 else "null",
        })
    if len(family) != len(PHASE1_FAMILY_NAMES):
        raise SystemExit("phase1 registered family: unexpected row count")

    decomp = json.load(open(PHASE1_DECOMP_JSON, encoding="utf-8"))
    decomposition = {
        "redundancy_share": float(decomp["redundancy_share"]),
        "category": PHASE1_DECOMP_CATEGORIES[decomp["category"]],
    }

    learning = read_csv_rows(PHASE1_ARM_C_OUT / "learning_speed_tests.csv")
    paired = read_csv_rows(PHASE1_ARM_C_OUT / "paired_tests.csv")

    def learn(lab, metric):
        values = [row["mean_diff_true_minus_false"] for row in learning
                  if row["profile"] == lab and row["metric"] == metric]
        if len(values) != 1:
            raise SystemExit(f"learning_speed_tests: expected one {lab}/{metric} row")
        return float(values[0])

    def bench(lab, metric):
        values = [row["mean_diff"] for row in paired
                  if row["profile"] == lab and row["metric"] == metric
                  and row["mode_a"] == "ql_true" and row["mode_b"] == "ql_false"]
        if len(values) != 1:
            raise SystemExit(f"paired_tests: expected one {lab}/{metric} row")
        return float(values[0])

    labs = {}
    for lab in ("lab1", "lab2", "lab3"):
        entry = {
            "headline": PHASE1_LAB_HEADLINES[lab],
            "auc_goal": learn(lab, "auc_goal"),
            "first_goal_presentations": learn(lab, "mean_first_goal_presentations"),
        }
        for out_key, metric in PHASE1_BENCH_KEYS.get(lab, ()):
            entry[out_key] = bench(lab, metric)
        labs[lab] = entry

    control_rows = read_csv_rows(PHASE1_CONTROLS_CSV)

    def control_counts(mode):
        corrected = sum(1 for row in control_rows
                        if row["run_mode"] == mode
                        and row["metric"] != LEGACY_ENERGY_METRIC
                        and float(row["mean_difference"]) != 0.0)
        legacy = sum(1 for row in control_rows
                     if row["run_mode"] == mode
                     and row["metric"] == LEGACY_ENERGY_METRIC
                     and float(row["mean_difference"]) != 0.0)
        return corrected, legacy

    baseline_nonzero, baseline_legacy = control_counts("phase1_v2_baseline")
    pbrs_nonzero, pbrs_legacy = control_counts("phase1_v2_pbrs_only")
    if baseline_legacy != pbrs_legacy:
        raise SystemExit("control modes disagree on legacy wall-clock row counts")

    def gate_value(src_key):
        values = {row[src_key] for row in run_rows}
        if len(values) != 1:
            raise SystemExit(f"phase1 gate summary: runs disagree on {src_key}")
        return values.pop()

    horizons = {tuple(row["fixed_horizons"]) for row in run_rows}
    if len(horizons) != 1 or len(next(iter(horizons))) != 1:
        raise SystemExit("phase1 gate summary: unexpected training horizon set")
    protocol_gates = {
        "training_cells_per_run": gate_value("training_cells"),
        "benchmark_cells_per_run": gate_value("benchmark_cells"),
        "episodes_per_training_cell": next(iter(horizons))[0],
        "fallback_count": gate_value("scenario_fallback_count"),
        "paired_schedule_mismatches": gate_value("schedule_pair_mismatches"),
        "first_goal_rows_per_run": gate_value("first_goal_rows"),
        "censored_first_goal_rows_per_run": gate_value("first_goal_censored_rows"),
    }

    obj = {
        "protocol_version": gates["protocol_version"],
        "status": "current thesis evidence",
        "head_sha": head_shas.pop(),
        "runs": runs,
        "registered_family": family,
        "decomposition": decomposition,
        "labs": labs,
        "controls": {
            "baseline_corrected_contrasts_nonzero": baseline_nonzero,
            "pbrs_corrected_contrasts_nonzero": pbrs_nonzero,
            "legacy_wallclock_rows_nonzero_per_control_mode": baseline_legacy,
            "note": ("Only the withdrawn wall-clock diagnostic varies between "
                     "otherwise identical labels."),
        },
        "protocol_gates": protocol_gates,
        "source": "results/phase1_v2_corrected/analysis/registered/phase1_v2_registered_family.csv",
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / "phase1.json", "w", encoding="utf-8", newline="\n") as handle:
        json.dump(obj, handle, indent=2)
        handle.write("\n")
    print("phase1.json written (protocol v2, derived from results/phase1_v2_corrected/)")


# Phase-2 corrected sources (protocol phase2-v2, detector fault-detector-v2).
PHASE2_V2_REGISTERED = ROOT / "results" / "phase2_v2_corrected" / "analysis" / "registered"
PHASE2_V2_RUNS = {"A1": 30001857104, "A2": 30001867521,
                  "B1": 30001878310, "B2": 30001888910}
PHASE2_HEAD_SHA = "19f4f3ff55f2b7ecebc015f85c6df0e55e44d7c9"
PHASE2_NICE = {
    "lab3_f1dead": "lab3 · Z1 lamp dead",
    "lab3_f1dead_z2": "lab3 · Z2 lamp dead",
    "lab3_f1bdead": "lab3 · Z1 blind dead",
    "lab3_f1binv": "lab3 · Z1 blind inverted",
    "lab2_f1bdead": "lab2 · Z1 blind dead",
    "labmon_f1dead": "labmon · lamp dead",
    "lab3_f2dead_lowsun": "lab3 · both lamps dead, low sun",
    "labmon2_f2dead_lowsun": "labmon2 · both lamps dead, low sun",
    "lab3_f1inv": "lab3 · Z1 lamp inverted",
    "lab3_f1inv_z2": "lab3 · Z2 lamp inverted",
    "lab2_f1binv": "lab2 · Z1 blind inverted",
}


def _phase2_family(name, verdict_metric):
    rows = []
    for row in read_csv_rows(PHASE2_V2_REGISTERED / name):
        q = float(row["q_signflip_bh_m8"])
        mean = float(row["mean_paired_difference"])
        if verdict_metric and q <= 0.05:
            verdict = "supported" if mean < 0 else "adverse"
        elif verdict_metric:
            verdict = "null"
        else:
            verdict = ""
        entry = {
            "cell": row["registered_cell"],
            "name": PHASE2_NICE.get(row["registered_cell"], row["registered_cell"]),
            "mean_ql_true": float(row["mean_ql_true"]),
            "mean_ql_false": float(row["mean_ql_false"]),
            "mean": mean,
            "ci_lo": float(row["ci_lo_bootstrap"]),
            "ci_hi": float(row["ci_hi_bootstrap"]),
            "p": float(row["p_signflip_two_sided"]),
            "q": q,
            "censored_ql_true": int(row["censored_ql_true"]),
            "censored_ql_false": int(row["censored_ql_false"]),
        }
        if verdict_metric:
            entry["verdict"] = verdict
        rows.append(entry)
    return rows


# Genuinely-faulty components per fault suffix; any blacklist event naming a
# component outside this set is a false positive.
PHASE2_FAULT_MAP = {
    "f1dead": ("SetZ1Light",), "f1inv": ("SetZ1Light",),
    "f2dead": ("SetZ1Light", "SetZ2Light"), "f2inv": ("SetZ1Light", "SetZ2Light"),
    "f1bdead": ("SetZ1Blinds",), "f1binv": ("SetZ1Blinds",),
    "f1dead_z2": ("SetZ2Light",), "f1inv_z2": ("SetZ2Light",),
    "f2bdead": ("SetZ1Blinds", "SetZ2Blinds"),
    "f2dead_lowsun": ("SetZ1Light", "SetZ2Light"),
}


def _phase2_false_positive_events():
    count = 0
    recoveries = sorted((ROOT / "results" / "phase2_v2_corrected").glob(
        "run_*/recovery_root/seed*/recovery_stereotypes_*.csv"))
    # The recovery CSVs are tracked; an empty scan would make the count vacuous.
    if not recoveries:
        raise SystemExit("phase2 FP scan: no recovery_stereotypes_*.csv found under "
                         "results/phase2_v2_corrected/run_*/recovery_root/")
    for recovery in recoveries:
        profile = recovery.stem.split("_", 3)[3]
        # Longest-suffix match handles variant profiles
        # (labmon2_infoonly_f2dead_lowsun -> f2dead_lowsun).
        genuine = ()
        for suffix in sorted(PHASE2_FAULT_MAP, key=len, reverse=True):
            if profile.endswith("_" + suffix):
                genuine = PHASE2_FAULT_MAP[suffix]
                break
        if not genuine:
            raise SystemExit(f"phase2 FP scan: unrecognized fault profile {profile}")
        rows = read_csv_rows(recovery)
        if not rows:
            continue
        for event in (rows[-1].get("BlacklistEvents") or "").split(";"):
            if event and event.split("@")[0].split("#")[-1] not in genuine:
                count += 1
    return count


def build_phase2(out_dir=None):
    tier1 = _phase2_family("phase2_v2_registered_family.csv", True)
    detection = _phase2_family("phase2_v2_detection_family.csv", False)
    if len(tier1) != 8 or len(detection) != 8:
        raise SystemExit("phase2 registered families: unexpected row counts")
    obj = {
        "protocol_version": "phase2-v2",
        "detector_version": "fault-detector-v2",
        "status": "current thesis evidence",
        "runs": PHASE2_V2_RUNS,
        "head_sha": PHASE2_HEAD_SHA,
        "adapt_horizon": 4000,
        "tier1": tier1,
        "detection": detection,
        "false_positive_blacklist_events": _phase2_false_positive_events(),
        "source": "results/phase2_v2_corrected/analysis/registered/phase2_v2_registered_family.csv",
    }
    _dump(obj, "phase2.json", out_dir)
    print("phase2.json written (protocol v2, derived from results/phase2_v2_corrected/)")


# Phase-3/4 corrected sources: derived solely from the committed protocol-v2
# campaign archives, like build_phase1. CI regenerates and byte-compares.
PHASE3_V2_RUN = 29926328852
PHASE3_V2_OUT = ROOT / "results" / "phase3_v2_corrected" / f"run_{PHASE3_V2_RUN}" / "analysis" / "out"
PHASE4_V2_RUNS = {"seeds_1_10": 29926341581, "seeds_11_20": 29926354783}
PHASE4_V2_REGISTERED = ROOT / "results" / "phase4_v2_corrected" / "analysis" / "registered"
# Corrected lab5 energy-compliance LEVELS. The archived registered table scores
# test runs that need no step as "all devices off"; the corrected table scores
# them from the start state the scenario file declares. The paired statistics
# are identical under both scorings, so they are still read from the archived
# registered table; only the level columns come from the corrected files.
PHASE4_CORRECTED = ROOT / "results" / "_corrected_tables" / "phase4_v2_corrected"
PHASE4_LEVELS_CSV = PHASE4_CORRECTED / "lab5_compliance_levels.csv"
PHASE34_HEAD_SHA = "90e53f8b087fc390a7ad51a0187b81c89c21c64e"
PHASE4_FAMILY_NAMES = {
    "lab4": "lab4 avg_redundant",
    "lab4dual": "lab4dual avg_redundant",
    "lab4chain": "lab4chain avg_redundant",
    "lab5": "lab5 energy_compliance",
}
# Favourable direction per registered member (negative = KG better for the
# redundancy cells, positive = KG better for lab5 energy compliance).
PHASE4_FAVOURABLE_SIGN = {"avg_redundant": -1.0, "energy_compliance": 1.0}


def _dump(obj, name, out_dir=None):
    out_dir = OUT if out_dir is None else Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / name, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(obj, handle, indent=2)
        handle.write("\n")


def build_phase3(out_dir=None):
    obj = {
        "protocol_version": "phase3-v2",
        "status": "current thesis evidence",
        "run": PHASE3_V2_RUN,
        "head_sha": PHASE34_HEAD_SHA,
        "energy_meter": "tick-v1",
        "delay": read_csv_rows(PHASE3_V2_OUT / "phase3_delay_accuracy.csv"),
        "compliance": read_csv_rows(PHASE3_V2_OUT / "phase3_compliance_ci.csv"),
        "source": f"results/phase3_v2_corrected/run_{PHASE3_V2_RUN}/analysis/out",
    }
    _dump(obj, "phase3.json", out_dir)
    print("phase3.json written (protocol v2, derived from results/phase3_v2_corrected/)")


def _phase4_lab5_levels():
    """Corrected (declared start state) and archived (all-off) lab5 levels per
    planner mode, read from lab5_compliance_levels.csv."""
    levels = {}
    for row in read_csv_rows(PHASE4_LEVELS_CSV):
        scoring = {"declared_start_state": "corrected",
                   "all_off_legacy": "archived"}[row["zero_step_scoring"]]
        entry = levels.setdefault(row["mode"], {})
        entry[scoring] = float(row["energy_compliance_mean"])
        entry["n_seeds"] = int(row["n_seeds"])
        entry["n_test_runs"] = int(row["n_test_runs"])
        entry["n_zero_step_runs"] = int(row["n_zero_step_runs"])
    for mode in ("ql_true", "ql_false", "rule_based"):
        if mode not in levels or not {"corrected", "archived"} <= set(levels[mode]):
            raise SystemExit(f"lab5 levels: missing rows for {mode}")
    return levels


def build_phase4(out_dir=None):
    levels = _phase4_lab5_levels()
    family = []
    for row in read_csv_rows(PHASE4_V2_REGISTERED / "phase4_v2_registered_family.csv"):
        q = float(row["q_signflip_bh_m4"])
        mean = float(row["mean_paired_difference"])
        favourable = PHASE4_FAVOURABLE_SIGN[row["metric"]] * mean > 0
        if q <= 0.05:
            verdict = "supported" if favourable else "adverse"
        else:
            verdict = "null"
        entry_true = float(row["mean_ql_true"])
        entry_false = float(row["mean_ql_false"])
        extra = {}
        if row["registered_cell"] == "lab5":
            # Level columns come from the corrected scoring. The archived
            # values must match the archived registered table, and the paired
            # difference must be unchanged under both scorings.
            if (abs(levels["ql_true"]["archived"] - entry_true) > 1e-9
                    or abs(levels["ql_false"]["archived"] - entry_false) > 1e-9):
                raise SystemExit("lab5 levels: archived scoring differs from registered table")
            gap = levels["ql_true"]["corrected"] - levels["ql_false"]["corrected"]
            if abs(gap - mean) > 1e-9:
                raise SystemExit("lab5 levels: corrected paired difference differs")
            extra = {"archived_mean_ql_true": entry_true,
                     "archived_mean_ql_false": entry_false}
            entry_true = levels["ql_true"]["corrected"]
            entry_false = levels["ql_false"]["corrected"]
        family.append({
            "name": PHASE4_FAMILY_NAMES[row["registered_cell"]],
            "cell": row["registered_cell"],
            "metric": row["metric"],
            "mean_ql_true": entry_true,
            "mean_ql_false": entry_false,
            **extra,
            "mean": mean,
            "ci_lo": float(row["ci_lo_bootstrap"]),
            "ci_hi": float(row["ci_hi_bootstrap"]),
            "p": float(row["p_signflip_two_sided"]),
            "q": q,
            "rank_biserial": float(row["paired_rank_biserial"]),
            "verdict": verdict,
        })
    if len(family) != 4:
        raise SystemExit("phase4 registered family: unexpected row count")
    ladder = []
    for row in read_csv_rows(PHASE4_V2_REGISTERED / "phase4_v2_ladder_trend.csv"):
        ladder.append({
            "contrast": row["contrast"],
            "role": row["role"],
            "mean": float(row["mean_difference_of_differences"]),
            "ci_lo": float(row["ci_lo_bootstrap"]),
            "ci_hi": float(row["ci_hi_bootstrap"]),
            "p": float(row["p_signflip_two_sided"]),
        })
    obj = {
        "protocol_version": "phase1-v2",
        "run_mode": "phase4_v2",
        "status": "current thesis evidence",
        "runs": PHASE4_V2_RUNS,
        "head_sha": PHASE34_HEAD_SHA,
        "registered_family": family,
        "lab5_levels": {
            "ql_true": levels["ql_true"]["corrected"],
            "ql_false": levels["ql_false"]["corrected"],
            "rule_based": levels["rule_based"]["corrected"],
            "archived_ql_true": levels["ql_true"]["archived"],
            "archived_ql_false": levels["ql_false"]["archived"],
            "archived_rule_based": levels["rule_based"]["archived"],
            "n_seeds": levels["ql_true"]["n_seeds"],
            "n_test_runs_per_arm": levels["ql_true"]["n_test_runs"],
            "n_zero_step_runs_per_arm": levels["ql_true"]["n_zero_step_runs"],
            "source": "results/_corrected_tables/phase4_v2_corrected/lab5_compliance_levels.csv",
        },
        "ladder_trend": ladder,
        "source": "results/phase4_v2_corrected/analysis/registered/phase4_v2_registered_family.csv",
    }
    _dump(obj, "phase4.json", out_dir)
    print("phase4.json written (protocol v2, derived from results/phase4_v2_corrected/)")


def _load(out_dir, name):
    with open(Path(out_dir) / name, encoding="utf-8") as handle:
        return json.load(handle)


def build_overview(out_dir=None):
    """The numbers on the overview page. Derived from the four phase files in
    out_dir (build those first) plus the seed count column of the registered
    tables, which the phase files omit."""
    out_dir = OUT if out_dir is None else Path(out_dir)
    p1, p2, p3, p4 = (_load(out_dir, f"phase{n}.json") for n in (1, 2, 3, 4))

    family1 = {row["name"]: row for row in p1["registered_family"]}
    lab2 = family1["Arm-C lab2 auc_goal"]
    n_seeds1 = {int(r["n_paired"]) for r in read_csv_rows(PHASE1_FAMILY_CSV)}
    if len(n_seeds1) != 1:
        raise SystemExit("phase1 registered family: seed counts differ")
    phase1 = {
        "lab2_auc_goal_gain": lab2["mean"],
        "lab2_auc_goal_q": lab2["q"],
        "lab2_auc_goal_verdict": lab2["verdict"],
        "redundancy_share": p1["decomposition"]["redundancy_share"],
        "n_seeds": n_seeds1.pop(),
        "source": "phase1.json: registered_family (Arm-C lab2 auc_goal), decomposition",
    }

    cells = p2["tier1"]
    n_seeds2 = {int(r["n_paired"]) for r in
                read_csv_rows(PHASE2_V2_REGISTERED / "phase2_v2_registered_family.csv")}
    if len(n_seeds2) != 1:
        raise SystemExit("phase2 registered family: seed counts differ")
    n_seeds2 = n_seeds2.pop()
    adverse = [c for c in cells if c["verdict"] == "adverse"]
    phase2 = {
        "n_cells": len(cells),
        "n_supported": sum(c["verdict"] == "supported" for c in cells),
        "n_null": sum(c["verdict"] == "null" for c in cells),
        "n_adverse": len(adverse),
        "n_seeds": n_seeds2,
        "adverse_cells_knowledge_arm_never_detected":
            sum(c["censored_ql_true"] == n_seeds2 for c in adverse),
        "source": "phase2.json: tier1 (verdict, censored_ql_true)",
    }

    learned = [float(r["slowest_learned_ticks"]) for r in p3["delay"]]
    truths = {float(r["ground_truth_ticks"]) for r in p3["delay"]}
    if len(truths) != 1:
        raise SystemExit("phase3: ground-truth delay differs between rows")

    def met(profile, mode):
        row = next(r for r in p3["compliance"]
                   if r["profile"] == profile and r["mode"] == mode)
        total = int(row["n_goals_total"]) * int(row["n_replicas"])
        return {"met": round(float(row["overall_compliance_mean"]) * total), "total": total}

    phase3 = {
        "ground_truth_ticks": truths.pop(),
        "learned_ticks_min": min(learned),
        "learned_ticks_max": max(learned),
        "deadlines": {profile: {"knowledge_arm": met(profile, "ql_true"),
                                "plain_arm": met(profile, "ql_false")}
                      for profile in ("lab2_slow", "lab3_slow")},
        "source": "phase3.json: delay (slowest_learned_ticks), compliance (overall_compliance_mean)",
    }

    steps = {}
    for row in p4["registered_family"]:
        if row["metric"] != "avg_redundant":
            continue
        steps[row["cell"]] = {
            "knowledge_arm": row["mean_ql_true"],
            "plain_arm": row["mean_ql_false"],
            "reduction_pct": 100.0 * (row["mean_ql_false"] - row["mean_ql_true"])
            / row["mean_ql_false"],
            "q": row["q"],
        }
    lab5 = next(r for r in p4["registered_family"] if r["cell"] == "lab5")
    phase4 = {
        "steps_per_test_run": steps,
        "lab5": {
            "knowledge_arm": p4["lab5_levels"]["ql_true"],
            "plain_arm": p4["lab5_levels"]["ql_false"],
            "hand_written": p4["lab5_levels"]["rule_based"],
            "paired_difference": lab5["mean"],
            "q": lab5["q"],
            "n_seeds": p4["lab5_levels"]["n_seeds"],
        },
        "source": "phase4.json: registered_family (mean_ql_true, mean_ql_false), lab5_levels",
    }
    _dump({"phase1": phase1, "phase2": phase2, "phase3": phase3, "phase4": phase4},
          "overview.json", out_dir)
    print("overview.json written (derived from phase1.json .. phase4.json)")


BUILDERS = {
    "phase1": build_phase1,
    "phase2": build_phase2,
    "phase3": build_phase3,
    "phase4": build_phase4,
    "overview": build_overview,
}


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Prepare dashboard JSON data.")
    parser.add_argument("--only", choices=list(BUILDERS), action="append",
                        help="build only the named dataset(s); default: all, in order")
    args = parser.parse_args()
    for name in (args.only or list(BUILDERS)):
        BUILDERS[name]()
    print("done ->", OUT)


if __name__ == "__main__":
    main()
