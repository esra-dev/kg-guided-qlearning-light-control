#!/usr/bin/env python3
"""Compare the result files of a fresh GitHub Actions run with the archived results.

Purpose
-------
The archives under ``results/`` were produced by GitHub Actions runs made months ago.
This script checks that a new run of the same workflow (same inputs, same code) still
produces the same result files.  It compares every result data file (``.csv``, ``.json``,
``.jsonl``, ``.ttl``) of the new run with its counterpart in the archive.  Log files are
not compared.  It reads only; it never writes into an archive or into the run folder.

Inputs
------
``--run-dir``      A folder with the downloaded artifacts of ONE run: one sub-folder per
                   artifact (for example ``train-lab2-stereo-false-seed-1``), as written by
                   ``gh run download <run id> -n <artifact> -D <run-dir>/<artifact>``.
                   ``--download <run id> --repo <owner/name>`` does this download itself
                   through the ``gh`` command line tool (per-cell artifacts only; the
                   ``build-classes`` and ``*-consolidated`` artifacts are skipped).
``--phase``        ``phase1``, ``phase2``, ``phase3`` or ``phase4``.
``--archive``      A run folder of an archive, for example
                   ``results/phase1_v2_corrected/run_29848584965``.  Repeat the option for
                   several archives; the first archive that holds a counterpart of a file is
                   the primary one, the others are only reported as "alt" in compare.csv.
                   For Phase 2, ``--archive lab2_f1bdead=<folder>`` pins the fault cells of
                   that fault lab (``adapt-<lab>-ql_*-seed-*``) to their own archive run;
                   plain ``--archive`` folders serve all remaining cells (clean cells).
``--archive-root`` The folder that plays the role of the repository root when archive
                   paths are printed.  Default: the repository this script is in.  Use it
                   when the archives are in another checkout (the printed paths stay
                   ``results/<campaign>/run_<id>/...``).
``--out``          Output folder (created).
``--run-id``       Optional run id, used only in the heading of summary.md.

Outputs (no absolute path and no machine or user name is ever written)
-------
``compare.csv``          One row per file: run-relative name, kind, status, detail,
                         repository-relative archive path (forward slashes), run SHA-256.
``summary.md``           Totals, per file kind, per cell, one example per kind of difference.
``phase3_compare.csv``   Phase 3 only: one row per goal and per delay-table row.

Status of one file
------------------
IDENTICAL                          Same bytes.
DIFFERENT-LINE-ENDINGS-ONLY        Same after turning CRLF into LF.  Archives were written
                                   on Linux; a Windows checkout or re-download may differ
                                   only in line endings.  Every comparison below is made
                                   after that normalisation.
DIFFERENT-TRAILING-NEWLINE-ONLY    Same except for the newline at the end of the file.
DIFFERENT-EXPECTED-FIELDS-ONLY     Differs only in an allowed field: the column
                                   ``LegacyWallClockTotalEnergyCost`` of a
                                   ``benchmark_results_*.csv`` (a legacy column whose name
                                   says it depends on the wall clock; the matching step
                                   logs are compared separately), the receipt key
                                   ``timestamp``, or the address in the ``@prefix elem:``
                                   line of a learned Turtle file that uses no ``elem:``
                                   term (since the vocabulary correction the code writes
                                   the address the published Elementary ontology declares;
                                   the archives carry the earlier one; the triples of such
                                   a file are the same).
DIFFERENT-RECEIPT-ADDED-KEYS-ONLY  A receipt (``TRAINING_OK.json``, ``ADAPT_OK_*.json``)
                                   in which every key present on both sides has the same
                                   value (``timestamp`` excepted) and the new run has
                                   additional keys that newer code writes.  A key that
                                   exists only in the archive receipt is a real difference.
DIFFERENT-DATA                     Any other difference: a changed value, a changed row
                                   count or header, an extra or missing line.  REAL.
MISSING-IN-ARCHIVE                 The run has a file for which the archive holds no
                                   counterpart.  Not a difference of data; reported.
MISSING-IN-RUN                     The archive cell folder holds a file of the same cell
                                   that the run did not produce.  REAL.

Exit code: 0 if no file has the status DIFFERENT-DATA or MISSING-IN-RUN (and at least one
file was compared); 1 otherwise; 2 for unusable arguments.

Phase 3
-------
Phase 3 measures real delays of the simulated devices, so its files are not
byte-identical.  The script compares per goal: the deadline flag ``met`` (must be equal
in every goal), and the chosen device.  The chosen device is compared with the archive
replica of the same number; a different choice is accepted only if the archive itself
chose that device in at least one of its replicas for that goal (the archive's own spread
between replicas).  Learned delay values are reported, not judged.  Exit code 1 if a
deadline flag differs, a choice is outside the archive's spread, or a cell has no
counterpart.

Standard library only.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_EXT = {".csv", ".json", ".jsonl", ".ttl"}
SKIP_ARTIFACTS = {"build-classes"}

ALLOWED_CSV_COLUMNS = {"LegacyWallClockTotalEnergyCost"}
ALLOWED_RECEIPT_KEYS = {"timestamp"}
# Prefix declaration of the Elementary ontology in a Turtle file, with either address.
ELEM_PREFIX_LINE = re.compile(r"^@prefix\s+elem:\s+<http://(?:www\.)?w3id\.org/elementary#>\s*\.\s*$")

IDENTICAL = "IDENTICAL"
LINE_ENDINGS = "DIFFERENT-LINE-ENDINGS-ONLY"
TRAILING_NEWLINE = "DIFFERENT-TRAILING-NEWLINE-ONLY"
EXPECTED = "DIFFERENT-EXPECTED-FIELDS-ONLY"
ADDED_KEYS = "DIFFERENT-RECEIPT-ADDED-KEYS-ONLY"
DATA = "DIFFERENT-DATA"
MISSING_ARCHIVE = "MISSING-IN-ARCHIVE"
MISSING_RUN = "MISSING-IN-RUN"
FAILING = {DATA, MISSING_RUN}
STATUS_ORDER = [IDENTICAL, LINE_ENDINGS, TRAILING_NEWLINE, EXPECTED, ADDED_KEYS,
                DATA, MISSING_ARCHIVE, MISSING_RUN]


# --------------------------------------------------------------------------- helpers
def sha256(path: Path) -> str:
    """SHA-256 of a file, read in chunks."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def display_path(path: Path, archive_root: Path) -> str:
    """Repository-relative path with forward slashes.

    Relative to ``archive_root``; a path outside it is shortened to the part from the
    last ``results`` folder on, or to its last three components, never an absolute path.
    """
    p = path.resolve()
    try:
        return p.relative_to(archive_root.resolve()).as_posix()
    except ValueError:
        parts = p.parts
        if "results" in parts:
            i = len(parts) - 1 - parts[::-1].index("results")
            return "/".join(parts[i:])
        return "/".join(parts[-3:])


