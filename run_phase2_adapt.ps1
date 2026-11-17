<#
.SYNOPSIS
    Phase 2 fault stage: runs the fault-detection, exclusion and re-learning
    agent in labs with a broken device.

.DESCRIPTION
    Needs the parent tables: the Q-tables that the same arm learned in the
    healthy parent lab (qtable_final_stereotypes_<true|false><parent suffix>*.csv
    in the repository root, written by run_full_project.ps1). Without them the
    agent starts from a zero table; the runner warns, and the receipt gets
    status cold_start.

    Before any simulator starts, every requested lab is checked: its training
    file in config/run_config.json (phase2.train_scenarios_map) must equal
    train_scenarios(...) of its profile in src/agt/lab_profiles.asl, and for a
    *_lowsun lab every situation of that file must set Sunshine to 100.

    Then, for each faulty lab:
      1. Starts its flow (simulator/simulator_flow_<lab>.json, written by
         simulator/generate_faulty_flows.ps1) on the parent lab's port. The
         port must be free; after the start the runner checks through /health
         that this flow answers (scripts/SimulatorControl.ps1).
      2. For each mode (ql_true, ql_false): deletes the cell's old outputs and
         runs "gradlew taskAdapt -Pprofile=<lab> -Pmode=<mode>" with the
         budget, seed, knowledge layer and settings-profile values as -P
         settings. The agent (illuminance_controller_agent_adapt.asl) loads
         the parent table, detects the broken device, blacklists it, lowers
         the target if it can no longer be reached, learns again and writes
         recovery_stereotypes_<bool><suffix>.csv (docs/ARCHITECTURE.md,
         section 6).
      3. Watches the JVM log (heartbeat every 60 s). Once the recovery CSV
         exists and the log has not grown for 90 s, the JVM tree is stopped
         and the cell counts as finished; after -WatchdogIdleSec seconds
         without log growth the JVM tree is stopped and the cell fails.
      4. Reads the recovery CSV, then checks the JVM log: the delivered
         knowledge layer must equal -KnowledgeLayer and the learning settings
         must equal those of the settings profile (reward limit, shaping,
         knowledge strength, fade, adaptive trust, protocol version, random
         stream, energy weight; also the seed and a requested budget);
         the number of random start states is read. Only then the receipt
         ADAPT_OK_stereotypes_<bool><suffix>.json is written.
      5. Stops the flow (whole process tree) before the next lab.

    The outcome measure is RecoveryEpisodes = ReconvergeEpisode -
    DetectEpisode (lower = faster recovery). The per-lab maps (port, flow,
    file suffix, parent, training file) are read from config/run_config.json
    (block phase2). The script changes no .asl file; the budget reaches the
    agent only through -Padapt.episodes (read with tools.jia.system_prop_num).

.PARAMETER AdaptProfiles
    Comma-separated subset of faulty profiles to run. Empty (default) = all
    profiles listed in config phase2.adapt_profiles.

.PARAMETER Modes
    Comma-separated subset of {ql_true, ql_false}. Default = both.

.PARAMETER Episodes
    Explicit re-learn budget (episodes) for every cell. 0 (default) = use each
    profile's training_params value from lab_profiles.asl. Any value > 0 is
    forwarded as -Padapt.episodes.

.PARAMETER Smoke
    Fast pipeline check: overrides Episodes with config phase2.smoke_episodes
    (default 200) unless -Episodes is given explicitly. Verifies the full
    detect -> blacklist -> warm-restart -> re-learn -> recovery-CSV -> stopMAS
    path without waiting for full convergence.

.PARAMETER SettingsProfile
    Named learning-settings profile from config/run_config.json
    phase2.adapt_settings.profiles: record (default; the settings of every
    archived fault-stage run), paired (both arms share one random stream) or
    aligned (paired plus the training reward limit 200). Its values are passed
    to the adaptation JVM explicitly as -P properties; after the run the
    effective values are read back from the JVM log and compared with the
    profile's expected values. A mismatch fails the cell before its ADAPT_OK
    receipt is written.

.PARAMETER WatchdogIdleSec
    Seconds without growth of the cell's JVM log after which the JVM is
    stopped and the cell fails (default 1200; phase2.yml passes 1800).

.PARAMETER SimReadyTimeoutSec
    Seconds to wait for the simulator to answer (default 90). A lab whose
    simulator does not answer in time is skipped with an error line.

.PARAMETER RunSeed
    Seed passed as -Prun.seed (default -1: nothing is passed and QLearner
    uses its base seed). The read-back check expects the JVM to report this
    seed (0 when -1 was given). The value is recorded in the receipt.

.PARAMETER KnowledgeLayer
    Knowledge layer of the whole fault stage in both arms (fault detector
    and re-learning): full (default), brick or redundancy. For brick and
    redundancy it is passed as -Pstereo.knowledgeLayer; for full nothing is
    passed, so the command line equals that of the archived campaigns. The
    layer the JVM reports in its log must match, otherwise the cell fails
    before its receipt is written.

.EXAMPLE
    .\run_phase2_adapt.ps1 -Smoke -AdaptProfiles lab2_f1dead -Modes ql_true
    Short pipeline check of one lab and one arm (budget phase2.smoke_episodes).

.EXAMPLE
    .\run_phase2_adapt.ps1 -Smoke -AdaptProfiles lab2_f1bdead -SettingsProfile paired

.EXAMPLE
    .\run_phase2_adapt.ps1 -AdaptProfiles lab2_f1bdead -Modes ql_true -Episodes 0 -RunSeed 3 -KnowledgeLayer brick
    One fault cell as a job of .github/workflows/phase2.yml runs it (after
    simulator/generate_faulty_flows.ps1 and with the parent tables of seed 3
    in the repository root).

.EXAMPLE
    .\run_phase2_adapt.ps1
    Every lab of phase2.adapt_profiles, both arms.

