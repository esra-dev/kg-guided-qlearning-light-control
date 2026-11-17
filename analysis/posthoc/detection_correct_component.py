#!/usr/bin/env python3
"""Phase-2 fault detection counted only when the right device is blamed (post-hoc, descriptive).

POST-HOC, DESCRIPTIVE. Specified after the results were known. Computes
counts, means, paired per-seed differences, 95% bootstrap intervals of the
mean paired difference and sign counts; no p-values and no q-values.

Definitions
-----------
* Blame: one ``<component-uri>@<episode>`` entry of ``BlacklistEvents`` in
  the LAST row of a cell's recovery CSV (the row the registered analyses
  read). The first blame is the entry with the lowest episode (ties keep the
  logged order).
* Record detection (the archive's own reading, reused from
  ``phase2_v2_registered_family._load_cell``): detected when
  ``DetectEpisode >= 0``; the detection episode is ``DetectEpisode``, censored
  at ``adapt_episodes_effective + 1`` (from the cell's ADAPT_OK manifest) when
  never detected.
* Strict detection: the episode of the first blame that names a truly broken
  device; censored at the same ``adapt_episodes_effective + 1`` when no blame
  names a broken device.
* Truly broken devices per fault lab: ``BROKEN_COMPONENTS`` below, taken from
  ``simulator/generate_faulty_flows.ps1`` (which actuator contributions each
  faulty flow removes or inverts) and ``config/run_config.json``
  ``simulator_flow_map`` (the ``_infoonly`` / ``_nostereo`` profiles run the
  same faulty flow as their parent cell). Every other blamed device is a
  healthy device.

Per (archive, fault lab, arm): number of seeds; seeds with any blame (the
record's "detected"); seeds whose first blame names a broken device; seeds
whose first blame names a healthy device; seeds with any healthy-device blame;
seeds detected under the strict rule; mean record and strict detection
episodes and their censoring counts; seeds whose strict value differs from
the record value. Paired per-seed differences ``ql_true - ql_false`` of the
record and of the strict detection episode are summarised per (archive, fault
lab).

Run (from the repository root; reads the tracked recovery_root/ CSVs of each run):
  py -3 analysis/posthoc/detection_correct_component.py \
      --archive v2_corrected=results/phase2_v2_corrected/run_30001857104 \
      --archive v2_corrected=results/phase2_v2_corrected/run_30001867521 ... \
      --archive structonly=results/phase2_v3_structonly/run_43590832041 \
      --archive lowsun=results/phase2_v3_lowsun/run_43099277363 \
      --seeds 1-20 --out <output folder>
Several ``--archive`` entries with the same label form one archive; each
(fault lab, arm, seed) must resolve to exactly one recovery file within it.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from analysis import phase2_v2_registered_family as p2  # noqa: E402
from analysis.posthoc import _common as cm  # noqa: E402

ANALYSIS = "detection_correct_component"
ARMS = (("true", "ql_true"), ("false", "ql_false"))

_L1, _L2 = "SetZ1Light", "SetZ2Light"
_B1, _B2 = "SetZ1Blinds", "SetZ2Blinds"
BROKEN_COMPONENTS: dict[str, tuple[str, ...]] = {
    "lab1_f1dead": (_L1,),
    "lab2_f1dead": (_L1,),
    "lab2_f1inv": (_L1,),
    "lab2_f2dead": (_L1, _L2),
    "lab2_f2inv": (_L1, _L2),
    "lab2_f1bdead": (_B1,),
    "lab2_f1binv": (_B1,),
    "lab3_f1dead": (_L1,),
    "lab3_f1inv": (_L1,),
    "lab3_f1dead_z2": (_L2,),
    "lab3_f1inv_z2": (_L2,),
    "lab3_f1bdead": (_B1,),
    "lab3_f1binv": (_B1,),
    "lab3_f2dead": (_L1, _L2),
    "lab3_f2inv": (_L1, _L2),
    "lab3_f2bdead": (_B1, _B2),
    "lab3_f2dead_lowsun": (_L1, _L2),
    "labmon_f1dead": (_L1,),
    "labmon_infoonly_f1dead": (_L1,),
    "labmon_nostereo_f1dead": (_L1,),
    "labmon2_f2dead_lowsun": (_L1, _L2),
    "labmon2_infoonly_f2dead_lowsun": (_L1, _L2),
    "labmon2_nostereo_f2dead_lowsun": (_L1, _L2),
}

_RECOVERY = re.compile(r"^recovery_stereotypes_(true|false)_(.+)\.csv$")
_SEED = re.compile(r"^seed(\d+)$")


def fragment(token: str) -> str:
    """URI fragment of a component ('http://example.org/was#SetZ1Blinds' -> 'SetZ1Blinds')."""
    return token.strip().split("#")[-1].split("/")[-1]


def parse_blames(blacklist_events: str | None) -> list[tuple[str, int]]:
    """[(component, episode), ...] ordered by episode (stable for ties)."""
    events = []
    for position, raw in enumerate((blacklist_events or "").split(";")):
        raw = raw.strip()
        if not raw:
            continue
        uri, _, episode = raw.rpartition("@")
        if not uri:
            raise ValueError(f"malformed blacklist event {raw!r}")
        events.append((int(episode), position, fragment(uri)))
    events.sort()
    return [(component, episode) for episode, _pos, component in events]


def strict_outcome(blames: list[tuple[str, int]], broken: tuple[str, ...],
                   horizon: int) -> dict:
    """Strict detection from the ordered blames of one record."""
    first = blames[0][0] if blames else ""
    correct = [episode for component, episode in blames if component in broken]
    return {
        "any_blame": bool(blames),
        "first_blame": first,
        "first_blame_broken": bool(blames) and first in broken,
        "first_blame_healthy": bool(blames) and first not in broken,
        "any_healthy_blame": any(component not in broken for component, _ in blames),
        "strict_detected": bool(correct),
        "strict_detect_episode": float(correct[0]) if correct else float(horizon + 1),
        "strict_censored": not correct,
    }


def discover(roots: list[Path]) -> dict[tuple[str, str], set[int]]:
    """{(fault lab, bool arm): {seeds}} present under recovery_root/seed<N>/."""
    found: dict[tuple[str, str], set[int]] = {}
    for root in roots:
        base = Path(root) / "recovery_root"
        if not base.is_dir():
            raise ValueError(f"{root}: no recovery_root folder")
        for seed_dir in base.iterdir():
            match = _SEED.match(seed_dir.name)
            if not match:
                continue
            for item in seed_dir.iterdir():
                rec = _RECOVERY.match(item.name)
                if rec:
                    found.setdefault((rec.group(2), rec.group(1)), set()).add(int(match.group(1)))
    return found


def recovery_record(roots: list[Path], profile: str, bool_name: str, seed: int) -> dict:
    name = f"stereotypes_{bool_name}_{profile}"
    matches = [Path(r) / "recovery_root" / f"seed{seed}" / f"recovery_{name}.csv" for r in roots]
    matches = [m for m in matches if m.is_file()]
    if len(matches) != 1:
        raise ValueError(f"expected one recovery file for {profile}/{bool_name}/seed{seed}, "
                         f"found {len(matches)}")
    rows = p2._csv_rows(matches[0])
    manifest = p2._json(matches[0].parent / f"ADAPT_OK_{name}.json")
    return {"last": rows[-1], "horizon": int(manifest["adapt_episodes_effective"]),
            "path": matches[0]}


def run(archives: dict[str, list[Path]], seeds: tuple[int, ...], out: Path,
        iters: int = cm.BOOTSTRAP_ITERS) -> dict:
    seed_rows, cell_rows, contrast_rows = [], [], []
    unknown: list[str] = []
    for label, roots in archives.items():
        cells = discover(roots)
        profiles = sorted({profile for profile, _ in cells})
        for profile in profiles:
            if profile not in BROKEN_COMPONENTS:
                unknown.append(f"{label}/{profile}")
                continue
            broken = BROKEN_COMPONENTS[profile]
            per_arm: dict[str, dict[str, dict[int, float]]] = {}
            for bool_name, arm in ARMS:
                record_vals: dict[int, float] = {}
                strict_vals: dict[int, float] = {}
                counts = {"n_seeds": 0, "n_detected_record": 0, "n_first_blame_broken": 0,
                          "n_first_blame_healthy": 0, "n_any_healthy_blame": 0,
                          "n_detected_strict": 0, "n_censored_record": 0,
                          "n_censored_strict": 0, "n_strict_differs_from_record": 0,
                          "n_defect_component_differs_from_first_blame": 0}
                for seed in seeds:
                    rec = recovery_record(roots, profile, bool_name, seed)
                    record_value, record_censored = p2._load_cell(
                        roots, profile, bool_name, seed, "DetectEpisode")
                    blames = parse_blames(rec["last"].get("BlacklistEvents"))
                    strict = strict_outcome(blames, broken, rec["horizon"])
                    detect_raw = int(rec["last"]["DetectEpisode"])
                    if (detect_raw >= 0) != strict["any_blame"]:
                        raise ValueError(f"{rec['path']}: DetectEpisode and BlacklistEvents disagree")
                    if blames and detect_raw != blames[0][1]:
                        raise ValueError(f"{rec['path']}: DetectEpisode is not the first blame episode")
                    defect = rec["last"].get("DefectComponent", "")
                    defect_differs = bool(blames) and fragment(defect) != strict["first_blame"]
                    record_vals[seed] = record_value
                    strict_vals[seed] = strict["strict_detect_episode"]
                    counts["n_seeds"] += 1
                    counts["n_detected_record"] += int(not record_censored)
                    counts["n_first_blame_broken"] += int(strict["first_blame_broken"])
                    counts["n_first_blame_healthy"] += int(strict["first_blame_healthy"])
                    counts["n_any_healthy_blame"] += int(strict["any_healthy_blame"])
                    counts["n_detected_strict"] += int(strict["strict_detected"])
                    counts["n_censored_record"] += int(record_censored)
                    counts["n_censored_strict"] += int(strict["strict_censored"])
                    counts["n_strict_differs_from_record"] += int(
                        strict["strict_detect_episode"] != record_value)
                    counts["n_defect_component_differs_from_first_blame"] += int(defect_differs)
                    seed_rows.append({
                        "archive": label, "fault_lab": profile, "arm": arm, "seed": seed,
                        "broken_components": ";".join(broken),
                        "blames": ";".join(f"{c}@{e}" for c, e in blames),
                        "first_blame": strict["first_blame"],
                        "first_blame_broken": strict["first_blame_broken"],
                        "first_blame_healthy": strict["first_blame_healthy"],
                        "any_healthy_blame": strict["any_healthy_blame"],
                        "record_detect_episode": record_value,
                        "record_censored": record_censored,
                        "strict_detect_episode": strict["strict_detect_episode"],
                        "strict_censored": strict["strict_censored"],
                        "censoring_value": rec["horizon"] + 1,
                        "DefectComponent": fragment(defect) if defect else "",
                    })
                per_arm[arm] = {"record": record_vals, "strict": strict_vals}
                cell_rows.append({
                    "archive": label, "fault_lab": profile, "arm": arm,
                    "broken_components": ";".join(broken), **counts,
                    "mean_record_detect_episode": cm.mean(record_vals.values()),
                    "mean_strict_detect_episode": cm.mean(strict_vals.values()),
                })
            for rule in ("record", "strict"):
                # One resampling stream per fault lab: the record and strict rows (and
                # identical archives) resample the same seeds.
                summary = cm.paired_summary(per_arm["ql_true"][rule], per_arm["ql_false"][rule],
                                            seeds, ANALYSIS, profile, iters=iters)
                contrast_rows.append({"archive": label, "fault_lab": profile,
                                      "detection_rule": rule,
                                      "contrast": "ql_true_minus_ql_false", **summary})
    if unknown:
        raise ValueError("fault labs without a broken-component entry: " + ", ".join(unknown))

    out.mkdir(parents=True, exist_ok=True)
    cm.write_csv(out / "per_seed.csv", seed_rows, [
        "archive", "fault_lab", "arm", "seed", "broken_components", "blames", "first_blame",
        "first_blame_broken", "first_blame_healthy", "any_healthy_blame",
        "record_detect_episode", "record_censored", "strict_detect_episode",
        "strict_censored", "censoring_value", "DefectComponent"])
    cm.write_csv(out / "cells.csv", cell_rows, [
        "archive", "fault_lab", "arm", "broken_components", "n_seeds", "n_detected_record",
        "n_first_blame_broken", "n_first_blame_healthy", "n_any_healthy_blame",
        "n_detected_strict", "n_censored_record", "n_censored_strict",
        "n_strict_differs_from_record", "n_defect_component_differs_from_first_blame",
        "mean_record_detect_episode", "mean_strict_detect_episode"])
    cm.write_csv(out / "contrasts.csv", contrast_rows, [
        "archive", "fault_lab", "detection_rule", "contrast", *cm.PAIRED_FIELDS])
    result = {"seed_rows": seed_rows, "cell_rows": cell_rows, "contrast_rows": contrast_rows}
    _write_summary(out / "summary.md", result, seeds, iters)
    return result


def _write_summary(path: Path, result: dict, seeds, iters: int) -> None:
    lines = [
        "# Phase-2 detection counted only when a truly broken device is blamed",
        "",
        f"Status: {cm.POSTHOC_LABEL}. No p-values or q-values are computed.",
        f"Seeds {seeds[0]}..{seeds[-1]} ({len(seeds)}); bootstrap {iters} resamples, "
        f"base seed {cm.BOOTSTRAP_BASE_SEED}.",
        "",
        "## Per fault lab and arm",
        "",
        "Columns: detected = seeds with any blame (record); first ok / first healthy = seeds "
        "whose first blame names a broken / healthy device; strict = seeds with a blame of a "
        "broken device; mean episodes are censored at adapt_episodes_effective + 1.",
        "",
    ]
    table = [{
        "archive": r["archive"], "fault_lab": r["fault_lab"], "arm": r["arm"],
        "n": r["n_seeds"], "detected": r["n_detected_record"],
        "first_ok": r["n_first_blame_broken"], "first_healthy": r["n_first_blame_healthy"],
        "strict": r["n_detected_strict"], "record_mean": r["mean_record_detect_episode"],
        "strict_mean": r["mean_strict_detect_episode"],
        "seeds_changed": r["n_strict_differs_from_record"],
    } for r in result["cell_rows"]]
    lines += cm.md_table(table, ["archive", "fault_lab", "arm", "n", "detected", "first_ok",
                                 "first_healthy", "strict", "record_mean", "strict_mean",
                                 "seeds_changed"], digits=2)
    healthy = [r for r in result["seed_rows"] if r["first_blame_healthy"]]
    lines += ["", f"## Seeds whose first blame names a healthy device ({len(healthy)})", ""]
    lines += cm.md_table(healthy, ["archive", "fault_lab", "arm", "seed", "blames",
                                   "record_detect_episode", "strict_detect_episode"], digits=0)
    changed = [r for r in result["contrast_rows"]]
    lines += ["", "## ql_true - ql_false detection episode, record vs strict "
                  "(mean [95% bootstrap]; seeds +/0/-)", ""]
    table = []
    for row in changed:
        table.append({"archive": row["archive"], "fault_lab": row["fault_lab"],
                      "rule": row["detection_rule"], "difference": row["mean_paired_difference"],
                      "ci": cm.interval(row["bootstrap_ci_lo_95"], row["bootstrap_ci_hi_95"], 2),
                      "signs": f"{row['n_seeds_left_higher']}/{row['n_seeds_tied']}/"
                               f"{row['n_seeds_left_lower']}"})
    lines += cm.md_table(table, ["archive", "fault_lab", "rule", "difference", "ci", "signs"],
                         digits=2)
    lines += ["", "Full tables: cells.csv, per_seed.csv, contrasts.csv."]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_archives(items: list[str]) -> dict[str, list[Path]]:
    archives: dict[str, list[Path]] = {}
    for item in items:
        label, sep, path = item.partition("=")
        if not sep or not label or not path:
            raise ValueError(f"--archive expects LABEL=PATH, got {item!r}")
        archives.setdefault(label, []).append(Path(path))
    return archives


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--archive", action="append", required=True,
                        help="LABEL=PATH to a run root holding recovery_root/ (repeatable)")
    parser.add_argument("--seeds", default="1-20")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    result = run(parse_archives(args.archive), cm.parse_seeds(args.seeds), args.out)
    print(f"{ANALYSIS}: {len(result['cell_rows'])} (archive, fault lab, arm) cells -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
