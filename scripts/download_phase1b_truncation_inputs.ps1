<#
.SYNOPSIS
Download the raw Phase-1b metric files needed by the truncated-learning analysis.

.DESCRIPTION
Reads the run list (run id and artifact id per mode) and the artifact name of the Phase-1b
campaign from the spec file, checks through the GitHub API that each artifact
exists, matches the recorded artifact id and has not expired, and downloads it
with `gh run download` into <OutRoot>/run_<id>/. Runs that already contain
analysis/out/workflow_inputs.json are skipped; a partially downloaded directory
is refused rather than overlaid. No learner is run. The downloaded raw files stay
local; analysis/phase1_truncated_learning.py (--phase1b-input-root) binds each
metric file to the committed SHA-256 inventories. Needs `gh` (authenticated).

.PARAMETER Spec
Spec JSON listing the runs (default: analysis/phase1_truncated_learning_spec.json).

.PARAMETER OutRoot
Destination directory (default: .phase1b_truncation_inputs).

.EXAMPLE
.\scripts\download_phase1b_truncation_inputs.ps1

.OUTPUTS
<OutRoot>/run_<id>/ with the downloaded artifact per run. The GitHub API path
names the repository esra-dev/MT-PhysicsBAS explicitly. A missing or expired
artifact, a wrong artifact id, a partial directory or a failed download is a
thrown error (non-zero exit code).
#>
param(
    [string]$Spec = "analysis/phase1_truncated_learning_spec.json",
    [string]$OutRoot = ".phase1b_truncation_inputs"
)
$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptRoot
Set-Location $RepoRoot

$cfg = Get-Content -Raw -LiteralPath $Spec | ConvertFrom-Json
$campaign = $cfg.campaigns.phase1b
$runs = @()
foreach ($modeProperty in $campaign.runs.PSObject.Properties) {
    foreach ($run in $modeProperty.Value) {
        $runs += [pscustomobject]@{
            Mode = $modeProperty.Name
            RunId = [string]$run.run_id
            ArtifactId = [long]$run.artifact_id
        }
    }
}

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
foreach ($run in $runs) {
    $dest = Join-Path $OutRoot ("run_" + $run.RunId)
    $sentinel = Join-Path $dest "analysis\out\workflow_inputs.json"
    if (Test-Path -LiteralPath $sentinel) {
        Write-Host "skip $($run.RunId): already downloaded"
        continue
    }
    if (Test-Path -LiteralPath $dest) {
        throw "Refusing to overlay partial directory: $dest (remove or relocate it explicitly)"
    }

    $api = gh api "repos/esra-dev/MT-PhysicsBAS/actions/runs/$($run.RunId)/artifacts" |
        ConvertFrom-Json
    $artifact = @($api.artifacts | Where-Object { $_.name -eq $campaign.artifact_name })
    if ($artifact.Count -ne 1) {
        throw "Expected one $($campaign.artifact_name) artifact for run $($run.RunId)"
    }
    if ([long]$artifact[0].id -ne $run.ArtifactId -or $artifact[0].expired) {
        throw "Artifact binding/expiry check failed for run $($run.RunId)"
    }
    New-Item -ItemType Directory -Path $dest | Out-Null
    gh run download $run.RunId --name $campaign.artifact_name --dir $dest
    if ($LASTEXITCODE -ne 0) { throw "gh run download failed for $($run.RunId)" }
}
Write-Host "Phase-1b truncation inputs ready under $OutRoot"
