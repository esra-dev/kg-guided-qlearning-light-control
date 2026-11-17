#!/usr/bin/env python3
"""Validate corrected Phase-3 (protocol phase3-v2) per-replica data.

Layout expected under <root> (the downloaded phase3-consolidated artifact):
  dynamics_root/rep<N>/DYNAMICS_OK_<bool>_<profile>.json
  dynamics_root/rep<N>/timebounded_results_<bool>_<profile>.csv
  dynamics_root/rep<N>/dynamics_delays_<bool>_<profile>.csv

Every cell must carry a DYNAMICS_OK gate manifest (energy meter tick-v1,
correct ground-truth constants) and a time-bounded CSV whose rows are stamped
energy_meter=tick-v1 with tick_energy/tick_span columns.

Receipts written by the current runner also carry the true blind delay read
from the started flow file, the learned delay per device and a tolerance check
(fields blind_delay_check, blind_delay_tolerance_ticks, learned_delay_ticks,
...). Archived receipts lack them and are accepted as they are; when the fields
are present they are checked against the configuration and recomputed from
the delay table.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


SOURCE_ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = "phase3-v2"
ENERGY_METER = "tick-v1"


def _config():
    cfg = json.loads((SOURCE_ROOT / "config" / "run_config.json")
                     .read_text(encoding="utf-8-sig"))
    return cfg["phase3"]


def learned_blind_delays(delays_path: Path) -> dict[str, float]:
    """Learned delay (ticks) of every device whose label names a blind."""
    with delays_path.open(encoding="utf-8-sig", newline="") as handle:
        return {row["action_label"]: float(row["delay_ticks"])
                for row in csv.DictReader(handle) if "Blind" in row["action_label"]}


def check_delay_fields(manifest: dict, delays_path: Path, expected_ticks: int,
                       tolerance: float, label: str) -> list[str]:
    """Check the true-delay and tolerance fields of a receipt, when present."""
    if "blind_delay_check" not in manifest:
        return []  # archived receipt written before these fields existed
    errors: list[str] = []
    for key, expected in (("blind_delay_check", "pass"),
                          ("blind_delay_ticks_config", expected_ticks),
                          ("blind_delay_tolerance_ticks", tolerance)):
        if manifest.get(key) != expected:
            errors.append(f"{label}: {key}={manifest.get(key)!r}, expected {expected!r}")
    source = manifest.get("blind_delay_ticks_source", "")
    if not (isinstance(source, str) and source.startswith("simulator/")
            and source.endswith(f"_{manifest.get('profile')}.json")):
        errors.append(f"{label}: blind_delay_ticks_source={source!r} is not the profile's flow file")
    reported = manifest.get("simulator_profile_reported")
    if reported is not None and reported != manifest.get("profile"):
        errors.append(f"{label}: simulator_profile_reported={reported!r}, expected {manifest.get('profile')!r}")
    if not delays_path.is_file():
        return errors
    blinds = learned_blind_delays(delays_path)
    if not blinds:
        errors.append(f"{delays_path}: no learned blind delay")
    learned = manifest.get("learned_delay_ticks") or {}
    for name, ticks in blinds.items():
        if abs(float(learned.get(name, float("nan"))) - ticks) > 1e-9:
            errors.append(f"{label}: learned_delay_ticks[{name!r}] does not match {delays_path.name}")
        if abs(ticks - expected_ticks) > tolerance:
            errors.append(f"{delays_path}: {name} learned {ticks} ticks, more than {tolerance} "
                          f"from the true {expected_ticks}")
    return errors


def validate(root: Path, profiles: list[str], replicas: list[int]) -> list[str]:
    errors: list[str] = []
    p3 = _config()
    expected_ticks = int(p3["blind_delay_ticks"])
    expected_spt = float(p3["seconds_per_tick"])
    tolerance = float(p3["blind_delay_tolerance_ticks"])
    for replica in replicas:
        rep_root = root / "dynamics_root" / f"rep{replica}"
        if not rep_root.is_dir():
            errors.append(f"missing replica root: {rep_root}")
            continue
        for profile in profiles:
            for bool_name, mode in (("true", "ql_true"), ("false", "ql_false")):
                manifest_path = rep_root / f"DYNAMICS_OK_{bool_name}_{profile}.json"
                results_path = rep_root / f"timebounded_results_{bool_name}_{profile}.csv"
                delays_path = rep_root / f"dynamics_delays_{bool_name}_{profile}.csv"
                if not manifest_path.is_file():
                    errors.append(f"missing gate manifest: {manifest_path}")
                    continue
                manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
                for key, expected in (
                    ("status", "ok"),
                    ("protocol_version", PROTOCOL),
                    ("energy_meter", ENERGY_METER),
                    ("profile", profile),
                    ("mode", mode),
                    ("replica", replica),
                    ("blind_delay_ticks", expected_ticks),
                    ("seconds_per_tick", expected_spt),
                ):
                    if manifest.get(key) != expected:
                        errors.append(f"{manifest_path}: {key}={manifest.get(key)!r}, "
                                      f"expected {expected!r}")
                for path in (results_path, delays_path):
                    if not path.is_file():
                        errors.append(f"missing data file: {path}")
                errors.extend(check_delay_fields(manifest, delays_path, expected_ticks,
                                                 tolerance, str(manifest_path)))
                if results_path.is_file():
                    with results_path.open(encoding="utf-8-sig", newline="") as handle:
                        rows = list(csv.DictReader(handle))
                    if not rows:
                        errors.append(f"{results_path}: no rows")
                    else:
                        required = {"tick_energy", "tick_span", "energy_meter",
                                    "energy_cost_wallclock_legacy"}
                        missing = required - set(rows[0])
                        if missing:
                            errors.append(f"{results_path}: missing v2 columns {sorted(missing)}")
                        elif any(row.get("energy_meter") != ENERGY_METER for row in rows):
                            errors.append(f"{results_path}: non-{ENERGY_METER} row")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--profiles", default="lab2_slow,lab3_slow")
    parser.add_argument("--replicas", default="1-10")
    args = parser.parse_args()
    profiles = [name.strip() for name in args.profiles.split(",") if name.strip()]
    if "-" in args.replicas and "," not in args.replicas:
        lo, hi = args.replicas.split("-", 1)
        replicas = list(range(int(lo), int(hi) + 1))
    else:
        replicas = [int(r) for r in args.replicas.split(",") if r.strip()]
    errors = validate(args.root, profiles, replicas)
    if errors:
        print("Phase-3 v2 archive validation FAILED:")
        for error in errors:
            print(f"- {error}")
        return 1
    print(f"Phase-3 v2 archive validation passed "
          f"({len(replicas)} replicas x {len(profiles)} profiles x 2 arms).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
