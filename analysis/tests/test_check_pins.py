"""Tests for analysis/check_pins.py.

Two parts:
- synthetic archives in a temporary folder exercise each kind of pin, the
  layout guard, the inventory check and the reproducer selection;
- a mutation test over the real pins files copies each archive's pinned files
  into a temporary folder, changes one pinned fact at a time, and requires the
  checker to report every change.
"""

from __future__ import annotations

import copy
import csv
import hashlib
import io
import json
import shutil
import sys
from contextlib import redirect_stdout
from pathlib import Path, PurePosixPath

import pytest

from analysis import check_pins as cp

ROOT = Path(__file__).resolve().parents[2]


# ------------------------------------------------------------------ helpers


def _write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(data, indent=2) + "\n").encode("utf-8"))


def _demo_repo(tmp_path: Path) -> tuple[Path, dict]:
    """A repository with one pinned archive results/demo/ that passes."""
    repo = tmp_path / "repo"
    archive = repo / "results" / "demo"
    _write_json(archive / "run_7/analysis/out/workflow_inputs.json", {
        "run_id": 7, "run_mode": "mode_a", "head_sha": "abc", "seeds": [1, 2, 3],
        "publish_results": True, "workflow": ".github/workflows/phase1.yml",
    })
    _write_csv(archive / "analysis/registered/family.csv", [
        {"cell": "a", "arm": "x", "mean": "0.1", "p": "0.5", "cat": "adds"},
        {"cell": "b", "arm": "x", "mean": "-2.25", "p": "1.0", "cat": "null"},
    ])
    _write_json(archive / "analysis/summary.json", {
        "ok": True, "share": 0.6981981981981937,
        "runs": [{"run_id": 7, "cells": 120}],
    })
    (archive / "CAMPAIGN_MANIFEST.md").write_bytes(b"demo\n")
    crlf_text = b"a,b\r\n1,2\r\n"
    (archive / "run_7/analysis/out/table.csv").write_bytes(crlf_text.replace(b"\r\n", b"\n"))
    manifest = archive / "CAMPAIGN_MANIFEST.md"
    _write_csv(archive / "SHA256SUMS.csv", [
        {"path": "CAMPAIGN_MANIFEST.md", "bytes": str(manifest.stat().st_size),
         "sha256": hashlib.sha256(manifest.read_bytes()).hexdigest()},
        {"path": "run_7/analysis/out/table.csv", "bytes": str(len(crlf_text)),
         "sha256": hashlib.sha256(crlf_text).hexdigest()},
        {"path": "run_7/raw/absent.csv", "bytes": "1", "sha256": "0" * 64},
    ])
    (repo / ".github/workflows").mkdir(parents=True)
    (repo / ".github/workflows/phase2.yml").write_text("name: x\n", encoding="utf-8")
    pins = {
        "schema": cp.SCHEMA, "campaign": "demo", "archive": "results/demo", "role": "accepted",
        "required_files": ["CAMPAIGN_MANIFEST.md"],
        "receipts": {
            "file": "run_{run_id}/analysis/out/workflow_inputs.json",
            "workflow_actual": ".github/workflows/phase2.yml",
            "all_runs": {"head_sha": "abc", "publish_results": True,
                         "workflow": ".github/workflows/phase1.yml"},
            "runs": {"7": {"run_mode": "mode_a", "seeds": {"int_range": [1, 3]}}},
        },
        "tables": [{
            "file": "analysis/registered/family.csv", "key": ["cell", "arm"],
            "rows": ["a|x", "b|x"], "all_rows": {"p": {"probability": True}},
            "row_values": {"a|x": {"mean": {"float": 0.1}, "cat": "adds"},
                           "b|x": {"mean": {"float": -2.25}}},
        }],
        "json_files": [{
            "file": "analysis/summary.json",
            "values": {"ok": True, "share": {"float": 0.6981981981981937}},
            "lists": [{"path": "runs", "key": "run_id", "rows": ["7"],
                       "all_rows": {"cells": 120}}],
        }],
        "inventories": {"files": [{"glob": "SHA256SUMS.csv", "base": "."}],
                        "lf_committed": ["run_7/analysis/out/table.csv"]},
        "reproducer": {"argv": ["tools/ok.py", "{archive}"], "data": "tracked"},
    }
    _write_json(repo / "results/pins/demo.json", pins)
    return repo, pins


def _check(repo: Path, pins: dict) -> list[str]:
    errors, _ = cp.check_campaign(pins, repo, include_local=True)
    return errors


# ------------------------------------------------------------------ synthetic


