<#
.SYNOPSIS
    Reads one run mode, or one fault-stage settings profile, from
    config/run_config.json.

.DESCRIPTION
    Called (not dot-sourced) by run_full_project.ps1 with -RunMode and by
    run_phase2_adapt.ps1 with -AdaptSettingsProfile.

    With -RunMode: checks that the run mode exists in "profiles" and has the
    nine required keys (tick, num_episodes, max_steps_per_episode,
    action_delay_ms, exec_delay_ms_ql, exec_max_steps_ql, bench_runs,
    exec_max_steps_bench, exec_delay_ms_bench), that the numeric ones are not
    negative, and merges the run mode's learning_overrides on top of the
    global "learning" block.

    With -AdaptSettingsProfile: see that parameter.

    If the config file does not exist, the script returns $null (the
    training runner then falls back to its own "dev" values).

.PARAMETER RunMode
    Name of the profile inside config/run_config.json.profiles to load
    (e.g. "phase1_v2_kg_only", "phase4_v2").

.PARAMETER ConfigPath
    Optional override of the config file path. Defaults to
    <repo>/config/run_config.json.

.PARAMETER AdaptSettingsProfile
    Instead of a run mode, resolve one fault-stage learning-settings profile
    from config/run_config.json phase2.adapt_settings.profiles (e.g. "record",
    "paired", "aligned"). Returns the profile's name, description, the -P
    arguments to pass to Gradle (in a fixed order) and the expected effective
    values the adaptation JVM must report. The global 'learning' block is
    deliberately NOT used here: it switches on reward shaping and adaptive
    trust, which only the training run modes switch off again.

.EXAMPLE
    $cfg = & "$PSScriptRoot\Read-RunConfig.ps1" -RunMode dev
    if ($cfg) { $tick = $cfg.profile.tick }

.EXAMPLE
    $s = & "$PSScriptRoot\Read-RunConfig.ps1" -AdaptSettingsProfile paired
    $gradleArgs += $s.gradle_args