def normalise(data: bytes) -> bytes:
    """Turn CRLF into LF, so that only line endings are ignored, nothing else."""
    return data.replace(b"\r\n", b"\n")


def seed_of(artifact: str):
    """Seed or replica number at the end of an artifact name, or None."""
    m = re.search(r"(?:seed|rep)-(\d+)$", artifact)
    return int(m.group(1)) if m else None


def kind_of(rel: str) -> str:
    """Kind of a result file, from its file name."""
    b = rel.rsplit("/", 1)[-1]
    for prefix, kind in (("TRAINING_OK", "receipt"), ("ADAPT_OK", "receipt"),
                         ("DYNAMICS_OK", "receipt"), ("qtable_", "qtable"),
                         ("metrics_adapted", "adapt-metrics"), ("metrics_", "training-metrics"),
                         ("recovery_", "recovery"), ("first_goal", "first-goal"),
                         ("coverage_", "coverage"), ("iv_stats", "iv-stats"),
                         ("benchmark_results", "benchmark-results"),
                         ("bench_step_log", "bench-step-log"), ("trace_bench", "trace")):
        if b.startswith(prefix):
            return kind
    return "learned-ttl" if b.endswith(".ttl") else "other-data"


def is_receipt(rel: str) -> bool:
    """True for TRAINING_OK*.json, ADAPT_OK_*.json and DYNAMICS_OK*.json receipts."""
    return kind_of(rel) == "receipt" and rel.endswith(".json")