.OUTPUTS
    Files, relative to the repository root:
      run_phase2_adapt.log (appended to by every run)
      log/adapt_<lab>_<mode>.log and .err.log (JVM output of each cell)
      recovery_stereotypes_<bool><suffix>.csv, qtable_adapted_stereotypes_<bool><suffix>.csv,
      metrics_adapted_stereotypes_<bool><suffix>.csv (written by the agent)
      ADAPT_OK_stereotypes_<bool><suffix>.json (receipt; fields in
      docs/EXPERIMENTS.md, section 6)
    <bool> is true for ql_true and false for ql_false; <suffix> comes from
    phase2.qtable_suffix_map. A summary table is printed at the end.

    Exit code: 0 when every cell that ran produced a recovery CSV and a
    receipt; 1 when node-red, the config file or its phase2 block is
    missing, when -KnowledgeLayer or -SettingsProfile is invalid, when a
    pre-start check fails, or when any cell has no recovery CSV or no
    receipt (including a failed read-back check). A lab that is skipped
    (incomplete config entry, missing flow file, simulator not ready) does
    not by itself set exit code 1.

.NOTES
    Target shell: Windows PowerShell 5.1+ (also works under PowerShell 7+).
    Do not use PS7-only syntax here — no null-conditional (?.), null-coalescing
    (??), ternary (a ? b : c), or native command chaining (&&, ||).
#>

param(
    [string]$AdaptProfiles = "",
    [string]$Modes = "ql_true,ql_false",
    [int]$Episodes = 0,
    [switch]$Smoke,
    [int]$WatchdogIdleSec = 1200,
    [int]$SimReadyTimeoutSec = 90,
    # -1 = no -Prun.seed is passed (see the help block).
    [int]$RunSeed = -1,
    # full | brick | redundancy (see the help block).
    [string]$KnowledgeLayer = "full",
    # record | paired | aligned (config phase2.adapt_settings.profiles).
    [string]$SettingsProfile = "record"
)

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptRoot

# ─── Logging ──────────────────────────────────────────────────────────────────
$LogFile   = Join-Path $ScriptRoot "run_phase2_adapt.log"
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false
$LogStream = New-Object System.IO.StreamWriter(
    [System.IO.FileStream]::new($LogFile,
        [System.IO.FileMode]::Append,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::ReadWrite), $Utf8NoBom)

function Write-Log {
    param([string]$msg)
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$ts] $msg"
    if ($LogStream) { $LogStream.WriteLine($line); $LogStream.Flush() }
    Write-Host $line
}
function Write-Header { param([string]$msg)
    $bar = "=" * 64
    Write-Log ""; Write-Log $bar; Write-Log "  $msg"; Write-Log $bar
}
function Write-OK   { param([string]$msg) Write-Log "  [OK]  $msg" }
function Write-Info { param([string]$msg) Write-Log "  [..] $msg" }
function Write-Warn { param([string]$msg) Write-Log "  [!!] $msg" }
function Write-Fail { param([string]$msg) Write-Log "  [XX] $msg" }

# ─── Tool resolution ──────────────────────────────────────────────────────────
if ($env:OS -eq 'Windows_NT') {
    $GradleExe = Join-Path $ScriptRoot 'gradlew.bat'
} else {
    $GradleExe = Join-Path $ScriptRoot 'gradlew'
}

# On Windows, node-red.ps1 is NOT in PATHEXT so Start-Process would open it as
# text. Resolve node-red.cmd explicitly (runs via cmd.exe, stays alive for the
# child node process lifetime).
$_nrCmd = Get-Command "node-red.cmd" -ErrorAction SilentlyContinue
if (-not $_nrCmd) { $_nrCmd = Get-Command "node-red" -ErrorAction SilentlyContinue }
if (-not $_nrCmd) {
    Write-Fail "node-red not found in PATH. Install it with:  npm install -g node-red"
    if ($LogStream) { $LogStream.Close() }
    exit 1
}
$NodeRedCmd = $_nrCmd.Source

# Simulator identity helpers (Assert-SimulatorIdentity, ...).
. (Join-Path $ScriptRoot 'scripts\SimulatorControl.ps1')

# ─── Config ───────────────────────────────────────────────────────────────────
$CfgPath = Join-Path $ScriptRoot "config\run_config.json"
if (-not (Test-Path $CfgPath)) { Write-Fail "Missing config: $CfgPath"; exit 1 }
$Cfg = Get-Content -Raw $CfgPath | ConvertFrom-Json
$P2  = $Cfg.phase2
if ($null -eq $P2) { Write-Fail "config/run_config.json has no 'phase2' block"; exit 1 }

# Resolve the smoke budget (config-driven, with a safe fallback).
$SmokeEpisodes = 200
if ($P2.smoke_episodes) { $SmokeEpisodes = [int]$P2.smoke_episodes }

# Effective per-cell episode override (-Padapt.episodes). 0 => profile default.
$EffEpisodes = $Episodes
if ($Smoke -and $Episodes -le 0) { $EffEpisodes = $SmokeEpisodes }

# Profile / mode selection.
if ($AdaptProfiles.Trim() -ne "") {
    $ProfileList = $AdaptProfiles.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
} else {
    $ProfileList = @($P2.adapt_profiles)
}
$ModeList = $Modes.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }

# Knowledge layer. The empty string and "full" both mean the default: no
# -Pstereo.knowledgeLayer is passed, so the Gradle command line is the same as
# in the archived campaigns.
$KnowledgeLayer = $KnowledgeLayer.Trim().ToLowerInvariant()
if ($KnowledgeLayer -eq "") { $KnowledgeLayer = "full" }
if (@("full", "brick", "redundancy") -notcontains $KnowledgeLayer) {
    Write-Fail "KnowledgeLayer must be full|brick|redundancy, got '$KnowledgeLayer'"
    if ($LogStream) { $LogStream.Close() }
    exit 1
}

# Learning-settings profile. Resolved through Read-RunConfig.ps1, which reads
# phase2.adapt_settings and never the global 'learning' block (that block
# switches on reward shaping and adaptive trust for training run modes).
$SettingsProfile = $SettingsProfile.Trim()
if ($SettingsProfile -eq "") { $SettingsProfile = "record" }
try {
    $AdaptSettings = & (Join-Path $ScriptRoot 'scripts\Read-RunConfig.ps1') -AdaptSettingsProfile $SettingsProfile -ConfigPath $CfgPath
} catch {
    Write-Fail "Settings profile could not be resolved: $($_.Exception.Message)"
    if ($LogStream) { $LogStream.Close() }
    exit 1
}

