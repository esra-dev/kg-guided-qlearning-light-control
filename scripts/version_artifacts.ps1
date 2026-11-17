<#
.SYNOPSIS
    Copies the result files of a training and test run into a new folder on
    the branch "results" and tags the current commit.

.DESCRIPTION
    Called by the aggregate jobs of .github/workflows/phase1.yml and
    phase4.yml (with -PublishResults) and by run_full_project.ps1 with
    -VersionResults. Steps:
    1. Tag name: results-<UTC yyyyMMdd-HHmmss>-<RunMode>-<short commit>.
    2. Copies from the repository root every qtable_final_stereotypes_*,
       qtable_initial_stereotypes_*, learned_stereotypes_*,
       metrics_stereotypes_*, iv_stats_stereotypes_*, benchmark_results_* and
       bench_step_log_* file, and the folder benchmark/results (as
       benchmark_results/), into a temporary folder, with a RUN_MANIFEST.json
       (tag, time, run mode, labs, test modes, commit, branch, list of
       copied files, and the computer and user name of the environment).
    3. Checks out the branch "results" in a temporary git worktree (fetched
       from origin when it exists there; created as an orphan branch when it
       exists nowhere), copies the folder to <tag>/ and commits it.
    4. With -PublishResults pushes the branch to origin.
    5. Creates the lightweight tag on the current commit (HEAD) and, inside
       GitHub Actions, writes "results_tag=<tag>" to the step output
       ($GITHUB_OUTPUT); with -PublishResults pushes the tag. The workflows
       then check that the tag exists on the remote.
    6. Removes the temporary worktree and folder.

.PARAMETER RunMode
    The run mode of the run (for example phase1_v2_kg_only). Used in the tag
    name and the manifest.

.PARAMETER Profiles
    Comma-separated labs of this run (recorded in the manifest and the
    commit message; the files are copied by name pattern, not by lab).

.PARAMETER Modes
    Comma-separated test modes (default rule_based,ql_false,ql_true);
    recorded only.

.PARAMETER PublishResults
    Push the branch and the tag to origin. Default: off (commit and tag
    locally only).

.PARAMETER ResultsBranch
    Name of the results branch (default: results).

.PARAMETER OverwriteResultsBranch
    Replace the results branch by a new orphan branch that holds only this
    run, and force-push it with -PublishResults. This deletes everything
    else on the branch; the workflows do not use it.

.EXAMPLE
    .\scripts\version_artifacts.ps1 -RunMode phase1_v2_kg_only -Profiles "lab1,lab2,lab3" -Modes "ql_true"
    Commits the result files to the local branch "results" and tags HEAD.

.OUTPUTS
    A commit on the branch "results" with the folder <tag>/, a local tag
    <tag> on HEAD, and (inside GitHub Actions) the step output results_tag.
    Errors (not a git repository, worktree creation, push or tag failure)
    are thrown, which ends the script with a non-zero exit code. "Nothing to
    commit" is only a warning.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)] [string]$RunMode,
    [Parameter(Mandatory=$true)] [string]$Profiles,
    [string]$Modes = "rule_based,ql_false,ql_true",
    [switch]$PublishResults,
    [string]$ResultsBranch = "results",
    [switch]$OverwriteResultsBranch
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $repoRoot

# Verify git repo.
$null = & git rev-parse --is-inside-work-tree 2>$null
if ($LASTEXITCODE -ne 0) { throw "version_artifacts: not a git repository: $repoRoot" }

$shortSha  = (& git rev-parse --short HEAD).Trim()
$utcStamp  = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$tagName   = "results-$utcStamp-$RunMode-$shortSha"

Write-Host "version_artifacts: tag=$tagName" -ForegroundColor Cyan

# Stage artifact list.
$candidatePatterns = @(
    "qtable_final_stereotypes_*.csv",
    "qtable_initial_stereotypes_*.csv",
    "learned_stereotypes_*.ttl",
    "metrics_stereotypes_*.csv",
    "iv_stats_stereotypes_*.json",
    "benchmark_results_*.csv",
    "bench_step_log_*.csv"
)

$stageDir = Join-Path ([System.IO.Path]::GetTempPath()) "mt-results-$tagName"
if (Test-Path $stageDir) { Remove-Item -Recurse -Force $stageDir }
New-Item -ItemType Directory -Force -Path $stageDir | Out-Null

$copied = @()
foreach ($pat in $candidatePatterns) {
    Get-ChildItem -Path $repoRoot -Filter $pat -File -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item $_.FullName -Destination $stageDir -Force
        $copied += $_.Name
    }
}
# Also pull benchmark/results subtree (per-cell archived artifacts).
$benchResults = Join-Path $repoRoot "benchmark" "results"
if (Test-Path $benchResults) {
    Copy-Item -Path $benchResults -Destination (Join-Path $stageDir "benchmark_results") -Recurse -Force
    $copied += "benchmark/results/**"
}

