#!/usr/bin/env python3
"""Check every result archive against its pins file (schema ``thesis-pins-v1``).

Each archive under ``results/`` has one pins file ``results/pins/<campaign>.json``
that states the facts the archive must keep: the dispatch receipts of its runs
(``workflow_inputs.json``), the key values of its registered result tables, a
few JSON summaries, its own SHA-256 inventories, and how to run its reproducer.
This script holds no campaign facts itself; adding a campaign means adding one
pins file.

Usage:
  python analysis/check_pins.py                      # check all pins
  python analysis/check_pins.py --only phase2_v2_corrected
  python analysis/check_pins.py --run-reproducers    # + reproducers on tracked data
  python analysis/check_pins.py --run-reproducers --include-local
                                                     # + reproducers that need the
                                                     #   ignored raw data (skipped
                                                     #   with a message if absent)

Pins file keys (all paths are relative to the archive unless stated):
  schema          "thesis-pins-v1"
  campaign        file stem of the pins file
  archive         repository-relative folder of the archive
  role            "accepted" or "exploratory"
  phase, description   free text
  required_files  files that must exist
  receipts        {"file": "run_{run_id}/analysis/out/workflow_inputs.json",
                   "workflow_actual": repository-relative workflow file that
                   dispatched the runs (the receipts themselves may name another
                   workflow; the receipt value is pinned as it is),
                   "workflow_note": text,
                   "all_runs": {field: spec}, "runs": {run_id: {field: spec}}}
                  Every run_* folder of the archive must be listed in "runs".
  tables          [{"file", "key": [columns], "rows": [row keys],
                    "all_rows": {column: spec}, "row_values": {row key: {column: spec}}}]
                  A row key is the key columns joined with "|".
  json_files      [{"file", "values": {dotted.path: spec},
                    "lists": [{"path", "key", "rows", "all_rows", "row_values"}]}]
  inventories     {"files": [{"glob", "base"}], "lf_committed": [paths]}
                  Every listed file that is present must match its recorded
                  SHA-256 (and byte count, when recorded). By default only
                  git-tracked files are hashed; --include-local also hashes
                  present untracked files. "lf_committed" names files whose
                  committed bytes are the LF form of inventoried CRLF bytes:
                  they must contain no CR, and their CRLF form must match.
  reproducer      {"argv": [script, args...], "data": "tracked" | "local",
                   "requires": [globs that must match for local data],
                   "approx_seconds": number, "note": text} or null
  reproducer_note text explaining a null reproducer

Value specs: a plain JSON value must be equal (strictly, booleans are not
numbers); {"float": x, "abs_tol": t} must parse as a float within t of x
(default t = 1e-15); {"probability": true} must be finite and in (0, 1];
{"int_range": [a, b]} must equal the list a, a+1, ..., b. Values read from CSV
tables are strings, so a plain spec there is compared as a string.

Placeholders in reproducer arguments and "requires": {archive} (the archive
folder) and {results} (the results folder), both repository-relative.
"""

from __future__ import annotations

import argparse
import csv
import glob
import hashlib
import json
import math
import os
import subprocess
import sys
import time
from pathlib import Path, PurePosixPath
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "thesis-pins-v1"
DEFAULT_ABS_TOL = 1e-15
RESULTS_DIR = "results"
PINS_DIR = "pins"
EXPLORATORY_DIR = "exploratory"
# Corrected re-computations of archived tables. They are derived from the
# archives by scripts in analysis/, not archives themselves, so they carry no
# pins file; each subfolder has a README naming the command that writes it.
CORRECTED_TABLES_DIR = "_corrected_tables"
# Post-hoc descriptive tables written by the scripts in analysis/posthoc/.
# They are derived from the archives, not archives themselves, so they carry
# no pins file; results/posthoc/README.md names the commands that write them.
POSTHOC_DIR = "posthoc"
TOP_KEYS = {
    "schema", "campaign", "archive", "role", "phase", "description",
    "required_files", "receipts", "tables", "json_files", "inventories",
    "reproducer", "reproducer_note",
}
RECEIPT_KEYS = {"file", "workflow_actual", "workflow_note", "all_runs", "runs"}
TABLE_KEYS = {"file", "key", "rows", "all_rows", "row_values"}
JSON_KEYS = {"file", "values", "lists"}
LIST_KEYS = {"path", "key", "rows", "all_rows", "row_values"}
INVENTORY_KEYS = {"files", "lf_committed"}
REPRODUCER_KEYS = {"argv", "data", "requires", "approx_seconds", "note"}


