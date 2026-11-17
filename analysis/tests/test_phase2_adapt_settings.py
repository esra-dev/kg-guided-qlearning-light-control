"""Fault-stage learning-settings profiles (record / paired / aligned).

The fault-stage runner ``run_phase2_adapt.ps1`` no longer relies on Java
defaults for its learning settings. It resolves a named profile from
``config/run_config.json`` (``phase2.adapt_settings``) through
``scripts/Read-RunConfig.ps1``, passes the values as ``-P`` properties, reads
the effective values back from the JVM log, and refuses to write the
``ADAPT_OK`` receipt on a mismatch. These tests pin:

- the three profiles and the exact ``-P`` list each one resolves to;
- that ``record`` passes exactly the Java defaults the archived runs used;
- that every passed key reaches the JVM (Gradle forwarding list);
- the runner wiring (parameter, pass-through, gate before the receipt);
- the log read-back and comparison functions, run on synthetic log text.
"""

import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which("pwsh") or shutil.which("powershell")

RECORD_ARGS = [
    "-Preward.clip=50.0",
    "-Preward.shaping=none",
    "-Pstereo.priorScale=1.0",
    "-Pstereo.priorDecayFloor=0.0",
    "-Pstereo.adaptiveTrust=false",
    "-Pphase2.pairedArmRng=false",
    "-Pstereo.energyPriorWeight=0.0",
]
PAIRED_ARGS = [a.replace("pairedArmRng=false", "pairedArmRng=true") for a in RECORD_ARGS]
ALIGNED_ARGS = ["-Preward.clip=200.0"] + PAIRED_ARGS[1:]


def _read(rel):
    return (ROOT / rel).read_text(encoding="utf-8-sig")


def _settings():
    return json.loads(_read("config/run_config.json"))["phase2"]["adapt_settings"]


def _http_keys():
    text = _read("build.gradle")
    match = re.search(r"def _httpKeys = \[(.*?)\]", text, re.DOTALL)
    body = re.sub(r"//[^\n]*", "", match.group(1))
    return set(re.findall(r"'([^']+)'", body))


def _pwsh(tmp_path, script):
    if PWSH is None:
        pytest.skip("PowerShell is not installed")
    path = tmp_path / "probe.ps1"
    path.write_text(script, encoding="utf-8")
    proc = subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-File", str(path)],
                          capture_output=True, text=True, cwd=str(ROOT), timeout=120)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    return proc.stdout


# ------------------------------------------------------------------ config --

def test_three_profiles_with_record_as_default():
    s = _settings()
    assert s["default"] == "record"
    assert set(s["profiles"]) == {"record", "paired", "aligned"}
    for name, prof in s["profiles"].items():
        assert prof["description"].strip(), name
        # the fade length stays automatic in every profile
        assert "stereo.priorDecayEpisodes" not in prof["properties"], name
        assert prof["expected"]["prior_decay"] == "auto", name
        for value in prof["properties"].values():
            assert isinstance(value, str), name


def test_record_passes_exactly_the_java_defaults_of_the_archived_runs():
    record = _settings()["profiles"]["record"]
    java = _read("src/env/tools/QLearner.java")
    assert 'parseDoubleProp("reward.clip", 50.0)' in java
    assert 'parseDoubleProp("stereo.priorScale", 1.0)' in java
    assert 'parseDoubleProp("stereo.priorDecayFloor", 0.0)' in java
    assert 'System.getProperty("reward.shaping", "none")' in java
    assert 'System.getProperty("stereo.adaptiveTrust", "false")' in java
    assert 'parseBooleanProp("phase2.pairedArmRng", false)' in java
    assert 'parseIntProp("stereo.priorFadeVisits", 25)' in java
    assert 'parseDoubleProp("stereo.energyPriorWeight", 0.0)' in java
    assert record["properties"] == {
        "reward.clip": "50.0", "reward.shaping": "none", "stereo.priorScale": "1.0",
        "stereo.priorDecayFloor": "0.0", "stereo.adaptiveTrust": "false",
        "phase2.pairedArmRng": "false", "stereo.energyPriorWeight": "0.0"}
    assert record["expected"]["protocol_version"] == "legacy"
    assert record["expected"]["paired_arm_rng"] is False


