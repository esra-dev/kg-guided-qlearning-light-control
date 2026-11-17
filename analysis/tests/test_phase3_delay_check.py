"""The Phase-3 true delay comes from the started flow and is checked.

run_phase3_dynamics.ps1 reads the blind delay from the slow flow it starts
('var DELAY = <ticks>;', scripts/SimulatorControl.ps1 Get-FlowBlindDelayTicks),
stops when it differs from config phase3.blind_delay_ticks, and records the
learned delay per device plus a tolerance check in DYNAMICS_OK.
validate_phase3_v2_archive.py accepts archived receipts without those fields
and checks the fields when present.
"""

import csv
import json
import shutil
import subprocess
from pathlib import Path

import pytest

from analysis import validate_phase3_v2_archive as v3

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh") or shutil.which("powershell")
HELPER = ROOT / "scripts" / "SimulatorControl.ps1"
ARCHIVE = ROOT / "results" / "phase3_v2_corrected" / "run_29926328852"
PROFILES = ["lab2_slow", "lab3_slow"]

needs_pwsh = pytest.mark.skipif(PWSH is None, reason="PowerShell is not installed")


def _config():
    return json.loads((ROOT / "config" / "run_config.json").read_text(encoding="utf-8-sig"))["phase3"]


def _pwsh(body, tmp_path):
    script = tmp_path / "probe.ps1"
    script.write_text(f". '{HELPER}'\n" + body, encoding="utf-8")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
                          capture_output=True, text=True, timeout=120)
    return proc.stdout.strip()


def test_tolerance_is_configured_and_covers_the_archived_estimates():
    p3 = _config()
    tol = p3["blind_delay_tolerance_ticks"]
    assert tol == 1.0 and "blind_delay_tolerance_note" in p3
    # every single archived blind estimate (10 replicas, 2 labs, 2 arms) passes
    worst = 0.0
    for path in ARCHIVE.glob("dynamics_root/rep*/dynamics_delays_*.csv"):
        for ticks in v3.learned_blind_delays(path).values():
            worst = max(worst, abs(ticks - p3["blind_delay_ticks"]))
    assert 0 < worst <= 0.5 < tol


@needs_pwsh
@pytest.mark.parametrize("profile", PROFILES)
def test_delay_is_read_from_the_committed_slow_flows(profile, tmp_path):
    out = _pwsh(f"Get-FlowBlindDelayTicks -FlowPath '{ROOT / 'simulator' / f'simulator_flow_{profile}.json'}'",
                tmp_path)
    assert out == str(_config()["blind_delay_ticks"])


def _flow(tmp_path, name, funcs):
    nodes = [{"id": "t", "type": "tab"}] + [
        {"id": f"f{i}", "type": "function", "func": body} for i, body in enumerate(funcs)]
    path = tmp_path / name
    path.write_text(json.dumps(nodes), encoding="utf-8")
    return path


@needs_pwsh
def test_delay_extraction_handles_other_values_and_rejects_bad_flows(tmp_path):
    ten = _flow(tmp_path, "ten.json", ["var x = 1;\nvar DELAY = 10;  // ticks\nrem = DELAY;"])
    none = _flow(tmp_path, "none.json", ["var x = 1;"])
    two = _flow(tmp_path, "two.json", ["var DELAY = 12;", "  var DELAY = 9;"])
    out = _pwsh(
        f"Get-FlowBlindDelayTicks -FlowPath '{ten}'\n"
        f"try {{ Get-FlowBlindDelayTicks -FlowPath '{none}' }} catch {{ 'ERR-NONE ' + $_.Exception.Message }}\n"
        f"try {{ Get-FlowBlindDelayTicks -FlowPath '{two}' }} catch {{ 'ERR-TWO ' + $_.Exception.Message }}\n",
        tmp_path)
    lines = out.splitlines()
    assert lines[0] == "10"
    assert lines[1].startswith("ERR-NONE No 'var DELAY = <ticks>;' line")
    assert lines[2].startswith("ERR-TWO Conflicting DELAY values") and "9, 12" in lines[2]