# ─── Simulator health check ───────────────────────────────────────────────────
# True as soon as /health or /was/rl/status answers HTTP 200; false after
# $TimeoutSec. The flow identity is checked afterwards (Assert-SimulatorIdentity).
function Wait-Simulator {
    param([int]$Port, [int]$TimeoutSec = 90)
    $healthUrl = "http://127.0.0.1:$Port/health"
    $statusUrl = "http://127.0.0.1:$Port/was/rl/status"
    $deadline  = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        foreach ($url in @($healthUrl, $statusUrl)) {
            try {
                $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
                if ($r.StatusCode -eq 200) { return $true }
            } catch { }
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# Read any new key marker lines from a gradle cell log and echo them.
$TailFilter = '\[FAULT\]|DEFECTIVE|Blacklisted|Warm restart|Warm-loaded|Re-CONVERGED|Recovery metric|action space now|BUILD (FAILED|SUCCESSFUL)|All done'
function Invoke-TailLog {
    param([string]$LogPath, [ref]$PosRef)
    if (-not (Test-Path $LogPath)) { return }
    try {
        $fs = [System.IO.FileStream]::new($LogPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite)
        [void]$fs.Seek($PosRef.Value, [System.IO.SeekOrigin]::Begin)
        $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
        while (($tLine = $sr.ReadLine()) -ne $null) {
            if ($tLine -match $TailFilter) { Write-Info "  >> $($tLine.Trim())" }
        }
        $PosRef.Value = [long]$fs.Position
        $sr.Dispose(); $fs.Dispose()
    } catch { }
}

# ─── State ────────────────────────────────────────────────────────────────────
$SimProcs = @{}   # port -> process (only flows WE launched, so cleanup is safe)
$SimUserDirs = @{} # port -> --userDir of the flow we launched there
$Summary  = @()   # result rows for the final table
$script:HadFatalError = $false

# Stop the simulator this runner launched on $Port: the whole process tree
# (on Windows node-red.cmd runs the real node.exe as a child), then wait until
# the port is free, so the next fault profile on the same port cannot meet the
# previous flow.
function Stop-LaunchedSimulator { param([int]$Port)
    if (-not $SimProcs.ContainsKey($Port)) { return }
    try {
        $res = Stop-SimulatorTree -Process $SimProcs[$Port] -Port $Port -UserDir $SimUserDirs[$Port]
        Write-OK ("Stopped simulator on port {0} (process tree: {1}; by userDir: {2})" -f $Port,
            (@($res.Stopped) -join ','), (@($res.StoppedByUserDir) -join ','))
        if (-not $res.PortFree) { Write-Warn "Port $Port is still in use after stopping the simulator: $(Get-PortUsage -Port $Port)" }
    } catch { Write-Warn "Could not stop the simulator on port $Port : $_" }
    $SimProcs.Remove($Port)
    $SimUserDirs.Remove($Port)
}

# Helper: map mode -> the boolean string used in artefact filenames.
function Mode-Bool { param([string]$mode)
    if ($mode -eq 'ql_true') { return 'true' } else { return 'false' }
}

# Helper: look up a value on a PSCustomObject map by dynamic key (or $null).
function Map-Get { param($map, [string]$key)
    if ($null -eq $map) { return $null }
    $prop = $map.PSObject.Properties[$key]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

# ─── Protocol-v2 gate-manifest helpers (ADAPT_OK.json) ────────────────────────
function Get-Sha256Hex { param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()
    } finally { $sha.Dispose() }
}
function Get-FileSetSha256 { param([string[]]$Paths)
    $lines = @()
    foreach ($p in ($Paths | Sort-Object)) {
        $h = Get-Sha256Hex ([System.IO.File]::ReadAllBytes($p))
        $lines += ("{0}:{1}" -f ([System.IO.Path]::GetFileName($p)), $h)
    }
    return Get-Sha256Hex ([System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n")))
}
# Ordered scenario IDs (real, possibly non-contiguous) + schedule hash for a
# scenario JSON file. Mirrors ScenarioCatalog.scheduleSha256 (comma-joined IDs).
function Get-ScenarioSchedule { param([string]$ScenarioPath)
    $rows = Get-Content -Raw $ScenarioPath | ConvertFrom-Json
    $ids = @()
    foreach ($row in $rows) {
        if ($row.PSObject.Properties['id']) { $ids += [int]$row.id }
    }
    if ($ids.Count -eq 0) { throw "Scenario file has no IDs: $ScenarioPath" }
    if ((@($ids | Select-Object -Unique)).Count -ne $ids.Count) {
        throw "Scenario file has duplicate IDs: $ScenarioPath"
    }
    $hash = Get-Sha256Hex ([System.Text.Encoding]::UTF8.GetBytes(($ids -join ',')))
    return [pscustomobject]@{ ids = $ids; hash = $hash }
}
# Evidence of what a training file delivers: the schedule hash covers only the
# situation ids, so a low-sun file derived from its parent (same ids, other
# Sunshine values) has the same schedule hash. Returns the SHA-256 of the file
# and the set of Sunshine values its situations set.
function Get-ScenarioTreatment { param([string]$ScenarioPath)
    $bytes = [System.IO.File]::ReadAllBytes($ScenarioPath)
    $rows = [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
    $sun = @()
    foreach ($row in $rows) {
        if ($row.PSObject.Properties['id'] -and $row.PSObject.Properties['Sunshine']) { $sun += [double]$row.Sunshine }
    }
    $sunValues = @($sun | Sort-Object -Unique)
    return [pscustomobject]@{
        file_sha256     = Get-Sha256Hex $bytes
        sunshine_values = $sunValues
    }
}
# The adapt agent reads its scenario file from lab_profiles.asl
# (train_scenarios(...)), while the manifests/validators use
# config phase2.train_scenarios_map. Both must name the same file.
function Get-AslTrainScenarios { param([string]$Profile)
    $asl = Get-Content -Raw (Join-Path $ScriptRoot "src\agt\lab_profiles.asl")
    $pattern = 'lab_profile\("' + [regex]::Escape($Profile) + '",[\s\S]*?train_scenarios\("([^"]+)"\)'
    $m = [regex]::Match($asl, $pattern)
    if (-not $m.Success) { throw "lab_profiles.asl: no train_scenarios(...) for profile '$Profile'" }
    return $m.Groups[1].Value
}
# The knowledge layer the fault-stage JVM really used, read from the
# StereotypeReasoner banner in the cell's log ("StereotypeReasoner: knowledge
# layer = BRICK"; the last banner counts). Returns it in lower case, or
# "unknown" when there is no banner; the runner then stops the cell before the
# receipt, because "unknown" never equals the requested layer.
function Get-DeliveredKnowledgeLayer { param([string]$LogPath)
    if (-not (Test-Path $LogPath)) { return "unknown" }
    $banner = $null
    try {
        $banner = Select-String -Path $LogPath `
            -Pattern 'StereotypeReasoner: knowledge layer = (\w+)' -ErrorAction Stop
    } catch { return "unknown" }
    if (-not $banner) { return "unknown" }
    return (@($banner) | Select-Object -Last 1).Matches[0].Groups[1].Value.ToLowerInvariant()
}

# Settings read-back: the effective learning settings the adaptation JVM
# reported in its log. Sources (the last occurrence wins; one JVM per cell):
#   "Reward: clip=.. shaping=.. priorScale=.. priorDecayEps=.. [(auto)]
#    priorDecayFloor=.. priorFadeVisits=.. adaptiveTrust=.. runSeed=.."
#   "setTrainingBudgetEpisodes: numEpisodes=N -> priorDecayEpisodes=D (auto ..."
#     or "setTrainingBudgetEpisodes: ignored (sysprop ...=D wins). priorDecayEpisodes=D"
#     (the Reward line shows the fade length BEFORE the agent sets the budget,
#     so the effective fade length is taken from this line)
#   "QLearner RNG: pairedArmRng=.. protocolVersion=.. armMixedIntoSeed=.. baseSeed=.."
#   "EnergyPrior: weight=.."
#   "StereotypeReasoner: knowledge layer = .."
# A missing line is an error: an unreported setting cannot be verified.
function Get-EffectiveAdaptSettings { param([string]$LogPath)
    if (-not (Test-Path -LiteralPath $LogPath)) { throw "settings read-back: cell log not found: $LogPath" }
    $text = [System.IO.File]::ReadAllText($LogPath)
    $inv = [System.Globalization.CultureInfo]::InvariantCulture
    $eff = [ordered]@{}

    $rm = [regex]::Matches($text, 'Reward: clip=(\S+) shaping=(\S+) priorScale=(\S+) priorDecayEps=(-?\d+)( \(auto\))? priorDecayFloor=(\S+) priorFadeVisits=(-?\d+) adaptiveTrust=(\w+)[^\r\n]*? runSeed=(-?\d+)')
    if ($rm.Count -eq 0) { throw "settings read-back: no 'Reward:' line in $LogPath" }
    $r = $rm[$rm.Count - 1]
    $eff['reward_clip']        = [double]::Parse($r.Groups[1].Value, $inv)
    $eff['reward_shaping']     = $r.Groups[2].Value
    $eff['prior_scale']        = [double]::Parse($r.Groups[3].Value, $inv)
    $eff['prior_decay_floor']  = [double]::Parse($r.Groups[6].Value, $inv)
    $eff['prior_fade_visits']  = [int]$r.Groups[7].Value
    $eff['adaptive_trust']     = ($r.Groups[8].Value -eq 'true')
    $eff['run_seed']           = [long]$r.Groups[9].Value

    $fa = [regex]::Matches($text, 'setTrainingBudgetEpisodes: numEpisodes=(\d+) \S+ priorDecayEpisodes=(\d+) \(auto')
    $fi = [regex]::Matches($text, 'setTrainingBudgetEpisodes: ignored \(sysprop -Dstereo\.priorDecayEpisodes=(\d+) wins\)\. priorDecayEpisodes=(\d+)')
    if ($fa.Count -gt 0 -and $fi.Count -eq 0) {
        $f = $fa[$fa.Count - 1]
        $eff['prior_decay']              = 'auto'
        $eff['training_budget_episodes'] = [int]$f.Groups[1].Value
        $eff['prior_decay_episodes']     = [int]$f.Groups[2].Value
    } elseif ($fi.Count -gt 0 -and $fa.Count -eq 0) {
        $f = $fi[$fi.Count - 1]
        $eff['prior_decay']              = 'fixed'
        $eff['training_budget_episodes'] = $null
        $eff['prior_decay_episodes']     = [int]$f.Groups[2].Value
    } else {
        throw "settings read-back: expected exactly one kind of 'setTrainingBudgetEpisodes' line in $LogPath (auto: $($fa.Count), fixed: $($fi.Count))"
    }

    $gm = [regex]::Matches($text, 'QLearner RNG: pairedArmRng=(true|false) protocolVersion=(\S+) armMixedIntoSeed=(true|false) baseSeed=(-?\d+)')
    if ($gm.Count -eq 0) { throw "settings read-back: no 'QLearner RNG:' line in $LogPath" }
    $g = $gm[$gm.Count - 1]
    $eff['paired_arm_rng']      = ($g.Groups[1].Value -eq 'true')
    $eff['protocol_version']    = $g.Groups[2].Value
    $eff['arm_mixed_into_seed'] = ($g.Groups[3].Value -eq 'true')
    $eff['base_seed']           = $g.Groups[4].Value

    $em = [regex]::Matches($text, 'EnergyPrior: weight=(\S+)')
    if ($em.Count -eq 0) { throw "settings read-back: no 'EnergyPrior:' line in $LogPath" }
    $eff['energy_prior_weight'] = [double]::Parse($em[$em.Count - 1].Groups[1].Value, $inv)

    $eff['knowledge_layer'] = Get-DeliveredKnowledgeLayer -LogPath $LogPath
    return $eff
}

# Compare the read-back settings with the settings profile's expected values.
# Returns a list of human-readable mismatches (empty = verified).
function Get-AdaptSettingsMismatches {
    param($Expected, $Effective, [int]$BudgetOverride, [long]$RunSeedRequested)
    $bad = @()
    foreach ($k in @('reward_clip', 'prior_scale', 'prior_decay_floor', 'energy_prior_weight')) {
        if ([double]$Effective[$k] -ne [double]$Expected[$k]) {
            $bad += ("{0}: expected {1}, log shows {2}" -f $k, $Expected[$k], $Effective[$k])
        }
    }
    foreach ($k in @('reward_shaping', 'protocol_version')) {
        if ([string]$Effective[$k] -ne [string]$Expected[$k]) {
            $bad += ("{0}: expected '{1}', log shows '{2}'" -f $k, $Expected[$k], $Effective[$k])
        }
    }
    if ([int]$Effective['prior_fade_visits'] -ne [int]$Expected['prior_fade_visits']) {
        $bad += ("prior_fade_visits: expected {0}, log shows {1}" -f $Expected['prior_fade_visits'], $Effective['prior_fade_visits'])
    }
    foreach ($k in @('adaptive_trust', 'paired_arm_rng')) {
        if ([bool]$Effective[$k] -ne [bool]$Expected[$k]) {
            $bad += ("{0}: expected {1}, log shows {2}" -f $k, $Expected[$k], $Effective[$k])
        }
    }
    # The fade length must be automatic: a quarter of the fault-stage budget
    # the agent reported (and that budget must be the requested override).
    if ($Effective['prior_decay'] -ne 'auto') {
        $bad += ("prior_decay: expected 'auto', log shows '{0}' ({1} episodes)" -f $Effective['prior_decay'], $Effective['prior_decay_episodes'])
    } else {
        $want = [Math]::Max(1, [int][Math]::Floor([int]$Effective['training_budget_episodes'] / 4))
        if ([int]$Effective['prior_decay_episodes'] -ne $want) {
            $bad += ("prior_decay_episodes: expected {0} (budget {1} / 4), log shows {2}" -f $want, $Effective['training_budget_episodes'], $Effective['prior_decay_episodes'])
        }
        if ($BudgetOverride -gt 0 -and [int]$Effective['training_budget_episodes'] -ne $BudgetOverride) {
            $bad += ("training budget: requested {0}, log shows {1}" -f $BudgetOverride, $Effective['training_budget_episodes'])
        }
    }
    $wantSeed = 0L
    if ($RunSeedRequested -ge 0) { $wantSeed = $RunSeedRequested }
    if ([long]$Effective['run_seed'] -ne $wantSeed) {
        $bad += ("run_seed: requested {0}, log shows {1}" -f $wantSeed, $Effective['run_seed'])
    }
    return ,$bad
}

# Number of starts that used a random state because no scenario was scheduled,
# as reported by the adaptation JVM before it writes the recovery CSV
# ("SCENARIO_FALLBACK_COUNT=<n>", LabEnvironment.reportScenarioFallbackCount).
# A missing line is an error: the receipt must carry a real count.
function Get-ScenarioFallbackCount { param([string]$LogPath)
    if (-not (Test-Path -LiteralPath $LogPath)) { throw "Fallback count: log not found: $LogPath" }
    $m = [regex]::Matches([System.IO.File]::ReadAllText($LogPath), 'SCENARIO_FALLBACK_COUNT=(\d+)')
    if ($m.Count -eq 0) { throw "Fallback count: no SCENARIO_FALLBACK_COUNT line in $LogPath" }
    return [int]$m[$m.Count - 1].Groups[1].Value
}

# The parent files the adaptation agent actually loads for one profile and arm.
# Mirrors QLearner.loadQTable: with base = qtable_final_stereotypes_<bool><sfx>
# it loads base_zone1.csv .. base_zone<N>.csv when all N zone files exist (N =
# number of goal zones, read from the "Goal: [..]" line of the JVM log), else
# the combined base.csv; then the sidecars base_visits.csv and base_trust.csv
# when present. Returns full paths (empty for a cold start).
function Get-ExactParentFiles { param([string]$Bool, [string]$CleanSfx, [string]$LogPath)
    $gm = [regex]::Matches([System.IO.File]::ReadAllText($LogPath), 'QLearner -\s+Goal: \[([^\]]*)\]')
    if ($gm.Count -eq 0) { throw "Exact parent set: no QLearner 'Goal: [..]' line in $LogPath" }
    $zones = @($gm[$gm.Count - 1].Groups[1].Value.Split(',') | Where-Object { $_.Trim() -ne '' }).Count
    $base = Join-Path $ScriptRoot ("qtable_final_stereotypes_{0}{1}" -f $Bool, $CleanSfx)
    $files = @()
    $zoneFiles = @()
    for ($z = 1; $z -le $zones; $z++) { $zoneFiles += ("{0}_zone{1}.csv" -f $base, $z) }
    $allZones = ($zones -gt 0)
    foreach ($zf in $zoneFiles) { if (-not (Test-Path -LiteralPath $zf)) { $allZones = $false } }
    if ($allZones) {
        $files += $zoneFiles
    } elseif (Test-Path -LiteralPath ("{0}.csv" -f $base)) {
        $files += ("{0}.csv" -f $base)
    }
    foreach ($side in @('_visits.csv', '_trust.csv')) {
        if (Test-Path -LiteralPath ($base + $side)) { $files += ($base + $side) }
    }
    return ,$files
}

# Check before any simulator starts. The training file named in
# phase2.train_scenarios_map must be the one the agent reads
# (train_scenarios(...) in lab_profiles.asl). A *_lowsun lab must use a file in
# which every situation sets Sunshine to 100 (sun rank 1); otherwise the low
# sun would not be delivered (the scheduled starts set the sun themselves) and
# the cell must not run.
function Assert-ScenarioTreatmentDelivery { param([string]$Profile, [string]$ScenarioRel)
    $aslRel = Get-AslTrainScenarios -Profile $Profile
    if ($aslRel -ne $ScenarioRel) {
        throw "Scenario-file mismatch for '$Profile': config train_scenarios_map='$ScenarioRel' but lab_profiles.asl train_scenarios='$aslRel'"
    }
    if ($Profile -like '*_lowsun') {
        $treat = Get-ScenarioTreatment (Join-Path $ScriptRoot ($ScenarioRel -replace '/', '\'))
        $bad = @($treat.sunshine_values | Where-Object { $_ -ne 100 })
        if ($bad.Count -gt 0 -or $treat.sunshine_values.Count -eq 0) {
            throw "Low-sun treatment NOT delivered for '$Profile': $ScenarioRel schedules Sunshine values {$($treat.sunshine_values -join ', ')}; every scenario must pin 100"
        }
    }
}

try {
    Write-Header "KG-QL Phase 2 — Fault-Detection / Blacklist / Re-learn  |  $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
    Write-OK   "node-red : $NodeRedCmd"
    Write-OK   "gradle   : $GradleExe"
    Write-OK   "profiles : $($ProfileList -join ', ')"
    Write-OK   "modes    : $($ModeList -join ', ')"
    Write-OK   "KG layer : $KnowledgeLayer"
    Write-OK   "settings : $($AdaptSettings.name)  ($($AdaptSettings.gradle_args -join ' '))"
    if ($EffEpisodes -gt 0) {
        Write-OK "episodes : $EffEpisodes (override via -Padapt.episodes)"
    } else {
        Write-OK "episodes : per-profile training_params default"
    }

    # Treatment-delivery preflight for every requested profile BEFORE any
    # simulator starts (fail fast, pre-data).
    foreach ($pf in $ProfileList) {
        $pfRel = Map-Get $P2.train_scenarios_map $pf
        if ($null -eq $pfRel) { throw "config phase2.train_scenarios_map has no entry for '$pf'" }
        Assert-ScenarioTreatmentDelivery -Profile $pf -ScenarioRel $pfRel
        Write-OK ("scenario : {0} -> {1}" -f $pf, $pfRel)
    }

    $cellLogDir = Join-Path $ScriptRoot "log"
    New-Item -ItemType Directory -Force -Path $cellLogDir | Out-Null

    foreach ($profile in $ProfileList) {
        $port       = Map-Get $P2.simulator_port_map  $profile
        $flow       = Map-Get $P2.simulator_flow_map   $profile
        $qtSuffix   = Map-Get $P2.qtable_suffix_map     $profile
        $cleanSfx   = Map-Get $P2.clean_source_suffix   $profile
        $parentName = Map-Get $P2.parent_profile        $profile

        if ($null -eq $port -or $null -eq $flow -or $null -eq $qtSuffix -or $null -eq $cleanSfx) {
            Write-Warn "Skipping '$profile' — incomplete phase2 config (port/flow/qtable_suffix/clean_source_suffix)."
            continue
        }
        $port = [int]$port

        Write-Header "Profile $profile  (parent=$parentName  port=$port  flow=$flow)"

        # ── Start the FAULTY Node-RED flow. The port must be free: a simulator
        #    this runner did not start is never reused (it may be the clean lab
        #    or another fault). ──
        $flowAbs = Join-Path $ScriptRoot "simulator\$flow"
        $userDir = Join-Path $ScriptRoot ".node-red-$profile"
        $weLaunched = $false
        $simReported = ""

        if (-not (Test-Path $flowAbs)) {
            Write-Fail "Flow file missing — skipping $profile : $flowAbs"
            continue
        } else {
            Assert-SimulatorPortFree -Port $port -Purpose "the $profile simulator"
            New-Item -ItemType Directory -Force -Path $userDir | Out-Null
            $spArgs = @{
                FilePath     = $NodeRedCmd
                ArgumentList = "--userDir `"$userDir`" --port $port `"$flowAbs`""
                PassThru     = $true
            }
            if ($IsWindows -or ($null -eq $IsWindows -and $env:OS -eq 'Windows_NT')) {
                $spArgs.WindowStyle = 'Hidden'
            }
            $proc = Start-Process @spArgs
            $SimProcs[$port] = $proc
            $SimUserDirs[$port] = $userDir
            $weLaunched = $true
            Write-Info "Launched faulty flow  port=$port  PID=$($proc.Id)"
            if (-not (Wait-Simulator -Port $port -TimeoutSec $SimReadyTimeoutSec)) {
                Write-Fail "Simulator $profile did not become ready within $SimReadyTimeoutSec s on port $port — skipping."
                continue
            }
            Write-OK "Simulator $profile ready on port $port"
            # Identity: the flow answering on the port must be the one started.
            $simReported = Assert-SimulatorIdentity -Port $port -FlowFile $flow
            Write-OK "Simulator on port $port reports profile '$simReported'"
        }

        foreach ($mode in $ModeList) {
            $bool = Mode-Bool $mode
            Write-Header "Cell  $profile / $mode  (stereo=$bool)"

            # Warm-load source sanity check (cold start is tolerated but warned).
            $cleanZone1 = Join-Path $ScriptRoot ("qtable_final_stereotypes_{0}{1}_zone1.csv" -f $bool, $cleanSfx)
            if (-not (Test-Path $cleanZone1)) {
                Write-Warn "Clean Phase-1 Q-table not found ($([System.IO.Path]::GetFileName($cleanZone1)))."
                Write-Warn "         The adapt agent will COLD-START (zero Q-table). For a proper warm-restart"
                Write-Warn "         result, train the parent lab '$parentName' first (Phase 1)."
            }

            # Output artefacts for this cell (recovery CSV is APPEND-mode, so we
            # must delete a stale one to read a clean single-row result).
            $recoveryFile = Join-Path $ScriptRoot ("recovery_stereotypes_{0}{1}.csv"        -f $bool, $qtSuffix)
            $adaptedFile  = Join-Path $ScriptRoot ("qtable_adapted_stereotypes_{0}{1}.csv"  -f $bool, $qtSuffix)
            $metricsFile  = Join-Path $ScriptRoot ("metrics_adapted_stereotypes_{0}{1}.csv" -f $bool, $qtSuffix)
            $adaptOkFile  = Join-Path $ScriptRoot ("ADAPT_OK_stereotypes_{0}{1}.json"       -f $bool, $qtSuffix)
            foreach ($stale in @($recoveryFile, $adaptedFile, $metricsFile, $adaptOkFile)) {
                if (Test-Path $stale) { Remove-Item $stale -Force -ErrorAction SilentlyContinue }
            }

            $cellLog = Join-Path $cellLogDir "adapt_${profile}_${mode}.log"
            $cellErr = Join-Path $cellLogDir "adapt_${profile}_${mode}.err.log"
            if (Test-Path $cellLog) { Remove-Item $cellLog -Force -ErrorAction SilentlyContinue }
            if (Test-Path $cellErr) { Remove-Item $cellErr -Force -ErrorAction SilentlyContinue }
            Write-Info "Gradle output -> $cellLog"

            $gArgs = @("taskAdapt", "-Pprofile=$profile", "-Pmode=$mode")
            if ($EffEpisodes -gt 0) { $gArgs += "-Padapt.episodes=$EffEpisodes" }
            if ($RunSeed -ge 0)     { $gArgs += "-Prun.seed=$RunSeed" }
            if ($KnowledgeLayer -ne "full") { $gArgs += "-Pstereo.knowledgeLayer=$KnowledgeLayer" }
            # Learning settings of the selected profile, passed explicitly so
            # nothing depends on a silent Java default.
            $gArgs += $AdaptSettings.gradle_args
            $gArgs += "--console=plain"

            $gpArgs = @{
                FilePath               = $GradleExe
                ArgumentList           = $gArgs
                WorkingDirectory       = $ScriptRoot
                RedirectStandardOutput = $cellLog
                RedirectStandardError  = $cellErr
                PassThru               = $true
            }
            if ($IsWindows -or ($null -eq $IsWindows -and $env:OS -eq 'Windows_NT')) {
                $gpArgs.WindowStyle = 'Hidden'
            }
            $cellStart = Get-Date
            $gProc = Start-Process @gpArgs

            # ── Watchdog: recovery CSV appearing == logically done (the agent
            #    writes it immediately before .stopMAS). JaCaMo can hang in its
            #    post-run event loop, so once the CSV exists and the JVM has been
            #    idle for a grace window we terminate it and continue. ──
            $tailPos             = 0L
            $heartbeatEverySec   = 60
            $postCompleteIdleSec = 90
            $lastHeartbeat = Get-Date
            $lastLogGrowth = Get-Date
            $lastLogLength = 0
            $completedGracefully = $false

            while (-not $gProc.HasExited) {
                Start-Sleep -Seconds 10
                Invoke-TailLog -LogPath $cellLog -PosRef ([ref]$tailPos)
                $now = Get-Date

                $logLength = 0
                if (Test-Path $cellLog) {
                    $gi = Get-Item -LiteralPath $cellLog -ErrorAction SilentlyContinue
                    if ($gi) { $logLength = $gi.Length }
                }
                if ($logLength -gt $lastLogLength) { $lastLogGrowth = $now; $lastLogLength = $logLength }

                if ((New-TimeSpan -Start $lastHeartbeat -End $now).TotalSeconds -ge $heartbeatEverySec) {
                    $elapsedMin = [int](New-TimeSpan -Start $cellStart -End $now).TotalMinutes
                    $idleSec    = [int](New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                    Write-Info "[Heartbeat] $profile/$mode elapsed=${elapsedMin}m idle=${idleSec}s pid=$($gProc.Id)"
                    $lastHeartbeat = $now
                }

                # Completion: recovery CSV written and JVM idle since.
                if (Test-Path $recoveryFile) {
                    $idleNow = (New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                    if ($idleNow -ge $postCompleteIdleSec) {
                        Write-Warn "Recovery CSV present and JVM idle ${postCompleteIdleSec}s — terminating JaCaMo and continuing."
                        try { if (-not $gProc.HasExited) { $null = Stop-ProcessTree -RootId $gProc.Id } } catch { }
                        $completedGracefully = $true
                        break
                    }
                }

                $idleNow = (New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                if ($idleNow -ge $WatchdogIdleSec) {
                    try { if (-not $gProc.HasExited) { $null = Stop-ProcessTree -RootId $gProc.Id } } catch { }
                    Write-Fail "Watchdog timeout for $profile/${mode}: no log growth for $WatchdogIdleSec s (see $cellLog)"
                    break
                }
            }

            try { $gProc.Refresh() } catch { }
            try { [void]$gProc.WaitForExit(60000) } catch { }
            Invoke-TailLog -LogPath $cellLog -PosRef ([ref]$tailPos)

            # Merge stderr tail into the cell log for post-mortem.
            if ((Test-Path $cellErr) -and (Get-Item $cellErr -ErrorAction SilentlyContinue).Length -gt 0) {
                "`n--- STDERR ---`n" | Out-File -FilePath $cellLog -Encoding utf8 -Append
                Get-Content $cellErr -ErrorAction SilentlyContinue | Out-File -FilePath $cellLog -Encoding utf8 -Append
            }

            # ── Parse the recovery metric ──
            $status     = "FAIL"
            $detectEp   = ""
            $reconvEp   = ""
            $recovery   = ""
            $defectComp = ""
            if (Test-Path $recoveryFile) {
                try {
                    $row = Import-Csv $recoveryFile | Select-Object -Last 1
                    if ($row) {
                        $defectComp = $row.DefectComponent
                        $detectEp   = $row.DetectEpisode
                        $reconvEp   = $row.ReconvergeEpisode
                        $recovery   = $row.RecoveryEpisodes
                        $status     = "OK"
                    }
                } catch {
                    Write-Warn "Could not parse $recoveryFile : $_"
                }
            }

            $elapsedMin = [int](New-TimeSpan -Start $cellStart -End (Get-Date)).TotalMinutes
            if ($status -eq "OK") {
                $detPretty = "none"; if ([int]$detectEp -ge 0) { $detPretty = [string]([int]$detectEp + 1) }
                $recPretty = $recovery; if ([int]$recovery -lt 0) { $recPretty = "N/A" }
                Write-OK "Done in ${elapsedMin}m  defect=$defectComp  detect=$detPretty  reconverge=$reconvEp  recovery=$recPretty"

                # ── Protocol-v2 gate manifest (ADAPT_OK.json). Written only for a
                # completed cell; the archive validator rejects cells without one.
                # scenario_fallback_count is the count the JVM reported (random
                # starts because no scenario was scheduled); every Phase-2
                # profile schedules scenarios, so it is 0 unless that breaks,
                # and the archive validator rejects a non-zero value.
                try {
                    $scRel = Map-Get $P2.train_scenarios_map $profile
                    if ($null -eq $scRel) {
                        throw "config phase2.train_scenarios_map has no entry for '$profile'"
                    }
                    $scAbs = Join-Path $ScriptRoot ($scRel -replace '/', '\')
                    $schedule = Get-ScenarioSchedule $scAbs
                    $treatment = Get-ScenarioTreatment $scAbs
                    $lowsunDelivered = $null
                    if ($profile -like '*_lowsun') {
                        $lowsunDelivered = (($treatment.sunshine_values.Count -eq 1) -and ([double]$treatment.sunshine_values[0] -eq 100))
                    }
                    # Legacy parent checksum, kept exactly as before so receipts
                    # stay comparable with the archived ones: a wildcard over the
                    # workspace root that also matches OTHER profiles whose name
                    # starts with this parent's (e.g. _labmon* also hashes the
                    # labmon2 and labmon_infoonly tables when they are present).
                    $qtFiles = @(Get-ChildItem -Path $ScriptRoot -Filter ("qtable_final_stereotypes_{0}{1}*.csv" -f $bool, $cleanSfx) |
                                 ForEach-Object { $_.FullName })
                    $qtHash = ""
                    if ($qtFiles.Count -gt 0) { $qtHash = Get-FileSetSha256 $qtFiles }
                    # Exact parent checksum: only the files the agent loads.
                    $qtExactFiles = Get-ExactParentFiles -Bool $bool -CleanSfx $cleanSfx -LogPath $cellLog
                    $qtExactHash = ""
                    if ($qtExactFiles.Count -gt 0) { $qtExactHash = Get-FileSetSha256 $qtExactFiles }
                    $epRows = 0
                    if (Test-Path $metricsFile) {
                        $epRows = @(Get-Content $metricsFile | Where-Object {
                            $_ -and -not $_.StartsWith('Episode') -and -not $_.StartsWith('#') }).Count
                    }
                    $deliveredLayer = Get-DeliveredKnowledgeLayer -LogPath $cellLog
                    if ($deliveredLayer -ne $KnowledgeLayer) {
                        # Stop before the receipt: a cell whose requested layer
                        # did not reach the JVM must not look complete.
                        throw "Knowledge-layer treatment NOT verified for $profile/${mode}: requested '$KnowledgeLayer', log shows '$deliveredLayer' (no receipt written)"
                    }
                    # Settings gate: read the effective learning settings back
                    # from the JVM log and compare them with the profile. A
                    # mismatch stops here, before the receipt is written.
                    $effSettings = Get-EffectiveAdaptSettings -LogPath $cellLog
                    $settingsBad = Get-AdaptSettingsMismatches -Expected $AdaptSettings.expected `
                        -Effective $effSettings -BudgetOverride $EffEpisodes -RunSeedRequested $RunSeed
                    if ($settingsBad.Count -gt 0) {
                        throw ("Settings verification FAILED for settings profile '{0}' (no receipt written): {1}" -f $AdaptSettings.name, ($settingsBad -join '; '))
                    }
                    Write-OK ("Settings verified ({0}): clip={1} fade=auto {2} eps (budget {3}) pairedArmRng={4} protocol={5}" -f `
                        $AdaptSettings.name, $effSettings['reward_clip'], $effSettings['prior_decay_episodes'],
                        $effSettings['training_budget_episodes'], $effSettings['paired_arm_rng'], $effSettings['protocol_version'])
                    $scenarioFallbackCount = Get-ScenarioFallbackCount -LogPath $cellLog
                    if ($scenarioFallbackCount -gt 0) {
                        Write-Warn "$profile/${mode}: $scenarioFallbackCount random start state(s) instead of scheduled scenarios"
                    }
                    $manifest = [ordered]@{
                        status                   = if ($qtFiles.Count -gt 0) { "ok" } else { "cold_start" }
                        protocol_version         = "phase2-v2"
                        detector_version         = "fault-detector-v2"
                        metric_schema            = "phase2-recovery-v2"
                        settled_start_state      = $true
                        scenario_fallback_count  = $scenarioFallbackCount
                        profile                  = $profile
                        parent_profile           = $parentName
                        mode                     = $mode
                        run_seed                 = $RunSeed
                        knowledge_layer          = $KnowledgeLayer
                        knowledge_layer_delivered = $deliveredLayer
                        adapt_episodes_effective = $epRows
                        adapt_episodes_override  = $EffEpisodes
                        train_scenarios_file     = $scRel
                        ordered_scenario_ids     = $schedule.ids
                        scenario_schedule_sha256 = $schedule.hash
                        scenario_file_sha256     = $treatment.file_sha256
                        scenario_sunshine_values = @($treatment.sunshine_values)
                        lowsun_pin_delivered     = $lowsunDelivered
                        parent_qtable_files      = @($qtFiles | ForEach-Object { [System.IO.Path]::GetFileName($_) } | Sort-Object)
                        parent_qtable_sha256     = $qtHash
                        parent_qtable_exact_files  = @($qtExactFiles | ForEach-Object { [System.IO.Path]::GetFileName($_) } | Sort-Object)
                        parent_qtable_exact_sha256 = $qtExactHash
                        settings_profile        = $AdaptSettings.name
                        paired_arm_rng           = $effSettings['paired_arm_rng']
                        effective_settings       = $effSettings
                        simulator_flow           = $flow
                        simulator_profile_reported = $simReported
                    }
                    $manifest | ConvertTo-Json -Depth 5 | Out-File -FilePath $adaptOkFile -Encoding utf8
                    Write-OK "ADAPT_OK manifest written: $([System.IO.Path]::GetFileName($adaptOkFile))"
                } catch {
                    Write-Fail "Could not write ADAPT_OK manifest for $profile/$mode : $_"
                    $script:HadFatalError = $true
                }
            } else {
                Write-Fail "No recovery CSV produced for $profile/$mode (see $cellLog)"
                $script:HadFatalError = $true
            }

            $Summary += [pscustomobject]@{
                Profile   = $profile
                Mode      = $mode
                Defect    = $defectComp
                DetectEp  = $detectEp
                ReconvEp  = $reconvEp
                Recovery  = $recovery
                Status    = $status
                Minutes   = $elapsedMin
            }
        }

        # ── Stop the faulty flow for this profile (only if we launched it) ──
        if ($weLaunched) { Stop-LaunchedSimulator -Port $port }
    }

    # ─── Summary ──────────────────────────────────────────────────────────────
    Write-Header "Phase 2 summary"
    if ($Summary.Count -gt 0) {
        $tbl = $Summary | Format-Table Profile, Mode, Defect, DetectEp, ReconvEp, Recovery, Status, Minutes -AutoSize | Out-String
        foreach ($l in ($tbl -split "`r?`n")) { if ($l.Trim() -ne "") { Write-Log $l } }
    } else {
        Write-Warn "No cells ran."
    }

} catch {
    $script:HadFatalError = $true
    Write-Fail "Fatal error: $_"
    Write-Fail $_.ScriptStackTrace
} finally {
    Write-Header "Cleanup"
    foreach ($cleanupPort in @($SimProcs.Keys)) { Stop-LaunchedSimulator -Port $cleanupPort }
    if ($script:HadFatalError) {
        Write-Fail "Phase 2 run finished WITH errors — review $LogFile"
    } else {
        Write-OK "Phase 2 run finished cleanly."
    }
    if ($LogStream) { $LogStream.Flush(); $LogStream.Close() }
}

if ($script:HadFatalError) { exit 1 } else { exit 0 }
