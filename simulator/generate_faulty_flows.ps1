<#
.SYNOPSIS
    Writes the 19 Node-RED flows with broken devices (Phase 2) from the
    healthy flows.

.DESCRIPTION
    Each faulty flow simulator/simulator_flow_<lab>_<fault>.json is a copy of
    a healthy flow (lab1, lab2, lab3, labmon, labmon2) in which fixed pieces
    of the light formula are replaced: a "dead" device contributes 0 lux, an
    "inverted" device contributes the negative of its normal light. Every
    replaced text must occur in the source, otherwise the script stops. The
    tab label gets the fault name, and the "profile" field of the /health
    answer is set to the faulty lab's name (taken from the output file name;
    it must occur exactly once in the source). The device and formula of
    each fault are described in docs/LABS.md.

    The output is deterministic: files are read and written as UTF-8 without
    BOM with LF line endings, so Windows PowerShell 5.1 and PowerShell 7 on
    Windows and Linux write identical bytes, and a second run leaves the
    committed files unchanged. At the end every simulator_flow_lab*_f*.json is
    parsed as JSON.

    Called by the adapt job of .github/workflows/phase2.yml before every fault
    cell. The faulty labs labmon_infoonly_f1dead and the like have no flow of
    their own; they run the flow of their parent's fault (for example
    simulator_flow_labmon_f1dead.json), as set in config/run_config.json.

.EXAMPLE
    .\simulator\generate_faulty_flows.ps1

.OUTPUTS
    The 19 flow files in simulator/ (overwritten), one "wrote <file>" line
    each, and one "OK <file>" line per parsed flow. A missing pattern, a
    profile field that does not occur exactly once, or a file that is not
    valid JSON is a thrown error (non-zero exit code).
#>
$ErrorActionPreference = 'Stop'
$SimDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -Path $SimDir

# Read and write UTF-8 without a byte-order mark, with LF line endings (the
# committed form, see .gitattributes), so the output is byte-identical under
# Windows PowerShell 5.1 and PowerShell 7 on Windows and Linux. (5.1's
# Get-Content reads a BOM-less UTF-8 file as the ANSI code page and its
# Set-Content -Encoding UTF8 adds a BOM, which would garble every non-ASCII
# character.) Paths are absolute because .NET resolves relative paths
# against the process directory, not the PowerShell location.
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false

function New-FaultyFlow($srcFile, $dstFile, $labelOld, $labelNew, $replacements) {
    $txt = [System.IO.File]::ReadAllText((Join-Path $SimDir $srcFile), $Utf8NoBom)
    $txt = $txt.Replace("`r`n", "`n")
    foreach ($r in $replacements) {
        $old = $r[0]; $new = $r[1]
        if ($txt.IndexOf($old) -lt 0) { throw "PATTERN NOT FOUND in ${srcFile}: '$old'" }
        $txt = $txt.Replace($old, $new)
    }
    $txt = $txt.Replace($labelOld, $labelNew)
    # The /health answer of every flow names its own profile (field 'profile'),
    # so a runner can check that the flow it asked for is the one running. The
    # healthy source reports its own name (e.g. 'lab3'); the faulty copy must
    # report the faulty profile (e.g. 'lab3_f1dead'), taken from the file name.
    $srcProfile = [System.IO.Path]::GetFileNameWithoutExtension($srcFile) -replace '^simulator_flow_', ''
    $dstProfile = [System.IO.Path]::GetFileNameWithoutExtension($dstFile) -replace '^simulator_flow_', ''
    $profileOld = "profile: '$srcProfile',"
    $profileHits = [regex]::Matches($txt, [regex]::Escape($profileOld)).Count
    if ($profileHits -ne 1) { throw "Expected exactly one ""$profileOld"" in ${srcFile}, found $profileHits" }
    $txt = $txt.Replace($profileOld, "profile: '$dstProfile',")
    [System.IO.File]::WriteAllText((Join-Path $SimDir $dstFile), $txt, $Utf8NoBom)
    Write-Host "wrote $dstFile"
}