# --------------------------------------------------------------------------- archive lookup
def candidates(root: Path, artifact: str, rel: str) -> list[Path]:
    """Archive paths, in order of preference, that may hold the counterpart of ``rel``.

    The archives keep the artifact files in different layouts (``_artifacts/<kind>/``,
    ``adapt_logs/``, ``recovery_root/seed<N>/``, ``dynamics_root/rep<N>/``,
    ``benchmark/results_seed<N>/<lab>/...``); all of them are tried.
    """
    n = seed_of(artifact)
    base = rel.rsplit("/", 1)[-1]
    c = [root / "_artifacts" / kind / artifact / rel for kind in ("clean", "train", "bench", "adapt")]
    c.append(root / "_artifacts" / artifact / rel)
    c.append(root / "adapt_logs" / artifact / rel)
    if n is not None:
        c.append(root / "recovery_root" / f"seed{n}" / base)
        c.append(root / "dynamics_root" / f"rep{n}" / base)
        if rel.startswith("benchmark/results/"):
            c.append(root / "benchmark" / f"results_seed{n}" / rel[len("benchmark/results/"):])
        else:
            m = re.match(r"(train|bench)-(lab\w+?)-(?:stereo-(true|false)|(ql_true|ql_false|rule_based))-seed-\d+$", artifact)
            if m:
                lab_dir = root / "benchmark" / f"results_seed{n}" / m.group(2)
                if m.group(1) == "train":
                    c += [lab_dir / f"training_stereo_{m.group(3)}" / base,
                          lab_dir / "ql_true" / base, lab_dir / "ql_false" / base]
                else:
                    c.append(lab_dir / m.group(4) / base)
    return c


def collect_run_files(run_dir: Path):
    """All result data files of the run: (artifact, run-relative name, path), sorted."""
    out = []
    for ad in sorted(p for p in run_dir.iterdir() if p.is_dir()):
        for f in sorted(ad.rglob("*")):
            if f.is_file() and f.suffix.lower() in DATA_EXT:
                out.append((ad.name, f.relative_to(ad).as_posix(), f))
    return out


# --------------------------------------------------------------------------- classification
def flatten(obj, prefix: str = "") -> dict:
    """Flatten nested JSON objects to {dotted.key: canonical JSON text of the value}."""
    out = {}
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.update(flatten(v, f"{prefix}.{k}" if prefix else k))
    else:
        out[prefix] = json.dumps(obj, sort_keys=True)
    return out


def _load_json(data: bytes):
    return json.loads(data.decode("utf-8-sig"))


def classify_receipt(na: bytes, nb: bytes):
    """Key-by-key comparison of a run receipt (``na``) with the archive receipt (``nb``)."""
    try:
        ja, jb = flatten(_load_json(na)), flatten(_load_json(nb))
    except ValueError:
        return DATA, "receipt is not valid JSON"
    only_run = sorted(set(ja) - set(jb))
    only_arch = sorted(set(jb) - set(ja))
    shared = sorted(set(ja) & set(jb))
    changed = [k for k in shared if ja[k] != jb[k] and k not in ALLOWED_RECEIPT_KEYS]
    stamp = [k for k in shared if ja[k] != jb[k] and k in ALLOWED_RECEIPT_KEYS]
    if changed or only_arch:
        parts = []
        if changed:
            parts.append("value differs: " + "; ".join(
                f"{k} run={ja[k][:60]} archive={jb[k][:60]}" for k in changed[:6]))
        if only_arch:
            parts.append("keys only in archive: " + ",".join(only_arch))
        return DATA, " | ".join(parts)
    n_shared = len([k for k in shared if k not in ALLOWED_RECEIPT_KEYS])
    if only_run:
        extra = "; timestamp differs" if stamp else ""
        return ADDED_KEYS, (f"same values in all {n_shared} shared keys{extra}; "
                            f"{len(only_run)} added keys: {','.join(only_run)}")
    return EXPECTED, "receipt timestamp only" if stamp else "receipt differs only in an allowed key"


def classify_json(na: bytes, nb: bytes):
    """Comparison of a non-receipt JSON file: any different key or value is real."""
    try:
        ja, jb = flatten(_load_json(na)), flatten(_load_json(nb))
    except ValueError:
        return None
    diff = sorted(k for k in set(ja) & set(jb) if ja[k] != jb[k])
    only_run, only_arch = sorted(set(ja) - set(jb)), sorted(set(jb) - set(ja))
    parts = []
    if only_run:
        parts.append("keys only in run: " + ",".join(only_run[:8]))
    if only_arch:
        parts.append("keys only in archive: " + ",".join(only_arch[:8]))
    if diff:
        parts.append("value differs: " + "; ".join(
            f"{k} run={ja[k][:60]} archive={jb[k][:60]}" for k in diff[:6]))
    return (DATA, " | ".join(parts)) if parts else None


