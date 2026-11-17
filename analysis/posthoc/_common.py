"""Shared helpers for the post-hoc, descriptive analyses in analysis/posthoc/.

Nothing here computes a p-value or a q-value. The paired summary reports the
mean of the per-seed differences, its median, a 95% percentile bootstrap
interval of the mean (10,000 resamples of the seed differences, generator
``random.Random`` seeded from ``BOOTSTRAP_BASE_SEED`` and the comparison's
context labels via ``phase1_truncated_learning.stable_bootstrap_seed``; the
measure is not part of the labels, so all measures of one comparison resample
the same seeds and identical seed vectors get identical intervals) and the
sign counts (seeds with a positive / zero / negative difference).

Archive readers are reused from the registered analysis modules wherever they
fit: ``phase4_v2_registered_family._cell_dir`` (exactly-one-cell lookup across
several run roots), ``phase4_v2_registered_family._csv_rows`` (CSV rows
without ``#`` footer lines), ``phase1_truncated_learning`` (strict training
metrics reader and the stable bootstrap seed) and ``validate_phase1_scenarios``
(expected start-state light levels).
"""

from __future__ import annotations

import csv
import json
import math
import random
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from analysis import exact_paired_stats as eps  # noqa: E402
from analysis import phase1_truncated_learning as ptl  # noqa: E402
from analysis import phase4_v2_registered_family as p4  # noqa: E402

POSTHOC_LABEL = "post-hoc, descriptive"
BOOTSTRAP_ITERS = 10_000
BOOTSTRAP_BASE_SEED = 20261116
ARMS_QL = ("ql_true", "ql_false")
TRAINING_SUBDIR = {"ql_true": "training_stereo_true", "ql_false": "training_stereo_false"}
TEXT_FIELDS = frozenset({"id", "comment", "description"})

# Goal definition used by every Phase-1 and Phase-4 lab read here
# (src/agt/lab_profiles.asl: light_bounds([50, 100, 300]) and target rank 3
# for every listed zone; src/env/tools/LabEnvironment.java discretize():
# rank 3 <=> level >= 300 lux).
GOAL_LUX = 300.0
TARGET_LEVEL_KEYS = {
    "lab1": ("Z1Level",),
    "lab2": ("Z1Level", "Z2Level"),
    "lab3": ("Z1Level", "Z2Level"),
    "lab4": ("Z1Level", "Z2Level"),
    "lab4dual": ("Z1Level", "Z2Level"),
    "lab4chain": ("Z1Level", "Z2Level"),
    "lab5": ("Z1Level", "Z2Level"),
}


# ── seeds, numbers, files ──────────────────────────────────────────────────

def parse_seeds(text: str) -> tuple[int, ...]:
    """Parse '1-20' or '1,3,5-7' into a sorted tuple of unique seeds."""
    seeds: set[int] = set()
    for part in str(text).split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = (int(x) for x in part.split("-", 1))
            if hi < lo:
                raise ValueError(f"empty seed range {part!r}")
            seeds.update(range(lo, hi + 1))
        else:
            seeds.add(int(part))
    if not seeds:
        raise ValueError("no seeds given")
    return tuple(sorted(seeds))


def mean(values: Iterable[float]) -> float:
    data = [float(v) for v in values]
    return sum(data) / len(data) if data else float("nan")


def is_nan(value) -> bool:
    return isinstance(value, float) and math.isnan(value)


