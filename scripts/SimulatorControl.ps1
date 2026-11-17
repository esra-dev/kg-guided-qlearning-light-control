<#
.SYNOPSIS
    Shared simulator helpers for the three runners (run_full_project.ps1,
    run_phase2_adapt.ps1, run_phase3_dynamics.ps1). Dot-source this file.

.DESCRIPTION
    Simulator identity: every Node-RED flow answers GET /health with a field
    'profile' that names the flow itself (simulator_flow_<profile>.json ->
    '<profile>'). After starting a flow, a runner compares that answer with
    the flow file it started, so it never trains or measures against another
    lab that happens to answer on the same port.

    Process clean-up: on Windows the runners start node-red.cmd, which runs
    the real simulator as a child node.exe. Stopping only the started process
    leaves that child running and holding the port. Stop-SimulatorTree stops
    the started process and all of its descendants, and as a safety net any
    process whose command line carries the runner's unique --userDir path.
    Nothing else is stopped. Before starting a simulator, a runner calls
    Assert-SimulatorPortFree, which refuses to go on when anything already
    listens on the port, and names that process.

    Phase 3: Get-FlowBlindDelayTicks reads the blind delay a slow flow
    applies; Get-LearnedDelayTicks reads the agent's delay table;
    Test-BlindDelayTolerance compares the two (used by
    run_phase3_dynamics.ps1 for the DYNAMICS_OK receipt).

    The file has no parameters and starts nothing when it is dot-sourced; it
    only defines functions. The functions throw on errors (no exit codes).

.EXAMPLE
    . (Join-Path $ScriptRoot 'scripts\SimulatorControl.ps1')
    Assert-SimulatorPortFree -Port 1893 -Purpose 'the lab2 simulator'
    # ... start node-red with simulator_flow_lab2.json on port 1893 ...
    $reported = Assert-SimulatorIdentity -Port 1893 -FlowFile 'simulator_flow_lab2.json'
    $res = Stop-SimulatorTree -Process $proc -Port 1893 -UserDir $userDir

.OUTPUTS
    None from dot-sourcing. Assert-SimulatorIdentity returns the reported
    flow name; Stop-SimulatorTree returns Stopped, StoppedByUserDir and
    PortFree; Test-BlindDelayTolerance returns check, blind_delays,
    max_abs_error_ticks and reason.

.NOTES
    Target shell: Windows PowerShell 5.1+ and PowerShell 7+ (Windows and
    Linux). No PS7-only syntax. ASCII only.
#>

# Name a flow file reports in its /health answer: the part of the file name
# between 'simulator_flow_' and '.json'. Several run profiles may share one
# flow (labmon_infoonly runs simulator_flow_labmon.json), so identity is the
# flow, not the run profile.
function Get-FlowProfileName {
    param([Parameter(Mandatory = $true)][string]$FlowFile)
    $name = [System.IO.Path]::GetFileName($FlowFile)
    if ($name -notmatch '^simulator_flow_(.+)\.json$') {
        throw "Flow file name '$name' does not follow simulator_flow_<profile>.json"
    }
    return $Matches[1]
}

# Ask the simulator on $Port which flow it runs. Returns the 'profile' field of
# the /health answer, or throws when there is no answer or no such field.
function Get-SimulatorReportedProfile {
    param([Parameter(Mandatory = $true)][int]$Port, [int]$TimeoutSec = 5)
    $url = "http://127.0.0.1:$Port/health"
    try {
        $health = Invoke-RestMethod -Uri $url -TimeoutSec $TimeoutSec -ErrorAction Stop
    } catch {
        throw "Simulator identity check: no /health answer on port ${Port}: $($_.Exception.Message)"
    }
    $prop = $null
    if ($null -ne $health) { $prop = $health.PSObject.Properties['profile'] }
    if ($null -eq $prop -or [string]::IsNullOrWhiteSpace([string]$prop.Value)) {
        throw "Simulator identity check: the /health answer on port $Port has no 'profile' field (a flow without identity, or not one of this project's simulators)"
    }
    return [string]$prop.Value
}