def classify_csv(la: list[str], lb: list[str]):
    """Cell-by-cell comparison of two CSV files given as lists of lines."""
    try:
        ra = list(csv.reader([x for x in la if x != ""]))
        rb = list(csv.reader([x for x in lb if x != ""]))
    except csv.Error:
        return None
    if not ra or not rb or ra[0] != rb[0] or len(ra) != len(rb):
        return None
    header = ra[0]
    cols = Counter()
    example = {}
    for x, y in zip(ra[1:], rb[1:]):
        if len(x) != len(header) or len(y) != len(header):
            return None
        for i, (u, v) in enumerate(zip(x, y)):
            if u != v:
                cols[header[i]] += 1
                example.setdefault(header[i], (u, v))
    if not cols:
        return None
    real = [c for c in cols if c not in ALLOWED_CSV_COLUMNS]
    col = real[0] if real else next(iter(cols))
    detail = "rows=%d; differing columns (rows differing): %s; e.g. %s run=%s archive=%s" % (
        len(ra) - 1, ", ".join(f"{c}({n})" for c, n in cols.items()), col, *example[col])
    return (DATA if real else EXPECTED), detail


def classify_turtle(la: list[str], lb: list[str]):
    """Allowed difference of a Turtle file: the address in its ``@prefix elem:`` line.

    Returns None when the files differ in any other line. When only prefix
    declarations of ``elem:`` differ, the file must not use the prefix anywhere
    else: then no triple names an Elementary term and the two files state the
    same triples. A file that uses the prefix is a real difference.
    """
    if len(la) != len(lb):
        return None
    bad = [(x, y) for x, y in zip(la, lb) if x != y]
    if not bad or not all(ELEM_PREFIX_LINE.match(x) and ELEM_PREFIX_LINE.match(y) for x, y in bad):
        return None
    uses = [line for line in la if "elem:" in line and not ELEM_PREFIX_LINE.match(line)]
    if uses:
        return DATA, (f"the address in '@prefix elem:' differs and the file uses the prefix in "
                      f"{len(uses)} line(s); first: {uses[0][:120]}")
    return EXPECTED, ("only the address in the '@prefix elem:' declaration differs; the file uses no "
                      "elem: term, so its triples are the same")


def classify(run_file: Path, arch_file: Path, rel: str):
    """Status and detail for one file pair (run file first, archive file second)."""
    ra, rb = run_file.read_bytes(), arch_file.read_bytes()
    if ra == rb:
        return IDENTICAL, ""
    na, nb = normalise(ra), normalise(rb)
    if na == nb:
        crlf = b"\r\n"
        return LINE_ENDINGS, f"run CRLF lines={ra.count(crlf)}, archive CRLF lines={rb.count(crlf)}"
    if na.rstrip(b"\n") == nb.rstrip(b"\n"):
        return TRAILING_NEWLINE, ""
    if is_receipt(rel):
        return classify_receipt(na, nb)
    suffix = run_file.suffix.lower()
    if suffix == ".json":
        r = classify_json(na, nb)
        if r:
            return r
    try:
        la, lb = na.decode("utf-8").split("\n"), nb.decode("utf-8").split("\n")
    except UnicodeDecodeError:
        return DATA, "binary content differs"
    if suffix == ".csv":
        r = classify_csv(la, lb)
        if r:
            return r
    if suffix == ".ttl":
        r = classify_turtle(la, lb)
        if r:
            return r
    if len(la) != len(lb):
        return DATA, f"line count run={len(la)} archive={len(lb)}"
    bad = [(i + 1, x, y) for i, (x, y) in enumerate(zip(la, lb)) if x != y]
    i, x, y = bad[0]
    return DATA, f"{len(bad)} differing lines of {len(la)}; first at line {i}: run: {x[:160]} | archive: {y[:160]}"


# --------------------------------------------------------------------------- Phase 1, 2, 4
def parse_archives(specs: list[str]):
    """Split ``--archive`` values into (plain archive folders, {lab: [folders]})."""
    plain, pinned = [], defaultdict(list)
    for s in specs:
        m = re.match(r"^([A-Za-z0-9_]+)=(.+)$", s)
        if m:
            pinned[m.group(1)].append(Path(m.group(2)))
        else:
            plain.append(Path(s))
    return plain, pinned