class PinsError(ValueError):
    """A pins file is malformed."""


# ---------------------------------------------------------------- loading


def pins_dir(repo_root: Path) -> Path:
    return repo_root / RESULTS_DIR / PINS_DIR


def load_pins(repo_root: Path) -> list[dict[str, Any]]:
    """Load and validate every pins file, sorted by campaign name."""
    loaded = []
    for path in sorted(pins_dir(repo_root).glob("*.json")):
        pins = json.loads(path.read_text(encoding="utf-8"))
        validate_schema(pins, path.stem)
        loaded.append(pins)
    return loaded


def _require_keys(mapping: Any, allowed: set[str], where: str) -> None:
    if not isinstance(mapping, dict):
        raise PinsError(f"{where}: expected an object")
    unknown = set(mapping) - allowed
    if unknown:
        raise PinsError(f"{where}: unknown keys {sorted(unknown)}")


def validate_schema(pins: Any, stem: str) -> None:
    where = f"pins {stem}"
    _require_keys(pins, TOP_KEYS, where)
    if pins.get("schema") != SCHEMA:
        raise PinsError(f"{where}: schema must be {SCHEMA!r}")
    if pins.get("campaign") != stem:
        raise PinsError(f"{where}: campaign {pins.get('campaign')!r} differs from file name")
    if not isinstance(pins.get("archive"), str) or not pins["archive"]:
        raise PinsError(f"{where}: archive path missing")
    if pins.get("role") not in ("accepted", "exploratory"):
        raise PinsError(f"{where}: role must be 'accepted' or 'exploratory'")
    receipts = pins.get("receipts")
    if receipts is not None:
        _require_keys(receipts, RECEIPT_KEYS, f"{where} receipts")
        if not isinstance(receipts.get("runs"), dict) or not receipts["runs"]:
            raise PinsError(f"{where} receipts: 'runs' must be a non-empty object")
    for index, table in enumerate(pins.get("tables", [])):
        _require_keys(table, TABLE_KEYS, f"{where} tables[{index}]")
        if not isinstance(table.get("key"), list) or not table["key"]:
            raise PinsError(f"{where} tables[{index}]: 'key' must be a non-empty list")
    for index, item in enumerate(pins.get("json_files", [])):
        _require_keys(item, JSON_KEYS, f"{where} json_files[{index}]")
        for list_index, spec in enumerate(item.get("lists", [])):
            _require_keys(spec, LIST_KEYS, f"{where} json_files[{index}].lists[{list_index}]")
    if pins.get("inventories") is not None:
        _require_keys(pins["inventories"], INVENTORY_KEYS, f"{where} inventories")
    reproducer = pins.get("reproducer")
    if reproducer is not None:
        _require_keys(reproducer, REPRODUCER_KEYS, f"{where} reproducer")
        if reproducer.get("data") not in ("tracked", "local"):
            raise PinsError(f"{where} reproducer: data must be 'tracked' or 'local'")
        if not isinstance(reproducer.get("argv"), list) or not reproducer["argv"]:
            raise PinsError(f"{where} reproducer: argv must be a non-empty list")
    elif "reproducer" in pins and not pins.get("reproducer_note"):
        raise PinsError(f"{where}: a null reproducer needs a reproducer_note")


# ---------------------------------------------------------------- values