# Stop unless the simulator on $Port reports the flow the runner started.
# Returns the reported name (recorded in the receipts).
function Assert-SimulatorIdentity {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$FlowFile
    )
    $expected = Get-FlowProfileName -FlowFile $FlowFile
    $reported = Get-SimulatorReportedProfile -Port $Port
    if ($reported -ne $expected) {
        throw "Wrong simulator on port ${Port}: it reports profile '$reported', but this runner started $([System.IO.Path]::GetFileName($FlowFile)) (profile '$expected'). Stop the other simulator and run again."
    }
    return $reported
}

# ---------------------------------------------------------------------------
# Ports and processes
# ---------------------------------------------------------------------------

function Test-SimHostIsWindows {
    if ($null -ne $IsWindows) { return [bool]$IsWindows }
    return ($env:OS -eq 'Windows_NT')
}

# True when something accepts TCP connections on 127.0.0.1:$Port (the address
# the runners and the agent use to talk to the simulator).
function Test-PortAnswering {
    param([Parameter(Mandatory = $true)][int]$Port, [int]$TimeoutMs = 1000)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        try { $client.EndConnect($async); return $true } catch { return $false }
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

# Process ids listening on $Port on any address (best effort; empty when the
# operating system tool is missing or the owner is not visible).
function Get-PortListenerIds {
    param([Parameter(Mandatory = $true)][int]$Port)
    $ids = @()
    if (Test-SimHostIsWindows) {
        try {
            $ids = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop |
                     ForEach-Object { [int]$_.OwningProcess })
        } catch { $ids = @() }
    } else {
        try {
            $lines = @(& ss -H -l -t -n -p "sport = :$Port" 2>$null)
            foreach ($line in $lines) {
                foreach ($m in [regex]::Matches([string]$line, 'pid=(\d+)')) { $ids += [int]$m.Groups[1].Value }
            }
        } catch { $ids = @() }
    }
    return @($ids | Where-Object { $_ -gt 0 } | Sort-Object -Unique)
}

# Full command line of a process, or '' when it cannot be read.
function Get-SimProcessCommandLine {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    try {
        if (Test-SimHostIsWindows) {
            $p = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction Stop
            if ($p -and $p.CommandLine) { return [string]$p.CommandLine }
            return ''
        }
        $raw = [System.IO.File]::ReadAllText("/proc/$ProcessId/cmdline")
        return ($raw -replace "`0", ' ').Trim()
    } catch {
        return ''
    }
}

# $null when $Port is free; otherwise a description of what holds it.
function Get-PortUsage {
    param([Parameter(Mandatory = $true)][int]$Port)
    $ids = @(Get-PortListenerIds -Port $Port)
    $answering = Test-PortAnswering -Port $Port
    if ($ids.Count -eq 0 -and -not $answering) { return $null }
    if ($ids.Count -eq 0) { return "an unidentified process (it accepts connections on 127.0.0.1:$Port)" }
    $parts = @()
    foreach ($id in $ids) {
        $name = '?'
        try { $name = (Get-Process -Id $id -ErrorAction Stop).ProcessName } catch { }
        $cmd = Get-SimProcessCommandLine -ProcessId $id
        if ($cmd.Length -gt 300) { $cmd = $cmd.Substring(0, 300) + '...' }
        $parts += "PID $id ($name): $cmd"
    }
    return ($parts -join '; ')
}

# Stop with a clear message when $Port is already in use. A runner never
# reuses a simulator it did not start: whatever answers there may be another
# lab, and its state is unknown.
function Assert-SimulatorPortFree {
    param([Parameter(Mandatory = $true)][int]$Port, [string]$Purpose = 'the simulator')
    $usage = Get-PortUsage -Port $Port
    if ($null -ne $usage) {
        throw "Port $Port is already in use, so $Purpose cannot be started there. Held by: $usage. Stop that process (or free the port) and run again; the runner does not reuse a simulator it did not start."
    }
}

