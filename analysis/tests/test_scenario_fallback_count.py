"""The receipts' scenario_fallback_count is a real count, not a constant.

LabEnvironment counts every reset to a random start state (the agents do that
only when no scenario is scheduled) and logs the total as
``SCENARIO_FALLBACK_COUNT=<n>`` at the end of the run. Both runners read that
line into their receipts, and a missing line fails the cell. The validators
that require 0 therefore check something real.
"""

import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh") or shutil.which("powershell")


def _read(rel):
    return (ROOT / rel).read_text(encoding="utf-8-sig")


def test_agents_report_the_count_before_their_final_outputs():
    ql = _read("src/agt/illuminance_controller_agent_ql.asl")
    done = ql.index("+!train(N) : num_episodes(MaxEpisodes) & N >= MaxEpisodes")
    report = ql.index("reportScenarioFallbackCount", done)
    assert report < ql.index("!execute_policy(0).", done)
    adapt = _read("src/agt/illuminance_controller_agent_adapt.asl")
    finish = adapt.index("+!adapt_finish <-")
    report = adapt.index("reportScenarioFallbackCount", finish)
    # after the greedy check (the last place that may reset randomly) and
    # before the recovery CSV the runner waits for
    assert adapt.index("!certify_recovery(GoalRate);", finish) < report
    assert report < adapt.index("saveRecoveryLog(", finish)


@pytest.mark.parametrize("runner", ["run_full_project.ps1", "run_phase2_adapt.ps1"])
def test_runners_write_the_logged_count(runner):
    text = _read(runner)
    assert "function Get-ScenarioFallbackCount" in text
    assert re.search(r"scenario_fallback_count\s*=\s*\$scenarioFallbackCount", text)
    assert not re.search(r"scenario_fallback_count\s*=\s*0\b", text)
    read = text.index("$scenarioFallbackCount = Get-ScenarioFallbackCount")
    assert read < text.index("scenario_fallback_count", read)


@pytest.mark.parametrize("runner", ["run_full_project.ps1", "run_phase2_adapt.ps1"])
def test_count_is_parsed_and_a_missing_line_fails(tmp_path, runner):
    if PWSH is None:
        pytest.skip("PowerShell is not installed")
    good = tmp_path / "good.log"
    good.write_text("x INFO tools.LabEnvironment - SCENARIO_FALLBACK_COUNT=0\n"
                    "y INFO tools.LabEnvironment - SCENARIO_FALLBACK_COUNT=3\n", encoding="utf-8")
    bad = tmp_path / "bad.log"
    bad.write_text("nothing here\n", encoding="utf-8")
    script = tmp_path / "probe.ps1"
    script.write_text(f"""
$ast = [System.Management.Automation.Language.Parser]::ParseFile('{ROOT / runner}', [ref]$null, [ref]$null)
foreach ($f in $ast.FindAll({{ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }}, $true)) {{
    if ($f.Name -eq 'Get-ScenarioFallbackCount') {{ . ([scriptblock]::Create($f.Extent.Text)) }}
}}
Write-Output ("count=" + (Get-ScenarioFallbackCount -LogPath '{good}'))
try {{ Get-ScenarioFallbackCount -LogPath '{bad}'; 'NO-ERROR' }} catch {{ 'ERROR: ' + $_.Exception.Message }}
""", encoding="utf-8")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
                          capture_output=True, text=True, timeout=120)
    assert "count=3" in proc.stdout
    assert "ERROR: Fallback count: no SCENARIO_FALLBACK_COUNT line" in proc.stdout
