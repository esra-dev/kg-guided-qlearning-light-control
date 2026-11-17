<#
.SYNOPSIS
Curate the eight Phase-1b confirmatory seed-half runs into the permanent campaign archive.

.DESCRIPTION
Phase 1b asks which kind of knowledge matters, in labs built to isolate one
mechanism each (docs/EXPERIMENTS.md). For each run id this script:
  1. downloads the run's phase1-consolidated artifact with `gh run download`
     into a staging directory,
  2. promotes it to the run root (analysis/out/... and
     benchmark/results_seed<N>/...),
  3. writes SHA256SUMS.csv with analysis/phase1_archive_inventory.py,
  4. validates the archive with analysis/validate_phase1b_archive.py at
     --stage confirmatory.
Runs that already have analysis/out/workflow_inputs.json are skipped. The
script stops with an error if any archive fails validation. The registered
analysis continues afterwards with analysis/phase1b_report.py and
analysis/reproduce_phase1b.py (registration:
docs/registrations/phase1b_registration_2026-07-26.md, section 6).

Needs the GitHub CLI (`gh`, authenticated) and `python` on the path. Run it
from any directory; it changes to the repository root.

.PARAMETER RunIds
GitHub Actions run ids to curate (default: the eight confirmatory runs).

.PARAMETER OutRoot
Archive directory that receives run_<id>/ (default: results/phase1b_corrected).

.EXAMPLE
.\scripts\curate_phase1b_confirmatory.ps1 -OutRoot results/phase1b_corrected

.OUTPUTS
<OutRoot>/run_<id>/ with analysis/, benchmark/ and SHA256SUMS.csv per run.
A failed download or inventory stops the script at once with a thrown error;
failed validations are counted and reported together at the end with a
thrown error. A thrown error ends the script with a non-zero exit code.
#>
param(
    [string[]]$RunIds = @("30199243656","30199247467","30199251189","30199254246",
                          "30199257204","30199260042","30199263213","30199266427"),
    [string]$OutRoot = "results/phase1b_corrected"
)
$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Split-Path -Parent $ScriptRoot)

$failed = 0
foreach ($id in $RunIds) {
    $dest = Join-Path $OutRoot "run_$id"
    if (Test-Path (Join-Path $dest "analysis\out\workflow_inputs.json")) {
        Write-Host "skip $dest (already curated)"
        continue
    }
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
    Write-Host "inventory for run $id ..."
    python analysis/phase1_archive_inventory.py --root $dest --out (Join-Path $dest "SHA256SUMS.csv") --include "analysis/**" --include "benchmark/**"
    if ($LASTEXITCODE -ne 0) { throw "inventory failed for $id" }
    Write-Host "validating run $id ..."
    python analysis/validate_phase1b_archive.py $dest --stage confirmatory
    if ($LASTEXITCODE -ne 0) { $failed++; Write-Host "VALIDATION FAILED: $dest" }
}
if ($failed -gt 0) { throw "$failed archive(s) failed validation" }
Write-Host "all curated archives validate (confirmatory)."