def test_demo_archive_passes(tmp_path):
    repo, pins = _demo_repo(tmp_path)
    assert _check(repo, pins) == []
    assert cp.check_layout(repo, cp.load_pins(repo)) == []


def test_unpinned_results_folder_fails_layout(tmp_path):
    repo, _ = _demo_repo(tmp_path)
    (repo / "results" / "new_campaign").mkdir()
    (repo / "results" / "exploratory" / "side_study").mkdir(parents=True)
    errors = cp.check_layout(repo, cp.load_pins(repo))
    assert any("results/new_campaign" in e for e in errors)
    assert any("results/exploratory/side_study" in e for e in errors)


def test_corrected_tables_folder_needs_no_pins(tmp_path):
    repo, _ = _demo_repo(tmp_path)
    (repo / "results" / "_corrected_tables" / "demo").mkdir(parents=True)
    assert cp.check_layout(repo, cp.load_pins(repo)) == []


def test_main_fails_on_unpinned_folder(tmp_path):
    repo, _ = _demo_repo(tmp_path)
    (repo / "results" / "new_campaign").mkdir()
    with redirect_stdout(io.StringIO()):
        assert cp.main(["--repo-root", str(repo)]) == 1


def test_float_tolerance_is_1e_15(tmp_path):
    assert cp.match_value({"float": 0.0037}, "0.0037") is None
    assert cp.match_value({"float": 0.0037}, "0.0037000000001") is not None
    assert cp.match_value({"float": 3512.0}, "3512.0000000000005") is not None
    assert cp.match_value({"float": 0.1}, "nan") is not None


def test_plain_values_are_strict():
    assert cp.match_value(True, 1) is not None
    assert cp.match_value(120, True) is not None
    assert cp.match_value("20", 20) is not None
    assert cp.match_value([3000], [3000]) is None
    assert cp.match_value({"int_range": [1, 3]}, [1, 2, 3]) is None
    assert cp.match_value({"int_range": [1, 3]}, [1, 2]) is not None
    assert cp.match_value({"probability": True}, "0") is not None
    assert cp.match_value({"probability": True}, "1.0") is None
    assert cp.match_value({"probability": True}, "1.5") is not None


def test_receipt_deviations_are_reported(tmp_path):
    repo, pins = _demo_repo(tmp_path)
    receipt = repo / "results/demo/run_7/analysis/out/workflow_inputs.json"
    original = json.loads(receipt.read_text(encoding="utf-8"))
    for field, value in (("run_id", 8), ("run_mode", "mode_b"), ("head_sha", "abd"),
                         ("seeds", [1, 2]), ("publish_results", False),
                         ("workflow", ".github/workflows/phase2.yml")):
        _write_json(receipt, dict(original, **{field: value}))
        assert _check(repo, pins), field
    receipt.unlink()
    assert any("missing receipt" in e for e in _check(repo, pins))


def test_unpinned_run_folder_is_reported(tmp_path):
    repo, pins = _demo_repo(tmp_path)
    (repo / "results/demo/run_8").mkdir()
    assert any("without receipt pins" in e and "'8'" in e for e in _check(repo, pins))


def test_missing_workflow_actual_is_reported(tmp_path):
    repo, pins = _demo_repo(tmp_path)
    (repo / ".github/workflows/phase2.yml").unlink()
    assert any("workflow_actual" in e for e in _check(repo, pins))


def test_table_and_json_deviations_are_reported(tmp_path):
    repo, pins = _demo_repo(tmp_path)
    table = repo / "results/demo/analysis/registered/family.csv"
    rows = list(csv.DictReader(table.open(encoding="utf-8", newline="")))
    for change in ({"mean": "0.10000000000001"}, {"cat": "null"}, {"p": "0"}, {"cell": "c"}):
        _write_csv(table, [dict(rows[0], **change), rows[1]])
        assert _check(repo, pins), change
    _write_csv(table, rows + [rows[0]])
    assert any("duplicate" in e for e in _check(repo, pins))
    _write_csv(table, rows)
    summary = repo / "results/demo/analysis/summary.json"
    data = json.loads(summary.read_text(encoding="utf-8"))
    for changed in (dict(data, ok=False), dict(data, share=0.69819819819819),
                    dict(data, runs=[{"run_id": 7, "cells": 121}]), dict(data, runs=[])):
        _write_json(summary, changed)
        assert _check(repo, pins)


def test_inventory_detects_changed_bytes_and_crlf(tmp_path):
    repo, pins = _demo_repo(tmp_path)
    manifest = repo / "results/demo/CAMPAIGN_MANIFEST.md"
    manifest.write_bytes(b"demo\r\n")
    assert any("CAMPAIGN_MANIFEST.md" in e for e in _check(repo, pins))
    manifest.write_bytes(b"demo\n")
    table = repo / "results/demo/run_7/analysis/out/table.csv"
    table.write_bytes(b"a,b\r\n1,2\r\n")
    assert any("contains CR" in e for e in _check(repo, pins))