.OUTPUTS
    With -RunMode, an object with: Path, RunMode, profile (hashtable of the
    run mode's keys), train_scenarios_alias, learning (merged block),
    http_client, raw (the whole parsed file), and further copies of
    top-level keys (profiles_to_run, stereo_modes, bench_modes,
    simulator_port_map, simulator_flow_map, qtable_suffix_map,
    expected_state_vec_dim, dry_run) that run_full_project.ps1 does not read.
    run_full_project.ps1 uses Path, profile, learning, http_client and
    train_scenarios_alias.

    With -AdaptSettingsProfile, an object with: Path, name, description,
    properties, gradle_args (the "-P<key>=<value>" strings, in file order)
    and expected (the values the JVM log must show).

    $null when the config file does not exist. Every other problem (file not
    valid JSON, unknown run mode or settings profile, missing key, negative
    value, a reserved or non-string property) is a thrown error.
#>
[CmdletBinding(DefaultParameterSetName='RunMode')]
param(
    [Parameter(Mandatory=$true, ParameterSetName='RunMode')] [string]$RunMode,
    [Parameter(Mandatory=$true, ParameterSetName='AdaptSettings')] [string]$AdaptSettingsProfile,
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'

if (-not $ConfigPath) {
    $repoRoot   = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
    $ConfigPath = Join-Path $repoRoot 'config\run_config.json'
}

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    Write-Verbose "Read-RunConfig: config file not found at $ConfigPath; returning null"
    return $null
}

try {
    $raw = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8
    $cfg = $raw | ConvertFrom-Json
} catch {
    throw "Read-RunConfig: failed to parse '$ConfigPath' as JSON: $($_.Exception.Message)"
}

if ($PSCmdlet.ParameterSetName -eq 'AdaptSettings') {
    # Fault-stage settings profile. Keys the runner sets itself (profile,
    # mode, budget, seed, knowledge layer) and the fade length (which must stay
    # automatic) may not appear in a profile.
    $reserved = @('profile', 'mode', 'adapt.episodes', 'run.seed',
                  'stereo.knowledgeLayer', 'stereo.priorDecayEpisodes')
    $requiredExpected = @('reward_clip', 'reward_shaping', 'prior_scale', 'prior_decay_floor',
                          'prior_decay', 'prior_fade_visits', 'adaptive_trust',
                          'protocol_version', 'paired_arm_rng', 'energy_prior_weight')
    $block = $null
    if ($cfg.phase2) { $block = $cfg.phase2.adapt_settings }
    if (-not $block -or -not $block.profiles) {
        throw "Read-RunConfig: '$ConfigPath' has no phase2.adapt_settings.profiles block."
    }
    $name = $AdaptSettingsProfile.Trim()
    if (-not $block.profiles.PSObject.Properties[$name]) {
        $known = ($block.profiles.PSObject.Properties.Name) -join ', '
        throw "Read-RunConfig: settings profile '$name' not found in phase2.adapt_settings.profiles. Known: $known"
    }
    $sp = $block.profiles.$name
    if (-not $sp.properties -or -not $sp.expected) {
        throw "Read-RunConfig: settings profile '$name' needs both 'properties' and 'expected'."
    }
    $props = [ordered]@{}
    $gradleArgs = @()
    foreach ($p in $sp.properties.PSObject.Properties) {
        if ($reserved -contains $p.Name) {
            throw "Read-RunConfig: settings profile '$name' may not set '$($p.Name)'."
        }
        if (-not ($p.Value -is [string])) {
            throw "Read-RunConfig: settings profile '$name' property '$($p.Name)' must be written as a JSON string (it is passed verbatim)."
        }
        $props[$p.Name] = $p.Value
        $gradleArgs += ("-P{0}={1}" -f $p.Name, $p.Value)
    }
    $expected = [ordered]@{}
    foreach ($p in $sp.expected.PSObject.Properties) { $expected[$p.Name] = $p.Value }
    foreach ($k in $requiredExpected) {
        if (-not $expected.Contains($k)) {
            throw "Read-RunConfig: settings profile '$name' is missing expected value '$k'."
        }
    }
    if ($expected['prior_decay'] -ne 'auto') {
        throw "Read-RunConfig: settings profile '$name': prior_decay must be 'auto' (fade length = a quarter of the fault-stage budget)."
    }
    return [pscustomobject]@{
        Path        = $ConfigPath
        name        = $name
        description = [string]$sp.description
        properties  = $props
        gradle_args = $gradleArgs
        expected    = $expected
    }
}

if (-not $cfg.profiles) {
    throw "Read-RunConfig: '$ConfigPath' missing required key 'profiles'."
}
if (-not $cfg.profiles.PSObject.Properties[$RunMode]) {
    $known = ($cfg.profiles.PSObject.Properties.Name) -join ', '
    throw "Read-RunConfig: profile '$RunMode' not found in '$ConfigPath'. Known: $known"
}
$profile = $cfg.profiles.$RunMode

# Required keys of a run mode (the same keys as the fallback table $Configs
# in run_full_project.ps1).
$required = @(
    'tick','num_episodes','max_steps_per_episode','action_delay_ms',
    'exec_delay_ms_ql','exec_max_steps_ql','bench_runs',
    'exec_max_steps_bench','exec_delay_ms_bench'
)
foreach ($k in $required) {
    if (-not $profile.PSObject.Properties[$k]) {
        throw "Read-RunConfig: profile '$RunMode' missing required key '$k'."
    }
}

# Light range validation.
foreach ($k in @('num_episodes','max_steps_per_episode','action_delay_ms',
                 'exec_delay_ms_ql','exec_max_steps_ql','bench_runs',
                 'exec_max_steps_bench','exec_delay_ms_bench')) {
    $v = $profile.$k
    if ($v -lt 0) { throw "Read-RunConfig: profile '$RunMode'.$k must be >= 0 (got $v)." }
}

# The run mode as a hashtable, so that the caller can use $P = $result.profile
# in the same way as its fallback table.
$profileHt = @{}
foreach ($prop in $profile.PSObject.Properties) { $profileHt[$prop.Name] = $prop.Value }

# Merge optional per-profile 'learning_overrides' on top of the global
# 'learning' block (a key of the overrides replaces the global one). Every
# accepted run mode has overrides (at least reward_shaping none and
# adaptive_trust false); a run mode without overrides ("dev") gets the global
# block unchanged.
$mergedLearning = $cfg.learning
if ($profile.PSObject.Properties['learning_overrides']) {
    $lh = @{}
    if ($cfg.learning) {
        foreach ($p in $cfg.learning.PSObject.Properties) { $lh[$p.Name] = $p.Value }
    }
    foreach ($p in $profile.learning_overrides.PSObject.Properties) { $lh[$p.Name] = $p.Value }
    $mergedLearning = [pscustomobject]$lh
}

return [pscustomobject]@{
    Path              = $ConfigPath
    RunMode           = $RunMode
    profile           = $profileHt
    # Variant clean profiles (labmon_infoonly etc.) share their base lab's
    # protocol-v2 scenario file; consumed by Get-Phase1ScenarioProvenance.
    train_scenarios_alias = $cfg.train_scenarios_alias
    profiles_to_run   = @($cfg.profiles_to_run)
    stereo_modes      = @($cfg.stereo_modes)
    bench_modes       = @($cfg.bench_modes)
    simulator_port_map  = $cfg.simulator_port_map
    simulator_flow_map  = $cfg.simulator_flow_map
    qtable_suffix_map   = $cfg.qtable_suffix_map
    expected_state_vec_dim = $cfg.expected_state_vec_dim
    http_client       = $cfg.http_client
    learning          = $mergedLearning
    dry_run           = [bool]$cfg.dry_run
    raw               = $cfg
}