# lab1_f1dead - DEAD Z1Light (kill 400 lux contribution)
New-FaultyFlow 'simulator_flow_lab1.json' 'simulator_flow_lab1_f1dead.json' `
    'Lab_1_Trivial (port 1892)' 'Lab_1_Trivial_F1DEAD (port 1892)' `
    @(,@('z1light ? 400', 'z1light ? 0'))

# lab2_f1dead - DEAD Z1Light
New-FaultyFlow 'simulator_flow_lab2.json' 'simulator_flow_lab2_f1dead.json' `
    'Lab_2_Intermediate (port 1893)' 'Lab_2_Intermediate_F1DEAD (port 1893)' `
    @(,@('z1l ? 400', 'z1l ? 0'))

# lab2_f1inv - INVERTED Z1Light
New-FaultyFlow 'simulator_flow_lab2.json' 'simulator_flow_lab2_f1inv.json' `
    'Lab_2_Intermediate (port 1893)' 'Lab_2_Intermediate_F1INV (port 1893)' `
    @(,@('z1l ? 400', 'z1l ? -400'))

# lab3_f1dead - DEAD Z1Light (kill BOTH own-zone 400 and cross-zone 100 spill;
# in lab3 a lamp adds 100 lux to the other zone and a blind 0.30*sun).
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f1dead.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F1DEAD (port 1894)' `
    @(@('z1l ? 400', 'z1l ? 0'), @('z1l ? 100', 'z1l ? 0'))

# lab3_f1inv - INVERTED Z1Light (negate BOTH contributions)
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f1inv.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F1INV (port 1894)' `
    @(@('z1l ? 400', 'z1l ? -400'), @('z1l ? 100', 'z1l ? -100'))

# ── Two lamps broken at once ─────────────────────────────────────────────────
# Only lamps are broken; the blinds (and the lab3 spotlight) stay healthy.

# lab2_f2dead - BOTH task lamps dead (Z1Light + Z2Light kill their 400 lux)
New-FaultyFlow 'simulator_flow_lab2.json' 'simulator_flow_lab2_f2dead.json' `
    'Lab_2_Intermediate (port 1893)' 'Lab_2_Intermediate_F2DEAD (port 1893)' `
    @(@('z1l ? 400', 'z1l ? 0'), @('z2l ? 400', 'z2l ? 0'))

# lab2_f2inv - BOTH task lamps inverted (Z1Light + Z2Light negate their 400 lux)
New-FaultyFlow 'simulator_flow_lab2.json' 'simulator_flow_lab2_f2inv.json' `
    'Lab_2_Intermediate (port 1893)' 'Lab_2_Intermediate_F2INV (port 1893)' `
    @(@('z1l ? 400', 'z1l ? -400'), @('z2l ? 400', 'z2l ? -400'))