def test_malformed_pins_are_rejected(tmp_path):
    repo, pins = _demo_repo(tmp_path)
    bad = copy.deepcopy(pins)
    bad["tabels"] = []
    with pytest.raises(cp.PinsError):
        cp.validate_schema(bad, "demo")
    with pytest.raises(cp.PinsError):
        cp.validate_schema(dict(pins, campaign="other"), "demo")
    with pytest.raises(cp.PinsError):
        cp.validate_schema(dict(pins, reproducer=None), "demo")


def test_reproducer_selection(tmp_path, capsys):
    repo, pins = _demo_repo(tmp_path)
    tools = repo / "tools"
    tools.mkdir()
    (tools / "ok.py").write_text("import sys\nprint('reproduced', sys.argv[1])\n", encoding="utf-8")
    (tools / "fail.py").write_text("raise SystemExit(3)\n", encoding="utf-8")
    local = dict(copy.deepcopy(pins), campaign="local_demo")
    local["reproducer"] = {"argv": ["tools/fail.py"], "data": "local",
                           "requires": ["{archive}/run_*/raw/*.csv"]}
    assert cp.run_reproducers([pins, local], repo, include_local=False) == 0
    out = capsys.readouterr().out
    assert "demo: OK" in out and "reproduced results/demo" in out
    assert "local_demo: needs local raw data; not run" in out
    assert cp.run_reproducers([local], repo, include_local=True) == 0
    assert "SKIPPED, local raw data absent" in capsys.readouterr().out
    (repo / "results/demo/run_7/raw").mkdir()
    (repo / "results/demo/run_7/raw/x.csv").write_text("1\n", encoding="utf-8")
    assert cp.run_reproducers([local], repo, include_local=True) == 1
    assert "FAILED (exit 3)" in capsys.readouterr().out


# ------------------------------------------------------------------ real pins: mutations


def _real_pins() -> list[dict]:
    return cp.load_pins(ROOT)


def _pinned_files(pins: dict) -> list[str]:
    files = list(pins.get("required_files", []))
    receipts = pins.get("receipts")
    if receipts:
        files += [receipts["file"].format(run_id=run_id) for run_id in receipts["runs"]]
    files += [table["file"] for table in pins.get("tables", [])]
    files += [item["file"] for item in pins.get("json_files", [])]
    return sorted(set(files))


def copy_pinned(pins: dict, dest_repo: Path, source_repo: Path = ROOT) -> dict:
    """Copy the pinned files of one archive into dest_repo; return pins without inventories."""
    for rel in _pinned_files(pins):
        source = source_repo / PurePosixPath(pins["archive"]) / PurePosixPath(rel)
        target = dest_repo / PurePosixPath(pins["archive"]) / PurePosixPath(rel)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    workflow = (pins.get("receipts") or {}).get("workflow_actual")
    if workflow:
        target = dest_repo / PurePosixPath(workflow)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_repo / PurePosixPath(workflow), target)
    reduced = copy.deepcopy(pins)
    reduced.pop("inventories", None)
    return reduced


def _changed(value):
    """A value of the same kind that differs from ``value``."""
    if isinstance(value, bool):
        return not value
    if isinstance(value, int):
        return value + 1
    if isinstance(value, float):
        return value + max(abs(value) * 1e-12, 1e-12)
    if isinstance(value, str):
        try:
            number = float(value)
        except ValueError:
            return value + "_changed"
        return repr(number + max(abs(number) * 1e-12, 1e-12))
    if isinstance(value, list):
        return value[:-1] if value else ["changed"]
    if isinstance(value, dict):
        return {}
    return "changed"


def _spec_mutation(spec, value):
    if isinstance(spec, dict) and spec.get("probability") is True:
        return "0"
    if isinstance(spec, dict) and "int_range" in spec:
        return list(value)[:-1]
    return _changed(value)


