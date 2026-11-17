<#
.SYNOPSIS
Curate the Phase-1b extended-arm re-run (protocol v3) into its campaign archive.

.DESCRIPTION
For each run id this script:
  1. downloads the phase1-consolidated artifact with `gh run download` into a
     fresh staging directory,
  2. promotes analysis/out/... and benchmark/results_seed<N>/... to the run root,
  3. prints the artifact id and digest for the campaign manifest,
  4. writes SHA256SUMS.csv with analysis/phase1_archive_inventory.py,
  5. validates with analysis/validate_phase1b_archive.py at --stage confirmatory
     and both treatment-delivery gates: --require-effective-props (every
     TRAINING_OK reports the registered extended knob values 2.0 / 5.0 / 2.0)
     and --divergence-against the frozen comparator runs (the labrel16 and
     labband stereo-true initial Q-tables must differ for every seed).
Already curated runs are skipped; the script stops with an error if any archive
fails validation. Registration:
docs/registrations/phase1b_extended_rerun_registration_2026-11-11.md, section 6.

The frozen comparator is the earlier Phase-1b campaign that keeps its raw files
(default results/phase1b_corrected; pass -Comparator explicitly when running
from a git worktree). Needs `gh` (authenticated) and Python (`py -3` or `python`).

.PARAMETER RunIds
GitHub Actions run ids of the extended-arm seed halves (required).

.PARAMETER OutRoot
Archive directory (default: results/phase1b_extended_v3).

.PARAMETER Comparator
Directory holding the frozen-KG comparator runs (default: results/phase1b_corrected).

.PARAMETER FrozenRuns
Names of the comparator run directories inside -Comparator (default: the two
kg_frozen seed halves).

.EXAMPLE
.\scripts\curate_phase1b_extended_v3.ps1 -RunIds 43099281565,43099286057

.OUTPUTS
<OutRoot>/run_<id>/ with analysis/, benchmark/ and SHA256SUMS.csv per run;
the artifact id and digest are printed. A failed download, a missing
comparator or a failed inventory stops the script at once with a thrown
error; failed validations are counted and reported together at the end
with a thrown error (non-zero exit code).
#>
param(
    [Parameter(Mandatory = $true)][string[]]$RunIds,
    [string]$OutRoot = "results/phase1b_extended_v3",
    [string]$Comparator = "results/phase1b_corrected",
    [string[]]$FrozenRuns = @("run_30199257204", "run_30199260042")
)
$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Split-Path -Parent $ScriptRoot)
# Interpreter: prefer the Windows launcher (py -3) when present, else python.
$PyCmd = @("python")
if (Get-Command py -ErrorAction SilentlyContinue) { $PyCmd = @("py", "-3") }
function Invoke-Py { if ($PyCmd.Count -gt 1) { & $PyCmd[0] $PyCmd[1] @args } else { & $PyCmd[0] @args } }

$frozen = @()
foreach ($r in $FrozenRuns) {
    $p = Join-Path $Comparator $r
    if (-not (Test-Path (Join-Path $p "analysis\out\workflow_inputs.json"))) { throw "frozen comparator archive missing: $p" }
    $frozen += $p
}

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
        gh run download $id --dir $staging --name phase1-consolidated
        if ($LASTEXITCODE -ne 0) { throw "gh run download failed for $id" }
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Move-Item (Join-Path $staging "analysis")  (Join-Path $dest "analysis")
        Move-Item (Join-Path $staging "benchmark") (Join-Path $dest "benchmark")
        Remove-Item -Recurse -Force $staging
        Write-Host "artifact provenance for run $id ..."
        $arts = (gh api "repos/{owner}/{repo}/actions/runs/$id/artifacts" | ConvertFrom-Json).artifacts
        foreach ($a in $arts) { if ($a.name -eq "phase1-consolidated") { Write-Host ("  artifact id {0}  digest {1}  size {2}" -f $a.id, $a.digest, $a.size_in_bytes) } }
    }
    if (-not (Test-Path (Join-Path $dest "SHA256SUMS.csv"))) {
        Write-Host "inventory for run $id ..."
        Invoke-Py analysis/phase1_archive_inventory.py --root $dest --out (Join-Path $dest "SHA256SUMS.csv") --include "analysis/**" --include "benchmark/**"
        if ($LASTEXITCODE -ne 0) { throw "inventory failed for $id" }
    }
    Write-Host "validating run $id (effective-props + initial-Q divergence gates ON) ..."
    Invoke-Py analysis/validate_phase1b_archive.py $dest --stage confirmatory --require-effective-props --divergence-against @frozen
    if ($LASTEXITCODE -ne 0) { $failed++; Write-Host "VALIDATION FAILED: $dest" }
}
if ($failed -gt 0) { throw "$failed archive(s) failed validation" }
Write-Host "all curated extended archives validate with treatment delivery evidenced."
