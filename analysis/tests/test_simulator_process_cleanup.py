"""The runners stop the whole simulator process tree and refuse a busy port.

On Windows a runner starts node-red.cmd, and the simulator itself is a child
node.exe. Stopping only the started process left that child running and
holding the port, so a later run could talk to the previous lab.
scripts/SimulatorControl.ps1 stops the started process with all descendants
(plus any process whose command line carries the runner's own --userDir), and
refuses to start a simulator on a port that is already in use. These tests run
the helpers against real processes and a real listening socket.
"""

import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh") or shutil.which("powershell")
HELPER = ROOT / "scripts" / "SimulatorControl.ps1"
RUNNERS = ("run_full_project.ps1", "run_phase2_adapt.ps1", "run_phase3_dynamics.ps1")

needs_pwsh = pytest.mark.skipif(PWSH is None, reason="PowerShell is not installed")


def _pwsh(script_text, tmp_path, timeout=180):
    script = tmp_path / "probe.ps1"
    script.write_text(f". '{HELPER}'\n" + script_text, encoding="utf-8")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
                          capture_output=True, text=True, timeout=timeout)
    return proc.stdout + proc.stderr


def _alive(pid):
    if os.name == "nt":
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"],
                             capture_output=True, text=True).stdout
        return re.search(rf"\b{pid}\b", out) is not None
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    # a zombie has exited but is not reaped yet
    try:
        return Path(f"/proc/{pid}/stat").read_text().split()[2] != "Z"
    except OSError:
        return False


def _wait_file(path, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if path.exists() and path.read_text().strip():
            return path.read_text().strip()
        time.sleep(0.2)
    raise AssertionError(f"{path} was not written")


@needs_pwsh
def test_busy_port_is_refused_and_the_holder_is_named(tmp_path):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        out = _pwsh(f"try {{ Assert-SimulatorPortFree -Port {port} -Purpose 'the lab2 simulator'; 'NO-ERROR' }}"
                    f" catch {{ 'ERROR: ' + $_.Exception.Message }}", tmp_path)
    assert f"ERROR: Port {port} is already in use, so the lab2 simulator cannot be started there" in out
    assert f"PID {os.getpid()}" in out, out
    out = _pwsh(f"Assert-SimulatorPortFree -Port {port}; 'FREE'", tmp_path)
    assert "FREE" in out


@needs_pwsh
def test_the_started_process_and_its_children_are_stopped(tmp_path):
    child_pid_file = tmp_path / "child.pid"
    parent = tmp_path / "parent.py"
    # The parent starts a long-running child (like node-red.cmd starting
    # node.exe), writes the child's id and waits.
    parent.write_text(
        "import subprocess, sys, time\n"
        "c = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(600)'])\n"
        f"open(r'{child_pid_file}', 'w').write(str(c.pid))\n"
        "time.sleep(600)\n", encoding="utf-8")
    out_file = tmp_path / "out.txt"
    script = f"""
$p = Start-Process -FilePath '{sys.executable}' -ArgumentList '"{parent}"' -PassThru
$deadline = (Get-Date).AddSeconds(30)
while (-not (Test-Path '{child_pid_file}') -and (Get-Date) -lt $deadline) {{ Start-Sleep -Milliseconds 200 }}
Start-Sleep -Milliseconds 500
$tree = @(Get-ProcessTreeIds -RootId $p.Id)
$res = Stop-SimulatorTree -Process $p -Port 1 -WaitSec 1
Set-Content -Path '{out_file}' -Value ("parent=" + $p.Id + " tree=" + ($tree -join ',') + " stopped=" + (@($res.Stopped) -join ','))
"""
    _pwsh(script, tmp_path)
    child = int(_wait_file(child_pid_file))
    text = out_file.read_text()
    parent_pid = int(re.search(r"parent=(\d+)", text).group(1))
    tree = [int(x) for x in re.search(r"tree=([\d,]*)", text).group(1).split(",") if x]
    assert tree[0] == parent_pid and child in tree, text
    time.sleep(1)
    assert not _alive(child), f"child {child} survived: {text}"
    assert not _alive(parent_pid)


@needs_pwsh
def test_userdir_rule_stops_only_the_runner_own_simulator(tmp_path):
    user_dir = str(tmp_path / ".node-red-lab2")
    sleeper = "import time, sys; time.sleep(600)"
    own = subprocess.Popen([sys.executable, "-c", sleeper, "--userDir", user_dir])
    other = subprocess.Popen([sys.executable, "-c", sleeper, "--userDir", user_dir + "_f1bdead"])
    try:
        time.sleep(1)
        out = _pwsh(f"$res = Stop-SimulatorTree -Process $null -Port 1 -UserDir '{user_dir}' -WaitSec 1\n"
                    "'BYUSERDIR=' + (@($res.StoppedByUserDir) -join ',')", tmp_path)
        assert f"BYUSERDIR={own.pid}" in out, out
        time.sleep(1)
        assert own.poll() is not None
        assert other.poll() is None, "a process with a different --userDir was stopped"
    finally:
        for proc in (own, other):
            if proc.poll() is None:
                proc.kill()


@pytest.mark.parametrize("runner", RUNNERS)
def test_runners_use_the_tree_stop_and_never_reuse_a_simulator(runner):
    text = (ROOT / runner).read_text(encoding="utf-8-sig")
    assert "Assert-SimulatorPortFree -Port" in text
    assert "Stop-SimulatorTree -Process" in text
    # the old clean-up stopped only the started process by id
    assert not re.search(r"Stop-Process -Id \$(SimProcs|entry\.Value)", text)
    assert not re.search(r"Stop-Process -Id \$gProc\.Id", text)
    assert "already answering" not in text and "already running" not in text
    # the port check comes before the simulator is started
    assert text.index("Assert-SimulatorPortFree -Port") < text.index("Start-Process @spArgs")
