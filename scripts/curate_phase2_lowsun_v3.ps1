<#
.SYNOPSIS
Curate the Phase-2 low-sun re-run (protocol v3) into its campaign archive.

.DESCRIPTION
Phase 2 is fault detection and recovery. For each run id this script:
  1. downloads the phase2-consolidated artifact with `gh run download` into a
     fresh staging directory,
  2. promotes analysis/, recovery_root/ and _artifacts/ to the run root,
  3. prints the artifact id and digest for the archive manifest,
  4. writes SHA256SUMS.csv with analysis/phase1_archive_inventory.py,
  5. validates with analysis/validate_phase2_v2_archive.py and its
     --require-treatment-delivery gate (every *_lowsun cell must carry
     lowsun_pin_delivered=true and Sunshine values [100]); profiles and seeds
     are read from the run's analysis/out/workflow_inputs.json.
Already curated runs are skipped; the script stops with an error if any archive
fails validation. Registration:
docs/registrations/phase2_lowsun_rerun_registration_2026-11-11.md, section 7.
The registered analysis continues with analysis/phase2_lowsun_v3_family.py and
analysis/reproduce_phase2_lowsun_v3.py.

Needs `gh` (authenticated) and Python (`py -3` or `python`).

.PARAMETER RunIds
GitHub Actions run ids to curate (required).

.PARAMETER OutRoot
Archive directory (default: results/phase2_v3_lowsun).

.EXAMPLE
.\scripts\curate_phase2_lowsun_v3.ps1 -RunIds 43099277363

.OUTPUTS
<OutRoot>/run_<id>/ with analysis/, recovery_root/, _artifacts/ and
SHA256SUMS.csv per run; the artifact id and digest are printed. A failed
download, a missing part of the artifact or a failed inventory stops the
script at once with a thrown error; failed validations are counted and
reported together at the end with a thrown error (non-zero exit code).
#>
param(
    [Parameter(Mandatory = $true)][string[]]$RunIds,
    [string]$OutRoot = "results/phase2_v3_lowsun"
)
$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Split-Path -Parent $ScriptRoot)
# Interpreter: prefer the Windows launcher (py -3) when present, else python.
$PyCmd = @("python")
if (Get-Command py -ErrorAction SilentlyContinue) { $PyCmd = @("py", "-3") }
function Invoke-Py { if ($PyCmd.Count -gt 1) { & $PyCmd[0] $PyCmd[1] @args } else { & $PyCmd[0] @args } }

$failed = 0
foreach ($id in $RunIds) {
    $dest = Join-Path $OutRoot "run_$id"
    if (Test-Path (Join-Path $dest "analysis\out\workflow_inputs.json")) {
        Write-Host "skip $dest (already curated)"
    } else {
        $staging = Join-Path $OutRoot "_staging_$id"
        if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
        New-Item -ItemType Directory -Force -Path $staging | Out-Null
        Write-Host "downloading run $id ..."
        gh run download $id --dir $staging --name phase2-consolidated
        if ($LASTEXITCODE -ne 0) { throw "gh run download failed for $id" }
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        foreach ($part in @("analysis", "recovery_root", "_artifacts")) {
            $src = Join-Path $staging $part
            if (-not (Test-Path $src)) { throw "artifact of run $id lacks $part/" }
            Move-Item $src (Join-Path $dest $part)
        }
        Remove-Item -Recurse -Force $staging
        Write-Host "artifact provenance for run $id ..."
        $arts = (gh api "repos/{owner}/{repo}/actions/runs/$id/artifacts" | ConvertFrom-Json).artifacts
        foreach ($a in $arts) { if ($a.name -eq "phase2-consolidated") { Write-Host ("  artifact id {0}  digest {1}  size {2}" -f $a.id, $a.digest, $a.size_in_bytes) } }
    }
    if (-not (Test-Path (Join-Path $dest "SHA256SUMS.csv"))) {
        Write-Host "inventory for run $id ..."
        Invoke-Py analysis/phase1_archive_inventory.py --root $dest --out (Join-Path $dest "SHA256SUMS.csv") --include "analysis/**" --include "recovery_root/**" --include "_artifacts/**"
        if ($LASTEXITCODE -ne 0) { throw "inventory failed for $id" }
    }
    $inputs = Get-Content -Raw (Join-Path $dest "analysis\out\workflow_inputs.json") | ConvertFrom-Json
    $profiles = $inputs.profiles
    if ($profiles -is [array]) { $profiles = $profiles -join "," }
    $seeds = $inputs.seeds
    if ($seeds -is [array]) { $seeds = $seeds -join "," }
    Write-Host "validating run $id (treatment-delivery gate ON) ..."
    Invoke-Py analysis/validate_phase2_v2_archive.py $dest --profiles $profiles --seeds $seeds --require-treatment-delivery
    if ($LASTEXITCODE -ne 0) { $failed++; Write-Host "VALIDATION FAILED: $dest" }
}
if ($failed -gt 0) { throw "$failed archive(s) failed validation" }
Write-Host "all curated low-sun archives validate with treatment delivery evidenced."
