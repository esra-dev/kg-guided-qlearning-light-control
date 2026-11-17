<#
.SYNOPSIS
Download the four Phase-1b pilot runs and mark them pilot-only.

.DESCRIPTION
Downloads each run with `gh run download` into <OutRoot>/run_<id>/ (skipping
directories that already exist) and writes PILOT_ONLY.json into it (marker,
run id, download time, note that the pilot seeds 1001-1010 are never pooled
with confirmatory results). Pilot artifacts must only be read through
  python analysis/phase1b_report.py --pilot-diagnostics ...
which emits blinded diagnostics only (docs/registrations/PHASE1B_POWER_PROTOCOL.md,
section 1). Needs `gh` (authenticated). Changes to the repository root.

.PARAMETER RunIds
GitHub Actions run ids of the pilot runs (default: the four registered pilot runs).

.PARAMETER OutRoot
Destination directory (default: results/exploratory/phase1b_pilot).

.EXAMPLE
.\scripts\download_phase1b_pilot.ps1

.OUTPUTS
<OutRoot>/run_<id>/ with every artifact of the run and PILOT_ONLY.json. A
failed download is a thrown error (non-zero exit code). The script also asks
`gh run view` for the run title but does not use the answer.
#>
param(
    [string[]]$RunIds = @("30171111962","30171112970","30171113750","30171114580"),
    [string]$OutRoot = "results/exploratory/phase1b_pilot"
)
$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Split-Path -Parent $ScriptRoot)

foreach ($id in $RunIds) {
    $dest = Join-Path $OutRoot "run_$id"
    if (Test-Path $dest) {
        Write-Host "skip $dest (exists)"
        continue
    }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Write-Host "downloading run $id -> $dest"
    gh run download $id --dir $dest
    if ($LASTEXITCODE -ne 0) { throw "gh run download failed for $id" }
    $mode = (gh run view $id --json displayTitle,databaseId | ConvertFrom-Json).displayTitle
    @{
        marker      = "PILOT_ONLY"
        run_id      = "$id"
        downloaded  = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
        note        = "Phase-1b pilot block seeds 1001-1010; never pool with confirmatory results."
    } | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $dest "PILOT_ONLY.json")
}
Write-Host "done."
