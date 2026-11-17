"""Every simulator flow names itself on /health, and the runners check that name.

A flow file simulator_flow_<profile>.json answers GET /health with a field
``profile`` equal to ``<profile>``. The faulty flows are written by
simulator/generate_faulty_flows.ps1, which sets the field from the output file
name. The three runners compare the answer with the flow they started
(scripts/SimulatorControl.ps1, Assert-SimulatorIdentity) and record it in
their receipts, so a run cannot silently use another lab on the same port.
"""

import json
import re
import shutil
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SIM = ROOT / "simulator"
PWSH = shutil.which("pwsh") or shutil.which("powershell")
HELPER = ROOT / "scripts" / "SimulatorControl.ps1"
RUNNERS = ("run_full_project.ps1", "run_phase2_adapt.ps1", "run_phase3_dynamics.ps1")



def _generated_names():
    text = (SIM / "generate_faulty_flows.ps1").read_text(encoding="utf-8-sig")
    return set(re.findall(r"New-FaultyFlow\s+'[^']+'\s+'([^']+)'", text))


def _health_func(nodes):
    funcs = [n for n in nodes if n.get("type") == "function" and n.get("id", "").endswith("_health_fn")]
    assert len(funcs) == 1
    return funcs[0]["func"]


def _reported_profile(path: Path):
    nodes = json.loads(path.read_text(encoding="utf-8-sig"))
    found = re.findall(r"^\s*profile: '([^']*)',$", _health_func(nodes), flags=re.M)
    assert len(found) == 1, f"{path.name}: expected one profile line in the health function"
    return found[0]


def _hand_written_flows():
    generated = _generated_names()
    return sorted(p for p in SIM.glob("simulator_flow*.json")
                  if p.name not in generated)


@pytest.mark.parametrize("path", _hand_written_flows(), ids=lambda p: p.name)
def test_hand_written_flow_reports_its_own_profile(path):
    expected = path.name[len("simulator_flow_"):-len(".json")]
    assert _reported_profile(path) == expected


def test_health_answer_keeps_its_existing_fields():
    nodes = json.loads((SIM / "simulator_flow_lab3.json").read_text(encoding="utf-8"))
    func = _health_func(nodes)
    for field in ("status: 'ok'", "tab: 'lab3_tab'", "uptime_ms:", "endpoints:"):
        assert field in func


def test_generator_sets_the_profile_of_every_faulty_flow(tmp_path):
    if PWSH is None:
        pytest.skip("PowerShell is not installed")
    work = tmp_path / "simulator"
    work.mkdir()
    for path in SIM.glob("simulator_flow_*.json"):
        shutil.copy2(path, work / path.name)
    shutil.copy2(SIM / "generate_faulty_flows.ps1", work / "generate_faulty_flows.ps1")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File",
                           str(work / "generate_faulty_flows.ps1")],
                          capture_output=True, text=True, timeout=300)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    names = _generated_names()
    assert len(names) == 19
    for name in names:
        assert _reported_profile(work / name) == name[len("simulator_flow_"):-len(".json")]
        # The committed faulty flows are exactly the generator's output
        # (UTF-8 without byte-order mark, LF), so CI regenerating them before
        # every fault cell changes nothing.
        assert (work / name).read_bytes() == (SIM / name).read_bytes(), f"{name} is stale"
        assert not (SIM / name).read_bytes().startswith(b"\xef\xbb\xbf")


def test_generator_output_does_not_depend_on_line_endings(tmp_path):
    if PWSH is None:
        pytest.skip("PowerShell is not installed")
    work = tmp_path / "simulator"
    work.mkdir()
    for path in SIM.glob("simulator_flow_*.json"):
        (work / path.name).write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
    shutil.copy2(SIM / "generate_faulty_flows.ps1", work / "generate_faulty_flows.ps1")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File",
                           str(work / "generate_faulty_flows.ps1")],
                          capture_output=True, text=True, timeout=300)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    for name in _generated_names():
        assert (work / name).read_bytes() == (SIM / name).read_bytes(), name


class _Health(BaseHTTPRequestHandler):
    payload = {}

    def do_GET(self):  # noqa: N802 (http.server API)
        body = json.dumps(type(self).payload).encode("utf-8")
        self.send_response(200 if self.path == "/health" else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture
def fake_simulator():
    handler = type("Handler", (_Health,), {"payload": {}})
    server = HTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield server.server_address[1], handler
    server.shutdown()
    server.server_close()


def _assert_identity(port, flow, tmp_path):
    script = tmp_path / "probe.ps1"
    script.write_text(f"""
. '{HELPER}'
try {{ 'REPORTED=' + (Assert-SimulatorIdentity -Port {port} -FlowFile '{flow}') }}
catch {{ 'ERROR: ' + $_.Exception.Message }}
""", encoding="utf-8")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
                          capture_output=True, text=True, timeout=120)
    return proc.stdout.strip()


def test_identity_check_accepts_the_started_flow_and_rejects_others(fake_simulator, tmp_path):
    if PWSH is None:
        pytest.skip("PowerShell is not installed")
    port, handler = fake_simulator
    handler.payload = {"status": "ok", "tab": "lab3_tab", "profile": "lab3_f1dead"}
    assert _assert_identity(port, "simulator_flow_lab3_f1dead.json", tmp_path) == "REPORTED=lab3_f1dead"
    out = _assert_identity(port, "simulator_flow_lab3.json", tmp_path)
    assert out.startswith("ERROR: Wrong simulator on port")
    assert "reports profile 'lab3_f1dead'" in out and "(profile 'lab3')" in out
    handler.payload = {"status": "ok", "tab": "lab3_tab"}
    out = _assert_identity(port, "simulator_flow_lab3.json", tmp_path)
    assert "has no 'profile' field" in out


def test_flow_profile_name_comes_from_the_file_name(tmp_path):
    if PWSH is None:
        pytest.skip("PowerShell is not installed")
    script = tmp_path / "names.ps1"
    script.write_text(f"""
. '{HELPER}'
Get-FlowProfileName -FlowFile 'simulator_flow_labmon_f1dead.json'
Get-FlowProfileName -FlowFile 'simulator/simulator_flow_lab2_slow.json'
try {{ Get-FlowProfileName -FlowFile 'other.json' }} catch {{ 'ERROR' }}
""", encoding="utf-8")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
                          capture_output=True, text=True, timeout=120)
    assert proc.stdout.split() == ["labmon_f1dead", "lab2_slow", "ERROR"]


@pytest.mark.parametrize("runner", RUNNERS)
def test_runners_check_and_record_the_reported_profile(runner):
    text = (ROOT / runner).read_text(encoding="utf-8-sig")
    assert "scripts\\SimulatorControl.ps1" in text
    assert "Assert-SimulatorIdentity -Port" in text
    assert re.search(r"simulator_profile_reported\s*=", text)
    assert re.search(r"simulator_flow\s*=", text)