def _strict_equal(expected: Any, actual: Any) -> bool:
    if isinstance(expected, bool) or isinstance(actual, bool):
        return isinstance(expected, bool) and isinstance(actual, bool) and expected == actual
    if isinstance(expected, list):
        return (isinstance(actual, list) and len(actual) == len(expected)
                and all(_strict_equal(e, a) for e, a in zip(expected, actual)))
    if isinstance(expected, dict):
        return (isinstance(actual, dict) and set(actual) == set(expected)
                and all(_strict_equal(expected[k], actual[k]) for k in expected))
    return type(expected) is type(actual) and expected == actual


def _as_float(actual: Any) -> float | None:
    if isinstance(actual, bool):
        return None
    try:
        return float(actual)
    except (TypeError, ValueError):
        return None


def match_value(spec: Any, actual: Any) -> str | None:
    """Return None when ``actual`` satisfies ``spec``, else a short reason."""
    if isinstance(spec, dict) and "float" in spec:
        value = _as_float(actual)
        tol = float(spec.get("abs_tol", DEFAULT_ABS_TOL))
        if value is None or not math.isfinite(value):
            return f"not a finite number ({actual!r})"
        if not math.isclose(value, float(spec["float"]), rel_tol=0.0, abs_tol=tol):
            return f"{actual!r} != {spec['float']!r} (abs_tol {tol!r})"
        return None
    if isinstance(spec, dict) and spec.get("probability") is True:
        value = _as_float(actual)
        if value is None or not math.isfinite(value) or not 0.0 < value <= 1.0:
            return f"{actual!r} is not a finite value in (0, 1]"
        return None
    if isinstance(spec, dict) and "int_range" in spec:
        low, high = spec["int_range"]
        expected = list(range(int(low), int(high) + 1))
        if not _strict_equal(expected, actual):
            return f"{actual!r} != {low}..{high}"
        return None
    if not _strict_equal(spec, actual):
        return f"{actual!r} != {spec!r}"
    return None


def _check_fields(record: dict[str, Any], specs: dict[str, Any], where: str,
                  errors: list[str]) -> None:
    for field, spec in specs.items():
        if field not in record:
            errors.append(f"{where}: field {field!r} missing")
            continue
        reason = match_value(spec, record[field])
        if reason:
            errors.append(f"{where}: {field} {reason}")


def _check_rows(rows: list[dict[str, Any]], key: list[str], spec: dict[str, Any],
                where: str, errors: list[str]) -> None:
    indexed: dict[str, dict[str, Any]] = {}
    for row in rows:
        row_key = "|".join(str(row.get(column, "")) for column in key)
        if row_key in indexed:
            errors.append(f"{where}: duplicate row {row_key!r}")
        indexed[row_key] = row
    if "rows" in spec:
        expected = set(spec["rows"])
        if set(indexed) != expected:
            missing = sorted(expected - set(indexed))
            extra = sorted(set(indexed) - expected)
            errors.append(f"{where}: row set changed (missing {missing}, unexpected {extra})")
    for row_key, row in indexed.items():
        _check_fields(row, spec.get("all_rows", {}), f"{where} [{row_key}]", errors)
    for row_key, fields in spec.get("row_values", {}).items():
        if row_key not in indexed:
            errors.append(f"{where}: pinned row {row_key!r} missing")
            continue
        _check_fields(indexed[row_key], fields, f"{where} [{row_key}]", errors)


def _dotted(document: Any, path: str) -> tuple[bool, Any]:
    current = document
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return False, None
        current = current[part]
    return True, current


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


# ---------------------------------------------------------------- checks


def _rel(path: Path, repo_root: Path) -> str:
    try:
        return path.relative_to(repo_root).as_posix()
    except ValueError:
        return str(path)


