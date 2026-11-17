<#
.SYNOPSIS
Curate the Phase-2 structure-only (Brick knowledge layer) campaign into its archive.

.DESCRIPTION
Phase 2 is fault detection and recovery. For each run id this script:
  1. downloads the phase2-consolidated artifact with `gh run download` into a
     fresh staging directory,
  2. promotes analysis/, recovery_root/ and _artifacts/ to the run root,
  3. unless -SkipAdaptLogs is given, downloads the per-cell adapt-* artifacts
     into adapt_logs/ (kept locally; GitHub retains artifacts for 90 days) and
     distils the knowledge-layer banner and the fault/blacklist log lines into
     verification/adapt_log_knowledge_layer_evidence.csv,
  4. prints the artifact id and digest for the archive manifest,
  5. writes SHA256SUMS.csv with analysis/phase1_archive_inventory.py,
  6. validates with analysis/validate_phase2_v2_archive.py using
     --require-treatment-delivery (low-sun pin evidence on the *_lowsun cells)
     and --require-knowledge-layer brick (layer recorded and log-verified in
     every cell).
Already curated runs are skipped. Exit code 1 if any archive fails validation,
else 0. Registration:
docs/registrations/phase2_structonly_registration_2026-11-14.md, section 9.
The registered analysis continues with analysis/phase2_structonly_v3_family.py
and analysis/reproduce_phase2_structonly_v3.py.

Needs `gh` (authenticated) and Python (`py -3` or `python`).

.PARAMETER RunIds
GitHub Actions run ids to curate (required).

.PARAMETER OutRoot
Archive directory (default: results/phase2_v3_structonly).

.PARAMETER SkipAdaptLogs
Do not download the adapt-* log artifacts or build the log evidence CSV.

.EXAMPLE
.\scripts\curate_phase2_structonly_v3.ps1 -RunIds 43590832041

.OUTPUTS
<OutRoot>/run_<id>/ with analysis/, recovery_root/, _artifacts/,
adapt_logs/ (unless -SkipAdaptLogs), verification/adapt_log_knowledge_layer_evidence.csv
and SHA256SUMS.csv per run; the artifact id and digest are printed. A failed
download, a missing part of the artifact or a failed inventory stops the
script with a thrown error; a failed validation gives exit code 1 at the end.
#>
param(
    [Parameter(Mandatory = $true)][string[]]$RunIds,
    [string]$OutRoot = "results/phase2_v3_structonly",
    [switch]$SkipAdaptLogs
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
        Write-Host "skip $dest (consolidated artifact already curated)"
    } else {
        $staging = Join-Path $OutRoot "_staging_$id"
        if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
        New-Item -ItemType Directory -Force -Path $staging | Out-Null
        Write-Host "downloading run $id (consolidated) ..."
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
        $arts = (gh api "repos/{owner}/{repo}/actions/runs/$id/artifacts?per_page=100" | ConvertFrom-Json).artifacts
        foreach ($a in $arts) { if ($a.name -eq "phase2-consolidated") { Write-Host ("  artifact id {0}  digest {1}  size {2}" -f $a.id, $a.digest, $a.size_in_bytes) } }
    }

    # Per-cell adapt logs -> committed knowledge-layer + blacklist log evidence.
    $evDir = Join-Path $dest "verification"
    $evCsv = Join-Path $evDir "adapt_log_knowledge_layer_evidence.csv"
    if ((Test-Path $evCsv) -or $SkipAdaptLogs) {
        if (Test-Path $evCsv) { Write-Host "skip adapt-log evidence (already built)" }
    } else {
        $logRoot = Join-Path $dest "adapt_logs"
        if (-not (Test-Path $logRoot)) {
            Write-Host "downloading run $id adapt-* artifacts (cell logs) ..."
            New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
            gh run download $id --dir $logRoot --pattern "adapt-*"
            if ($LASTEXITCODE -ne 0) { throw "gh run download (adapt-*) failed for $id" }
        }
        New-Item -ItemType Directory -Force -Path $evDir | Out-Null
        $rows = @("cell,knowledge_layer_banners,delivered_layer,defective_lines,blacklist_lines")
        foreach ($cellDir in (Get-ChildItem -Path $logRoot -Directory | Sort-Object Name)) {
            $logs = @(Get-ChildItem -Path $cellDir.FullName -Recurse -Filter "adapt_*.log" |
                      Where-Object { $_.Name -notlike "*.err.log" })
            $banners = @(); $defect = 0; $black = 0
            foreach ($lg in $logs) {
                $b = @(Select-String -Path $lg.FullName -Pattern 'StereotypeReasoner: knowledge layer = (\w+)' -ErrorAction SilentlyContinue)
                $banners += @($b | ForEach-Object { $_.Matches[0].Groups[1].Value.ToLowerInvariant() })
                $defect += @(Select-String -Path $lg.FullName -Pattern 'flagged DEFECTIVE' -ErrorAction SilentlyContinue).Count
                $black  += @(Select-String -Path $lg.FullName -Pattern 'Blacklisted' -ErrorAction SilentlyContinue).Count
            }
            $delivered = "unknown"
            if ($banners.Count -gt 0) { $delivered = $banners[-1] }
            $rows += ("{0},{1},{2},{3},{4}" -f $cellDir.Name, $banners.Count, $delivered, $defect, $black)
        }
        $rows | Out-File -FilePath $evCsv -Encoding utf8
        Write-Host ("adapt-log evidence written: {0} ({1} cells)" -f $evCsv, ($rows.Count - 1))
    }

    if (-not (Test-Path (Join-Path $dest "SHA256SUMS.csv"))) {
        Write-Host "inventory for run $id ..."
        Invoke-Py analysis/phase1_archive_inventory.py --root $dest --out (Join-Path $dest "SHA256SUMS.csv") --include "analysis/**" --include "recovery_root/**" --include "_artifacts/**" --include "verification/**"
        if ($LASTEXITCODE -ne 0) { throw "inventory failed for $id" }
    }
    $inputs = Get-Content -Raw (Join-Path $dest "analysis\out\workflow_inputs.json") | ConvertFrom-Json
    $profiles = $inputs.profiles
    if ($profiles -is [array]) { $profiles = $profiles -join "," }
    $seeds = $inputs.seeds
    if ($seeds -is [array]) { $seeds = $seeds -join "," }
    Write-Host "validating run $id (knowledge-layer + treatment-delivery gates ON) ..."
    Invoke-Py analysis/validate_phase2_v2_archive.py $dest --profiles $profiles --seeds $seeds --require-treatment-delivery --require-knowledge-layer brick
    if ($LASTEXITCODE -ne 0) { $failed++; Write-Host "VALIDATION FAILED: $dest" }
}
if ($failed -gt 0) { exit 1 }
Write-Host "curation complete."
exit 0