def test_profiles_differ_only_where_intended():
    p = _settings()["profiles"]
    rec, par, ali = p["record"], p["paired"], p["aligned"]
    diff = {k for k in rec["properties"] if rec["properties"][k] != par["properties"][k]}
    assert diff == {"phase2.pairedArmRng"}
    diff = {k for k in par["properties"] if par["properties"][k] != ali["properties"][k]}
    assert diff == {"reward.clip"}
    assert ali["expected"]["reward_clip"] == 200.0
    assert par["expected"]["paired_arm_rng"] is True and ali["expected"]["paired_arm_rng"] is True


def test_every_profile_property_reaches_the_jvm():
    keys = _http_keys()
    assert "phase2.pairedArmRng" in keys
    for name, prof in _settings()["profiles"].items():
        missing = sorted(set(prof["properties"]) - keys)
        assert not missing, f"{name}: not forwarded by build.gradle: {missing}"


@pytest.mark.parametrize("name,expected", [("record", RECORD_ARGS),
                                           ("paired", PAIRED_ARGS),
                                           ("aligned", ALIGNED_ARGS)])
def test_read_runconfig_resolves_the_intended_property_list(tmp_path, name, expected):
    out = _pwsh(tmp_path,
                f"$s = & '{ROOT / 'scripts' / 'Read-RunConfig.ps1'}' -AdaptSettingsProfile {name}\n"
                "$s.gradle_args | ForEach-Object { Write-Output $_ }\n")
    assert out.split() == expected


def test_read_runconfig_rejects_unknown_profile(tmp_path):
    out = _pwsh(tmp_path,
                f"try {{ & '{ROOT / 'scripts' / 'Read-RunConfig.ps1'}' -AdaptSettingsProfile nope; 'NO-ERROR' }}"
                " catch { 'ERROR: ' + $_.Exception.Message }\n")
    assert "ERROR" in out and "Known: record, paired, aligned" in out


# ------------------------------------------------------------------ runner --

def test_runner_wiring():
    text = _read("run_phase2_adapt.ps1")
    assert re.search(r'\[string\]\$SettingsProfile\s*=\s*"record"', text)
    assert "-AdaptSettingsProfile $SettingsProfile" in text
    assert "$gArgs += $AdaptSettings.gradle_args" in text
    # the gate runs before the receipt is written
    gate = text.index("Get-AdaptSettingsMismatches -Expected")
    assert gate < text.index("$manifest | ConvertTo-Json")
    for key in ("settings_profile ", "paired_arm_rng ", "effective_settings "):
        assert key in text


def test_workflow_passes_settings_profile():
    text = _read(".github/workflows/phase2.yml")
    assert "settings_profile:" in text
    assert "SETTINGS_PROFILE: ${{ github.event.inputs.settings_profile || 'record' }}" in text
    assert "-SettingsProfile ${{ env.SETTINGS_PROFILE }}" in text


SYNTHETIC_LOG = """\
taskAdapt: active.profile=lab2_f1bdead
15:39:39.765 [Thread-3] INFO  tools.StereotypeReasoner - StereotypeReasoner: knowledge layer = FULL
15:39:40.447 [Thread-3] INFO  tools.QLearner -   Goal: [3, 3]
15:39:40.453 [Thread-3] INFO  tools.QLearner -   Reward: clip={clip} shaping=none priorScale=1.0 priorDecayEps=2500 (auto) priorDecayFloor=0.0 priorFadeVisits=25 adaptiveTrust=false runSeed=1
15:39:40.454 [Thread-3] INFO  tools.QLearner -   QLearner RNG: pairedArmRng={paired} protocolVersion=legacy armMixedIntoSeed={mixed} baseSeed=921069992870502293
15:39:40.455 [Thread-3] INFO  tools.QLearner -   EnergyPrior: weight=0.0
15:39:40.457 [Thread-5] INFO  tools.QLearner - QLearner.setTrainingBudgetEpisodes: numEpisodes=200 {arrow} priorDecayEpisodes={decay} (auto = 25% of budget)
"""