# Manifest.
$manifest = [pscustomobject]@{
    tag           = $tagName
    utc           = $utcStamp
    runMode       = $RunMode
    profiles      = ($Profiles -split ',' | ForEach-Object { $_.Trim() })
    bench_modes   = ($Modes -split ',' | ForEach-Object { $_.Trim() })
    git_sha       = (& git rev-parse HEAD).Trim()
    git_short_sha = $shortSha
    git_branch    = (& git rev-parse --abbrev-ref HEAD).Trim()
    artifact_count = $copied.Count
    artifacts     = $copied
    host          = $env:COMPUTERNAME
    user          = $env:USERNAME
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stageDir "RUN_MANIFEST.json") -Encoding UTF8

if ($copied.Count -eq 0) {
    Write-Warning "version_artifacts: no artifacts found to version. Manifest will be committed alone."
}

# Set up worktree on the results branch.
$worktreePath = Join-Path ([System.IO.Path]::GetTempPath()) "mt-results-wt-$tagName"
if (Test-Path $worktreePath) { Remove-Item -Recurse -Force $worktreePath }

if (-not $OverwriteResultsBranch) {
    $branchExists = (& git ls-remote --exit-code --heads origin $ResultsBranch 2>$null)
    if ($LASTEXITCODE -eq 0) {
        & git fetch origin "${ResultsBranch}:${ResultsBranch}" 2>$null
    } else {
        # Local-only branch handling.
        $localExists = (& git rev-parse --verify "refs/heads/$ResultsBranch" 2>$null)
    }
}

try {
    if ($OverwriteResultsBranch) {
        Write-Host "version_artifacts: using overwrite mode for '$ResultsBranch' (CI-safe)" -ForegroundColor Yellow
        & git worktree add --detach $worktreePath HEAD
        Push-Location $worktreePath
        & git checkout --orphan $ResultsBranch
        & git rm -rf . 2>$null | Out-Null
        Pop-Location
    }
    elseif (& git rev-parse --verify "refs/heads/$ResultsBranch" 2>$null) {
        & git worktree add $worktreePath $ResultsBranch
    } else {
        # Create orphan branch.
        & git worktree add --detach $worktreePath HEAD
        Push-Location $worktreePath
        & git checkout --orphan $ResultsBranch
        & git rm -rf . 2>$null | Out-Null
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0) { throw "git worktree add failed" }

    $tagDir = Join-Path $worktreePath $tagName
    New-Item -ItemType Directory -Force -Path $tagDir | Out-Null
    Copy-Item -Path (Join-Path $stageDir '*') -Destination $tagDir -Recurse -Force

    Push-Location $worktreePath
    & git add .
    $msg = "results: $tagName ($Profiles | $Modes)"
    & git -c user.email="bot@local" -c user.name="version_artifacts" commit -m $msg | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Nothing to commit on $ResultsBranch."
    } else {
        Write-Host "Committed snapshot to branch '$ResultsBranch'." -ForegroundColor Green
    }

    if ($PublishResults) {
        if ($OverwriteResultsBranch) {
            & git push -u --force origin $ResultsBranch
        } else {
            & git push -u origin $ResultsBranch
        }
        if ($LASTEXITCODE -ne 0) { throw "Push to origin/$ResultsBranch failed." }
        Write-Host "Pushed origin/$ResultsBranch." -ForegroundColor Green
    }
    Pop-Location

    # Tag the current commit (HEAD; lightweight, local).
    & git tag $tagName HEAD
    if ($LASTEXITCODE -ne 0) { throw "git tag $tagName failed." }
    Write-Host "Tagged HEAD as '$tagName'." -ForegroundColor Green
    # In GitHub Actions, hand the tag name to later steps (step output
    # 'results_tag') so the workflow can check that the tag reached the remote.
    if ($env:GITHUB_OUTPUT) {
        "results_tag=$tagName" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
    }
    if ($PublishResults) {
        # A rejected tag push must fail the step, not pass unnoticed.
        & git push origin $tagName
        if ($LASTEXITCODE -ne 0) { throw "Push of tag $tagName to origin failed." }
        Write-Host "Pushed tag '$tagName'." -ForegroundColor Green
    }
}
finally {
    if (Test-Path $worktreePath) {
        Push-Location $repoRoot
        & git worktree remove --force $worktreePath 2>$null | Out-Null
        Pop-Location
    }
    if (Test-Path $stageDir) { Remove-Item -Recurse -Force $stageDir }
}