def mutations(pins: dict, repo: Path):
    """Yield (label, apply) pairs; apply(repo) changes one pinned fact on disk."""
    archive = repo / PurePosixPath(pins["archive"])
    receipts = pins.get("receipts")
    if receipts:
        for run_id, overrides in receipts["runs"].items():
            path = archive / PurePosixPath(receipts["file"].format(run_id=run_id))
            specs = dict(receipts.get("all_runs", {}))
            specs.update(overrides or {})
            for field in ["run_id", *specs]:
                def apply(path=path, field=field, spec=specs.get(field)):
                    data = json.loads(path.read_text(encoding="utf-8"))
                    data[field] = _spec_mutation(spec, data[field])
                    path.write_text(json.dumps(data), encoding="utf-8")
                yield f"receipt {run_id} {field}", apply
            yield f"receipt {run_id} missing", (lambda path=path: path.unlink())
    for table in pins.get("tables", []):
        path = archive / PurePosixPath(table["file"])

        def edit_rows(edit, path=path):
            with path.open(encoding="utf-8", newline="") as handle:
                reader = csv.DictReader(handle)
                fields, rows = reader.fieldnames, list(reader)
            rows = edit(rows)
            with path.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
                writer.writeheader()
                writer.writerows(rows)

        def key_of(row, key=table["key"]):
            return "|".join(row[c] for c in key)

        yield f"{table['file']} row dropped", (lambda e=edit_rows: e(lambda rows: rows[:-1]))
        for field, spec in table.get("all_rows", {}).items():
            def apply(e=edit_rows, field=field, spec=spec):
                def edit(rows):
                    rows[0][field] = _spec_mutation(spec, rows[0][field])
                    return rows
                e(edit)
            yield f"{table['file']} all_rows {field}", apply
        for row_key, fields in table.get("row_values", {}).items():
            for field, spec in fields.items():
                def apply(e=edit_rows, row_key=row_key, field=field, spec=spec, key_of=key_of):
                    def edit(rows):
                        for row in rows:
                            if key_of(row) == row_key:
                                row[field] = _spec_mutation(spec, row[field])
                        return rows
                    e(edit)
                yield f"{table['file']} [{row_key}] {field}", apply
        yield f"{table['file']} missing", (lambda path=path: path.unlink())
    for item in pins.get("json_files", []):
        path = archive / PurePosixPath(item["file"])
        for dotted, spec in item.get("values", {}).items():
            def apply(path=path, dotted=dotted, spec=spec):
                data = json.loads(path.read_text(encoding="utf-8"))
                parts = dotted.split(".")
                target = data
                for part in parts[:-1]:
                    target = target[part]
                target[parts[-1]] = _spec_mutation(spec, target[parts[-1]])
                path.write_text(json.dumps(data), encoding="utf-8")
            yield f"{item['file']} {dotted}", apply
        for spec_list in item.get("lists", []):
            def drop(path=path, spec_list=spec_list):
                data = json.loads(path.read_text(encoding="utf-8"))
                data[spec_list["path"]] = data[spec_list["path"]][:-1]
                path.write_text(json.dumps(data), encoding="utf-8")
            yield f"{item['file']} {spec_list['path']} row dropped", drop
            fields = dict(spec_list.get("all_rows", {}))
            for row_fields in spec_list.get("row_values", {}).values():
                fields.update(row_fields)
            for field, spec in fields.items():
                def apply(path=path, spec_list=spec_list, field=field, spec=spec):
                    data = json.loads(path.read_text(encoding="utf-8"))
                    row = data[spec_list["path"]][0]
                    row[field] = _spec_mutation(spec, row[field])
                    path.write_text(json.dumps(data), encoding="utf-8")
                yield f"{item['file']} {spec_list['path']}[0] {field}", apply
    for name in pins.get("required_files", []):
        target = archive / PurePosixPath(name)
        yield f"required {name} missing", (lambda target=target: target.unlink())


def snapshot(repo: Path) -> dict[Path, bytes]:
    return {p: p.read_bytes() for p in repo.rglob("*") if p.is_file()}


def restore(repo: Path, saved: dict[Path, bytes]) -> None:
    for path, data in saved.items():
        if not path.exists() or path.read_bytes() != data:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)


@pytest.mark.parametrize("campaign", [p["campaign"] for p in _real_pins()])
def test_every_pinned_fact_of_real_archives_is_checked(tmp_path, campaign):
    pins = next(p for p in _real_pins() if p["campaign"] == campaign)
    repo = tmp_path / "repo"
    reduced = copy_pinned(pins, repo)
    assert _check(repo, reduced) == [], "unchanged copy must pass"
    saved = snapshot(repo)
    labels = []
    for label, apply in mutations(reduced, repo):
        apply()
        errors = _check(repo, reduced)
        assert errors, f"{campaign}: mutation not detected: {label}"
        labels.append(label)
        restore(repo, saved)
    assert labels, campaign


def test_real_pins_pass_and_cover_every_results_folder():
    all_pins = _real_pins()
    assert cp.check_layout(ROOT, all_pins) == []
    for pins in all_pins:
        assert (ROOT / PurePosixPath(pins["archive"])).is_dir(), pins["campaign"]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