def _gate(tmp_path, log_text, profile, budget=200, seed=1):
    log = tmp_path / "cell.log"
    log.write_text(log_text, encoding="utf-8")
    runner = ROOT / "run_phase2_adapt.ps1"
    script = f"""
$ErrorActionPreference = 'Stop'
$ast = [System.Management.Automation.Language.Parser]::ParseFile('{runner}', [ref]$null, [ref]$null)
$wanted = @('Get-DeliveredKnowledgeLayer', 'Get-EffectiveAdaptSettings', 'Get-AdaptSettingsMismatches')
foreach ($f in $ast.FindAll({{ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }}, $true)) {{
    if ($wanted -contains $f.Name) {{ . ([scriptblock]::Create($f.Extent.Text)) }}
}}
$s = & '{ROOT / 'scripts' / 'Read-RunConfig.ps1'}' -AdaptSettingsProfile {profile}
$eff = Get-EffectiveAdaptSettings -LogPath '{log}'
$bad = Get-AdaptSettingsMismatches -Expected $s.expected -Effective $eff -BudgetOverride {budget} -RunSeedRequested {seed}
[pscustomobject]@{{ effective = $eff; mismatches = @($bad) }} | ConvertTo-Json -Depth 4 -Compress
"""
    return json.loads(_pwsh(tmp_path, script))


def test_read_back_verifies_record_settings(tmp_path):
    log = SYNTHETIC_LOG.format(clip="50.0", paired="false", mixed="true",
                               arrow="→", decay=50)
    res = _gate(tmp_path, log, "record")
    assert res["mismatches"] == []
    eff = res["effective"]
    assert eff["reward_clip"] == 50.0 and eff["prior_decay"] == "auto"
    assert eff["prior_decay_episodes"] == 50 and eff["training_budget_episodes"] == 200
    assert eff["paired_arm_rng"] is False and eff["protocol_version"] == "legacy"
    assert eff["knowledge_layer"] == "full" and eff["run_seed"] == 1


def test_read_back_tolerates_a_garbled_arrow(tmp_path):
    # On some consoles the arrow character arrives as mojibake.
    log = SYNTHETIC_LOG.format(clip="200.0", paired="true", mixed="false",
                               arrow="â†’", decay=50)
    assert _gate(tmp_path, log, "aligned")["mismatches"] == []


def test_read_back_reports_every_mismatch(tmp_path):
    # record expected, but the JVM ran with clip 200 and a paired stream
    log = SYNTHETIC_LOG.format(clip="200.0", paired="true", mixed="false",
                               arrow="->", decay=50)
    bad = _gate(tmp_path, log, "record")["mismatches"]
    assert any(m.startswith("reward_clip") for m in bad)
    assert any(m.startswith("paired_arm_rng") for m in bad)
    # wrong fade length and wrong budget
    log = SYNTHETIC_LOG.format(clip="50.0", paired="false", mixed="true",
                               arrow="->", decay=60)
    bad = _gate(tmp_path, log, "record", budget=300, seed=2)["mismatches"]
    assert any(m.startswith("prior_decay_episodes") for m in bad)
    assert any(m.startswith("training budget") for m in bad)
    assert any(m.startswith("run_seed") for m in bad)


def test_knowledge_layer_mismatch_stops_before_the_receipt():
    text = _read("run_phase2_adapt.ps1")
    stop = text.index('throw "Knowledge-layer treatment NOT verified')
    assert stop < text.index("$manifest | ConvertTo-Json")


# --------------------------------------------------------- training runner --

