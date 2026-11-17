<#
.SYNOPSIS
    Trains the Q-learning agent and runs the test (benchmark) in one or more
    labs: the runner of Phases 1, 1b and 4, and of the parent training of
    Phase 2.

.DESCRIPTION
    The script works in the following steps. The log headers call them
    "Phase 1" to "Phase 6"; these are steps of this script, not the four
    experiment phases.

    Step "Phase 1" (apply settings). Reads the run mode from
    config/run_config.json through scripts/Read-RunConfig.ps1 and writes its
    values into the source files the agents read:
      - the "repeat" interval (tick) of every flow file in the simulator table
        below (simulator/simulator_flow_<lab>.json); this change is kept after
        the run;
      - num_episodes, max_steps_per_episode, action_delay_ms, exec_delay_ms
        and exec_max_steps in src/agt/illuminance_controller_agent_ql.asl, and
        bench_runs, exec_max_steps and exec_delay_ms in
        src/agt/illuminance_controller_agent_bench.asl; at the end both files
        get back their original text, but with these values of the run mode;
      - the episode count of every training_params(...) in
        src/agt/lab_profiles.asl (set to the run mode's num_episodes); the
        file is restored at the end.
    Step "Phase 2" (simulators). Starts one Node-RED simulator for each
    selected lab (port and flow from the simulator table in this script).
    The port must be free; a simulator this script did not start is never
    used. Waits up to 90 s for each simulator and checks through /health that
    it runs the flow that was started (scripts/SimulatorControl.ps1).
    Step "Phase 2.5" (preflight). Runs "gradlew preflight" for the selected
    labs (see build.gradle) unless -SkipPreflight is given.
    Step "Phase 3" (training). For every selected lab and arm
    (use_stereotypes true and false) that has no TRAINING_OK.json yet:
    sets use_stereotypes in the training agent program and active_profile in
    lab_profiles.asl, runs "gradlew taskQl -Pprofile=<lab>" with the -P
    settings of the run mode, watches the JVM log, then checks the result:
    the expected output files exist; the settings the JVM reports in its log
    (STEREO_EFFECTIVE_PROPS line, knowledge layer, reward limit, fade length,
    energy-preference weight) equal the requested ones; the number of random
    start states is read from the log; for protocol phase1-v2 the metrics
    file has exactly num_episodes episode rows and the first-goal file one
    row per training situation. Then the outputs are copied to
    benchmark/results/<lab>/training_stereo_<true|false>/ together with the
    receipt TRAINING_OK.json. Any failed check stops the run before the
    receipt is written. With -SkipTraining this step only checks that every
    needed TRAINING_OK.json exists and is valid.
    Step "Phase 4" (test). Runs "gradlew runFullSweep" for the selected labs
    and test modes (rule_based, ql_false, ql_true); results go to
    benchmark/results/<lab>/<mode>/.
    Step "Phase 5" (analysis). Runs analysis/sweep_report.py with its default
    arguments; a non-zero exit code only gives a warning.
    Step "Phase 6" (only with -VersionResults). Calls
    scripts/version_artifacts.ps1; a failure only gives a warning.
    Cleanup (always). Stops every simulator this run started, with its whole
    process tree, and restores the agent program files as described above.

    The GitHub workflows run one lab, one arm or test mode and one seed per
    job, with -OnlyProfiles, -OnlyStereo or -OnlyModes, -RunSeed,
    -SkipBenchmark or -SkipTraining, and -SkipPreflight.

.PARAMETER RunMode
    Name of a run mode in config/run_config.json ("profiles" block). The
    allowed values are listed in the param block. "dev" is a short check
    (50 episodes, 2 test runs per situation); the phase1_v2_*, phase1b_v2_*
    and phase4_v2* modes are the run modes of the accepted campaigns
    (docs/EXPERIMENTS.md, section 3). Default: dev.

.PARAMETER OnlyProfiles
    Comma-separated labs to train and test. Every name must be in the list
    of known labs inside this script. Default (empty): lab1, lab2, lab3.

.PARAMETER OnlyStereo
    Train only the arm use_stereotypes=true ("true"), only the plain arm
    ("false"), or both ("both", the default).

.PARAMETER OnlyModes
    Comma-separated test modes for step "Phase 4": rule_based, ql_false,
    ql_true. Default (empty): all three.

.PARAMETER ScenarioProvenanceCheckOnly
    Resolves the training-situation file of every known lab exactly as a
    training cell does (including the alias map train_scenarios_alias of
    config/run_config.json), prints one line per lab and exits: 0 when every
    lab resolves, 1 otherwise. Nothing is started and no file is changed;
    the log file is not opened. CI runs this check.

.PARAMETER SkipTraining
    Do not train. Instead check that TRAINING_OK.json exists, has status ok
    and lists only files that exist, for every selected lab and arm (for
    protocol phase1-v2 also the seed, the 3,000-episode horizon, the pairing
    and the metric schema, and a fallback count of 0); otherwise stop with
    exit code 1.

.PARAMETER SkipBenchmark
    Do not run steps "Phase 4" to "Phase 6" (test, analysis, versioning).

.PARAMETER SkipPreflight
    Do not run "gradlew preflight" (step "Phase 2.5").

.PARAMETER VersionResults
    After the analysis, call scripts/version_artifacts.ps1, which copies the
    result files to the branch "results" and creates a local tag. Has an
    effect only when the test runs (no -SkipBenchmark).

.PARAMETER PublishResults
    Together with -VersionResults: version_artifacts.ps1 also pushes the
    branch and the tag to origin.

.PARAMETER DryRun
    Accepted but not used: nothing in this script reads it.

.PARAMETER RunSeed
    Seed of the agent's random choices, passed as -Prun.seed. QLearner mixes
    it (SplitMix64, then XOR) into its base seed. 0 (the default) passes
    nothing, so the base seed is used unchanged. The value is recorded in
    TRAINING_OK.json.

.EXAMPLE
    .\run_full_project.ps1 -RunMode phase1_v2_kg_only -OnlyProfiles lab2 -OnlyStereo true -RunSeed 1 -SkipBenchmark -SkipPreflight
    Trains the knowledge arm in lab2 with seed 1, as one training job of
    .github/workflows/phase1.yml does.

.EXAMPLE
    .\run_full_project.ps1 -RunMode phase1_v2_kg_only -OnlyProfiles lab2 -OnlyModes ql_true -RunSeed 1 -SkipTraining -SkipPreflight
    Tests the trained knowledge arm in lab2, as one test job of phase1.yml
    does. Needs both training receipts of lab2.

.EXAMPLE
    .\run_full_project.ps1 -RunMode phase1_v2_kg_only -ScenarioProvenanceCheckOnly
    Checks that every known lab has a training-situation file, then exits.

.EXAMPLE
    .\run_full_project.ps1 -RunMode dev
    Short local run: trains both arms in lab1, lab2 and lab3 and tests all
    three modes.

.OUTPUTS
    Files, all relative to the repository root:
      run_full_project.log (overwritten by every run)
      log/train_<lab>_stereo_<true|false>.log and .err.log (one per training cell)
      log/benchmark_sweep.log (the test)
      qtable_*, metrics_*, iv_stats_*, coverage_*, first_goal_* and
        learned_stereotypes_* files of each training cell (root)
      benchmark/results/<lab>/training_stereo_<true|false>/ with TRAINING_OK.json
      benchmark/results/<lab>/<mode>/ (test results, written by runFullSweep)
      analysis/out/ (written by analysis/sweep_report.py)
    The receipt fields are listed in docs/EXPERIMENTS.md, section 6.

    Exit code: 0 when every step succeeded; 1 when node-red or Python 3 is
    not found, when any step throws (a failed check, a simulator that does
    not start, a watchdog time-out, a failed Gradle task), or when
    -ScenarioProvenanceCheckOnly finds a lab without a training file. An
    unknown name in -OnlyProfiles or -OnlyModes stops the script with an
    error before anything is started.

.NOTES
    Target shell: Windows PowerShell 5.1+ (also works under PowerShell 7+,
    on Windows and Linux; the GitHub workflows use pwsh on Linux).
    Do not use PS7-only syntax here — no null-conditional (?.), null-coalescing (??),
    ternary (a ? b : c), or native command chaining (&&, ||).
#>

param(
    [ValidateSet("dev","phase1_v2_kg_only","phase1_v2_redundancy_only","phase1_v2_brick_only","phase1_v2_baseline","phase1_v2_pbrs_only","phase1b_v2_baseline","phase1b_v2_redundancy_only","phase1b_v2_kg_frozen","phase1b_v2_extended","phase4_v2","phase4_v2_redundancy_only","phase4_v2_brick_only")]
    [string]$RunMode = "dev",

    # The parameters are described in the help block above.
    [string]$OnlyProfiles = "",

    [ValidateSet("true","false","both")]
    [string]$OnlyStereo = "both",

    [string]$OnlyModes = "",

    [switch]$ScenarioProvenanceCheckOnly,

    [switch]$SkipTraining,
    [switch]$SkipBenchmark,

    [switch]$SkipPreflight,

    [switch]$VersionResults,
    [switch]$PublishResults,

    # Not read anywhere in this script.
    [switch]$DryRun,

    # 0 = no -Prun.seed is passed; QLearner then uses its base seed unchanged.
    [int]$RunSeed = 0
)

$ErrorActionPreference = "Stop"
$script:HadFatalError = $false
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptRoot

# ─── Run-mode settings ────────────────────────────────────────────────────────
# Fallback used only when scripts/Read-RunConfig.ps1 is missing or fails; it
# holds the "dev" values of config/run_config.json. For any other run mode
# the fallback has no entry, so a failed config read leaves $P empty.
$Configs = @{
    dev = @{
        tick                  = "0.05"    # Node-RED repeat interval (seconds)
        num_episodes          = 50
        max_steps_per_episode = 20
        action_delay_ms       = 65      # must be > tick*1000 + jitter buffer
        exec_delay_ms_ql      = 65
        exec_max_steps_ql     = 20
        bench_runs            = 2
        exec_max_steps_bench  = 20
        exec_delay_ms_bench   = 65
    }
}
# ─── Configuration ────────────────────────────────────────────────────────
# Read the run mode from config/run_config.json. $P is the run mode's entry
# in "profiles"; $RunConfig.learning is the global "learning" block with the
# run mode's "learning_overrides" merged on top.
$RunConfig = $null
$_readCfg  = Join-Path $ScriptRoot 'scripts\Read-RunConfig.ps1'
if (Test-Path -LiteralPath $_readCfg) {
    try {
        $RunConfig = & $_readCfg -RunMode $RunMode
    } catch {
        Write-Warning "Read-RunConfig failed; falling back to inline `\$Configs. Reason: $($_.Exception.Message)"
        $RunConfig = $null
    }
}
if ($RunConfig) {
    Write-Host "Loaded run config from: $($RunConfig.Path) (profile: $RunMode)" -ForegroundColor DarkCyan
    $P = $RunConfig.profile
} else {
    $P = $Configs[$RunMode]
}

# -P settings for the agent's JVM, added to every taskQl and runFullSweep
# call. build.gradle passes a -P setting on as a JVM system property only if
# its name is in its list _httpKeys.
$HttpArgs = @()
# Token:weight list of the deterministic energy measure PolicyEnergyCost
# (Phase1PolicyEnergy); without it the built-in weights apply.
if ($P.policy_energy_weights) {
    $HttpArgs += "-Pphase1.policyEnergyWeights=$($P.policy_energy_weights)"
}
# Protocol version (phase1-v2 in the accepted run modes; read by QLearner
# and LabEnvironment, "legacy" when absent).
if ($P.protocol_version) {
    $HttpArgs += "-Pphase1.protocolVersion=$($P.protocol_version)"
}
# Time-outs and retries of the simulator HTTP client (read by LabEnvironment).
if ($RunConfig -and $RunConfig.http_client) {
    $hc = $RunConfig.http_client
    if ($hc.connect_timeout_ms  -ne $null) { $HttpArgs += "-Psim.http.connectMs=$($hc.connect_timeout_ms)" }
    if ($hc.response_timeout_ms -ne $null) { $HttpArgs += "-Psim.http.responseMs=$($hc.response_timeout_ms)" }
    if ($hc.max_retries         -ne $null) { $HttpArgs += "-Psim.http.maxRetries=$($hc.max_retries)" }
    if ($hc.backoff_base_ms     -ne $null) { $HttpArgs += "-Psim.http.backoffMs=$($hc.backoff_base_ms)" }
}
# Learning settings of the merged "learning" block; a key that is absent is
# not passed, so the Java default applies.
# $RequestedStereoProps: the three Phase-1b settings that are checked after
# every training cell against the values StereotypeReasoner prints in its
# STEREO_EFFECTIVE_PROPS log line; both are recorded in TRAINING_OK.json.
# A setting the run mode does not set must be reported as 0.0 (off).
$RequestedStereoProps = [ordered]@{
    'stereo.irrelevantDvPrior' = 0.0
    'stereo.bandMirrorInit'    = 0.0
    'stereo.bandMirrorPrior'   = 0.0
}
if ($RunConfig -and $RunConfig.learning) {
    $ln = $RunConfig.learning
    if ($ln.reward_clip                 -ne $null) { $HttpArgs += "-Preward.clip=$($ln.reward_clip)" }
    if ($ln.stereo_prior_scale          -ne $null) { $HttpArgs += "-Pstereo.priorScale=$($ln.stereo_prior_scale)" }
    if ($ln.stereo_prior_decay_episodes -ne $null) { $HttpArgs += "-Pstereo.priorDecayEpisodes=$($ln.stereo_prior_decay_episodes)" }
    if ($ln.stereo_prior_decay_floor    -ne $null) { $HttpArgs += "-Pstereo.priorDecayFloor=$($ln.stereo_prior_decay_floor)" }
    # Size of the starting-value penalty for a redundant action and for an
    # action whose condition (for example sun for a blind) is not met.
    if ($ln.stereo_prior_redundant      -ne $null) { $HttpArgs += "-Pstereo.priorRedundant=$($ln.stereo_prior_redundant)" }
    if ($ln.stereo_prior_iv_unsat       -ne $null) { $HttpArgs += "-Pstereo.priorIVUnsat=$($ln.stereo_prior_iv_unsat)" }
    # true = knowledge layer "redundancy" (only the redundant-action rule);
    # set by the *_redundancy_only run modes.
    if ($ln.stereo_redundancy_only      -ne $null) { $HttpArgs += "-Pstereo.redundancyOnly=$($ln.stereo_redundancy_only)" }
    # Knowledge layer of the reasoner: full | brick | redundancy (set by the
    # *_brick_only run modes).
    if ($ln.stereo_knowledge_layer      -ne $null) { $HttpArgs += "-Pstereo.knowledgeLayer=$($ln.stereo_knowledge_layer)" }
    # Positive starting value (per missing rank) for an action that the
    # knowledge says helps a zone below its target.
    if ($ln.stereo_init_bonus           -ne $null) { $HttpArgs += "-Pstereo.initBonus=$($ln.stereo_init_bonus)" }
    # The same for an action that lights a neighbouring zone below its target
    # (Java default 0.0 = off).
    if ($ln.cross_zone_bonus            -ne $null) { $HttpArgs += "-Pstereo.crossZoneBonus=$($ln.cross_zone_bonus)" }
    # Weight of the energy preference, which does not fade (Java default
    # 0.0 = off; 2.0 in the phase4_v2 run modes). Only lab5's graph declares
    # ws:energyCost, so the weight has no effect in the other labs.
    if ($ln.stereo_energy_prior_weight  -ne $null) { $HttpArgs += "-Pstereo.energyPriorWeight=$($ln.stereo_energy_prior_weight)" }
    # Phase-1b settings about irrelevant devices and the direction of effects
    # (Java default 0.0 = off). Only phase1b_v2_extended sets them.
    if ($ln.stereo_irrelevant_dv_prior   -ne $null) { $HttpArgs += "-Pstereo.irrelevantDvPrior=$($ln.stereo_irrelevant_dv_prior)"; $RequestedStereoProps['stereo.irrelevantDvPrior'] = [double]$ln.stereo_irrelevant_dv_prior }
    if ($ln.stereo_band_mirror_init      -ne $null) { $HttpArgs += "-Pstereo.bandMirrorInit=$($ln.stereo_band_mirror_init)";       $RequestedStereoProps['stereo.bandMirrorInit']    = [double]$ln.stereo_band_mirror_init }
    if ($ln.stereo_band_mirror_prior     -ne $null) { $HttpArgs += "-Pstereo.bandMirrorPrior=$($ln.stereo_band_mirror_prior)";     $RequestedStereoProps['stereo.bandMirrorPrior']   = [double]$ln.stereo_band_mirror_prior }
    # Potential-based reward shaping (pbrs | none) and adaptive trust in the
    # knowledge; the global block switches both on, the accepted run modes
    # switch them off again in their learning_overrides (phase1_v2_pbrs_only
    # keeps pbrs).
    if ($ln.reward_shaping              -ne $null) { $HttpArgs += "-Preward.shaping=$($ln.reward_shaping)" }
    if ($ln.adaptive_trust              -ne $null) { $HttpArgs += "-Pstereo.adaptiveTrust=$($ln.adaptive_trust)" }
    if ($ln.adaptive_trust_min_samples  -ne $null) { $HttpArgs += "-Pstereo.adaptiveTrust.minSamples=$($ln.adaptive_trust_min_samples)" }
    if ($ln.adaptive_trust_floor        -ne $null) { $HttpArgs += "-Pstereo.adaptiveTrust.floor=$($ln.adaptive_trust_floor)" }
}
# Requested values of the settings whose delivery is checked after every
# training cell (read back from the JVM log, recorded in TRAINING_OK.json). A
# setting the run mode does not pass is requested at its Java default.
$RequestedKnowledgeLayer = 'full'
$RequestedRewardClip     = 50.0
$RequestedPriorDecayEps  = $null      # $null = automatic (a quarter of the budget)
$RequestedEnergyWeight   = 0.0
if ($RunConfig -and $RunConfig.learning) {
    $ln = $RunConfig.learning
    if ($ln.stereo_knowledge_layer -ne $null) {
        $RequestedKnowledgeLayer = ([string]$ln.stereo_knowledge_layer).Trim().ToLowerInvariant()
    } elseif ($ln.stereo_redundancy_only -ne $null -and ([string]$ln.stereo_redundancy_only) -eq 'True') {
        $RequestedKnowledgeLayer = 'redundancy'
    }
    if ($ln.reward_clip -ne $null)                { $RequestedRewardClip   = [double]$ln.reward_clip }
    if ($ln.stereo_prior_decay_episodes -ne $null -and [int]$ln.stereo_prior_decay_episodes -gt 0) {
        $RequestedPriorDecayEps = [int]$ln.stereo_prior_decay_episodes
    }
    if ($ln.stereo_energy_prior_weight -ne $null) { $RequestedEnergyWeight = [double]$ln.stereo_energy_prior_weight }
}
# Per-run seed (-RunSeed N); 0 passes nothing.
if ($RunSeed -ne 0) {
    $HttpArgs += "-Prun.seed=$RunSeed"
}

# ─── Project layout ───────────────────────────────────────────────────────────
# Labs to train and test when -OnlyProfiles is not given: the Phase-1 labs.
# The other labs are selected with -OnlyProfiles (the workflows pass one lab
# per job).
$TrainProfiles = @(
    "lab1", "lab2", "lab3"
)
# Every lab name -OnlyProfiles accepts; also the list that
# -ScenarioProvenanceCheckOnly checks.
$KnownProfiles = @(
    "lab1", "lab2", "lab3", "lab4", "lab4dual", "lab4chain", "lab5",
    "labmon", "labmon2",
    "labmon_infoonly", "labmon_nostereo", "labmon2_infoonly", "labmon2_nostereo",
    "labrel0", "labrel4", "labrel8", "labrel16", "labrel8s",
    "labband", "lab4chain3"
)

# Apply the -OnlyProfiles filter.
if ($OnlyProfiles) {
    $requested = @($OnlyProfiles -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($requested.Count -eq 0) {
        throw "-OnlyProfiles supplied but parsed to empty list: '$OnlyProfiles'"
    }
    $unknown = @($requested | Where-Object { $_ -notin $KnownProfiles })
    if ($unknown.Count -gt 0) {
        throw "Unknown profile(s) in -OnlyProfiles: $($unknown -join ', '). Valid: $($KnownProfiles -join ', ')"
    }
    $TrainProfiles = $requested
}

$BenchProfiles = $TrainProfiles -join ","
$BenchModes    = "rule_based,ql_false,ql_true"
$StereoModes   = @("true", "false")

# Apply the -OnlyStereo filter (the workflows pass one arm per training job).
if ($OnlyStereo -ne "both") {
    $StereoModes = @($OnlyStereo)
}

# Apply the -OnlyModes filter (the workflows pass one test mode per job).
if ($OnlyModes) {
    $_validModes = @("rule_based","ql_false","ql_true")
    $requestedModes = @($OnlyModes -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($requestedModes.Count -eq 0) {
        throw "-OnlyModes supplied but parsed to empty list: '$OnlyModes'"
    }
    $unknownModes = @($requestedModes | Where-Object { $_ -notin $_validModes })
    if ($unknownModes.Count -gt 0) {
        throw "Unknown mode(s) in -OnlyModes: $($unknownModes -join ', '). Valid: $($_validModes -join ', ')"
    }
    $BenchModes = $requestedModes -join ','
}

# Cross-platform gradlew lookup. PS 5.1 always sets $env:OS = 'Windows_NT';
# pwsh on Linux/macOS leaves it empty, so we fall back to the POSIX wrapper.
# The CI workflows chmod +x ./gradlew before invoking this script.
if ($env:OS -eq 'Windows_NT') {
    $GradleExe = Join-Path $ScriptRoot 'gradlew.bat'
} else {
    $GradleExe = Join-Path $ScriptRoot 'gradlew'
}

# Suffix of the output file names of each lab (qtable_final_stereotypes_<arm><suffix>.csv
# and so on); equal to the lab's qtable_suffix(...) in src/agt/lab_profiles.asl.
$ProfileQtableSuffix = @{
    lab1 = "_lab1"
    lab2 = "_lab2"
    lab3 = "_lab3"
    lab4 = "_lab4"
    lab4dual = "_lab4dual"
    lab4chain = "_lab4chain"
    lab5 = "_lab5"
    labmon = "_labmon"
    labmon2 = "_labmon2"
    labmon_infoonly = "_labmon_infoonly"
    labmon_nostereo = "_labmon_nostereo"
    labmon2_infoonly = "_labmon2_infoonly"
    labmon2_nostereo = "_labmon2_nostereo"
    labrel0    = "_labrel0"
    labrel4    = "_labrel4"
    labrel8    = "_labrel8"
    labrel16   = "_labrel16"
    labrel8s   = "_labrel8s"
    labband    = "_labband"
    lab4chain3 = "_lab4chain3"
}

# Simulator table: lab -> port and flow file (simulator/<Flow>). This table,
# not simulator_port_map / simulator_flow_map of config/run_config.json,
# decides which simulator this script starts. The ports must match the Thing
# Descriptions the agent uses (src/resources/interactions-*.ttl).
$Simulators = @(
    [pscustomobject]@{ Profile="lab1"; Port=1892; Flow="simulator_flow_lab1.json" }
    [pscustomobject]@{ Profile="lab2"; Port=1893; Flow="simulator_flow_lab2.json" }
    [pscustomobject]@{ Profile="lab3"; Port=1894; Flow="simulator_flow_lab3.json" }
    [pscustomobject]@{ Profile="lab4"; Port=1897; Flow="simulator_flow_lab4.json" }
    [pscustomobject]@{ Profile="lab4dual"; Port=1901; Flow="simulator_flow_lab4dual.json" }
    [pscustomobject]@{ Profile="lab4chain"; Port=1902; Flow="simulator_flow_lab4chain.json" }
    [pscustomobject]@{ Profile="lab5"; Port=1898; Flow="simulator_flow_lab5.json" }
    [pscustomobject]@{ Profile="labmon"; Port=1899; Flow="simulator_flow_labmon.json" }
    [pscustomobject]@{ Profile="labmon2"; Port=1900; Flow="simulator_flow_labmon2.json" }
    # Variants of labmon and labmon2 with a reduced knowledge graph: same flow
    # and port as their parent; only the agent's graph files differ.
    [pscustomobject]@{ Profile="labmon_infoonly"; Port=1899; Flow="simulator_flow_labmon.json" }
    [pscustomobject]@{ Profile="labmon_nostereo"; Port=1899; Flow="simulator_flow_labmon.json" }
    [pscustomobject]@{ Profile="labmon2_infoonly"; Port=1900; Flow="simulator_flow_labmon2.json" }
    [pscustomobject]@{ Profile="labmon2_nostereo"; Port=1900; Flow="simulator_flow_labmon2.json" }
    # Phase-1b labs.
    [pscustomobject]@{ Profile="labrel0"; Port=1904; Flow="simulator_flow_labrel0.json" }
    [pscustomobject]@{ Profile="labrel4"; Port=1905; Flow="simulator_flow_labrel4.json" }
    [pscustomobject]@{ Profile="labrel8"; Port=1906; Flow="simulator_flow_labrel8.json" }
    [pscustomobject]@{ Profile="labrel16"; Port=1907; Flow="simulator_flow_labrel16.json" }
    [pscustomobject]@{ Profile="labrel8s"; Port=1908; Flow="simulator_flow_labrel8s.json" }
    [pscustomobject]@{ Profile="labband"; Port=1912; Flow="simulator_flow_labband.json" }
    [pscustomobject]@{ Profile="lab4chain3"; Port=1913; Flow="simulator_flow_lab4chain3.json" }
)

# Agent program files this script edits (relative to the repository root,
# which is the current location).
$QlAslPath      = "src\agt\illuminance_controller_agent_ql.asl"
$BenchAslPath    = "src\agt\illuminance_controller_agent_bench.asl"
$ProfilesAslPath = "src\agt\lab_profiles.asl"

# ─── Logging setup ────────────────────────────────────────────────────────────
$LogFile    = Join-Path $ScriptRoot "run_full_project.log"
$LogStream  = $null

function Write-Log {
    param([string]$msg)
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$ts] $msg"
    if ($LogStream) { $LogStream.WriteLine($line) ; $LogStream.Flush() }
    Write-Host $line
}

function Write-Header {
    param([string]$msg)
    $bar = "═" * 60
    Write-Log ""
    Write-Log $bar
    Write-Log "  $msg"
    Write-Log $bar
}
function Write-OK   { param([string]$msg) Write-Log "  [OK]  $msg" }
function Write-Info { param([string]$msg) Write-Log "  [..] $msg" }
function Write-Warn { param([string]$msg) Write-Log "  [!!] $msg" }
function Write-Fail { param([string]$msg) Write-Log "  [XX] $msg" }

# ─── File I/O helpers (UTF-8 without BOM, matching Java/Gradle expectation) ───
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false

function ReadFile  { param([string]$path)
    [System.IO.File]::ReadAllText((Resolve-Path $path), $Utf8NoBom)
}
function WriteFile { param([string]$path, [string]$content)
    $abs = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($path)
    [System.IO.File]::WriteAllText($abs, $content, $Utf8NoBom)
}

# ─── Simulator health check ───────────────────────────────────────────────────
function Wait-Simulator {
    param([int]$Port, [int]$TimeoutSec = 90)
    # True as soon as /health or /was/rl/status answers HTTP 200; false after
    # $TimeoutSec. Which flow answers is checked afterwards by
    # Assert-SimulatorIdentity, which needs /health.
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

# ─── State tracking ───────────────────────────────────────────────────────────
$OrigFiles = @{}   # absolute path → original file text (saved before any edit)
$SimProcs  = @{}   # port (int) → System.Diagnostics.Process
$SimReported = @{} # run profile → profile name its simulator reported on /health
$SimFlows    = @{} # port (int) → flow file this run started there
$SimUserDirs = @{} # port (int) → --userDir of that simulator

# Keep the original text of a file before its first edit (restored at the end).
function Save-Orig { param([string]$path)
    $abs = (Resolve-Path $path).Path
    if (-not $OrigFiles.ContainsKey($abs)) {
        $OrigFiles[$abs] = ReadFile $abs
    }
}

# Names of the files one training cell must leave in the repository root;
# they are archived next to TRAINING_OK.json and listed in it.
function Get-ExpectedTrainingArtifacts {
    param(
        [string]$Profile,
        [string]$Stereo
    )
    if (-not $ProfileQtableSuffix.ContainsKey($Profile)) {
        throw "No qtable suffix mapping found for profile '$Profile'"
    }
    $suffix = $ProfileQtableSuffix[$Profile]
    return @(
        "qtable_final_stereotypes_${Stereo}${suffix}.csv",
        "qtable_initial_stereotypes_${Stereo}${suffix}.csv",
        "metrics_stereotypes_${Stereo}${suffix}.csv",
        "iv_stats_stereotypes_${Stereo}${suffix}.json",
        "coverage_stereotypes_${Stereo}${suffix}.csv",
        "first_goal_stereotypes_${Stereo}${suffix}.csv",
        "learned_stereotypes_${Stereo}${suffix}.ttl"
    )
}

# Training-situation schedule of a lab for the TRAINING_OK receipt: the file
# benchmark/train_scenarios_<lab>.json, its situation ids in file order and
# the SHA-256 of the ids joined by commas (the same hash as
# ScenarioCatalog.scheduleSha256). Throws when the file is missing, has no
# ids, an entry without id, or duplicate ids.
function Get-Phase1ScenarioProvenance {
    param([string]$Profile)
    # Variant labs (labmon_infoonly etc.) use the training file of their base
    # lab, as their train_scenarios(...) in lab_profiles.asl does;
    # train_scenarios_alias in config/run_config.json maps lab -> base. The
    # alias map is taken from the Read-RunConfig result ($RunConfig).
    # -ScenarioProvenanceCheckOnly runs this function for every known lab.
    $scenarioBase = $Profile
    $aliasMap = $null
    if ($RunConfig) { $aliasMap = $RunConfig.train_scenarios_alias }
    if ($aliasMap) {
        $aliasProp = $aliasMap.PSObject.Properties[$Profile]
        if ($null -ne $aliasProp) { $scenarioBase = $aliasProp.Value }
    }
    $scenarioFile = Join-Path $ScriptRoot "benchmark\train_scenarios_${scenarioBase}.json"
    if (-not (Test-Path -LiteralPath $scenarioFile)) {
        throw "Protocol-v2 scenario file missing: $scenarioFile"
    }
    # Parameter-passing (not piping) keeps the JSON array's elements intact
    # under Windows PowerShell 5.1; piping through ConvertFrom-Json emits the
    # whole array as ONE item there (pwsh 7 enumerates), collapsing $rows.
    $rows = @((ConvertFrom-Json (Get-Content -LiteralPath $scenarioFile -Raw -Encoding UTF8)))
    $ids = @($rows | Where-Object { $_.PSObject.Properties['id'] } | ForEach-Object { [int]$_.id })
    if ($ids.Count -ne $rows.Count) {
        throw "Protocol-v2 scenario entry is missing an ID: $scenarioFile"
    }
    if ($ids.Count -eq 0) { throw "Protocol-v2 scenario file has no IDs: $scenarioFile" }
    if ((@($ids | Select-Object -Unique)).Count -ne $ids.Count) {
        throw "Protocol-v2 scenario file has duplicate IDs: $scenarioFile"
    }
    $canonical = $ids -join ','
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($canonical)
        $hash = ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    return [pscustomobject]@{
        # Record the RESOLVED file (after any alias), never the name-derived one.
        file = "benchmark/train_scenarios_${scenarioBase}.json"
        ids = $ids
        hash = $hash
    }
}

# ── Scenario-file check (-ScenarioProvenanceCheckOnly) ──────────────────────
# Runs the same resolution as a training cell for every known lab, then exits
# (0 = all resolve, 1 = at least one fails) before anything is started.
if ($ScenarioProvenanceCheckOnly) {
    Write-Header "Protocol-v2 scenario-provenance resolution check ($($KnownProfiles.Count) profiles)"
    $provenanceFailed = $false
    foreach ($profileName in $KnownProfiles) {
        try {
            $prov = Get-Phase1ScenarioProvenance -Profile $profileName
            Write-OK ("{0,-22} -> {1} ({2} IDs)" -f $profileName, $prov.file, $prov.ids.Count)
        } catch {
            Write-Fail ("{0,-22} -> {1}" -f $profileName, $_.Exception.Message)
            $provenanceFailed = $true
        }
    }
    if ($provenanceFailed) {
        Write-Fail "Scenario-provenance resolution check FAILED."
        exit 1
    }
    Write-OK "All known profiles resolve a protocol-v2 scenario file."
    exit 0
}

# Delivery check of the three Phase-1b settings: read the values the training
# JVM printed (last "STEREO_EFFECTIVE_PROPS {json}" line of the cell log) and
# compare them with the requested ones. A missing line or any mismatch throws,
# so no TRAINING_OK receipt is written for a cell whose settings did not reach
# the JVM. (build.gradle passes a -P setting only when it is in _httpKeys.)
function Get-EffectiveStereoProps {
    param([string]$LogPath, $Requested)
    if (-not (Test-Path -LiteralPath $LogPath)) {
        throw "Effective-props gate: training log not found: $LogPath"
    }
    $line = $null
    foreach ($l in (Get-Content -LiteralPath $LogPath)) {
        if ($l -match 'STEREO_EFFECTIVE_PROPS\s+(\{.*\})\s*$') { $line = $Matches[1] }
    }
    if ($null -eq $line) {
        throw "Effective-props gate: no STEREO_EFFECTIVE_PROPS line in $LogPath (JVM did not report its stereotype knobs)"
    }
    $effective = $line | ConvertFrom-Json
    $mismatch = @()
    foreach ($key in $Requested.Keys) {
        $prop = $effective.PSObject.Properties[$key]
        if ($null -eq $prop) { $mismatch += "$key missing from effective props"; continue }
        if ([double]$prop.Value -ne [double]$Requested[$key]) {
            $mismatch += ("{0}: requested {1}, effective {2}" -f $key, $Requested[$key], $prop.Value)
        }
    }
    if ($mismatch.Count -gt 0) {
        throw "Effective-props gate FAILED (requested treatment did not reach the JVM): $($mismatch -join '; ')"
    }
    return $effective
}

# Delivery gate for the knowledge layer and key learning settings: read the
# values the training JVM reported in its log and compare them with what the
# run mode requested. Log lines used (the last occurrence wins):
#   "StereotypeReasoner: knowledge layer = FULL|BRICK|REDUNDANCY"
#   "Reward: clip=..."                      (reward limit)
#   "setTrainingBudgetEpisodes: ..."        (effective fade length: either
#                                            "ignored (sysprop ...) priorDecayEpisodes=D"
#                                            or "numEpisodes=N -> priorDecayEpisodes=D (auto")
#   "EnergyPrior: weight=..."               (energy-preference weight)
# A missing line or any mismatch throws, so no TRAINING_OK manifest is written.
# The requested values come from $RequestedKnowledgeLayer and the variables
# next to it; $PriorDecayEps = $null means "automatic".
function Get-TrainingDeliveredSettings {
    param([string]$LogPath, [string]$Layer, [double]$RewardClip, $PriorDecayEps, [double]$EnergyWeight)
    if (-not (Test-Path -LiteralPath $LogPath)) {
        throw "Delivery gate: training log not found: $LogPath"
    }
    $text = [System.IO.File]::ReadAllText($LogPath)
    $inv = [System.Globalization.CultureInfo]::InvariantCulture
    $last = {
        param($pattern, $what)
        $m = [regex]::Matches($text, $pattern)
        if ($m.Count -eq 0) { return $null }
        return $m[$m.Count - 1]
    }
    $lm = & $last 'StereotypeReasoner: knowledge layer = (\w+)'
    if ($null -eq $lm) { throw "Delivery gate: no knowledge-layer banner in $LogPath" }
    $rm = & $last 'Reward: clip=(\S+) '
    if ($null -eq $rm) { throw "Delivery gate: no 'Reward:' line in $LogPath" }
    $em = & $last 'EnergyPrior: weight=(\S+)'
    if ($null -eq $em) { throw "Delivery gate: no 'EnergyPrior:' line in $LogPath" }
    $fi = & $last 'setTrainingBudgetEpisodes: ignored \(sysprop -Dstereo\.priorDecayEpisodes=\d+ wins\)\. priorDecayEpisodes=(\d+)'
    $fa = & $last 'setTrainingBudgetEpisodes: numEpisodes=\d+ \S+ priorDecayEpisodes=(\d+) \(auto'
    if ($null -ne $fi) {
        $decayMode = 'fixed'; $decayEps = [int]$fi.Groups[1].Value
    } elseif ($null -ne $fa) {
        $decayMode = 'auto'; $decayEps = [int]$fa.Groups[1].Value
    } else {
        throw "Delivery gate: no 'setTrainingBudgetEpisodes' line in $LogPath"
    }
    $delivered = [ordered]@{
        knowledge_layer      = $lm.Groups[1].Value.ToLowerInvariant()
        reward_clip          = [double]::Parse($rm.Groups[1].Value, $inv)
        prior_decay_mode     = $decayMode
        prior_decay_episodes = $decayEps
        energy_prior_weight  = [double]::Parse($em.Groups[1].Value, $inv)
    }
    $bad = @()
    if ($delivered.knowledge_layer -ne $Layer) {
        $bad += "knowledge layer: requested '$Layer', log shows '$($delivered.knowledge_layer)'"
    }
    if ($delivered.reward_clip -ne $RewardClip) {
        $bad += "reward limit: requested $RewardClip, log shows $($delivered.reward_clip)"
    }
    if ($null -ne $PriorDecayEps) {
        if ($decayMode -ne 'fixed' -or $decayEps -ne [int]$PriorDecayEps) {
            $bad += "fade length: requested $PriorDecayEps (fixed), log shows $decayEps ($decayMode)"
        }
    } elseif ($decayMode -ne 'auto') {
        $bad += "fade length: requested automatic, log shows $decayEps ($decayMode)"
    }
    if ($delivered.energy_prior_weight -ne $EnergyWeight) {
        $bad += "energy-preference weight: requested $EnergyWeight, log shows $($delivered.energy_prior_weight)"
    }
    if ($bad.Count -gt 0) {
        throw "Delivery gate FAILED (requested settings did not reach the JVM): $($bad -join '; ')"
    }
    return $delivered
}

# Number of episodes that started from a random state because no scenario was
# scheduled, as reported by the training JVM at the end of training
# ("SCENARIO_FALLBACK_COUNT=<n>", LabEnvironment.reportScenarioFallbackCount).
# A missing line is an error: the receipt must carry a real count.
function Get-ScenarioFallbackCount {
    param([string]$LogPath)
    if (-not (Test-Path -LiteralPath $LogPath)) { throw "Fallback count: log not found: $LogPath" }
    $m = [regex]::Matches([System.IO.File]::ReadAllText($LogPath), 'SCENARIO_FALLBACK_COUNT=(\d+)')
    if ($m.Count -eq 0) { throw "Fallback count: no SCENARIO_FALLBACK_COUNT line in $LogPath" }
    return [int]$m[$m.Count - 1].Groups[1].Value
}

# Used with -SkipTraining: every selected lab and arm needs a valid
# TRAINING_OK.json (status ok, every listed file present; for protocol
# phase1-v2 also the seed of this run, a 3,000-episode horizon, the pairing
# version common-seed-v1, the schema phase1-benchmark-v2, a fallback count of
# 0 and the scenario schedule). Throws with the list of problems otherwise.
# The horizon 3000 is written here as a number, not read from the run mode.
function Assert-TrainingManifestsComplete {
    $missing = @()
    foreach ($profile in $TrainProfiles) {
        foreach ($stereo in $StereoModes) {
            $archiveDir = Join-Path $ScriptRoot "benchmark\\results\\$profile\\training_stereo_$stereo"
            $manifestPath = Join-Path $archiveDir "TRAINING_OK.json"
            if (-not (Test-Path $manifestPath)) {
                $missing += "Missing manifest: $manifestPath"
                continue
            }

            try {
                $manifest = Get-Content -Raw -Path $manifestPath | ConvertFrom-Json
            } catch {
                $missing += "Invalid manifest JSON: $manifestPath"
                continue
            }

            if ($manifest.status -ne "ok") {
                $missing += "Manifest status not ok: $manifestPath"
                continue
            }

            if ($P.protocol_version -eq 'phase1-v2') {
                if ($manifest.protocol_version -ne 'phase1-v2' -or
                    $manifest.run_seed -ne $RunSeed -or
                    $manifest.fixed_horizon_episodes -ne 3000 -or
                    $manifest.paired_rng_version -ne 'common-seed-v1' -or
                    $manifest.metric_schema -ne 'phase1-benchmark-v2' -or
                    $manifest.scenario_fallback_count -ne 0 -or
                    -not $manifest.ordered_scenario_ids -or
                    -not $manifest.scenario_schedule_sha256) {
                    $missing += "Protocol-v2 provenance invalid: $manifestPath"
                    continue
                }
            }

            if (-not $manifest.artifacts -or $manifest.artifacts.Count -eq 0) {
                $missing += "Manifest has no artifacts list: $manifestPath"
                continue
            }

            foreach ($fname in $manifest.artifacts) {
                $fpath = Join-Path $archiveDir $fname
                if (-not (Test-Path $fpath)) {
                    $missing += "Missing archived artifact: $fpath"
                }
            }
        }
    }

    if ($missing.Count -gt 0) {
        throw "SkipTraining validation failed.`n - " + ($missing -join "`n - ")
    }
}

# ─── Pre-flight checks ────────────────────────────────────────────────────────
# Open the log file (overwritten by every run). FileShare.ReadWrite lets
# other processes read or append to it while the run goes on; a plain
# StreamWriter would hold an exclusive write lock.
$_logFs     = [System.IO.FileStream]::new(
    $LogFile,
    [System.IO.FileMode]::Create,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::ReadWrite)
$LogStream  = [System.IO.StreamWriter]::new($_logFs, $Utf8NoBom)

Write-Header "KG-QL full-project run  |  mode=$RunMode  |  $(Get-Date -Format 'yyyy-MM-dd HH:mm')"

# Verify node-red is on PATH
# On Windows, node-red.ps1 is NOT in PATHEXT so Start-Process would open it
# as a text file.  Resolve node-red.cmd explicitly; it runs via cmd.exe and
# stays alive for the lifetime of the node process.
$_nrCmd = Get-Command "node-red.cmd" -ErrorAction SilentlyContinue
if (-not $_nrCmd) { $_nrCmd = Get-Command "node-red" -ErrorAction SilentlyContinue }
if (-not $_nrCmd) {
    Write-Fail "node-red not found in PATH.  Install it with:  npm install -g node-red"
    $LogStream.Close()
    exit 1
}
$NodeRedCmd = $_nrCmd.Source

# Simulator identity helpers (Assert-SimulatorIdentity, ...).
. (Join-Path $ScriptRoot 'scripts\SimulatorControl.ps1')

# Python 3 for step "Phase 5" (analysis/sweep_report.py). Resolved once here,
# so a run without Python stops before anything is started.
# Candidates in this order: "py -3", "python3", "python" (the order this
# script has always used, so a machine where one of them already worked gets
# the same command and arguments as before). A candidate is accepted only if
# it is found AND really runs and reports major version 3; this skips, for
# example, the Microsoft Store stub "python.exe" on Windows, which is found
# on the PATH but only prints a hint.
function Resolve-Python3 {
    $candidates = @(
        [pscustomobject]@{ Exe = 'py';      PreArgs = @('-3') },
        [pscustomobject]@{ Exe = 'python3'; PreArgs = @() },
        [pscustomobject]@{ Exe = 'python';  PreArgs = @() }
    )
    # A failing candidate may write to stderr; with 'Stop' that would throw
    # under Windows PowerShell 5.1.
    $ErrorActionPreference = 'Continue'
    foreach ($c in $candidates) {
        if (-not (Get-Command $c.Exe -ErrorAction SilentlyContinue)) { continue }
        $out = $null
        try {
            $out = & $c.Exe @($c.PreArgs) -c "import sys; print(sys.version_info[0])" 2>$null
        } catch {
            continue
        }
        if ($LASTEXITCODE -ne 0 -or $null -eq $out) { continue }
        if (([string](@($out) | Select-Object -Last 1)).Trim() -eq '3') { return $c }
    }
    return $null
}
$PythonResolved = Resolve-Python3
if (-not $PythonResolved) {
    Write-Fail "Python 3 not found: tried 'py -3', 'python3' and 'python'; none of them is on the PATH and reports version 3 when run.  Install Python 3 and add it to PATH."
    $LogStream.Close()
    exit 1
}
$PythonCmd     = $PythonResolved.Exe       # logged below ("py", "python3" or "python")
$PythonPreArgs = $PythonResolved.PreArgs   # "-3" for py, nothing otherwise

Write-OK "node-red : $NodeRedCmd"
Write-OK "python   : $PythonCmd"
Write-OK "RunMode  : $RunMode (tick=$($P.tick)s, episodes=$($P.num_episodes), delay=$($P.action_delay_ms)ms)"
Write-OK "Log file : $LogFile"

# ─────────────────────────────────────────────────────────────────────────────
try {

    # ════════════════════════════════════════════════════════════════════════
    # Phase 1 — Apply parameters to source files
    # ════════════════════════════════════════════════════════════════════════
    Write-Header "Phase 1 — Applying $RunMode parameters"

    # 1a. Simulator tick in every flow file of the simulator table (also the
    # labs not selected for this run). Not restored at the end.
    foreach ($sim in $Simulators) {
        $flowPath = "simulator\$($sim.Flow)"
        if (-not (Test-Path $flowPath)) {
            Write-Warn "Flow file not found — skipping: $flowPath"
            continue
        }
        Save-Orig $flowPath
        # The repeat inject node stores the interval as a quoted string, e.g. "repeat": "0.2"
        $patched = (ReadFile $flowPath) -replace '(?<="repeat"\s*:\s*)"[0-9.]+"', "`"$($P.tick)`""
        WriteFile $flowPath $patched
        Write-OK "Tick $($P.tick)s  →  $($sim.Flow)"
    }

    # 1b. Training agent parameters (the beliefs at the start of a line).
    Save-Orig $QlAslPath
    $ql = ReadFile $QlAslPath
    $ql = $ql -replace '(?m)^num_episodes\(\d+\)\.',            "num_episodes($($P.num_episodes))."
    $ql = $ql -replace '(?m)^max_steps_per_episode\(\d+\)\.',   "max_steps_per_episode($($P.max_steps_per_episode))."
    $ql = $ql -replace '(?m)^action_delay_ms\(\d+\)\.',         "action_delay_ms($($P.action_delay_ms))."
    $ql = $ql -replace '(?m)^exec_delay_ms\(\d+\)\.',           "exec_delay_ms($($P.exec_delay_ms_ql))."
    $ql = $ql -replace '(?m)^exec_max_steps\(\d+\)\.',          "exec_max_steps($($P.exec_max_steps_ql))."
    WriteFile $QlAslPath $ql
    Write-OK "QL agent   : episodes=$($P.num_episodes)  steps=$($P.max_steps_per_episode)  delay=$($P.action_delay_ms)ms"

    # 1c. Test agent parameters.
    Save-Orig $BenchAslPath
    $bench = ReadFile $BenchAslPath
    $bench = $bench -replace '(?m)^bench_runs\(\d+\)\.',        "bench_runs($($P.bench_runs))."
    $bench = $bench -replace '(?m)^exec_max_steps\(\d+\)\.',    "exec_max_steps($($P.exec_max_steps_bench))."
    $bench = $bench -replace '(?m)^exec_delay_ms\(\d+\)\.',     "exec_delay_ms($($P.exec_delay_ms_bench))."
    WriteFile $BenchAslPath $bench
    Write-OK "Bench agent: runs=$($P.bench_runs)  steps=$($P.exec_max_steps_bench)  delay=$($P.exec_delay_ms_bench)ms"

    # 1d. lab_profiles.asl — two edits, both undone after step "Phase 3" and
    # in the clean-up:
    #   (a) here: the episode count of every training_params(N, decay) becomes
    #       the run mode's num_episodes, so the training agent uses the run
    #       mode's budget instead of the lab's own value. The exploration decay
    #       (the value after the comma) stays the lab's own.
    #   (b) in step "Phase 3": active_profile("<lab>") for each training cell.
    Save-Orig $ProfilesAslPath
    $lp = ReadFile $ProfilesAslPath
    $lp = $lp -replace '(?m)(training_params\()\d+(,)', "`${1}$($P.num_episodes)`${2}"
    WriteFile $ProfilesAslPath $lp
    Write-OK "lab_profiles.asl : training_params episodes → $($P.num_episodes)  (run_mode=$RunMode)"

    # ════════════════════════════════════════════════════════════════════════
    # Phase 2 — Start Node-RED simulators
    # ════════════════════════════════════════════════════════════════════════
    Write-Header "Phase 2 — Starting Node-RED simulators"

    # Only the simulators of the selected labs are started (without
    # -OnlyProfiles: lab1, lab2, lab3).
    $ActiveSims = $Simulators | Where-Object { $_.Profile -in $TrainProfiles }

    foreach ($sim in $ActiveSims) {
        $flowAbs = Join-Path $ScriptRoot "simulator\$($sim.Flow)"
        $userDir = Join-Path $ScriptRoot ".node-red-$($sim.Profile)"

        # Profiles that share a flow also share its port (labmon_infoonly runs
        # the labmon flow): one simulator this run started serves them all.
        # A different flow on a port this run already uses is a config error.
        if ($SimProcs.ContainsKey($sim.Port)) {
            if ($SimFlows[$sim.Port] -eq $sim.Flow) {
                Write-OK "$($sim.Profile)  :$($sim.Port)  shares the simulator started for $($sim.Flow)"
                continue
            }
            throw "Profiles need two different flows on port $($sim.Port): $($SimFlows[$sim.Port]) and $($sim.Flow)"
        }

        if (-not (Test-Path $flowAbs)) {
            Write-Warn "Flow file missing — skipping: $($sim.Flow)"
            continue
        }
        # The port must be free: a simulator this run did not start is never
        # reused (it may be another lab whose state is unknown).
        Assert-SimulatorPortFree -Port $sim.Port -Purpose "the $($sim.Profile) simulator"
        New-Item -ItemType Directory -Force -Path $userDir | Out-Null
        $spArgs = @{
            FilePath     = $NodeRedCmd
            ArgumentList = "--userDir `"$userDir`" --port $($sim.Port) `"$flowAbs`""
            PassThru     = $true
        }
        # -WindowStyle is Windows-only; PowerShell Core on Linux/macOS does not support it.
        if ($IsWindows -or ($null -eq $IsWindows -and $env:OS -eq 'Windows_NT')) {
            $spArgs.WindowStyle = 'Hidden'
        }
        $proc = Start-Process @spArgs
        $SimProcs[$sim.Port] = $proc
        $SimFlows[$sim.Port] = $sim.Flow
        $SimUserDirs[$sim.Port] = $userDir
        Write-Info "Launched $($sim.Profile)  port=$($sim.Port)  PID=$($proc.Id)"
    }

    Write-Info "Waiting for newly-launched simulators to respond (up to 90 s each)..."
    foreach ($sim in $ActiveSims) {
        if (-not $SimProcs.ContainsKey($sim.Port)) { continue }   # skipped above (flow file missing)
        if (Wait-Simulator -Port $sim.Port -TimeoutSec 90) {
            Write-OK "$($sim.Profile)  :$($sim.Port)  ready"
            # Identity: the flow answering on the port must be the one started.
            $SimReported[$sim.Profile] = Assert-SimulatorIdentity -Port $sim.Port -FlowFile $sim.Flow
            Write-OK "$($sim.Profile)  :$($sim.Port)  reports profile '$($SimReported[$sim.Profile])'"
        } else {
            throw "Simulator $($sim.Profile) did not start within 90 s on port $($sim.Port)"
        }
    }

    # ════════════════════════════════════════════════════════════════════════
    # Phase 2.5 — Preflight
    # "gradlew preflight" (build.gradle): parses the Turtle files, checks the
    # action registries, the files named in each lab's profile and the
    # simulator's /health answer. The workflows skip it (-SkipPreflight).
    # ════════════════════════════════════════════════════════════════════════
    if (-not $SkipPreflight) {
        Write-Header "Phase 2.5 — Preflight"
        $pfArgs = @("preflight", "-Pprofiles=$($TrainProfiles -join ',')", "--console=plain")
        & $GradleExe @pfArgs *>&1 | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "Preflight failed (exit $LASTEXITCODE). Re-run with -SkipPreflight to bypass."
        }
        Write-OK "Preflight passed"
    } else {
        Write-Log "Phase 2.5 — Preflight SKIPPED (-SkipPreflight)"
    }

    # ════════════════════════════════════════════════════════════════════════
    # Phase 3 — Training
    # Each lab has its own file-name suffix (e.g. _lab2), so the output
    # files of different labs never overwrite each other.
    # ════════════════════════════════════════════════════════════════════════
    if (-not $SkipTraining) {
        $totalCells = $TrainProfiles.Count * $StereoModes.Count
        $cellsDone  = 0
        Write-Header "Phase 3 — Training  ($totalCells cells:  $($TrainProfiles.Count) profiles × $($StereoModes.Count) stereotype modes)"

        foreach ($profile in $TrainProfiles) {
            foreach ($stereo in $StereoModes) {
                $cellsDone++
                $cellStart = Get-Date
                Write-Header "Training [$cellsDone/$totalCells]  profile=$profile  use_stereotypes=$stereo"

                # Resume: a cell that already has its TRAINING_OK receipt is
                # skipped. The receipt is written only after every check of
                # the cell passed and its files were archived.
                $archiveDirPre   = "benchmark\results\$profile\training_stereo_$stereo"
                $manifestPathPre = Join-Path $archiveDirPre "TRAINING_OK.json"
                if (Test-Path $manifestPathPre) {
                    Write-OK "Already trained (manifest present) — skipping  profile=$profile  stereo=$stereo"
                    continue
                }

                # The lab is passed to taskQl as -Pprofile (system property
                # active.profile, read by the plan apply_runtime_overrides_ql
                # of the training agent at start-up).

                # Set the arm: use_stereotypes(true|false) in the training agent.
                $ql = ReadFile $QlAslPath
                $ql = $ql -replace '(?m)^use_stereotypes\((true|false)\)\.', "use_stereotypes($stereo)."
                WriteFile $QlAslPath $ql

                # Also write active_profile("<lab>") into lab_profiles.asl, so
                # the lab is right even if the system property is not read.
                # The original text was saved in step "Phase 1".
                $lp = ReadFile $ProfilesAslPath
                $lp = $lp -replace '(?m)^active_profile\(".*?"\)\.', "active_profile(`"$profile`")."
                WriteFile $ProfilesAslPath $lp

                $expectedArtefacts = Get-ExpectedTrainingArtifacts -Profile $profile -Stereo $stereo
                # Delete this cell's output files from an earlier run, so the
                # check below sees only files of this run.
                foreach ($fname in $expectedArtefacts) {
                    $fpath = Join-Path $ScriptRoot $fname
                    if (Test-Path $fpath) {
                        Remove-Item -Path $fpath -Force
                    }
                }

                # Run the training (taskQl depends on 'classes', so Gradle
                # compiles first). Gradle is started with Start-Process and
                # writes stdout and stderr to the cell's own log files (no
                # PowerShell pipeline that could slow the JVM down). Every 15 s
                # the script copies new progress lines of the cell log into
                # run_full_project.log and the console.
                $cellLogDir = Join-Path $ScriptRoot "log"
                New-Item -ItemType Directory -Force -Path $cellLogDir | Out-Null
                $cellLogFile = Join-Path $cellLogDir "train_${profile}_stereo_${stereo}.log"
                $cellErrFile = Join-Path $cellLogDir "train_${profile}_stereo_${stereo}.err.log"
                Write-Info "Gradle output -> $cellLogFile"
                $gArgs     = @("taskQl", "-Pprofile=$profile") + $HttpArgs + @("--console=plain")
                $gradleExe = $GradleExe
                # -WindowStyle is Windows-only; PowerShell Core on Linux/macOS does not support it.
                $gpArgs = @{
                    FilePath               = $gradleExe
                    ArgumentList           = $gArgs
                    WorkingDirectory       = $ScriptRoot
                    RedirectStandardOutput = $cellLogFile
                    RedirectStandardError  = $cellErrFile
                    PassThru               = $true
                }
                if ($IsWindows -or ($null -eq $IsWindows -and $env:OS -eq 'Windows_NT')) {
                    $gpArgs.WindowStyle = 'Hidden'
                }
                $gProc = Start-Process @gpArgs
                # Helper: read the lines added to $cellLogFile since the last
                # call and echo those that match $tailFilter via Write-Info.
                $tailPos = 0L
                $tailFilter = '\[Training\]|\[QL\]\s+Final|BUILD (FAILED|SUCCESSFUL)'
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
                            if ($tLine -match $tailFilter) {
                                Write-Info "  >> $($tLine.Trim())"
                            }
                        }
                        $PosRef.Value = [long]$fs.Position
                        $sr.Dispose(); $fs.Dispose()
                    } catch { }
                }
                # True when the last 600 lines of the training log show that
                # training finished: "[Training] ep N/N", "BUILD SUCCESSFUL" or
                # "Training complete after". Used when the JVM had to be
                # stopped or its exit code is not available.
                function Test-TrainingCompletionMarkers {
                    param([string]$LogPath, [int]$ExpectedEpisodes)
                    if (-not (Test-Path -LiteralPath $LogPath)) { return $false }
                    try {
                        $tail = Get-Content -LiteralPath $LogPath -Tail 600 -ErrorAction SilentlyContinue
                        if (-not $tail) { return $false }
                        $epPattern = "\\[Training\\]\\s+ep\\s+${ExpectedEpisodes}/${ExpectedEpisodes}\\b"
                        $joined = ($tail -join "`n")
                        return ($joined -match $epPattern) -or ($joined -match 'BUILD SUCCESSFUL') -or ($joined -match 'Training complete after')
                    } catch {
                        return $false
                    }
                }
                $heartbeatEverySec = 60
                $watchdogNoGrowthSec = 1200
                # Watchdog. Heartbeat line every 60 s. JaCaMo can stay in its
                # event loop after the agent has finished, so: when the log has
                # not grown for 180 s and shows a completion marker, the JVM
                # tree is stopped and the cell counts as finished; when the log
                # has not grown for 1200 s, the JVM tree is stopped and the cell
                # fails.
                $postCompleteIdleSec = 180
                $completedGracefully = $false
                $lastHeartbeat = Get-Date
                $lastLogGrowth = Get-Date
                $lastLogLength = if (Test-Path $cellLogFile) {
                    (Get-Item -LiteralPath $cellLogFile -ErrorAction SilentlyContinue).Length
                } else {
                    0
                }
                while (-not $gProc.HasExited) {
                    Start-Sleep -Seconds 15
                    Invoke-TailLog -LogPath $cellLogFile -PosRef ([ref]$tailPos)

                    $now = Get-Date
                    $logLength = if (Test-Path $cellLogFile) {
                        (Get-Item -LiteralPath $cellLogFile -ErrorAction SilentlyContinue).Length
                    } else {
                        0
                    }
                    if ($logLength -gt $lastLogLength) {
                        $lastLogGrowth = $now
                        $lastLogLength = $logLength
                    }

                    if ((New-TimeSpan -Start $lastHeartbeat -End $now).TotalSeconds -ge $heartbeatEverySec) {
                        $elapsedMin = [int](New-TimeSpan -Start $cellStart -End $now).TotalMinutes
                        $idleSec = [int](New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                        Write-Info "[Heartbeat] training still running: profile=$profile stereo=$stereo elapsed=${elapsedMin}m idle=${idleSec}s pid=$($gProc.Id)"
                        $lastHeartbeat = $now
                    }

                    $idleNow = (New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                    if ($idleNow -ge $postCompleteIdleSec) {
                        if (Test-TrainingCompletionMarkers -LogPath $cellLogFile -ExpectedEpisodes ([int]$P.num_episodes)) {
                            $idleInt = [int]$idleNow
                            Write-Warn "Training markers complete but JVM still alive after ${idleInt}s idle (profile=$profile stereo=$stereo); terminating JaCaMo and continuing."
                            try {
                                if (-not $gProc.HasExited) {
                                    $null = Stop-ProcessTree -RootId $gProc.Id
                                }
                            } catch { }
                            $completedGracefully = $true
                            break
                        }
                    }

                    if ($idleNow -ge $watchdogNoGrowthSec) {
                        try {
                            if (-not $gProc.HasExited) {
                                $null = Stop-ProcessTree -RootId $gProc.Id
                            }
                        } catch { }
                        throw "Training watchdog timeout for profile=$profile stereo=${stereo}: no log growth for $watchdogNoGrowthSec s (see $cellLogFile)"
                    }
                }
                # Final tail pass: capture any lines written after the last poll
                # The process is expected to be exited here, but refresh + wait
                # defensively to ensure ExitCode is populated.
                try { $gProc.Refresh() } catch {}
                try { [void]$gProc.WaitForExit(120000) } catch {}
                Invoke-TailLog -LogPath $cellLogFile -PosRef ([ref]$tailPos)
                # ExitCode can still be temporarily unavailable on Windows even
                # after HasExited due to process handle timing. Retry briefly.
                $gExitCode = $null
                for ($exitTry = 1; $exitTry -le 20; $exitTry++) {
                    try { $gProc.Refresh() } catch {}
                    if ($null -ne $gProc.ExitCode) {
                        $gExitCode = [int]$gProc.ExitCode
                        break
                    }
                    Start-Sleep -Milliseconds 250
                }
                if ($null -eq $gExitCode) { $gExitCode = -999 }
                # Merge stderr into main log if non-empty
                if ((Test-Path $cellErrFile) -and
                    (Get-Item $cellErrFile -ErrorAction SilentlyContinue).Length -gt 0) {
                    "`n--- STDERR ---`n" | Out-File -FilePath $cellLogFile -Encoding utf8 -Append
                    Get-Content $cellErrFile -ErrorAction SilentlyContinue |
                        Out-File -FilePath $cellLogFile -Encoding utf8 -Append
                }
                if ($gExitCode -ne 0) {
                    $logLooksComplete = Test-TrainingCompletionMarkers -LogPath $cellLogFile -ExpectedEpisodes ([int]$P.num_episodes)
                    if ($completedGracefully -and $logLooksComplete) {
                        Write-Warn "taskQl JVM was force-killed after completion markers (profile=$profile stereo=$stereo); treating as success."
                        $elapsed = [int](New-TimeSpan -Start $cellStart -End (Get-Date)).TotalMinutes
                        Write-OK "Done in ${elapsed} min  profile=$profile  stereo=$stereo"
                        $gExitCode = 0
                    } elseif (($gExitCode -eq -999) -and $logLooksComplete) {
                        Write-Warn "taskQl exit code unavailable (profile=$profile stereo=$stereo); completion markers found in log, continuing."
                        $elapsed = [int](New-TimeSpan -Start $cellStart -End (Get-Date)).TotalMinutes
                        Write-OK "Done in ${elapsed} min  profile=$profile  stereo=$stereo"
                        $gExitCode = 0
                    } else {
                        throw "taskQl failed with exit code $gExitCode for profile=$profile stereo=$stereo (see $cellLogFile)"
                    }
                } else {
                    $elapsed = [int](New-TimeSpan -Start $cellStart -End (Get-Date)).TotalMinutes
                    Write-OK "Done in ${elapsed} min  profile=$profile  stereo=$stereo"
                }

                $missingNow = @()
                foreach ($fname in $expectedArtefacts) {
                    $fpath = Join-Path $ScriptRoot $fname
                    if (-not (Test-Path $fpath)) {
                        $missingNow += $fname
                    }
                }
                if ($missingNow.Count -gt 0) {
                    throw "Training artifacts missing for profile=$profile stereo=${stereo}: $($missingNow -join ', ')"
                }

                $effectiveStereoProps = Get-EffectiveStereoProps -LogPath $cellLogFile -Requested $RequestedStereoProps
                Write-OK ("Effective stereotype knobs verified: " + (($RequestedStereoProps.Keys | ForEach-Object { "{0}={1}" -f $_, $RequestedStereoProps[$_] }) -join ' '))
                $deliveredSettings = Get-TrainingDeliveredSettings -LogPath $cellLogFile `
                    -Layer $RequestedKnowledgeLayer -RewardClip $RequestedRewardClip `
                    -PriorDecayEps $RequestedPriorDecayEps -EnergyWeight $RequestedEnergyWeight
                Write-OK ("Delivered settings verified: layer={0} clip={1} fade={2} ({3}) energyWeight={4}" -f `
                    $deliveredSettings.knowledge_layer, $deliveredSettings.reward_clip,
                    $deliveredSettings.prior_decay_episodes, $deliveredSettings.prior_decay_mode,
                    $deliveredSettings.energy_prior_weight)
                $scenarioFallbackCount = Get-ScenarioFallbackCount -LogPath $cellLogFile
                if ($scenarioFallbackCount -gt 0) {
                    Write-Warn "Training used $scenarioFallbackCount random start state(s) instead of scheduled scenarios (profile=$profile stereo=$stereo)"
                }

                $scenarioProvenance = $null
                if ($P.protocol_version -eq 'phase1-v2') {
                    $scenarioProvenance = Get-Phase1ScenarioProvenance -Profile $profile
                    $metricsName = "metrics_stereotypes_${stereo}$($ProfileQtableSuffix[$profile]).csv"
                    $metricsPath = Join-Path $ScriptRoot $metricsName
                    $episodeRows = @(Import-Csv -LiteralPath $metricsPath | Where-Object { $_.Episode -match '^\d+$' })
                    if ($episodeRows.Count -ne [int]$P.num_episodes) {
                        throw "Protocol-v2 fixed horizon failed for $profile/${stereo}: expected $($P.num_episodes), got $($episodeRows.Count)"
                    }
                    $fgName = "first_goal_stereotypes_${stereo}$($ProfileQtableSuffix[$profile]).csv"
                    $fgPath = Join-Path $ScriptRoot $fgName
                    $fgRows = @(Import-Csv -LiteralPath $fgPath | Where-Object { $_.ProtocolVersion -eq 'phase1-v2' })
                    if ($fgRows.Count -ne $scenarioProvenance.ids.Count) {
                        throw "Protocol-v2 first-goal rows failed for $profile/${stereo}: expected $($scenarioProvenance.ids.Count), got $($fgRows.Count)"
                    }
                }

                # Archive training artefacts for this cell
                $archiveDir = "benchmark\results\$profile\training_stereo_$stereo"
                New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
                foreach ($fname in $expectedArtefacts) {
                    Copy-Item -Path (Join-Path $ScriptRoot $fname) -Destination (Join-Path $archiveDir $fname) -Force
                }

                $manifestPath = Join-Path $archiveDir "TRAINING_OK.json"
                # Which flow served this cell, and the name it reported on /health.
                $cellSim = @($Simulators | Where-Object { $_.Profile -eq $profile }) | Select-Object -First 1
                $cellSimFlow = ""
                if ($cellSim) { $cellSimFlow = $cellSim.Flow }
                $cellSimReported = ""
                if ($SimReported.ContainsKey($profile)) { $cellSimReported = $SimReported[$profile] }
                $manifest = [ordered]@{
                    status = "ok"
                    profile = $profile
                    stereotype = $stereo
                    run_seed = [int]$RunSeed
                    run_mode = $RunMode
                    protocol_version = $(if ($P.protocol_version) { $P.protocol_version } else { 'legacy' })
                    ordered_scenario_ids = $(if ($scenarioProvenance) { $scenarioProvenance.ids } else { @() })
                    scenario_schedule_sha256 = $(if ($scenarioProvenance) { $scenarioProvenance.hash } else { '' })
                    scenario_file = $(if ($scenarioProvenance) { $scenarioProvenance.file } else { '' })
                    fixed_horizon_episodes = $(if ($P.protocol_version -eq 'phase1-v2') { [int]$P.num_episodes } else { 0 })
                    paired_rng_version = $(if ($P.protocol_version -eq 'phase1-v2') { 'common-seed-v1' } else { 'legacy-arm-mixed' })
                    metric_schema = $(if ($P.metric_schema) { $P.metric_schema } else { 'legacy' })
                    policy_energy_weights = $(if ($P.policy_energy_weights) { $P.policy_energy_weights } else { '' })
                    stereo_requested_props = $RequestedStereoProps
                    stereo_effective_props = $effectiveStereoProps
                    knowledge_layer_requested = $RequestedKnowledgeLayer
                    knowledge_layer_delivered = $deliveredSettings.knowledge_layer
                    reward_clip_effective = $deliveredSettings.reward_clip
                    prior_decay_mode_effective = $deliveredSettings.prior_decay_mode
                    prior_decay_episodes_effective = $deliveredSettings.prior_decay_episodes
                    stereo_energy_prior_weight_requested = $RequestedEnergyWeight
                    stereo_energy_prior_weight_effective = $deliveredSettings.energy_prior_weight
                    scenario_fallback_count = $scenarioFallbackCount
                    simulator_flow = $cellSimFlow
                    simulator_profile_reported = $cellSimReported
                    timestamp = (Get-Date).ToString("o")
                    artifacts = $expectedArtefacts
                } | ConvertTo-Json -Depth 4
                WriteFile $manifestPath $manifest
                Write-Info "Artefacts archived → $archiveDir"
            }
        }

        # Restore the training agent (original text, but with the run mode's
        # timing values) and lab_profiles.asl (original text).
        $restoredQl = $OrigFiles[(Resolve-Path $QlAslPath).Path]
        $restoredQl = $restoredQl -replace '(?m)^num_episodes\(\d+\)\.',           "num_episodes($($P.num_episodes))."
        $restoredQl = $restoredQl -replace '(?m)^max_steps_per_episode\(\d+\)\.',  "max_steps_per_episode($($P.max_steps_per_episode))."
        $restoredQl = $restoredQl -replace '(?m)^action_delay_ms\(\d+\)\.',        "action_delay_ms($($P.action_delay_ms))."
        $restoredQl = $restoredQl -replace '(?m)^exec_delay_ms\(\d+\)\.',          "exec_delay_ms($($P.exec_delay_ms_ql))."
        $restoredQl = $restoredQl -replace '(?m)^exec_max_steps\(\d+\)\.',         "exec_max_steps($($P.exec_max_steps_ql))."
        WriteFile $QlAslPath $restoredQl
        Write-OK "QL ASL restored (timing params preserved)"

        $restoredLp = $OrigFiles[(Resolve-Path $ProfilesAslPath).Path]
        WriteFile $ProfilesAslPath $restoredLp
        Write-OK "lab_profiles.asl restored"

    } else {
        Write-Header "Phase 3 — Training SKIPPED (-SkipTraining)"
        Write-Info "Validating training completion manifests before benchmark..."
        Assert-TrainingManifestsComplete
        Write-OK "SkipTraining validation passed for all profile × stereotype cells."
    }

    # ════════════════════════════════════════════════════════════════════════
    # Phase 4 — Benchmark sweep
    # runFullSweep (build.gradle) runs the test agent once per lab and mode,
    # passing both as system properties (active.profile, bench.mode), and
    # copies the result files to benchmark/results/<lab>/<mode>/.
    # ════════════════════════════════════════════════════════════════════════
    if (-not $SkipBenchmark) {
        Write-Header "Phase 4 — Benchmark sweep  ($($TrainProfiles.Count) profiles × 3 modes)"
        $sweepStart = Get-Date

        $sweepLogDir = Join-Path $ScriptRoot "log"
        New-Item -ItemType Directory -Force -Path $sweepLogDir | Out-Null
        $sweepLogFile = Join-Path $sweepLogDir "benchmark_sweep.log"
        Write-Info "Gradle output → $sweepLogFile"
        # Gradle output goes to the console and to its own log file, not into
        # $LogFile (which $LogStream holds open).
        & $GradleExe "runFullSweep" `
            "-Pprofiles=$BenchProfiles" `
            "-Pmodes=$BenchModes" `
            @HttpArgs `
            "--console=plain" *>&1 |
            Tee-Object -FilePath $sweepLogFile |
            ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "runFullSweep failed with exit code $LASTEXITCODE (see $sweepLogFile)"
        } else {
            $elapsed = [int](New-TimeSpan -Start $sweepStart -End (Get-Date)).TotalMinutes
            Write-OK "Benchmark sweep complete in ${elapsed} min"
        }

        # ════════════════════════════════════════════════════════════════════
        # Phase 5 — Analysis
        # ════════════════════════════════════════════════════════════════════
        Write-Header "Phase 5 — Sweep analysis report"

        & $PythonCmd @PythonPreArgs "analysis\sweep_report.py"

        if ($LASTEXITCODE -eq 0) {
            Write-OK "Analysis complete"
        } else {
            Write-Warn "Analysis script returned non-zero — review output above"
        }

        # ════════════════════════════════════════════════════════════════════
        # Phase 6 — Version artifacts (only with -VersionResults)
        # ════════════════════════════════════════════════════════════════════
        if ($VersionResults) {
            Write-Header "Phase 6 — Version artifacts"
            $vaScript = Join-Path $ScriptRoot "scripts\version_artifacts.ps1"
            if (Test-Path -LiteralPath $vaScript) {
                $vaArgs = @(
                    "-RunMode", $RunMode,
                    "-Profiles", $BenchProfiles,
                    "-Modes",    $BenchModes
                )
                if ($PublishResults) { $vaArgs += "-PublishResults" }
                try {
                    & $vaScript @vaArgs
                    Write-OK "Versioning complete"
                } catch {
                    Write-Warn "version_artifacts.ps1 failed: $($_.Exception.Message)"
                }
            } else {
                Write-Warn "scripts\version_artifacts.ps1 not found; skipping versioning."
            }
        }

    } else {
        Write-Header "Phases 4 & 5 — Benchmark + Analysis SKIPPED (-SkipBenchmark)"
    }

    Write-Header "All phases complete  |  $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
    Write-OK "Training artefacts : benchmark/results/<profile>/training_stereo_<bool>/"
    Write-OK "Benchmark results  : benchmark/results/<profile>/<mode>/"
    Write-OK "Log                : run_full_project.log"

} catch {
    $script:HadFatalError = $true
    Write-Fail "Fatal error: $_"
    Write-Fail $_.ScriptStackTrace

} finally {

    # ════════════════════════════════════════════════════════════════════════
    # Cleanup — always runs regardless of success or failure
    # ════════════════════════════════════════════════════════════════════════
    Write-Header "Cleanup"

    # Stop all simulators this run started, each with its whole process tree
    # (on Windows node-red.cmd runs the real node.exe as a child, which would
    # otherwise keep running and hold the port).
    foreach ($entry in $SimProcs.GetEnumerator()) {
        try {
            $res = Stop-SimulatorTree -Process $entry.Value -Port $entry.Key -UserDir $SimUserDirs[$entry.Key]
            Write-OK ("Stopped simulator on port {0} (process tree: {1}; by userDir: {2})" -f $entry.Key,
                (@($res.Stopped) -join ','), (@($res.StoppedByUserDir) -join ','))
            if (-not $res.PortFree) { Write-Warn "Port $($entry.Key) is still in use after stopping the simulator: $(Get-PortUsage -Port $entry.Key)" }
        } catch {
            Write-Warn "Could not stop port $($entry.Key): $_"
        }
    }

    # Restore the agent program files:
    #   - test agent and training agent: original text, but with the run
    #     mode's timing values (use_stereotypes gets its original value back);
    #   - lab_profiles.asl: original text (below).
    # The tick written into the flow files is not restored.

    $absBench = (Resolve-Path $BenchAslPath).Path
    if ($OrigFiles.ContainsKey($absBench)) {
        $r = $OrigFiles[$absBench]
        $r = $r -replace '(?m)^bench_runs\(\d+\)\.',        "bench_runs($($P.bench_runs))."
        $r = $r -replace '(?m)^exec_max_steps\(\d+\)\.',    "exec_max_steps($($P.exec_max_steps_bench))."
        $r = $r -replace '(?m)^exec_delay_ms\(\d+\)\.',     "exec_delay_ms($($P.exec_delay_ms_bench))."
        WriteFile $BenchAslPath $r
        Write-OK "Restored: $BenchAslPath  (timing params preserved)"
    }

    $absQl = (Resolve-Path $QlAslPath).Path
    if ($OrigFiles.ContainsKey($absQl)) {
        $r = $OrigFiles[$absQl]
        $r = $r -replace '(?m)^num_episodes\(\d+\)\.',           "num_episodes($($P.num_episodes))."
        $r = $r -replace '(?m)^max_steps_per_episode\(\d+\)\.',  "max_steps_per_episode($($P.max_steps_per_episode))."
        $r = $r -replace '(?m)^action_delay_ms\(\d+\)\.',        "action_delay_ms($($P.action_delay_ms))."
        $r = $r -replace '(?m)^exec_delay_ms\(\d+\)\.',          "exec_delay_ms($($P.exec_delay_ms_ql))."
        $r = $r -replace '(?m)^exec_max_steps\(\d+\)\.',         "exec_max_steps($($P.exec_max_steps_ql))."
        WriteFile $QlAslPath $r
        Write-OK "Restored: $QlAslPath  (timing params preserved)"
    }

    # Restore lab_profiles.asl (edited in step "Phase 1" for training_params and
    # per cell in step "Phase 3" for active_profile).  Must run in finally so the file
    # is always left clean even when the script exits via a thrown exception.
    # NOTE: avoid the PS7 null-conditional (?.) — keep this compatible with Windows PowerShell 5.1.
    $resolvedProfiles = Resolve-Path $ProfilesAslPath -ErrorAction SilentlyContinue
    $absProfiles = if ($resolvedProfiles) { $resolvedProfiles.Path } else { $null }
    if ($absProfiles -and $OrigFiles.ContainsKey($absProfiles)) {
        WriteFile $ProfilesAslPath $OrigFiles[$absProfiles]
        Write-OK "Restored: $ProfilesAslPath"
    }

    Write-Info "Note: simulator tick=$($P.tick) kept in all flow JSONs."
    Write-Info "      If this changed a flow file, 'git checkout -- simulator' restores the committed files."

    if ($LogStream) {
        Write-Log "Script exited  |  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        $LogStream.Flush()
        $LogStream.Dispose()
    }
    if ($_logFs) {
        $_logFs.Dispose()
    }
    if ($script:HadFatalError) {
        exit 1
    } else {
        exit 0
    }
}