def check_receipts(pins: dict[str, Any], archive: Path, repo_root: Path,
                   errors: list[str]) -> None:
    receipts = pins.get("receipts")
    if not receipts:
        return
    template = receipts.get("file", "run_{run_id}/analysis/out/workflow_inputs.json")
    actual_workflow = receipts.get("workflow_actual")
    if actual_workflow and not (repo_root / PurePosixPath(actual_workflow)).is_file():
        errors.append(f"{pins['campaign']}: workflow_actual {actual_workflow} does not exist")
    pinned_runs = set(receipts["runs"])
    present_runs = {path.name[len("run_"):] for path in archive.glob("run_*") if path.is_dir()}
    unpinned = sorted(present_runs - pinned_runs)
    if unpinned:
        errors.append(f"{pins['campaign']}: run folders without receipt pins: {unpinned}")
    for run_id, overrides in receipts["runs"].items():
        path = archive / PurePosixPath(template.format(run_id=run_id))
        where = _rel(path, repo_root)
        if not path.is_file():
            errors.append(f"missing receipt {where}")
            continue
        data = _read_json(path)
        if str(data.get("run_id")) != str(run_id):
            errors.append(f"{where}: run_id {data.get('run_id')!r} != {run_id}")
        specs = dict(receipts.get("all_runs", {}))
        specs.update(overrides or {})
        _check_fields(data, specs, where, errors)


def check_tables(pins: dict[str, Any], archive: Path, repo_root: Path,
                 errors: list[str]) -> None:
    for table in pins.get("tables", []):
        path = archive / PurePosixPath(table["file"])
        where = _rel(path, repo_root)
        if not path.is_file():
            errors.append(f"missing table {where}")
            continue
        _check_rows(_read_csv(path), table["key"], table, where, errors)


def check_json_files(pins: dict[str, Any], archive: Path, repo_root: Path,
                     errors: list[str]) -> None:
    for item in pins.get("json_files", []):
        path = archive / PurePosixPath(item["file"])
        where = _rel(path, repo_root)
        if not path.is_file():
            errors.append(f"missing JSON file {where}")
            continue
        document = _read_json(path)
        for dotted, spec in item.get("values", {}).items():
            found, value = _dotted(document, dotted)
            if not found:
                errors.append(f"{where}: {dotted} missing")
                continue
            reason = match_value(spec, value)
            if reason:
                errors.append(f"{where}: {dotted} {reason}")
        for spec in item.get("lists", []):
            found, rows = _dotted(document, spec["path"])
            if not found or not isinstance(rows, list):
                errors.append(f"{where}: list {spec['path']} missing")
                continue
            _check_rows([row if isinstance(row, dict) else {} for row in rows],
                        [spec["key"]], spec, f"{where} {spec['path']}", errors)


def _tracked_files(repo_root: Path, archive: Path) -> set[str] | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo_root), "ls-files", "-z", "--", _rel(archive, repo_root)],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.CalledProcessError):
        return None
    return {item for item in result.stdout.decode("utf-8").split("\0") if item}


def _sha256(path: Path, crlf: bool, cache: dict) -> tuple[str, int, bool]:
    """SHA-256, byte count and CR presence (of the CRLF form when ``crlf``)."""
    cache_key = (str(path), crlf)
    if cache_key not in cache:
        data = path.read_bytes()
        has_cr = b"\r" in data
        if crlf:
            data = data.replace(b"\n", b"\r\n")
        cache[cache_key] = (hashlib.sha256(data).hexdigest(), len(data), has_cr)
    return cache[cache_key]


