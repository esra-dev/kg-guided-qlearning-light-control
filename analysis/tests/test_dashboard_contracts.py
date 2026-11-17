"""Schema checks on the committed dashboard data, the dashboard text and its lab physics.

The dashboard data files are regenerated and byte-compared in CI; these tests
keep the schema guards that sit beside that comparison: the protocol markers
of the corrected campaigns, the absence of withdrawn keys, and the current
lab1 / lab3 sunshine coupling in the dashboard's physics model. They also
check that the dashboard source carries none of the withdrawn numbers or
claims, that every document it names exists, that the committed phase and
overview files equal a fresh regeneration, that the data builder can no
longer overwrite the frozen replay and training files, and that the toy-room
light formulas equal the simulator flows.
"""

from __future__ import annotations

import csv
import importlib.util
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "dashboard" / "public" / "data"
SRC = ROOT / "dashboard" / "src"
SCRIPT = ROOT / "dashboard" / "scripts" / "prepare_data.py"


def _data(name: str) -> dict:
    return json.loads((DATA / name).read_text(encoding="utf-8"))


def _source_files():
    return sorted(p for p in SRC.rglob("*") if p.suffix in {".js", ".jsx", ".css"})


def _load_builder():
    spec = importlib.util.spec_from_file_location("prepare_data_contracts", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_phase1_dashboard_is_the_corrected_campaign():
    data = _data("phase1.json")
    assert data["protocol_version"] == "phase1-v2"
    assert data["status"] == "current thesis evidence"
    assert data["head_sha"] == "d3442385c91fbe11a5714bd15ca83add8f81f115"
    assert set(map(str, data["runs"].values())) == {
        "29848584965", "29848587274", "29848589682", "29848592010"}
    assert len(data["registered_family"]) == 5
    assert "registered_pooled20" not in data
    assert all(float(row["p"]) > 0 and float(row["q"]) > 0 for row in data["registered_family"])


def test_phase2_dashboard_is_the_corrected_campaign():
    data = _data("phase2.json")
    assert data["protocol_version"] == "phase2-v2"
    assert data["detector_version"] == "fault-detector-v2"
    assert data["false_positive_blacklist_events"] == 0
    assert "ci" not in data and "paired" not in data


def test_phase3_and_phase4_dashboards_have_no_withdrawn_keys():
    phase3 = _data("phase3.json")
    assert phase3["protocol_version"] == "phase3-v2"
    phase4 = _data("phase4.json")
    assert phase4["run_mode"] == "phase4_v2"
    assert "summary" not in phase4


def test_dashboard_physics_uses_current_sunshine_coupling():
    physics = (ROOT / "dashboard" / "src" / "lib" / "physics.js").read_text(encoding="utf-8")
    lab1 = physics.split("lab1:", 1)[-1].split("lab2:", 1)[0]
    lab3 = physics.split("lab3:", 1)[-1].split("lab4:", 1)[0]
    assert "0.10 * sun" not in lab1 and "0.1 * sun" not in lab1
    assert "0.3 * sun" in lab3 and "(Z2Light?100)" in lab3


# --- the text on the page -------------------------------------------------

FORBIDDEN_STRINGS = [
    # withdrawn or never-accepted numbers
    "q < 0.01", "0.0012", "0.784", "0.683", "0.00093", "n = 10", "n=10",
    "53.30", "0.753 ", "0.710", "+0.096", "0.096", "1.9×", "1.9x",
    "−0.33", "-0.33", "−0.93", "-0.93", "−1.46", "-1.46",
    "0.7 ticks", "38 cells", "100% vs 50%", "<=0.7", "≤0.7",
    # claims that have no data behind them
    "general knowledge", "multi-agent", "Multi-agent",
    "multi agent", "zero false-positive", "Zero false-positive",
]


@pytest.mark.parametrize("needle", FORBIDDEN_STRINGS)
def test_dashboard_source_has_no_withdrawn_numbers_or_claims(needle):
    hits = [str(p.relative_to(ROOT)) for p in _source_files()
            if needle in p.read_text(encoding="utf-8")]
    assert not hits, f"{needle!r} found in {hits}"


def test_every_document_path_named_in_the_dashboard_source_exists():
    pattern = re.compile(r"\b(?:docs|results|analysis|simulator)/[A-Za-z0-9_./-]*[A-Za-z0-9_]\.(?:md|csv|json|py|ttl)")
    named = set()
    for path in _source_files():
        named.update(pattern.findall(path.read_text(encoding="utf-8")))
    assert named, "expected the pages to name their source documents"
    missing = sorted(name for name in named if not (ROOT / name).exists())
    assert not missing, f"documents named in dashboard/src do not exist: {missing}"


def test_every_page_labels_its_panels_and_the_app_explains_the_labels():
    for page in ("Overview", "Phase1", "Phase2", "Phase3", "Phase4"):
        text = (SRC / "pages" / f"{page}.jsx").read_text(encoding="utf-8")
        assert "SourceTag" in text, f"{page}.jsx has no SourceTag"
        assert 'kind="results"' in text or 'kind="illustration"' in text
    for part in ("Replay.jsx", "FaultLab.jsx"):
        assert 'kind="illustration"' in (SRC / "components" / part).read_text(encoding="utf-8")
    tag = (SRC / "components" / "SourceTag.jsx").read_text(encoding="utf-8")
    assert "From the stored results" in tag and "Illustration" in tag
    app = (SRC / "App.jsx").read_text(encoding="utf-8")
    assert "aboutbox" in app and "From the stored results" in app


def test_overview_text_is_driven_by_the_overview_file():
    overview_jsx = (SRC / "pages" / "Overview.jsx").read_text(encoding="utf-8")
    assert "useData('overview.json')" in overview_jsx
    # result sentences are built from fields of the generated file
    for field in ("lab2_auc_goal_gain", "n_supported", "n_adverse", "learned_ticks_min",
                  "steps_per_test_run", "hand_written"):
        assert field in overview_jsx


# --- the data files -------------------------------------------------------

def test_regenerated_phase_and_overview_files_equal_the_committed_ones(tmp_path):
    module = _load_builder()
    for name in ("phase1", "phase2", "phase3", "phase4"):
        module.BUILDERS[name](out_dir=tmp_path)
    module.BUILDERS["overview"](out_dir=tmp_path)
    for name in ("phase1.json", "phase2.json", "phase3.json", "phase4.json", "overview.json"):
        assert (tmp_path / name).read_bytes() == (DATA / name).read_bytes(), name


def test_data_builder_cannot_overwrite_the_frozen_replay_and_training_files():
    module = _load_builder()
    assert set(module.BUILDERS) == {"phase1", "phase2", "phase3", "phase4", "overview"}
    for old in ("build_replays", "build_training", "BENCH_DIRS", "TRAIN_DIRS"):
        assert not hasattr(module, old), old
    text = SCRIPT.read_text(encoding="utf-8")
    assert "replay_" not in text.split('"""', 2)[2] and "training.json" not in text.split('"""', 2)[2]
    assert "benchmark" not in text.split('"""', 2)[2].lower().replace("benchmark_", "")
    # the frozen files are still committed and are not empty entries
    for lab in ("lab1", "lab2", "lab3", "lab4", "lab5"):
        replay = _data(f"replay_{lab}.json")
        assert replay["scenarios"], lab


def test_phase4_lab5_levels_are_the_corrected_ones():
    phase4 = _data("phase4.json")
    levels = phase4["lab5_levels"]
    corrected = ROOT / "results" / "_corrected_tables" / "phase4_v2_corrected" / "lab5_compliance_levels.csv"
    with corrected.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    for mode in ("ql_true", "ql_false", "rule_based"):
        stored = [float(r["energy_compliance_mean"]) for r in rows
                  if r["mode"] == mode and r["zero_step_scoring"] == "declared_start_state"][0]
        assert levels[mode] == pytest.approx(stored)
    assert levels["ql_true"] == pytest.approx(0.503125)
    assert levels["ql_false"] == pytest.approx(0.46)
    assert levels["rule_based"] == pytest.approx(0.75)
    lab5 = next(r for r in phase4["registered_family"] if r["cell"] == "lab5")
    assert lab5["mean_ql_true"] == pytest.approx(0.503125)
    assert lab5["mean"] == pytest.approx(0.043125)
    assert lab5["archived_mean_ql_true"] == pytest.approx(0.753125)


def test_overview_numbers_follow_the_phase_files():
    overview = _data("overview.json")
    p2 = _data("phase2.json")
    verdicts = [row["verdict"] for row in p2["tier1"]]
    assert overview["phase2"]["n_supported"] == verdicts.count("supported")
    assert overview["phase2"]["n_null"] == verdicts.count("null")
    assert overview["phase2"]["n_adverse"] == verdicts.count("adverse")
    assert (overview["phase2"]["n_supported"], overview["phase2"]["n_null"],
            overview["phase2"]["n_adverse"]) == (2, 3, 3)
    assert overview["phase2"]["adverse_cells_knowledge_arm_never_detected"] == 3
    p1 = _data("phase1.json")
    assert overview["phase1"]["redundancy_share"] == p1["decomposition"]["redundancy_share"]
    for cell, row in overview["phase4"]["steps_per_test_run"].items():
        assert row["reduction_pct"] == pytest.approx(
            100 * (row["plain_arm"] - row["knowledge_arm"]) / row["plain_arm"])
    assert set(overview["phase4"]["steps_per_test_run"]) == {"lab4", "lab4dual", "lab4chain"}
    for profile in overview["phase3"]["deadlines"].values():
        assert profile["plain_arm"]["total"] == profile["knowledge_arm"]["total"]


# --- toy-room physics against the simulator flows -------------------------

_COMPARE_JS = r"""
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const [physicsPath, simDir] = process.argv.slice(2);
const { LABS, FAULTS, SUN_LEVELS } = await import(pathToFileURL(physicsPath).href);

function loadFlow(name) {
  const text = readFileSync(`${simDir}/simulator_flow_${name}.json`, 'utf8').replace(/^﻿/, '');
  const node = JSON.parse(text).find((n) => n.type === 'function' && n.name === 'Update environment');
  return new Function('flow', 'msg', node.func);
}

function simulate(fn, state, sun) {
  const store = { ...state, Sunshine: sun };
  const flow = { get: (k) => store[k], set: (k, v) => { store[k] = v; } };
  fn(flow, {});
  return store;
}

const mismatches = [];
function check(label, lab, simName, faults, comparePower) {
  const fn = loadFlow(simName);
  const ids = lab.actuators.map((a) => a.id);
  for (let mask = 0; mask < (1 << ids.length); mask += 1) {
    const state = {};
    ids.forEach((id, i) => { state[id] = Boolean(mask & (1 << i)); });
    for (const sun of SUN_LEVELS) {
      const sim = simulate(fn, state, sun);
      const mine = lab.compute(state, sun, faults);
      for (const zone of lab.zones) {
        if (Math.abs(sim[`${zone}Level`] - mine[zone]) > 1e-9) {
          mismatches.push({ label, kind: 'lux', zone, state, sun, sim: sim[`${zone}Level`], dashboard: mine[zone] });
        }
      }
      if (comparePower && lab.power && Math.abs(sim.EnergyCost - lab.power(state)) > 1e-9) {
        mismatches.push({ label, kind: 'power', state, sun, sim: sim.EnergyCost, dashboard: lab.power(state) });
      }
    }
  }
}

for (const id of ['lab1', 'lab2', 'lab3', 'lab4', 'lab5']) check(id, LABS[id], id, {}, true);
for (const [id, f] of Object.entries(FAULTS)) check(id, LABS[f.base], id, f.faults, false);
console.log(JSON.stringify({ n: mismatches.length, first: mismatches.slice(0, 3) }));
"""


def test_toy_room_light_formulas_equal_the_simulator_flows(tmp_path):
    node = shutil.which("node")
    if node is None:
        pytest.skip("node is not installed")
    script = tmp_path / "compare_physics.mjs"
    script.write_text(_COMPARE_JS, encoding="utf-8")
    result = subprocess.run(
        [node, str(script), str(ROOT / "dashboard" / "src" / "lib" / "physics.js"),
         str(ROOT / "simulator")],
        capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    outcome = json.loads(result.stdout.strip().splitlines()[-1])
    assert outcome["n"] == 0, outcome["first"]