# Parent/child table of all processes: objects with Id, ParentId, Created
# (Created is $null where the platform does not report it cheaply).
function Get-SimProcessTable {
    $rows = @()
    if (Test-SimHostIsWindows) {
        foreach ($p in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) {
            $rows += [pscustomobject]@{ Id = [int]$p.ProcessId; ParentId = [int]$p.ParentProcessId; Created = $p.CreationDate }
        }
    } else {
        foreach ($line in @(& ps -e -o 'pid=,ppid=' 2>$null)) {
            $f = ([string]$line).Trim() -split '\s+'
            if ($f.Count -ge 2) {
                $rows += [pscustomobject]@{ Id = [int]$f[0]; ParentId = [int]$f[1]; Created = $null }
            }
        }
    }
    return $rows
}

# Ids of a process and all its descendants, the root first. On Windows a
# child keeps the id of a parent that has exited, and ids are reused, so a
# process counts as a child only if it was created after its parent.
function Get-ProcessTreeIds {
    param([Parameter(Mandatory = $true)][int]$RootId)
    $table = @(Get-SimProcessTable)
    $byId = @{}
    foreach ($r in $table) { $byId[$r.Id] = $r }
    $result = New-Object System.Collections.Generic.List[int]
    $result.Add($RootId)
    $queue = New-Object System.Collections.Generic.Queue[int]
    $queue.Enqueue($RootId)
    while ($queue.Count -gt 0) {
        $parentId = $queue.Dequeue()
        $parent = $byId[$parentId]
        foreach ($r in $table) {
            if ($r.ParentId -ne $parentId -or $r.Id -eq $parentId -or $result.Contains($r.Id)) { continue }
            if ($parent -and $parent.Created -and $r.Created -and $r.Created -lt $parent.Created) { continue }
            $result.Add($r.Id)
            $queue.Enqueue($r.Id)
        }
    }
    return $result.ToArray()
}

# Stop a process the runner started together with all its descendants
# (deepest first, the started process last). Returns the ids that were
# running and got a stop request.
function Stop-ProcessTree {
    param([Parameter(Mandatory = $true)][int]$RootId)
    $ids = @(Get-ProcessTreeIds -RootId $RootId)
    [array]::Reverse($ids)
    $stopped = @()
    foreach ($id in $ids) {
        try {
            Stop-Process -Id $id -Force -ErrorAction Stop
            $stopped += $id
        } catch { }
    }
    return $stopped
}

# Stop the simulator a runner started on $Port: the started process and its
# descendants; then any process whose command line names the runner's own
# --userDir directory (unique per runner, profile and checkout), in case a
# child escaped the tree. Waits until the port is free. Returns an object with
# Stopped (ids), StoppedByUserDir (ids) and PortFree (bool).
function Stop-SimulatorTree {
    param(
        $Process,
        [Parameter(Mandatory = $true)][int]$Port,
        [string]$UserDir = '',
        [int]$WaitSec = 20
    )
    $stopped = @()
    # Walk the tree only while the started process still runs: its id then
    # surely belongs to it. Children of an already exited process are found
    # by the --userDir rule below.
    $rootRunning = $false
    if ($null -ne $Process) { try { $rootRunning = -not $Process.HasExited } catch { $rootRunning = $false } }
    if ($rootRunning) { $stopped = @(Stop-ProcessTree -RootId $Process.Id) }
    $byUserDir = @()
    if ($UserDir -ne '') {
        $pattern = '(^|[\s"''=])' + [regex]::Escape($UserDir) + '([\s"'']|$)'
        $candidates = @()
        if (Test-SimHostIsWindows) {
            $candidates = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
                            ForEach-Object { [pscustomobject]@{ Id = [int]$_.ProcessId; Cmd = [string]$_.CommandLine } })
        } else {
            foreach ($row in @(Get-SimProcessTable)) {
                $candidates += [pscustomobject]@{ Id = $row.Id; Cmd = (Get-SimProcessCommandLine -ProcessId $row.Id) }
            }
        }
        foreach ($c in $candidates) {
            if ($c.Id -eq $PID -or [string]::IsNullOrEmpty($c.Cmd)) { continue }
            if ($c.Cmd -match $pattern) {
                try { Stop-Process -Id $c.Id -Force -ErrorAction Stop; $byUserDir += $c.Id } catch { }
            }
        }
    }
    $deadline = (Get-Date).AddSeconds($WaitSec)
    $free = $false
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Get-PortUsage -Port $Port)) { $free = $true; break }
        Start-Sleep -Milliseconds 250
    }
    return [pscustomobject]@{ Stopped = $stopped; StoppedByUserDir = $byUserDir; PortFree = $free }
}

