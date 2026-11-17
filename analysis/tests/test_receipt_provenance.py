"""Receipt provenance: exact parent checksum and the real workflow name.

- ``run_phase2_adapt.ps1`` keeps the legacy ``parent_qtable_sha256`` (a
  workspace wildcard that can also match other profiles' tables) unchanged
  for comparison with archived receipts, and adds
  ``parent_qtable_exact_sha256`` over exactly the files the adaptation agent
  loads, with the list of file names.
- ``analysis/write_workflow_provenance.py`` records the workflow that really
  ran (``--workflow`` or ``GITHUB_WORKFLOW_REF``) instead of always phase1.yml.
"""

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from analysis import write_workflow_provenance as prov

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh") or shutil.which("powershell")


def _read(rel):
    return (ROOT / rel).read_text(encoding="utf-8-sig")


# ---------------------------------------------------------- parent checksum --

def test_legacy_checksum_is_kept_and_exact_one_added():
    text = _read("run_phase2_adapt.ps1")
    legacy = ('$qtFiles = @(Get-ChildItem -Path $ScriptRoot -Filter '
              '("qtable_final_stereotypes_{0}{1}*.csv" -f $bool, $cleanSfx)')
    assert legacy in text
    assert "parent_qtable_sha256     = $qtHash" in text
    assert "parent_qtable_exact_sha256 = $qtExactHash" in text
    assert "parent_qtable_exact_files  = " in text


def _exact(tmp_path, names, goal="[3, 3]", bool_name="true", sfx="_lab2"):
    root = tmp_path / "ws"
    root.mkdir()
    for name in names:
        (root / name).write_text("x\n", encoding="utf-8")
    log = tmp_path / "cell.log"
    log.write_text(f"10:00:00.000 [Thread-3] INFO  tools.QLearner -   Goal: {goal}\n", encoding="utf-8")
    script = tmp_path / "probe.ps1"
    script.write_text(f"""
$ScriptRoot = '{root}'
$ast = [System.Management.Automation.Language.Parser]::ParseFile('{ROOT / 'run_phase2_adapt.ps1'}', [ref]$null, [ref]$null)
foreach ($f in $ast.FindAll({{ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }}, $true)) {{
    if ($f.Name -eq 'Get-ExactParentFiles') {{ . ([scriptblock]::Create($f.Extent.Text)) }}
}}
$files = Get-ExactParentFiles -Bool '{bool_name}' -CleanSfx '{sfx}' -LogPath '{log}'
ConvertTo-Json -InputObject @($files | ForEach-Object {{ [System.IO.Path]::GetFileName($_) }} | Sort-Object) -Compress
""", encoding="utf-8")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
                          capture_output=True, text=True, timeout=120)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    return json.loads(proc.stdout)


@pytest.mark.skipif(PWSH is None, reason="PowerShell is not installed")
def test_exact_set_is_what_the_agent_loads(tmp_path):
    own = ["qtable_final_stereotypes_true_lab2.csv",
           "qtable_final_stereotypes_true_lab2_zone1.csv",
           "qtable_final_stereotypes_true_lab2_zone2.csv",
           "qtable_final_stereotypes_true_lab2_visits.csv",
           "qtable_final_stereotypes_true_lab2_trust.csv"]
    others = ["qtable_final_stereotypes_true_lab2noise_zone1.csv",
              "qtable_final_stereotypes_true_lab2_slow_zone1.csv",
              "qtable_final_stereotypes_false_lab2_zone1.csv"]
    got = _exact(tmp_path, own + others)
    # zone files exist, so the combined table is not loaded; other profiles never
    assert got == sorted(own[1:])


@pytest.mark.skipif(PWSH is None, reason="PowerShell is not installed")
def test_exact_set_falls_back_to_the_combined_table(tmp_path):
    got = _exact(tmp_path, ["qtable_final_stereotypes_true_lab2.csv",
                            "qtable_final_stereotypes_true_lab2_zone1.csv"])
    assert got == ["qtable_final_stereotypes_true_lab2.csv"]


@pytest.mark.skipif(PWSH is None, reason="PowerShell is not installed")
def test_exact_set_excludes_the_labmon2_family_for_labmon(tmp_path):
    names = ["qtable_final_stereotypes_true_labmon_zone1.csv",
             "qtable_final_stereotypes_true_labmon_visits.csv",
             "qtable_final_stereotypes_true_labmon2_zone1.csv",
             "qtable_final_stereotypes_true_labmon_infoonly_zone1.csv"]
    got = _exact(tmp_path, names, goal="[2]", sfx="_labmon")
    assert got == ["qtable_final_stereotypes_true_labmon_visits.csv",
                   "qtable_final_stereotypes_true_labmon_zone1.csv"]


# ---------------------------------------------------------- workflow name --

def _run(tmp_path, monkeypatch, extra, env_ref=None):
    if env_ref is None:
        monkeypatch.delenv("GITHUB_WORKFLOW_REF", raising=False)
    else:
        monkeypatch.setenv("GITHUB_WORKFLOW_REF", env_ref)
    out = tmp_path / "workflow_inputs.json"
    argv = ["prog", "--out", str(out), "--run-id", "1", "--sha", "deadbeef",
            "--ref", "refs/heads/x", "--run-mode", "m", "--profiles", "a",
            "--seeds", "1", "--publish-results", "false"] + extra
    monkeypatch.setattr(sys, "argv", argv)
    assert prov.main() == 0
    return json.loads(out.read_text(encoding="utf-8"))


def test_workflow_from_github_environment(tmp_path, monkeypatch):
    ref = "owner/repo/.github/workflows/phase3.yml@refs/heads/main"
    assert _run(tmp_path, monkeypatch, [], ref)["workflow"] == ".github/workflows/phase3.yml"


def test_command_line_override_wins(tmp_path, monkeypatch):
    ref = "o/r/.github/workflows/phase3.yml@refs/heads/main"
    payload = _run(tmp_path, monkeypatch, ["--workflow", ".github/workflows/phase2.yml"], ref)
    assert payload["workflow"] == ".github/workflows/phase2.yml"


def test_unknown_outside_github(tmp_path, monkeypatch):
    assert _run(tmp_path, monkeypatch, [])["workflow"] == "unknown"
    assert prov.workflow_from_ref("garbage") is None


@pytest.mark.parametrize("n", [2, 3, 4])
def test_callers_name_their_own_workflow(n):
    text = _read(f".github/workflows/phase{n}.yml")
    assert f"--workflow .github/workflows/phase{n}.yml" in text