def fmt(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        if math.isnan(value):
            return ""
        if value == 0:
            return "0"
        return format(value, ".12g")
    return str(value)


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({key: fmt(row.get(key)) for key in fields})


def read_json(path: Path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def csv_rows(path: Path) -> list[dict]:
    """CSV rows without '#' footer lines (registered Phase-4 reader)."""
    return p4._csv_rows(Path(path))


def cell_dir(roots: list[Path], seed: int, lab: str, sub: str) -> Path:
    """Exactly one ``<root>/benchmark/results_seed<seed>/<lab>/<sub>`` across roots."""
    return p4._cell_dir([Path(r) for r in roots], seed, lab, sub)


def num(value: float, digits: int = 4) -> str:
    """Fixed-point text without a spurious '-0.0000' for float noise around zero."""
    if math.isnan(value):
        return "n/a"
    text = f"{value:.{digits}f}"
    return text[1:] if text.startswith("-") and float(text) == 0 else text


def interval(lo: float, hi: float, digits: int = 4) -> str:
    return f"[{num(lo, digits)}, {num(hi, digits)}]"


def md_table(rows: list[dict], fields: list[str], headers: list[str] | None = None,
             digits: int = 4) -> list[str]:
    def cell(value) -> str:
        if isinstance(value, float):
            return num(value, digits)
        return str(value)
    head = headers or fields
    out = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for row in rows:
        out.append("| " + " | ".join(cell(row.get(f, "")) for f in fields) + " |")
    return out


# ── paired, descriptive summary ────────────────────────────────────────────

def bootstrap_mean_interval(diffs: list[float], *labels: object,
                            iters: int = BOOTSTRAP_ITERS) -> tuple[float, float]:
    """95% percentile bootstrap interval of the mean of ``diffs``.

    ``iters`` resamples with replacement; the generator is ``random.Random``
    seeded with ``stable_bootstrap_seed(BOOTSTRAP_BASE_SEED, *labels)`` so
    every comparison context has its own reproducible stream. Percentile indices
    follow the registered Phase-2/Phase-4 scripts: sorted means at
    ``int(0.025 * iters)`` and ``min(iters - 1, int(0.975 * iters))``.
    """
    n = len(diffs)
    if n < 2:
        return float("nan"), float("nan")
    rng = random.Random(ptl.stable_bootstrap_seed(BOOTSTRAP_BASE_SEED, *labels))
    boots = []
    for _ in range(iters):
        boots.append(sum(diffs[rng.randrange(n)] for _ in range(n)) / n)
    boots.sort()
    return boots[int(0.025 * iters)], boots[min(iters - 1, int(0.975 * iters))]


PAIRED_FIELDS = [
    "n_paired", "seeds_paired", "seeds_excluded_undefined", "mean_left", "mean_right",
    "mean_paired_difference", "median_paired_difference",
    "bootstrap_ci_lo_95", "bootstrap_ci_hi_95",
    "n_seeds_left_higher", "n_seeds_tied", "n_seeds_left_lower",
]


def paired_summary(left: dict[int, float], right: dict[int, float], seeds: Iterable[int],
                   *labels: object, iters: int = BOOTSTRAP_ITERS) -> dict:
    """Describe the per-seed differences left - right over ``seeds``.

    A seed whose value is undefined (NaN) in either arm is excluded from the
    pair and listed in ``seeds_excluded_undefined``; a seed missing from either
    dict is an error.
    """
    used, excluded, diffs, lv, rv = [], [], [], [], []
    for seed in seeds:
        if seed not in left or seed not in right:
            raise ValueError(f"seed {seed} missing for {labels}")
        a, b = float(left[seed]), float(right[seed])
        if math.isnan(a) or math.isnan(b):
            excluded.append(seed)
            continue
        used.append(seed)
        lv.append(a)
        rv.append(b)
        diffs.append(a - b)
    lo, hi = bootstrap_mean_interval(diffs, *labels, iters=iters)
    return {
        "n_paired": len(diffs),
        "seeds_paired": ";".join(map(str, used)),
        "seeds_excluded_undefined": ";".join(map(str, excluded)),
        "mean_left": mean(lv),
        "mean_right": mean(rv),
        "mean_paired_difference": mean(diffs),
        "median_paired_difference": eps.median(diffs) if diffs else float("nan"),
        "bootstrap_ci_lo_95": lo,
        "bootstrap_ci_hi_95": hi,
        "n_seeds_left_higher": sum(1 for d in diffs if d > 0),
        "n_seeds_tied": sum(1 for d in diffs if d == 0),
        "n_seeds_left_lower": sum(1 for d in diffs if d < 0),
    }


# ── run archives ───────────────────────────────────────────────────────────

_SEED_DIR = re.compile(r"^results_seed(\d+)$")


def seed_dirs(root: Path) -> dict[int, Path]:
    bench = Path(root) / "benchmark"
    out: dict[int, Path] = {}
    if bench.is_dir():
        for child in bench.iterdir():
            match = _SEED_DIR.match(child.name)
            if match and child.is_dir():
                out[int(match.group(1))] = child
    return out


def detect_run_mode(root: Path) -> str:
    """The single ``run_mode`` recorded in every TRAINING_OK.json under a run root."""
    modes = set()
    for seed_dir in seed_dirs(root).values():
        for marker in seed_dir.glob("*/training_stereo_*/TRAINING_OK.json"):
            mode = read_json(marker).get("run_mode")
            if mode:
                modes.add(str(mode))
    if len(modes) != 1:
        raise ValueError(f"{root}: expected exactly one run_mode in TRAINING_OK.json files, "
                         f"found {sorted(modes)}")
    return modes.pop()


def group_roots_by_mode(roots: Iterable[Path]) -> dict[str, list[Path]]:
    grouped: dict[str, list[Path]] = {}
    for root in roots:
        grouped.setdefault(detect_run_mode(Path(root)), []).append(Path(root))
    return grouped


def labs_in(roots: list[Path]) -> list[str]:
    labs: set[str] = set()
    for root in roots:
        for seed_dir in seed_dirs(root).values():
            labs.update(p.name for p in seed_dir.iterdir() if p.is_dir())
    return sorted(labs)


# ── scenarios ──────────────────────────────────────────────────────────────

def load_scenarios(path: Path) -> list[dict]:
    rows = read_json(path)
    if not isinstance(rows, list) or not rows:
        raise ValueError(f"{path}: expected a non-empty JSON list of scenarios")
    ids = [row.get("id") for row in rows]
    if any(not isinstance(i, int) for i in ids) or len(set(ids)) != len(ids):
        raise ValueError(f"{path}: scenario ids must be unique integers")
    return rows


def state_key(row: dict, ignore: Iterable[str] = TEXT_FIELDS) -> tuple:
    """Start state of a scenario: every field except ``ignore`` and text values."""
    skip = set(ignore)
    return tuple(sorted((k, v) for k, v in row.items()
                        if k not in skip and not isinstance(v, str)))


@dataclass(frozen=True)
class StartClass:
    test_id: int
    trained: bool
    matching_training_id: int | None


def classify_test_starts(test_rows: list[dict], train_rows: list[dict],
                         ignore: Iterable[str] = TEXT_FIELDS) -> list[StartClass]:
    """TRAINED when a test start state equals some training start state."""
    train_by_state: dict[tuple, int] = {}
    for row in train_rows:
        train_by_state.setdefault(state_key(row, ignore), int(row["id"]))
    out = []
    for row in test_rows:
        match = train_by_state.get(state_key(row, ignore))
        out.append(StartClass(int(row["id"]), match is not None, match))
    return out


def devices_on(row: dict) -> str:
    """Names of the boolean device fields that are switched on, ';'-joined."""
    on = [k for k, v in row.items() if isinstance(v, bool) and v]
    return ";".join(on) if on else "none"


def start_meets_goal(lab: str, row: dict) -> bool:
    """True when every target zone's declared start level is >= GOAL_LUX."""
    keys = TARGET_LEVEL_KEYS[lab]
    return all(float(row[k]) >= GOAL_LUX for k in keys)