# ---------------------------------------------------------------------------
# Phase 3: the blind response delay of the slow flows
# ---------------------------------------------------------------------------

# The blind delay (in simulator ticks) that a slow flow actually applies: the
# value of the line 'var DELAY = <n>;' in its function nodes. Throws when the
# flow has no such line or two different values.
function Get-FlowBlindDelayTicks {
    param([Parameter(Mandatory = $true)][string]$FlowPath)
    $text = [System.IO.File]::ReadAllText($FlowPath, (New-Object System.Text.UTF8Encoding $false))
    $nodes = $text | ConvertFrom-Json
    $values = @()
    foreach ($node in @($nodes)) {
        if ($node.type -ne 'function' -or -not $node.func) { continue }
        foreach ($m in [regex]::Matches([string]$node.func, '(?m)^\s*var\s+DELAY\s*=\s*(\d+)\s*;')) {
            $values += [int]$m.Groups[1].Value
        }
    }
    $distinct = @($values | Sort-Object -Unique)
    if ($distinct.Count -eq 0) { throw "No 'var DELAY = <ticks>;' line in the function nodes of $FlowPath" }
    if ($distinct.Count -gt 1) { throw "Conflicting DELAY values in ${FlowPath}: $($distinct -join ', ')" }
    return [int]$distinct[0]
}

# Learned delay per device from a dynamics_delays_<bool>_<profile>.csv:
# ordered map action_label -> delay_ticks (double).
function Get-LearnedDelayTicks {
    param([Parameter(Mandatory = $true)][string]$DelayCsv)
    $learned = [ordered]@{}
    foreach ($row in @(Import-Csv -LiteralPath $DelayCsv)) {
        $ticks = 0.0
        if ([double]::TryParse([string]$row.delay_ticks, [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture, [ref]$ticks)) {
            $learned[[string]$row.action_label] = $ticks
        }
    }
    return $learned
}

# Check the learned blind delays against the true delay. Every device whose
# label names a blind must be within ToleranceTicks of TrueTicks, and there
# must be at least one. Returns an ordered map with check ('pass' or 'fail'),
# the blind delays, the largest absolute error and a reason.
function Test-BlindDelayTolerance {
    param(
        [Parameter(Mandatory = $true)]$Learned,
        [Parameter(Mandatory = $true)][double]$TrueTicks,
        [Parameter(Mandatory = $true)][double]$ToleranceTicks
    )
    $blinds = [ordered]@{}
    foreach ($key in @($Learned.Keys)) { if ($key -match 'Blind') { $blinds[$key] = [double]$Learned[$key] } }
    $maxErr = -1.0
    foreach ($v in $blinds.Values) { $e = [Math]::Abs($v - $TrueTicks); if ($e -gt $maxErr) { $maxErr = $e } }
    $check = 'pass'
    $reason = "all $($blinds.Count) blind delay(s) within $ToleranceTicks ticks of $TrueTicks"
    if ($blinds.Count -eq 0) {
        $check = 'fail'; $reason = 'no learned blind delay in the delay table'; $maxErr = $null
    } elseif ($maxErr -gt $ToleranceTicks) {
        $check = 'fail'; $reason = "a learned blind delay is $maxErr ticks from the true $TrueTicks (tolerance $ToleranceTicks)"
    }
    return [ordered]@{ check = $check; blind_delays = $blinds; max_abs_error_ticks = $maxErr; reason = $reason }
}