def missing_in_run(rows, seen_arch, archive_root):
    """Archive files of the same cells (same profile) that the run did not produce."""
    dirs_by_cell = defaultdict(set)
    for r in rows:
        if r["_arch"] is not None:
            dirs_by_cell[r["cell"]].add(r["_arch"].parent)
    extra = []
    for art, dirs in sorted(dirs_by_cell.items()):
        ma = re.match(r"(?:train|bench|adapt|clean|dynamics)-([A-Za-z0-9_]+?)-(?:stereo-(true|false)|(ql_true|ql_false|rule_based))-", art)
        prof = ma.group(1) if ma else None
        flag = None
        if ma and ma.group(3) in ("ql_true", "ql_false"):
            flag = "true" if ma.group(3) == "ql_true" else "false"
        for d in sorted(dirs):
            for f in sorted(d.iterdir()):
                if not f.is_file() or f.suffix.lower() not in DATA_EXT or f.resolve() in seen_arch:
                    continue
                fn = f.name
                mex = re.match(r"^(?:ADAPT_OK_|recovery_|metrics_adapted_|qtable_adapted_)stereotypes_(?:true|false)_(.+)\.(?:csv|json)$", fn)
                if mex and prof and mex.group(1) != prof:
                    continue
                if prof and not mex and not re.search(r"_%s(?:[_.]|$)" % re.escape(prof), fn) and re.search(r"_lab\w*", fn):
                    continue
                if flag and "adapt" in art and f"stereotypes_{flag}_" not in fn:
                    continue
                seen_arch.add(f.resolve())
                extra.append({"cell": art, "file": fn, "kind": kind_of(fn), "status": MISSING_RUN,
                              "detail": f"archive file in {d.name} with no counterpart in the run artifact",
                              "archive_path": display_path(f, archive_root), "alt": "",
                              "run_sha256": "", "run_bytes": "", "_arch": None})
    return extra


def compare_files(run_dir: Path, archives: list[str], archive_root: Path):
    """Compare every data file of the run with its archive counterpart; returns the rows."""
    plain, pinned = parse_archives(archives)
    rows = []
    for art, rel, full in collect_run_files(run_dir):
        row = {"cell": art, "file": rel, "kind": kind_of(rel), "status": "", "detail": "",
               "archive_path": "", "alt": "", "run_sha256": sha256(full),
               "run_bytes": full.stat().st_size, "_arch": None}
        m = re.match(r"adapt-(.+?)-ql_(?:true|false)-seed-\d+$", art)
        roots = pinned[m.group(1)] if m and m.group(1) in pinned else plain
        found = []
        for root in roots:
            for c in candidates(root, art, rel):
                if c.is_file():
                    found.append(c)
                    break
        if not found:
            row["status"] = MISSING_ARCHIVE
        else:
            row["_arch"] = found[0]
            row["archive_path"] = display_path(found[0], archive_root)
            row["status"], row["detail"] = classify(full, found[0], rel)
            row["alt"] = "; ".join(
                f"{_run_label(c, archive_root)}={classify(full, c, rel)[0]}" for c in found[1:])
        rows.append(row)
    seen = {r["_arch"].resolve() for r in rows if r["_arch"] is not None}
    rows += missing_in_run(rows, seen, archive_root)
    return rows


def _run_label(path: Path, archive_root: Path) -> str:
    """Name of the ``run_<id>`` folder a file lies in (for the ``alt`` column)."""
    for part in display_path(path, archive_root).split("/"):
        if part.startswith("run_"):
            return part
    return path.parent.name


CSV_COLUMNS = ["cell", "file", "kind", "status", "detail", "archive_path", "alt", "run_sha256", "run_bytes"]


def write_compare_csv(rows, out: Path):
    """Write compare.csv (no internal columns)."""
    with open(out / "compare.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_COLUMNS, extrasaction="ignore", lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def table(header: list[str], body: list[list]) -> list[str]:
    """Markdown table lines."""
    return ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)] + [
        "| " + " | ".join(str(x) for x in r) + " |" for r in body]