def check_inventories(pins: dict[str, Any], archive: Path, repo_root: Path,
                      include_local: bool, errors: list[str]) -> dict[str, int]:
    counts = {"verified": 0, "skipped": 0}
    inventories = pins.get("inventories")
    if not inventories:
        return counts
    tracked = None if include_local else _tracked_files(repo_root, archive)
    counts["tracked_only"] = int(tracked is not None)
    lf_committed = {PurePosixPath(p).as_posix() for p in inventories.get("lf_committed", [])}
    seen_lf: set[str] = set()
    cache: dict = {}  # several inventories of one archive may list the same file
    repo_abs = repo_root.resolve()
    archive_abs = archive.resolve()
    for entry in inventories.get("files", []):
        matches = sorted(archive.glob(entry["glob"]))
        if not matches:
            errors.append(f"{pins['campaign']}: no inventory matches {entry['glob']!r}")
        for inventory in matches:
            base = (inventory.parent / entry.get("base", ".")).resolve()
            for row in _read_csv(inventory):
                rel_name = row.get("path") or row.get("file") or ""
                target = Path(os.path.normpath(base / PurePosixPath(rel_name)))
                repo_rel = _rel(target, repo_abs)
                # Listed files that are not tracked (default) or not present are
                # not hashed: the raw data of most archives is ignored by git.
                if (tracked is not None and repo_rel not in tracked) or not target.is_file():
                    counts["skipped"] += 1
                    continue
                archive_rel = _rel(target, archive_abs)
                is_lf = archive_rel in lf_committed
                digest, size, has_cr = _sha256(target, is_lf, cache)
                where = f"{_rel(inventory, repo_root)}: {rel_name}"
                if is_lf:
                    seen_lf.add(archive_rel)
                    if has_cr:
                        errors.append(f"{where}: listed as LF-committed but contains CR")
                if digest != row.get("sha256"):
                    errors.append(f"{where}: SHA-256 differs from the inventory")
                elif row.get("bytes") not in (None, "") and str(size) != row["bytes"]:
                    errors.append(f"{where}: byte count differs from the inventory")
                else:
                    counts["verified"] += 1
    for name in sorted(lf_committed - seen_lf):
        if (archive / PurePosixPath(name)).is_file():
            errors.append(f"{pins['campaign']}: lf_committed file {name} is in no inventory")
    return counts


def check_campaign(pins: dict[str, Any], repo_root: Path = REPO_ROOT,
                   include_local: bool = False) -> tuple[list[str], dict[str, int]]:
    errors: list[str] = []
    archive = repo_root / PurePosixPath(pins["archive"])
    if not archive.is_dir():
        return [f"{pins['campaign']}: archive folder {pins['archive']} is missing"], {}
    for name in pins.get("required_files", []):
        if not (archive / PurePosixPath(name)).is_file():
            errors.append(f"{pins['campaign']}: required file {name} is missing")
    check_receipts(pins, archive, repo_root, errors)
    check_tables(pins, archive, repo_root, errors)
    check_json_files(pins, archive, repo_root, errors)
    counts = check_inventories(pins, archive, repo_root, include_local, errors)
    return errors, counts


def check_layout(repo_root: Path, all_pins: list[dict[str, Any]]) -> list[str]:
    """Every archive folder under results/ (and results/exploratory/) needs pins."""
    errors: list[str] = []
    results = repo_root / RESULTS_DIR
    pinned_archives = {PurePosixPath(p["archive"]).as_posix() for p in all_pins}
    if not results.is_dir():
        return errors
    skip = (PINS_DIR, EXPLORATORY_DIR, CORRECTED_TABLES_DIR, POSTHOC_DIR)
    folders = [p for p in results.iterdir() if p.is_dir() and p.name not in skip]
    exploratory = results / EXPLORATORY_DIR
    if exploratory.is_dir():
        folders += [p for p in exploratory.iterdir() if p.is_dir()]
    for folder in sorted(folders):
        rel = _rel(folder, repo_root)
        if rel not in pinned_archives:
            errors.append(f"{rel}: archive folder has no pins file in {RESULTS_DIR}/{PINS_DIR}/")
    return errors


# ---------------------------------------------------------------- reproducers


def _expand(text: str, pins: dict[str, Any]) -> str:
    return text.replace("{archive}", pins["archive"]).replace("{results}", RESULTS_DIR)