# lab3_f2dead - BOTH task lamps dead (own 400 + cross-zone 100 spill, both lamps).
# Spotlight (+150 both zones) and blinds survive as recovery levers.
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f2dead.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F2DEAD (port 1894)' `
    @(@('z1l ? 400', 'z1l ? 0'), @('z1l ? 100', 'z1l ? 0'), `
      @('z2l ? 400', 'z2l ? 0'), @('z2l ? 100', 'z2l ? 0'))

# lab3_f2inv - BOTH task lamps inverted (own 400 + cross-zone 100 spill, both lamps)
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f2inv.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F2INV (port 1894)' `
    @(@('z1l ? 400', 'z1l ? -400'), @('z1l ? 100', 'z1l ? -100'), `
      @('z2l ? 400', 'z2l ? -400'), @('z2l ? 100', 'z2l ? -100'))

# ── Zone-2 lamp faults in lab3, and blind faults ─────────────────────────────
# (1) Z2-lamp variants, symmetric to the Z1-lamp faults of lab3.
# (2) Blind faults: a blind adds 0.50*sun to its own zone and, in lab3 only,
#     0.30*sun to the other zone. DEAD zeros both; INVERTED negates both. The
#     fault-stage agent can see such a fault only when the blind is opened
#     with sun rank >= 2 (its blind test, docs/ARCHITECTURE.md section 6).

# lab3_f1dead_z2 - DEAD Z2Light (own 400 + cross-zone 100 spill), symmetric to f1dead
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f1dead_z2.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F1DEAD_Z2 (port 1894)' `
    @(@('z2l ? 400', 'z2l ? 0'), @('z2l ? 100', 'z2l ? 0'))

# lab3_f1inv_z2 - INVERTED Z2Light (negate own 400 + cross-zone 100), symmetric to f1inv
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f1inv_z2.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F1INV_Z2 (port 1894)' `
    @(@('z2l ? 400', 'z2l ? -400'), @('z2l ? 100', 'z2l ? -100'))

# lab3_f1bdead - DEAD Z1Blinds (kill own-zone 0.50*sun + cross-zone 0.30*sun)
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f1bdead.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F1BDEAD (port 1894)' `
    @(@('z1b ? 0.50 * sun', 'z1b ? 0'), @('z1b ? 0.30 * sun', 'z1b ? 0'))

# lab3_f1binv - INVERTED Z1Blinds (negate own-zone 0.50*sun + cross-zone 0.30*sun)
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f1binv.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F1BINV (port 1894)' `
    @(@('z1b ? 0.50 * sun', 'z1b ? -0.50 * sun'), @('z1b ? 0.30 * sun', 'z1b ? -0.30 * sun'))

# lab2_f1bdead - DEAD Z1Blinds in the Intermediate lab (own-zone 0.50*sun; no cross-zone)
New-FaultyFlow 'simulator_flow_lab2.json' 'simulator_flow_lab2_f1bdead.json' `
    'Lab_2_Intermediate (port 1893)' 'Lab_2_Intermediate_F1BDEAD (port 1893)' `
    @(,@('z1b ? 0.50 * sun', 'z1b ? 0'))

# lab2_f1binv - INVERTED Z1Blinds in the Intermediate lab (negate own-zone 0.50*sun)
New-FaultyFlow 'simulator_flow_lab2.json' 'simulator_flow_lab2_f1binv.json' `
    'Lab_2_Intermediate (port 1893)' 'Lab_2_Intermediate_F1BINV (port 1893)' `
    @(,@('z1b ? 0.50 * sun', 'z1b ? -0.50 * sun'))

# ── Two blinds broken at once ────────────────────────────────────────────────
# lab3_f2bdead - BOTH blinds dead in the Complex lab (each blind's own-zone
# 0.50*sun AND cross-zone 0.30*sun contributions zeroed). The two-lamp faults
# above (f2dead/f2inv) break lamps only; here two sun-dependent devices break,
# each visible only when opened under sun rank >= 2, so the agent has to test
# and blacklist twice. The remaining devices (Z1Light, Z2Light, Spotlight)
# still reach rank 3 in both zones without sun (25 + 400 own + 100 cross + 150
# spotlight = 675 lux), whereas the two-lamp faults leave only sun-dependent
# devices (and, in lab3, the spotlight). Exploratory cell, registered in
# docs/registrations/pre_registration.md section 9.11; not a member of the
# section 9.5/9.6 families.
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f2bdead.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F2BDEAD (port 1894)' `
    @(@('z1b ? 0.50 * sun', 'z1b ? 0'), @('z1b ? 0.30 * sun', 'z1b ? 0'), `
      @('z2b ? 0.50 * sun', 'z2b ? 0'), @('z2b ? 0.30 * sun', 'z2b ? 0'))

# ── Monitor lab ──────────────────────────────────────────────────────────────
# labmon_f1dead - DEAD primary lamp (kill the +400 lux Z1Light contribution).
# The lamp is the only device that reaches rank 3, so once it is blacklisted
# the goal (rank 3) is unreachable. The best reachable state is rank 2 with the
# monitor (25 + 260 = 285 lux); the agent's reachability probe lowers the
# target to that rank.
New-FaultyFlow 'simulator_flow_labmon.json' 'simulator_flow_labmon_f1dead.json' `
    'Lab_Monitor_Emergency (port 1899)' 'Lab_Monitor_Emergency_F1DEAD (port 1899)' `
    @(,@('z1l   ? 400 : 0', 'z1l   ? 0 : 0'))

# ── Two lamps broken and low sun ─────────────────────────────────────────────
# lab3_f2dead_lowsun - BOTH task lamps dead (own 400 + cross-zone 100 spill) AND
# the random sun of the flow's reset node replaced by 100 lux (rank 1). In the
# runs the low sun comes from the training file
# benchmark/train_scenarios_lab3_lowsun.json (Sunshine 100 in every situation),
# because a scheduled start sets the sun itself; run_phase2_adapt.ps1 checks
# that file before the cell starts. Why low sun:
#   * With both lamps dead but high sun, the blinds alone reach rank 3
#     (0.50*900 = 450 > 300), so plain lab3_f2dead is degraded only in low-sun
#     situations. With sun = 100 the goal is unreachable in every situation.
#   * With both lamps gone and sun = 100 the per-zone ceiling is
#     25 + spotlight(150) + own_blind(0.50*100=50) + cross_blind(0.30*100=30)
#     = 255 lux = rank 2. Both zones are degraded to rank 2 (target rank 3).
#   * Remaining devices after the two lamps are blacklisted: Z1Blinds,
#     Z2Blinds, Spotlight -> 2^3 = 8 combinations for the reachability probe,
#     in both zones.
#   * The Spotlight never suffices alone in the healthy lab (150 < 300); after
#     the fault it is needed for the best reachable rank.
New-FaultyFlow 'simulator_flow_lab3.json' 'simulator_flow_lab3_f2dead_lowsun.json' `
    'Lab_3_Complex (port 1894)' 'Lab_3_Complex_F2DEAD_LOWSUN (port 1894)' `
    @(@('z1l ? 400', 'z1l ? 0'), @('z1l ? 100', 'z1l ? 0'), `
      @('z2l ? 400', 'z2l ? 0'), @('z2l ? 100', 'z2l ? 0'), `
      @('sunRanks[Math.floor(Math.random() * sunRanks.length)]', '100'))

# ── Two-zone monitor lab: two lamps broken and low sun ──────────────────────
# labmon2_f2dead_lowsun - BOTH primary task lamps dead (kill each +400 lux
# contribution) AND the random sun of the reset node replaced by 100 lux
# (rank 1); in the runs the low sun comes from
# benchmark/train_scenarios_labmon2_lowsun.json, as for lab3_f2dead_lowsun.
#   * With both lamps gone and sun = 100 the per-zone ceiling is
#     25 + monitor(200) + blind(0.50*100=50) = 275 lux = rank 2. The rank-3
#     goal is unreachable in both zones in every situation.
#   * Remaining devices after the two lamps are blacklisted: Z1Monitor,
#     Z2Monitor, Z1Blinds, Z2Blinds -> 2^4 = 16 combinations for the
#     reachability probe, in both zones.
#   * The monitor (rank 2 alone: 25 + 200 = 225 lux) is needed in each zone
#     for the best reachable rank.
New-FaultyFlow 'simulator_flow_labmon2.json' 'simulator_flow_labmon2_f2dead_lowsun.json' `
    'Labmon2_DualMonitor (port 1900)' 'Labmon2_DualMonitor_F2DEAD_LOWSUN (port 1900)' `
    @(@('z1l ? 400', 'z1l ? 0'), `
      @('z2l ? 400', 'z2l ? 0'), `
      @('sunRanks[Math.floor(Math.random() * sunRanks.length)]', '100'))

Write-Host "`n--- verify each faulty flow is valid JSON ---"
Get-ChildItem simulator_flow_lab*_f*.json | ForEach-Object {
    $null = [System.IO.File]::ReadAllText($_.FullName, $Utf8NoBom) | ConvertFrom-Json
    Write-Host ("OK  {0}  ({1} bytes)" -f $_.Name, $_.Length)
}
