<#
.SYNOPSIS
Start the seven Phase-1b Node-RED simulator flows and run the live smoke probe.

.DESCRIPTION
Starts one isolated Node-RED instance per flow (labrel0, labrel4, labrel8,
labrel16, labrel8s, labband, lab4chain3 on ports 1904-1908, 1912, 1913; flows
from simulator/simulator_flow_<profile>.json, temporary user directories), runs
analysis/phase1b_flow_smoke.py against them, then stops the instances and
deletes the temporary directories. The probe's JSON evidence is written to
-OutputPath (relative paths resolve against the repository root). Refuses to
start if any of the ports is already in use. Throws if node/npm, a global
Node-RED 4 install, or Python is missing, or if the probe fails. One example
of the evidence: docs/evidence/phase1b_live_flow_smoke_2026-07-27.json.

Node-RED is started directly as "node <global node_modules>/node-red/red.js",
not through node-red.cmd. Python is the first of "python" and "python3" that
Get-Command finds. Called by the job smoke-training of .github/workflows/ci.yml.

.PARAMETER OutputPath
Where to write the smoke evidence JSON (default: analysis/out/phase1b_live_flow_smoke.json).

.EXAMPLE
.\scripts\Run-Phase1bFlowSmoke.ps1 -OutputPath analysis/out/phase1b_live_flow_smoke.json

.OUTPUTS
The JSON file at -OutputPath (written by analysis/phase1b_flow_smoke.py).
Every failure is a thrown error, so the script ends with a non-zero exit code;
in every case the Node-RED processes are stopped and the temporary folder is
removed (a folder that cannot be removed gives a warning).
#>
[CmdletBinding()]
param(
    [string]$OutputPath = "analysis/out/phase1b_live_flow_smoke.json"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Specs = @(
    @{ Profile = "labrel0";    Port = 1904; Flow = "simulator_flow_labrel0.json" },
    @{ Profile = "labrel4";    Port = 1905; Flow = "simulator_flow_labrel4.json" },
    @{ Profile = "labrel8";    Port = 1906; Flow = "simulator_flow_labrel8.json" },
    @{ Profile = "labrel16";   Port = 1907; Flow = "simulator_flow_labrel16.json" },
    @{ Profile = "labrel8s";   Port = 1908; Flow = "simulator_flow_labrel8s.json" },
    @{ Profile = "labband";    Port = 1912; Flow = "simulator_flow_labband.json" },
    @{ Profile = "lab4chain3"; Port = 1913; Flow = "simulator_flow_lab4chain3.json" }
)

function Test-PortOpen {
    param([int]$Port)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(300)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$node = Get-Command "node.exe" -ErrorAction SilentlyContinue
if (-not $node) { $node = Get-Command "node" -ErrorAction SilentlyContinue }
$npm = Get-Command "npm.cmd" -ErrorAction SilentlyContinue
if (-not $npm) { $npm = Get-Command "npm" -ErrorAction SilentlyContinue }
if (-not $node -or -not $npm) {
    throw "node/npm not found; install Node.js and Node-RED 4"
}
$globalNodeModules = (& $npm.Source root -g).ToString().Trim()
$nodeRedScript = Join-Path $globalNodeModules "node-red/red.js"
if (-not (Test-Path -LiteralPath $nodeRedScript)) {
    throw "Node-RED runtime not found at $nodeRedScript; install with: npm install -g --unsafe-perm node-red@4"
}

$python = Get-Command "python" -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command "python3" -ErrorAction SilentlyContinue
}
if (-not $python) {
    throw "Python 3 not found"
}

foreach ($spec in $Specs) {
    if (Test-PortOpen -Port $spec.Port) {
        throw "Port $($spec.Port) is already occupied; refusing to test an unidentified service"
    }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    "phase1b-flow-smoke-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$processes = @()

try {
    foreach ($spec in $Specs) {
        $flow = Join-Path $RepoRoot (Join-Path "simulator" $spec.Flow)
        $userDir = Join-Path $tempRoot $spec.Profile
        $stdout = Join-Path $tempRoot ($spec.Profile + ".stdout.log")
        $stderr = Join-Path $tempRoot ($spec.Profile + ".stderr.log")
        New-Item -ItemType Directory -Path $userDir | Out-Null

        $start = @{
            FilePath = $node.Source
            ArgumentList = "`"$nodeRedScript`" --userDir `"$userDir`" --port $($spec.Port) `"$flow`""
            RedirectStandardOutput = $stdout
            RedirectStandardError = $stderr
            PassThru = $true
        }
        if ($IsWindows -or ($null -eq $IsWindows -and $env:OS -eq "Windows_NT")) {
            $start.WindowStyle = "Hidden"
        }
        $processes += Start-Process @start
    }

    $version = (& $node.Source $nodeRedScript --version 2>&1 |
        Select-Object -First 1).ToString().Trim()
    Push-Location $RepoRoot
    try {
        & $python.Source analysis/phase1b_flow_smoke.py `
            --root $RepoRoot `
            --output $OutputPath `
            --node-red-version $version
        if ($LASTEXITCODE -ne 0) {
            throw "Phase-1b flow probe failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($process in $processes) {
        try {
            if (-not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction Stop
                $null = $process.WaitForExit(5000)
            }
        } catch {
            Write-Warning "Could not stop Node-RED process $($process.Id): $_"
        }
    }
    if (Test-Path -LiteralPath $tempRoot) {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
        $resolvedSystemTemp = [System.IO.Path]::GetFullPath(
            [System.IO.Path]::GetTempPath())
        $leaf = Split-Path -Leaf $resolvedTempRoot
        if (-not $resolvedTempRoot.StartsWith(
                $resolvedSystemTemp,
                [System.StringComparison]::OrdinalIgnoreCase) -or
            -not $leaf.StartsWith("phase1b-flow-smoke-")) {
            throw "Refusing to remove unexpected temporary path: $resolvedTempRoot"
        }
        $removed = $false
        for ($attempt = 1; $attempt -le 5 -and -not $removed; $attempt++) {
            try {
                Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force -ErrorAction Stop
                $removed = $true
            } catch {
                if ($attempt -lt 5) { Start-Sleep -Milliseconds 200 }
            }
        }
        if (-not $removed) {
            Write-Warning "Could not remove temporary smoke directory: $resolvedTempRoot"
        }
    }
}