def _missing_requirements(pins: dict[str, Any], repo_root: Path) -> list[str]:
    missing = []
    for pattern in pins["reproducer"].get("requires", []):
        expanded = _expand(pattern, pins)
        if not glob.glob(str(repo_root / expanded)):
            missing.append(expanded)
    return missing


def run_reproducers(all_pins: list[dict[str, Any]], repo_root: Path,
                    include_local: bool) -> int:
    failures = 0
    for pins in all_pins:
        reproducer = pins.get("reproducer")
        name = pins["campaign"]
        if reproducer is None:
            print(f"[reproducer] {name}: none ({pins.get('reproducer_note', 'no reproducer')})")
            continue
        if reproducer["data"] == "local" and not include_local:
            print(f"[reproducer] {name}: needs local raw data; not run (use --include-local)")
            continue
        missing = _missing_requirements(pins, repo_root)
        if missing:
            if reproducer["data"] == "tracked":
                print(f"[reproducer] {name}: FAILED, tracked inputs missing: {missing}")
                failures += 1
            else:
                print(f"[reproducer] {name}: SKIPPED, local raw data absent: {missing}")
            continue
        argv = [sys.executable] + [_expand(arg, pins) for arg in reproducer["argv"]]
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        print(f"[reproducer] {name}: running {' '.join(argv[1:])}", flush=True)
        started = time.monotonic()
        result = subprocess.run(argv, cwd=repo_root, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT)
        elapsed = time.monotonic() - started
        output = result.stdout.decode("utf-8", errors="replace").strip().splitlines()
        tail = output[-1] if output else ""
        if result.returncode == 0:
            print(f"[reproducer] {name}: OK in {elapsed:.1f} s ({tail})")
        else:
            failures += 1
            print(f"[reproducer] {name}: FAILED (exit {result.returncode}) in {elapsed:.1f} s")
            for line in output[-20:]:
                print(f"    {line}")
    return failures


# ---------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--only", action="append", default=[],
                        help="check only this campaign (repeatable)")
    parser.add_argument("--run-reproducers", action="store_true",
                        help="also run the reproducers that work on tracked files")
    parser.add_argument("--include-local", action="store_true",
                        help="also run reproducers that need ignored local raw data, and "
                             "hash present untracked files listed in the inventories")
    args = parser.parse_args(argv)
    repo_root = args.repo_root.resolve()

    try:
        all_pins = load_pins(repo_root)
    except (PinsError, json.JSONDecodeError) as exc:
        print(f"PINS CHECK FAILED - malformed pins file: {exc}")
        return 1
    if not all_pins:
        print(f"PINS CHECK FAILED - no pins files in {RESULTS_DIR}/{PINS_DIR}/")
        return 1
    errors = check_layout(repo_root, all_pins)
    selected = [p for p in all_pins if not args.only or p["campaign"] in args.only]
    unknown = sorted(set(args.only) - {p["campaign"] for p in all_pins})
    if unknown:
        errors.append(f"--only names unknown campaigns: {unknown}")
    for pins in selected:
        campaign_errors, counts = check_campaign(pins, repo_root, args.include_local)
        errors.extend(campaign_errors)
        status = "FAIL" if campaign_errors else "ok"
        detail = ""
        if counts.get("verified") or counts.get("skipped"):
            scope = "tracked" if counts.get("tracked_only") else "present"
            detail = (f" (inventory: {counts['verified']} {scope} files verified, "
                      f"{counts['skipped']} listed files not {scope})")
        print(f"[pins] {pins['campaign']}: {status}{detail}")
    if errors:
        print(f"PINS CHECK FAILED - {len(errors)} violation(s):")
        for error in errors:
            print(f"  {error}")
        return 1
    print(f"pins check OK ({len(selected)} archive(s))")
    if args.run_reproducers:
        failures = run_reproducers(selected, repo_root, args.include_local)
        if failures:
            print(f"REPRODUCERS FAILED - {failures} failure(s)")
            return 1
        print("reproducers OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