def write_summary(rows, out: Path, phase: str, run_id: str, archives_shown: list[str]):
    """Write summary.md for Phases 1, 2 and 4."""
    total = Counter(r["status"] for r in rows)
    statuses = [s for s in STATUS_ORDER if total[s]]
    by_kind, by_cell = defaultdict(Counter), defaultdict(Counter)
    for r in rows:
        by_kind[r["kind"]][r["status"]] += 1
        by_cell[r["cell"]][r["status"]] += 1
    real = sum(total[s] for s in FAILING)
    L = [f"# Comparison of {'run ' + run_id if run_id else 'a run'} with the archive ({phase})", "",
         "- archives (repository-relative): " + "; ".join(f"`{a}`" for a in archives_shown),
         f"- result data files compared: {len(rows)} (csv, json, jsonl, ttl; logs are not compared)",
         "- line endings: every comparison is made after turning CRLF into LF, so a Windows checkout"
         " or re-download of a Linux-written archive does not count as a difference",
         f"- verdict: {'NO real data difference' if real == 0 and rows else 'REAL DIFFERENCE FOUND'}"
         f" ({real} files with a real data difference or missing from the run)", "",
         "## Totals", ""] + [f"- {s}: {total[s]}" for s in statuses]
    L += ["", "## Per file kind", ""] + table(["kind"] + statuses, [[k] + [c[s] for s in statuses] for k, c in sorted(by_kind.items())])
    L += ["", "## Per cell", ""] + table(["cell"] + statuses, [[k] + [c[s] for s in statuses] for k, c in sorted(by_cell.items())])
    L += ["", "## One example per kind of difference", ""]
    shown = set()
    for r in rows:
        if r["status"] == IDENTICAL or (r["status"], r["kind"]) in shown:
            continue
        shown.add((r["status"], r["kind"]))
        L.append(f"- {r['status']} / {r['kind']}: `{r['file']}` ({r['cell']}) {r['detail'][:600]}")
    (out / "summary.md").write_text("\n".join(L) + "\n", encoding="utf-8", newline="\n")
    return real