TRAIN_LOG = """\
11:37:52.450 [Thread-3] INFO  tools.StereotypeReasoner - StereotypeReasoner: knowledge layer = {layer}
11:37:52.958 [Thread-3] INFO  tools.QLearner -   Reward: clip=200.0 shaping=none priorScale=1.0 priorDecayEps=10000 priorDecayFloor=0.0 priorFadeVisits=25 adaptiveTrust=false runSeed=1
11:37:52.959 [Thread-3] INFO  tools.QLearner -   EnergyPrior: weight={energy}
11:37:52.962 [Thread-2] INFO  tools.QLearner - QLearner.setTrainingBudgetEpisodes: ignored (sysprop -Dstereo.priorDecayEpisodes=10000 wins). priorDecayEpisodes=10000
"""


def _train_gate(tmp_path, log_text, layer="full", clip="200.0", decay="10000", energy="0.0"):
    log = tmp_path / "train.log"
    log.write_text(log_text, encoding="utf-8")
    runner = ROOT / "run_full_project.ps1"
    script = f"""
$ErrorActionPreference = 'Stop'
$ast = [System.Management.Automation.Language.Parser]::ParseFile('{runner}', [ref]$null, [ref]$null)
foreach ($f in $ast.FindAll({{ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }}, $true)) {{
    if ($f.Name -eq 'Get-TrainingDeliveredSettings') {{ . ([scriptblock]::Create($f.Extent.Text)) }}
}}
try {{
    $d = Get-TrainingDeliveredSettings -LogPath '{log}' -Layer '{layer}' -RewardClip {clip} -PriorDecayEps {decay} -EnergyWeight {energy}
    $d | ConvertTo-Json -Compress
}} catch {{ 'GATE-ERROR: ' + $_.Exception.Message }}
"""
    return _pwsh(tmp_path, script).strip()


def test_training_gate_accepts_delivered_settings(tmp_path):
    out = _train_gate(tmp_path, TRAIN_LOG.format(layer="FULL", energy="0.0"))
    d = json.loads(out)
    assert d == {"knowledge_layer": "full", "reward_clip": 200.0, "prior_decay_mode": "fixed",
                 "prior_decay_episodes": 10000, "energy_prior_weight": 0.0}
    out = _train_gate(tmp_path, TRAIN_LOG.format(layer="FULL", energy="2.0"), energy="2.0")
    assert json.loads(out)["energy_prior_weight"] == 2.0


def test_training_gate_rejects_undelivered_layer_and_weight(tmp_path):
    out = _train_gate(tmp_path, TRAIN_LOG.format(layer="FULL", energy="0.0"),
                      layer="brick", energy="2.0")
    assert out.startswith("GATE-ERROR")
    assert "knowledge layer" in out and "energy-preference weight" in out
    out = _train_gate(tmp_path, TRAIN_LOG.format(layer="FULL", energy="0.0"), decay="$null")
    assert out.startswith("GATE-ERROR") and "fade length" in out
    out = _train_gate(tmp_path, TRAIN_LOG.format(layer="FULL", energy="0.0").replace(
        "EnergyPrior", "Other"))
    assert out.startswith("GATE-ERROR") and "EnergyPrior" in out


def test_training_manifest_records_delivery_after_the_gate():
    text = _read("run_full_project.ps1")
    gate = text.index("$deliveredSettings = Get-TrainingDeliveredSettings")
    for key in ("knowledge_layer_requested = $RequestedKnowledgeLayer",
                "knowledge_layer_delivered = $deliveredSettings.knowledge_layer",
                "reward_clip_effective = ", "prior_decay_episodes_effective = ",
                "stereo_energy_prior_weight_requested = ",
                "stereo_energy_prior_weight_effective = "):
        assert gate < text.index(key), key


def test_read_back_rejects_a_fixed_fade_length(tmp_path):
    log = SYNTHETIC_LOG.replace(
        "QLearner.setTrainingBudgetEpisodes: numEpisodes=200 {arrow} priorDecayEpisodes={decay} (auto = 25% of budget)",
        "QLearner.setTrainingBudgetEpisodes: ignored (sysprop -Dstereo.priorDecayEpisodes=10000 wins). priorDecayEpisodes=10000",
    ).format(clip="50.0", paired="false", mixed="true")
    bad = _gate(tmp_path, log, "record")["mismatches"]
    assert any(m.startswith("prior_decay:") for m in bad)