@needs_pwsh
def test_tolerance_check_passes_and_fails(tmp_path):
    csv_path = tmp_path / "d.csv"
    csv_path.write_text("action_label,action_value,samples,delay_ticks,delay_seconds,std_ticks,response_class\n"
                        "SetZ1Blinds=ON,true,3,12.3333,61.6,0.5,delayed\n"
                        "SetZ1Light=ON,true,3,1.3333,6.6,0.5,instantaneous\n"
                        "SetZ2Blinds=ON,true,3,12.0000,60.0,0.0,delayed\n", encoding="utf-8")
    out = _pwsh(
        f"$l = Get-LearnedDelayTicks -DelayCsv '{csv_path}'\n"
        "'N=' + $l.Count + ' Z1L=' + $l['SetZ1Light=ON']\n"
        "$a = Test-BlindDelayTolerance -Learned $l -TrueTicks 12 -ToleranceTicks 1.0\n"
        "'A=' + $a.check + ' blinds=' + $a.blind_delays.Count\n"
        "$b = Test-BlindDelayTolerance -Learned $l -TrueTicks 10 -ToleranceTicks 1.0\n"
        "'B=' + $b.check + ' ' + $b.reason\n"
        "$c = Test-BlindDelayTolerance -Learned ([ordered]@{ 'SetZ1Light=ON' = 1.0 }) -TrueTicks 12 -ToleranceTicks 1.0\n"
        "'C=' + $c.check + ' ' + $c.reason\n", tmp_path)
    lines = out.splitlines()
    assert lines[0] == "N=3 Z1L=1.3333"
    assert lines[1] == "A=pass blinds=2"
    assert lines[2].startswith("B=fail a learned blind delay is 2.3333 ticks")
    assert lines[3] == "C=fail no learned blind delay in the delay table"


def _copy_archive(tmp_path, replica=1):
    src = ARCHIVE / "dynamics_root" / f"rep{replica}"
    dst = tmp_path / "dynamics_root" / f"rep{replica}"
    shutil.copytree(src, dst)
    return dst


def test_validator_accepts_archived_receipts_unchanged(tmp_path):
    _copy_archive(tmp_path)
    assert v3.validate(tmp_path, PROFILES, [1]) == []


def _add_new_fields(rep, profile="lab2_slow", bool_name="true", **overrides):
    manifest_path = rep / f"DYNAMICS_OK_{bool_name}_{profile}.json"
    delays_path = rep / f"dynamics_delays_{bool_name}_{profile}.csv"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    with delays_path.open(encoding="utf-8-sig", newline="") as handle:
        learned = {r["action_label"]: float(r["delay_ticks"]) for r in csv.DictReader(handle)}
    manifest.update({
        "simulator_flow": f"simulator_flow_{profile}.json",
        "simulator_profile_reported": profile,
        "blind_delay_ticks_source": f"simulator/simulator_flow_{profile}.json",
        "blind_delay_ticks_config": 12,
        "learned_delay_ticks": learned,
        "blind_delay_tolerance_ticks": 1.0,
        "blind_delay_check": "pass",
        "blind_delay_max_abs_error_ticks": 0.125,
    })
    manifest.update(overrides)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return manifest_path, delays_path


def test_validator_checks_the_new_fields_when_present(tmp_path):
    rep = _copy_archive(tmp_path)
    _add_new_fields(rep)
    assert v3.validate(tmp_path, PROFILES, [1]) == []

    _add_new_fields(rep, blind_delay_check="fail", blind_delay_tolerance_ticks=5.0,
                    simulator_profile_reported="lab2")
    errors = "\n".join(v3.validate(tmp_path, PROFILES, [1]))
    assert "blind_delay_check='fail'" in errors
    assert "blind_delay_tolerance_ticks=5.0" in errors
    assert "simulator_profile_reported='lab2'" in errors

    _, delays_path = _add_new_fields(rep)
    text = delays_path.read_text(encoding="utf-8").replace("SetZ1Blinds=ON,true,8,12.1250", "SetZ1Blinds=ON,true,8,13.5000")
    delays_path.write_text(text, encoding="utf-8")
    errors = "\n".join(v3.validate(tmp_path, PROFILES, [1]))
    assert "SetZ1Blinds=ON learned 13.5 ticks, more than 1.0 from the true 12" in errors
    assert "learned_delay_ticks['SetZ1Blinds=ON'] does not match" in errors


def test_runner_reads_the_true_delay_from_the_flow():
    text = (ROOT / "run_phase3_dynamics.ps1").read_text(encoding="utf-8-sig")
    read = text.index("$flowDelayTicks = Get-FlowBlindDelayTicks -FlowPath $flowAbs")
    assert read < text.index("Start-Process @spArgs")
    assert "blind_delay_ticks = $flowDelayTicks" in text
    for key in ("learned_delay_ticks", "blind_delay_tolerance_ticks", "blind_delay_check",
                "blind_delay_ticks_source", "blind_delay_ticks_config"):
        assert f"{key} =" in text