# --------------------------------------------------------------------------- Phase 3
def read_csv_rows(path: Path) -> list[dict]:
    """Rows of a CSV file as dictionaries."""
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def compare_phase3(run_dir: Path, archives: list[str], archive_root: Path, out: Path, run_id: str):
    """Phase-3 comparison: counts and choices instead of bytes.  Returns the failure count."""
    plain, _ = parse_archives(archives)
    rows, problems, cells = [], [], []

    def arch_file(name: str, rep: int):
        for root in plain:
            p = root / "dynamics_root" / f"rep{rep}" / name
            if p.is_file():
                return p
        return None

    for ad in sorted(p for p in run_dir.iterdir() if p.is_dir()):
        m = re.match(r"dynamics-(\w+?)-(ql_true|ql_false)-rep-(\d+)$", ad.name)
        if not m:
            continue
        prof, mode, rep = m.group(1), m.group(2), int(m.group(3))
        flag = "true" if mode == "ql_true" else "false"
        tb_name = f"timebounded_results_{flag}_{prof}.csv"
        dl_name = f"dynamics_delays_{flag}_{prof}.csv"
        run_tb, run_dl = ad / tb_name, ad / dl_name
        a_tb, a_dl = arch_file(tb_name, rep), arch_file(dl_name, rep)
        cells.append(ad.name)
        if not run_tb.is_file() or a_tb is None:
            problems.append(f"{ad.name}: missing file (run has {tb_name}: {run_tb.is_file()}; archive has it: {a_tb is not None})")
            continue
        spread = defaultdict(Counter)
        for r in range(1, 100):
            p = arch_file(tb_name, r)
            if p:
                for row in read_csv_rows(p):
                    spread[row["goal_id"]][row["chosen_label"]] += 1
        arch_rep = {r["goal_id"]: r for r in read_csv_rows(a_tb)}
        for r in read_csv_rows(run_tb):
            g = r["goal_id"]
            ar = arch_rep.get(g, {})
            rows.append({
                "cell": ad.name, "profile": prof, "mode": mode, "rep": rep, "goal": g,
                "deadline_sec": r["deadline_sec"],
                "run_chosen": r["chosen_label"], "arch_rep_chosen": ar.get("chosen_label", ""),
                "run_met": r["met"], "arch_rep_met": ar.get("met", ""),
                "run_learned_delay_sec": r["learned_delay_sec"],
                "arch_rep_learned_delay_sec": ar.get("learned_delay_sec", ""),
                "run_actual_delay_sec": r["actual_delay_sec"],
                "arch_rep_actual_delay_sec": ar.get("actual_delay_sec", ""),
                "arch_all_reps_choices": "; ".join(f"{k} x{v}" for k, v in spread[g].most_common()),
                "choice_equal": int(r["chosen_label"] == ar.get("chosen_label")),
                "choice_in_archive_spread": int(spread[g][r["chosen_label"]] > 0),
                "met_equal": int(r["met"] == ar.get("met")),
                "learned_delay_equal": int(r["learned_delay_sec"] == ar.get("learned_delay_sec")),
            })
        if run_dl.is_file() and a_dl:
            arch_delay = {x["action_label"]: x for x in read_csv_rows(a_dl)}
            for x in read_csv_rows(run_dl):
                y = arch_delay.get(x["action_label"], {})
                rows.append({
                    "cell": ad.name, "profile": prof, "mode": mode, "rep": rep,
                    "goal": "DELAY:" + x["action_label"],
                    "run_chosen": x["response_class"], "arch_rep_chosen": y.get("response_class", ""),
                    "run_learned_delay_sec": x["delay_seconds"],
                    "arch_rep_learned_delay_sec": y.get("delay_seconds", ""),
                    "choice_equal": int(x["response_class"] == y.get("response_class")),
                    "learned_delay_equal": int(x["delay_seconds"] == y.get("delay_seconds")),
                })
    if not cells:
        problems.append("no dynamics-<profile>-<mode>-rep-<n> artifact found in the run folder")
    if rows:
        cols = list(dict.fromkeys(k for r in rows for k in r))
        with open(out / "phase3_compare.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=cols, lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
    goals = [r for r in rows if not str(r["goal"]).startswith("DELAY:")]
    delay_rows = [r for r in rows if str(r["goal"]).startswith("DELAY:")]
    met_bad = [r for r in goals if not r["met_equal"]]
    outside = [r for r in goals if not r["choice_in_archive_spread"]]
    other_rep = [r for r in goals if r["choice_in_archive_spread"] and not r["choice_equal"]]
    by_cell = defaultdict(Counter)
    for r in rows:
        c = by_cell[r["cell"]]
        if str(r["goal"]).startswith("DELAY:"):
            c["delay_rows"] += 1
            c["delay_equal"] += r["learned_delay_equal"]
        else:
            c["goals"] += 1
            c["choice_equal"] += r["choice_equal"]
            c["met_equal"] += r["met_equal"]
            c["delay_equal"] += r["learned_delay_equal"]
            c["run_met"] += int(r["run_met"] == "1")
            c["arch_met"] += int(r["arch_rep_met"] == "1")
    L = [f"# Phase-3 comparison of {'run ' + run_id if run_id else 'a run'} with the archive (counts and choices, not bytes)", "",
         "- archives (repository-relative): " + "; ".join(f"`{display_path(p, archive_root)}`" for p in plain),
         "- Phase 3 measures real device delays, so bytes differ; the deadline flag and the chosen device are compared per goal",
         f"- goals compared: {len(goals)} in {len(by_cell)} cells",
         f"- deadline flag (`met`) identical: {len(goals) - len(met_bad)} of {len(goals)}",
         f"- chosen device identical to the archive replica of the same number: {len(goals) - len(other_rep) - len(outside)} of {len(goals)}",
         f"- chosen device different from that replica but chosen by the archive in another replica of the same goal: {len(other_rep)}",
         f"- chosen device never chosen by the archive for that goal: {len(outside)}",
         f"- learned-delay values equal to the archive replica (goal rows plus delay-table rows): "
         f"{sum(r['learned_delay_equal'] for r in rows)} of {len(rows)} (measured values, reported not judged)",
         "- line endings: not relevant here (values are read, not bytes)",
         f"- verdict: {'PASS' if not (met_bad or outside or problems) else 'FAIL'}", "",
         "## Per cell", ""]
    L += table(["cell", "goals", "same choice as archive replica", "same deadline flag", "deadlines met (run)",
                "deadlines met (archive replica)", "learned delay values equal"],
               [[k, c["goals"], c["choice_equal"], c["met_equal"], c["run_met"], c["arch_met"],
                 f"{c['delay_equal']} of {c['goals'] + c['delay_rows']}"] for k, c in sorted(by_cell.items())])
    L += ["", "## Goals whose choice differs from the archive replica of the same number", ""]
    if other_rep or outside:
        L += table(["cell", "goal", "run chose", "archive replica chose", "archive spread over all replicas"],
                   [[r["cell"], r["goal"], r["run_chosen"], r["arch_rep_chosen"], r["arch_all_reps_choices"]]
                    for r in other_rep + outside])
    else:
        L.append("none")
    if problems:
        L += ["", "## Problems", ""] + [f"- {p}" for p in problems]
    L.append("")
    (out / "summary.md").write_text("\n".join(L), encoding="utf-8", newline="\n")
    return len(met_bad) + len(outside) + len(problems)


# --------------------------------------------------------------------------- download
def gh(args: list[str]) -> bytes:
    """Run the GitHub command line tool and return its output; raise on failure."""
    p = subprocess.run(["gh"] + args, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("gh " + " ".join(args[:3]) + " failed: " + p.stderr.decode("utf-8", "replace")[:300])
    return p.stdout


def download(run_id: str, repo: str, run_dir: Path) -> None:
    """Download the per-cell artifacts of a run into ``run_dir`` (skips what is present)."""
    page, artifacts = 1, []
    while True:
        d = json.loads(gh(["api", f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100&page={page}"]))
        artifacts += d.get("artifacts", [])
        if len(artifacts) >= d.get("total_count", 0) or not d.get("artifacts"):
            break
        page += 1
    run_dir.mkdir(parents=True, exist_ok=True)
    for a in artifacts:
        name = a["name"]
        if name in SKIP_ARTIFACTS or name.endswith("-consolidated") or a.get("expired"):
            continue
        dest = run_dir / name
        if dest.is_dir() and any(dest.iterdir()):
            continue
        gh(["run", "download", run_id, "-R", repo, "-n", name, "-D", str(dest)])


# --------------------------------------------------------------------------- main
def main(argv=None) -> int:
    """Command line entry point; returns the exit code."""
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--run-dir", required=True, type=Path, help="folder with one sub-folder per downloaded artifact")
    ap.add_argument("--phase", required=True, choices=["phase1", "phase2", "phase3", "phase4"])
    ap.add_argument("--archive", action="append", required=True,
                    help="archive run folder; repeatable; phase 2: <fault lab>=<folder>")
    ap.add_argument("--archive-root", type=Path, default=REPO_ROOT,
                    help="folder that plays the role of the repository root in printed paths")
    ap.add_argument("--out", required=True, type=Path, help="output folder")
    ap.add_argument("--run-id", default="", help="run id, used in the heading only")
    ap.add_argument("--download", metavar="RUN_ID", help="download the artifacts of this run into --run-dir first")
    ap.add_argument("--repo", help="owner/name of the GitHub repository for --download")
    a = ap.parse_args(argv)

    if a.download:
        if not a.repo:
            ap.error("--download needs --repo")
        download(a.download, a.repo, a.run_dir)
    if not a.run_dir.is_dir():
        print(f"error: run folder not found: {a.run_dir.name}", file=sys.stderr)
        return 2
    plain, pinned = parse_archives(a.archive)
    for folder in plain + [p for v in pinned.values() for p in v]:
        if not folder.is_dir():
            print(f"error: archive folder not found: {display_path(folder, a.archive_root)}", file=sys.stderr)
            return 2
    a.out.mkdir(parents=True, exist_ok=True)
    run_id = a.run_id or a.download or ""

    if a.phase == "phase3":
        bad = compare_phase3(a.run_dir, a.archive, a.archive_root, a.out, run_id)
        print(f"phase3: {bad} problem(s); see summary.md")
        return 1 if bad else 0

    rows = compare_files(a.run_dir, a.archive, a.archive_root)
    write_compare_csv(rows, a.out)
    shown = [display_path(p, a.archive_root) for p in plain] + [
        f"{lab}={display_path(p, a.archive_root)}" for lab, v in pinned.items() for p in v]
    real = write_summary(rows, a.out, a.phase, run_id, shown)
    print(dict(Counter(r["status"] for r in rows)))
    return 1 if (real or not rows) else 0


if __name__ == "__main__":
    sys.exit(main())
