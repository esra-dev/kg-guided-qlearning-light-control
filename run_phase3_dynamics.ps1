<#
.SYNOPSIS
    Phase 3: runs the delay-learning and deadline-planning agent in the labs
    with a slow blind.

.DESCRIPTION
    No Q-table is trained in Phase 3. For each slow lab (lab2_slow on port
    1895, lab3_slow on port 1896; ports and flows from the phase3 block of
    config/run_config.json):
      1. Reads the blind delay that the flow file really applies (the line
         "var DELAY = <n>;" in its function nodes) and stops when it differs
         from phase3.blind_delay_ticks (12). Starts the flow on a free port
         and checks through /health that this flow answers
         (scripts/SimulatorControl.ps1).
      2. For each mode: deletes the cell's old outputs and runs
         "gradlew taskDynamics -Pprofile=<lab> -Pmode=<mode>" with the probe
         settings as -P values. The agent
         (illuminance_controller_agent_dynamics.asl) measures the delay of
         every device by switching it on from a fixed baseline and counting
         simulator ticks until a zone's light rank rises, writes the mean
         delays to dynamics_delays_<bool>_<lab>.csv and, as ws:responseDelay,
         to learned_dynamics_<bool>_<lab>.ttl (no code reads this file back),
         then plans the six deadline goals and writes
         timebounded_results_<bool>_<lab>.csv. ql_true plans with the measured
         delays, ql_false assumes that every device acts at once; neither mode
         uses knowledge from the graph (docs/ARCHITECTURE.md, section 7).
      3. Watches the JVM log (heartbeat every 60 s). Once the results CSV
         exists and the log has not grown for 60 s, the JVM tree is stopped
         and the cell counts as finished; after -WatchdogIdleSec seconds
         without log growth the JVM tree is stopped and the cell fails.
      4. Reads the results (goals met, summed tick_energy) and the delay
         table, checks that every learned blind delay lies within
         phase3.blind_delay_tolerance_ticks (1.0) of the true delay, and
         writes the receipt DYNAMICS_OK_<bool>_<lab>.json (status
         delay_check_failed when the check fails).
      5. Stops the flow (whole process tree) before the next lab.

    The script changes no .asl file; the probe settings reach the agent only
    as -P values (seconds.per.tick, probe.count, probe.settle.ms,
    probe.poll.ms, probe.max.wait.ticks).

.PARAMETER DynamicsProfiles
    Comma-separated slow labs to run. Empty (default) = all labs listed in
    config phase3.dynamics_profiles.

.PARAMETER Modes
    Comma-separated subset of {ql_true, ql_false}. Default = both. An empty
    value uses config phase3.dynamics_modes.

.PARAMETER Probes
    Delay samples per device (-Pprobe.count). 0 (default) = config
    phase3.probe.probes_per_actuator (8). Recorded in the receipt as
    probes_override (so 0 there means "the config value").

.PARAMETER Smoke
    Short pipeline check: uses config phase3.smoke_probes (3) samples per
    device unless -Probes is given.

.PARAMETER WatchdogIdleSec
    Seconds without growth of the cell's JVM log after which the JVM is
    stopped and the cell fails (default 600; phase3.yml passes 600).

.PARAMETER SimReadyTimeoutSec
    Seconds to wait for the simulator to answer (default 90). A lab whose
    simulator does not answer in time is skipped with an error line.

.PARAMETER Replica
    Replica number written into the receipt (default -1 = a local run
    without number; phase3.yml passes the replica of the job). It does not
    change the run itself.

.EXAMPLE
    .\run_phase3_dynamics.ps1 -Smoke -DynamicsProfiles lab2_slow -Modes ql_true
    Short check of one lab and one mode with 3 samples per device.

.EXAMPLE
    .\run_phase3_dynamics.ps1 -DynamicsProfiles lab3_slow -Modes ql_false -Probes 0 -Replica 4
    One cell as a job of .github/workflows/phase3.yml runs it.

.EXAMPLE
    .\run_phase3_dynamics.ps1
    Both slow labs, both modes.

.OUTPUTS
    Files, relative to the repository root:
      run_phase3_dynamics.log (appended to by every run)
      log/dynamics_<lab>_<mode>.log and .err.log (JVM output of each cell)
      dynamics_delays_<bool>_<lab>.csv, timebounded_results_<bool>_<lab>.csv,
      learned_dynamics_<bool>_<lab>.ttl (written by the agent)
      DYNAMICS_OK_<bool>_<lab>.json (receipt; fields in docs/EXPERIMENTS.md,
      section 6)
    <bool> is true for ql_true and false for ql_false. A summary table is
    printed at the end.

    Exit code: 0 when every cell that ran produced its results and a receipt
    with a passed delay check; 1 when node-red, the config file or its
    phase3 block is missing, when a flow's delay differs from the config,
    or when any cell has no results CSV, no receipt or a failed delay check.
    A lab that is skipped (incomplete config entry, missing flow file,
    simulator not ready) does not by itself set exit code 1.

.NOTES
    Target shell: Windows PowerShell 5.1+ (also works under PowerShell 7+).
    Do not use PS7-only syntax here - no null-conditional (?.), null-coalescing
    (??), ternary (a ? b : c), or native command chaining (&&, ||).
    ASCII-only by design (no BOM dependency).
#>

param(
    [string]$DynamicsProfiles = "",
    [string]$Modes = "ql_true,ql_false",
    [int]$Probes = 0,
    [switch]$Smoke,
    [int]$WatchdogIdleSec = 600,
    [int]$SimReadyTimeoutSec = 90,
    # Replica index for the protocol-v2 DYNAMICS_OK gate manifest. -1 = local
    # unindexed run; CI passes the matrix replica.
    [int]$Replica = -1
)

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptRoot

# --- Logging ----------------------------------------------------------------
$LogFile   = Join-Path $ScriptRoot "run_phase3_dynamics.log"
$Utf8NoBom = New-Object System.Text.UTF8Encoding $false
$LogStream = New-Object System.IO.StreamWriter(
    [System.IO.FileStream]::new($LogFile,
        [System.IO.FileMode]::Append,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::ReadWrite), $Utf8NoBom)

function Write-Log {
    param([string]$msg)
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$ts] $msg"
    if ($LogStream) { $LogStream.WriteLine($line); $LogStream.Flush() }
    Write-Host $line
}
function Write-Header { param([string]$msg)
    $bar = "=" * 64
    Write-Log ""; Write-Log $bar; Write-Log "  $msg"; Write-Log $bar
}
function Write-OK   { param([string]$msg) Write-Log "  [OK]  $msg" }
function Write-Info { param([string]$msg) Write-Log "  [..] $msg" }
function Write-Warn { param([string]$msg) Write-Log "  [!!] $msg" }
function Write-Fail { param([string]$msg) Write-Log "  [XX] $msg" }

# --- Tool resolution --------------------------------------------------------
if ($env:OS -eq 'Windows_NT') {
    $GradleExe = Join-Path $ScriptRoot 'gradlew.bat'
} else {
    $GradleExe = Join-Path $ScriptRoot 'gradlew'
}

# On Windows, node-red.ps1 is NOT in PATHEXT so Start-Process would open it as
# text. Resolve node-red.cmd explicitly (runs via cmd.exe, stays alive for the
# child node process lifetime).
$_nrCmd = Get-Command "node-red.cmd" -ErrorAction SilentlyContinue
if (-not $_nrCmd) { $_nrCmd = Get-Command "node-red" -ErrorAction SilentlyContinue }
if (-not $_nrCmd) {
    Write-Fail "node-red not found in PATH. Install it with:  npm install -g node-red"
    if ($LogStream) { $LogStream.Close() }
    exit 1
}
$NodeRedCmd = $_nrCmd.Source

# Simulator identity helpers (Assert-SimulatorIdentity, ...).
. (Join-Path $ScriptRoot 'scripts\SimulatorControl.ps1')

# --- Config -----------------------------------------------------------------
$CfgPath = Join-Path $ScriptRoot "config\run_config.json"
if (-not (Test-Path $CfgPath)) { Write-Fail "Missing config: $CfgPath"; exit 1 }
$Cfg = Get-Content -Raw $CfgPath | ConvertFrom-Json
$P3  = $Cfg.phase3
if ($null -eq $P3) { Write-Fail "config/run_config.json has no 'phase3' block"; exit 1 }

# Probe cadence / budget (config-driven, with safe fallbacks).
$SecondsPerTick = 5.0
if ($P3.seconds_per_tick) { $SecondsPerTick = [double]$P3.seconds_per_tick }
# Configured blind delay. The delay that acts is the one typed into the slow
# flow; each profile reads it from the flow it starts and stops on a mismatch.
$GroundTruthTicks = 12
if ($P3.blind_delay_ticks) { $GroundTruthTicks = [int]$P3.blind_delay_ticks }
# Largest accepted distance (ticks) between a learned blind delay and the true one.
$DelayToleranceTicks = 1.0
if ($null -ne $P3.blind_delay_tolerance_ticks) { $DelayToleranceTicks = [double]$P3.blind_delay_tolerance_ticks }
$SmokeProbes = 3
if ($P3.smoke_probes) { $SmokeProbes = [int]$P3.smoke_probes }

$ProbesDefault = 8; $SettleMs = 400; $PollMs = 50; $MaxWaitTicks = 60
if ($P3.probe) {
    if ($P3.probe.probes_per_actuator) { $ProbesDefault = [int]$P3.probe.probes_per_actuator }
    if ($P3.probe.settle_ms)           { $SettleMs      = [int]$P3.probe.settle_ms }
    if ($P3.probe.poll_ms)             { $PollMs        = [int]$P3.probe.poll_ms }
    if ($P3.probe.max_wait_ticks)      { $MaxWaitTicks  = [int]$P3.probe.max_wait_ticks }
}

# Effective per-cell probe budget (-Pprobe.count). 0 => config default.
$EffProbes = $Probes
if ($EffProbes -le 0) { $EffProbes = $ProbesDefault }
if ($Smoke -and $Probes -le 0) { $EffProbes = $SmokeProbes }

# Profile / mode selection.
if ($DynamicsProfiles.Trim() -ne "") {
    $ProfileList = $DynamicsProfiles.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
} else {
    $ProfileList = @($P3.dynamics_profiles)
}
if ($Modes.Trim() -ne "") {
    $ModeList = $Modes.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
} else {
    $ModeList = @($P3.dynamics_modes)
}

# --- Simulator health check -------------------------------------------------
# True as soon as /health or /was/rl/status answers HTTP 200; false after
# $TimeoutSec. The flow identity is checked afterwards (Assert-SimulatorIdentity).
function Wait-Simulator {
    param([int]$Port, [int]$TimeoutSec = 90)
    $healthUrl = "http://127.0.0.1:$Port/health"
    $statusUrl = "http://127.0.0.1:$Port/was/rl/status"
    $deadline  = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        foreach ($url in @($healthUrl, $statusUrl)) {
            try {
                $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
                if ($r.StatusCode -eq 200) { return $true }
            } catch { }
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# Read new key marker lines from a gradle cell log and echo them.
$TailFilter = '\[Probe\]|\[Dynamics\]|\[Exploit\]|\[RuntimeOverride\]|first-effect delay|wrote |PHASE 3 DYNAMICS COMPLETE|BUILD (FAILED|SUCCESSFUL)'
function Invoke-TailLog {
    param([string]$LogPath, [ref]$PosRef)
    if (-not (Test-Path $LogPath)) { return }
    try {
        $fs = [System.IO.FileStream]::new($LogPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite)
        [void]$fs.Seek($PosRef.Value, [System.IO.SeekOrigin]::Begin)
        $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
        while (($tLine = $sr.ReadLine()) -ne $null) {
            if ($tLine -match $TailFilter) { Write-Info "  >> $($tLine.Trim())" }
        }
        $PosRef.Value = [long]$fs.Position
        $sr.Dispose(); $fs.Dispose()
    } catch { }
}

# --- State ------------------------------------------------------------------
$SimProcs = @{}   # port -> process (only flows WE launched, so cleanup is safe)
$SimUserDirs = @{} # port -> --userDir of the flow we launched there
$Summary  = @()   # result rows for the final table
$script:HadFatalError = $false

# Stop the simulator this runner launched on $Port: the whole process tree
# (on Windows node-red.cmd runs the real node.exe as a child), then wait until
# the port is free so a later start cannot meet a leftover simulator.
function Stop-LaunchedSimulator { param([int]$Port)
    if (-not $SimProcs.ContainsKey($Port)) { return }
    try {
        $res = Stop-SimulatorTree -Process $SimProcs[$Port] -Port $Port -UserDir $SimUserDirs[$Port]
        Write-OK ("Stopped simulator on port {0} (process tree: {1}; by userDir: {2})" -f $Port,
            (@($res.Stopped) -join ','), (@($res.StoppedByUserDir) -join ','))
        if (-not $res.PortFree) { Write-Warn "Port $Port is still in use after stopping the simulator: $(Get-PortUsage -Port $Port)" }
    } catch { Write-Warn "Could not stop the simulator on port $Port : $_" }
    $SimProcs.Remove($Port)
    $SimUserDirs.Remove($Port)
}

# Helper: map mode -> the boolean string used in artefact filenames.
function Mode-Bool { param([string]$mode)
    if ($mode -eq 'ql_true') { return 'true' } else { return 'false' }
}

# Helper: look up a value on a PSCustomObject map by dynamic key (or $null).
function Map-Get { param($map, [string]$key)
    if ($null -eq $map) { return $null }
    $prop = $map.PSObject.Properties[$key]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

try {
    Write-Header "KG-QL Phase 3 - Response-Delay Learning / Time-Bounded Exploitation  |  $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
    Write-OK   "node-red : $NodeRedCmd"
    Write-OK   "gradle   : $GradleExe"
    Write-OK   "profiles : $($ProfileList -join ', ')"
    Write-OK   "modes    : $($ModeList -join ', ')"
    Write-OK   "probes   : $EffProbes per actuator  (seconds/tick=$SecondsPerTick, ground-truth blind=$GroundTruthTicks ticks)"

    $cellLogDir = Join-Path $ScriptRoot "log"
    New-Item -ItemType Directory -Force -Path $cellLogDir | Out-Null

    foreach ($profile in $ProfileList) {
        $port = Map-Get $P3.simulator_port_map $profile
        $flow = Map-Get $P3.simulator_flow_map $profile

        if ($null -eq $port -or $null -eq $flow) {
            Write-Warn "Skipping '$profile' - incomplete phase3 config (port/flow)."
            continue
        }
        $port = [int]$port

        Write-Header "Profile $profile  (port=$port  flow=$flow)"

        # -- Start the SLOW Node-RED flow. The port must be free: a simulator
        #    this runner did not start is never reused (it may be another lab). --
        $flowAbs = Join-Path $ScriptRoot "simulator\$flow"
        $userDir = Join-Path $ScriptRoot ".node-red-$profile"
        $weLaunched = $false
        $simReported = ""
        $flowDelayTicks = -1

        if (-not (Test-Path $flowAbs)) {
            Write-Fail "Flow file missing - skipping $profile : $flowAbs"
            continue
        } else {
            # True delay = the one in the flow file about to be started.
            $flowDelayTicks = Get-FlowBlindDelayTicks -FlowPath $flowAbs
            if ($flowDelayTicks -ne $GroundTruthTicks) {
                throw "Blind delay mismatch for ${profile}: $flow applies DELAY = $flowDelayTicks ticks, but config phase3.blind_delay_ticks = $GroundTruthTicks"
            }
            Write-OK "True blind delay $flowDelayTicks ticks (read from $flow, equals config)"
            Assert-SimulatorPortFree -Port $port -Purpose "the $profile simulator"
            New-Item -ItemType Directory -Force -Path $userDir | Out-Null
            $spArgs = @{
                FilePath     = $NodeRedCmd
                ArgumentList = "--userDir `"$userDir`" --port $port `"$flowAbs`""
                PassThru     = $true
            }
            if ($IsWindows -or ($null -eq $IsWindows -and $env:OS -eq 'Windows_NT')) {
                $spArgs.WindowStyle = 'Hidden'
            }
            $proc = Start-Process @spArgs
            $SimProcs[$port] = $proc
            $SimUserDirs[$port] = $userDir
            $weLaunched = $true
            Write-Info "Launched slow flow  port=$port  PID=$($proc.Id)"
            if (-not (Wait-Simulator -Port $port -TimeoutSec $SimReadyTimeoutSec)) {
                Write-Fail "Simulator $profile did not become ready within $SimReadyTimeoutSec s on port $port - skipping."
                continue
            }
            Write-OK "Simulator $profile ready on port $port"
            # Identity: the flow answering on the port must be the one started.
            $simReported = Assert-SimulatorIdentity -Port $port -FlowFile $flow
            Write-OK "Simulator on port $port reports profile '$simReported'"
        }

        foreach ($mode in $ModeList) {
            $bool = Mode-Bool $mode
            Write-Header "Cell  $profile / $mode  (planner uses learned delays=$bool)"

            # Output artefacts for this cell. The results CSV is the completion
            # marker (written last, immediately before .stopMAS).
            $ttlFile     = Join-Path $ScriptRoot ("learned_dynamics_{0}_{1}.ttl"    -f $bool, $profile)
            $delayFile   = Join-Path $ScriptRoot ("dynamics_delays_{0}_{1}.csv"     -f $bool, $profile)
            $resultsFile = Join-Path $ScriptRoot ("timebounded_results_{0}_{1}.csv" -f $bool, $profile)
            $dynOkFile   = Join-Path $ScriptRoot ("DYNAMICS_OK_{0}_{1}.json"        -f $bool, $profile)
            foreach ($stale in @($ttlFile, $delayFile, $resultsFile, $dynOkFile)) {
                if (Test-Path $stale) { Remove-Item $stale -Force -ErrorAction SilentlyContinue }
            }

            $cellLog = Join-Path $cellLogDir "dynamics_${profile}_${mode}.log"
            $cellErr = Join-Path $cellLogDir "dynamics_${profile}_${mode}.err.log"
            if (Test-Path $cellLog) { Remove-Item $cellLog -Force -ErrorAction SilentlyContinue }
            if (Test-Path $cellErr) { Remove-Item $cellErr -Force -ErrorAction SilentlyContinue }
            Write-Info "Gradle output -> $cellLog"

            $gArgs = @("taskDynamics", "-Pprofile=$profile", "-Pmode=$mode",
                       "-Pseconds.per.tick=$SecondsPerTick",
                       "-Pprobe.count=$EffProbes",
                       "-Pprobe.settle.ms=$SettleMs",
                       "-Pprobe.poll.ms=$PollMs",
                       "-Pprobe.max.wait.ticks=$MaxWaitTicks",
                       "--console=plain")

            $gpArgs = @{
                FilePath               = $GradleExe
                ArgumentList           = $gArgs
                WorkingDirectory       = $ScriptRoot
                RedirectStandardOutput = $cellLog
                RedirectStandardError  = $cellErr
                PassThru               = $true
            }
            if ($IsWindows -or ($null -eq $IsWindows -and $env:OS -eq 'Windows_NT')) {
                $gpArgs.WindowStyle = 'Hidden'
            }
            $cellStart = Get-Date
            $gProc = Start-Process @gpArgs

            # -- Watchdog: results CSV appearing == logically done (the agent
            #    writes it immediately before .stopMAS). JaCaMo can hang in its
            #    post-run event loop, so once the CSV exists and the JVM has been
            #    idle for a grace window we terminate it and continue. --
            $tailPos             = 0L
            $heartbeatEverySec   = 60
            $postCompleteIdleSec = 60
            $lastHeartbeat = Get-Date
            $lastLogGrowth = Get-Date
            $lastLogLength = 0

            while (-not $gProc.HasExited) {
                Start-Sleep -Seconds 5
                Invoke-TailLog -LogPath $cellLog -PosRef ([ref]$tailPos)
                $now = Get-Date

                $logLength = 0
                if (Test-Path $cellLog) {
                    $gi = Get-Item -LiteralPath $cellLog -ErrorAction SilentlyContinue
                    if ($gi) { $logLength = $gi.Length }
                }
                if ($logLength -gt $lastLogLength) { $lastLogGrowth = $now; $lastLogLength = $logLength }

                if ((New-TimeSpan -Start $lastHeartbeat -End $now).TotalSeconds -ge $heartbeatEverySec) {
                    $elapsedMin = [int](New-TimeSpan -Start $cellStart -End $now).TotalMinutes
                    $idleSec    = [int](New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                    Write-Info "[Heartbeat] $profile/$mode elapsed=${elapsedMin}m idle=${idleSec}s pid=$($gProc.Id)"
                    $lastHeartbeat = $now
                }

                # Completion: results CSV written and JVM idle since.
                if (Test-Path $resultsFile) {
                    $idleNow = (New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                    if ($idleNow -ge $postCompleteIdleSec) {
                        Write-Warn "Results CSV present and JVM idle ${postCompleteIdleSec}s - terminating JaCaMo and continuing."
                        try { if (-not $gProc.HasExited) { $null = Stop-ProcessTree -RootId $gProc.Id } } catch { }
                        break
                    }
                }

                $idleNow = (New-TimeSpan -Start $lastLogGrowth -End $now).TotalSeconds
                if ($idleNow -ge $WatchdogIdleSec) {
                    try { if (-not $gProc.HasExited) { $null = Stop-ProcessTree -RootId $gProc.Id } } catch { }
                    Write-Fail "Watchdog timeout for $profile/${mode}: no log growth for $WatchdogIdleSec s (see $cellLog)"
                    break
                }
            }

            try { $gProc.Refresh() } catch { }
            try { [void]$gProc.WaitForExit(60000) } catch { }
            Invoke-TailLog -LogPath $cellLog -PosRef ([ref]$tailPos)

            # Merge stderr tail into the cell log for post-mortem.
            if ((Test-Path $cellErr) -and (Get-Item $cellErr -ErrorAction SilentlyContinue).Length -gt 0) {
                "`n--- STDERR ---`n" | Out-File -FilePath $cellLog -Encoding utf8 -Append
                Get-Content $cellErr -ErrorAction SilentlyContinue | Out-File -FilePath $cellLog -Encoding utf8 -Append
            }

            # -- Parse the time-bounded results + delay table. The summary column
            #    BlindTicks shows the largest learned delay over ALL devices of
            #    the delay table (in these labs the blind is the slowest one);
            #    the receipt's check below looks at the blinds only. --
            $status     = "FAIL"
            $metStr     = ""
            $energyStr  = ""
            $blindStr   = ""
            if (Test-Path $resultsFile) {
                try {
                    $rows = @(Import-Csv $resultsFile)
                    if ($rows.Count -gt 0) {
                        $met   = ($rows | Where-Object { $_.met -eq '1' }).Count
                        $total = $rows.Count
                        $metStr = "$met/$total"
                        $energySum = 0.0
                        foreach ($r in $rows) {
                            $e = 0.0
                            # Protocol v2: tick_energy is the deterministic
                            # per-attempt delta; the wall-clock column is a
                            # labelled legacy diagnostic and is not summarised.
                            if ([double]::TryParse($r.tick_energy, [ref]$e)) { $energySum += $e }
                        }
                        $energyStr = "{0:N1}" -f $energySum
                        $status = "OK"
                    }
                } catch {
                    Write-Warn "Could not parse $resultsFile : $_"
                }
            }
            if (Test-Path $delayFile) {
                try {
                    $drows = @(Import-Csv $delayFile)
                    $maxTicks = -1.0
                    foreach ($d in $drows) {
                        $t = -1.0
                        if ([double]::TryParse($d.delay_ticks, [ref]$t)) {
                            if ($t -gt $maxTicks) { $maxTicks = $t }
                        }
                    }
                    if ($maxTicks -ge 0) {
                        $blindStr = ("{0:N1}/{1}" -f $maxTicks, $GroundTruthTicks)
                    }
                } catch {
                    Write-Warn "Could not parse $delayFile : $_"
                }
            }

            $elapsedMin = [int](New-TimeSpan -Start $cellStart -End (Get-Date)).TotalMinutes
            if ($status -eq "OK") {
                Write-OK "Done in ${elapsedMin}m  met=$metStr  tick_energy=$energyStr  blindTicks(learned/truth)=$blindStr"

                # -- Protocol-v2 gate manifest (DYNAMICS_OK.json) --
                try {
                    # Learned delay per device and the tolerance check of the
                    # blinds against the true delay read from the flow.
                    $learnedTicks = [ordered]@{}
                    if (Test-Path $delayFile) { $learnedTicks = Get-LearnedDelayTicks -DelayCsv $delayFile }
                    $delayCheck = Test-BlindDelayTolerance -Learned $learnedTicks -TrueTicks $flowDelayTicks -ToleranceTicks $DelayToleranceTicks
                    $cellStatus = "ok"
                    if ($delayCheck.check -ne 'pass') { $cellStatus = "delay_check_failed" }
                    $manifest = [ordered]@{
                        status            = $cellStatus
                        protocol_version  = "phase3-v2"
                        energy_meter      = "tick-v1"
                        profile           = $profile
                        mode              = $mode
                        replica           = $Replica
                        blind_delay_ticks = $flowDelayTicks
                        seconds_per_tick  = $SecondsPerTick
                        probes_override   = $Probes
                        simulator_flow    = $flow
                        simulator_profile_reported = $simReported
                        blind_delay_ticks_source = "simulator/$flow"
                        blind_delay_ticks_config = $GroundTruthTicks
                        learned_delay_ticks = $learnedTicks
                        blind_delay_tolerance_ticks = $DelayToleranceTicks
                        blind_delay_check = $delayCheck.check
                        blind_delay_max_abs_error_ticks = $delayCheck.max_abs_error_ticks
                    }
                    $manifest | ConvertTo-Json -Depth 4 | Out-File -FilePath $dynOkFile -Encoding utf8
                    Write-OK "DYNAMICS_OK manifest written: $([System.IO.Path]::GetFileName($dynOkFile))"
                    if ($delayCheck.check -eq 'pass') {
                        Write-OK "Blind delay check passed: $($delayCheck.reason)"
                    } else {
                        Write-Fail "Blind delay check FAILED for $profile/${mode}: $($delayCheck.reason)"
                        $script:HadFatalError = $true
                    }
                } catch {
                    Write-Fail "Could not write DYNAMICS_OK manifest for $profile/$mode : $_"
                    $script:HadFatalError = $true
                }
            } else {
                Write-Fail "No time-bounded results CSV produced for $profile/$mode (see $cellLog)"
                $script:HadFatalError = $true
            }

            $Summary += [pscustomobject]@{
                Profile    = $profile
                Mode       = $mode
                Met        = $metStr
                Energy     = $energyStr
                BlindTicks = $blindStr
                Status     = $status
                Minutes    = $elapsedMin
            }
        }

        # -- Stop the slow flow for this profile (only if we launched it) --
        if ($weLaunched) { Stop-LaunchedSimulator -Port $port }
    }

    # --- Summary ----------------------------------------------------------------
    Write-Header "Phase 3 summary"
    if ($Summary.Count -gt 0) {
        $tbl = $Summary | Format-Table Profile, Mode, Met, Energy, BlindTicks, Status, Minutes -AutoSize | Out-String
        foreach ($l in ($tbl -split "`r?`n")) { if ($l.Trim() -ne "") { Write-Log $l } }
        Write-Log ""
        Write-Log "  Met = time-bounded goals met / total (deadline compliance)."
        Write-Log "  BlindTicks = learned slowest-actuator delay / ground-truth blind_delay_ticks."
        Write-Log "  Expectation: ql_true meets MORE tight-deadline goals than ql_false."
    } else {
        Write-Warn "No cells ran."
    }

} catch {
    $script:HadFatalError = $true
    Write-Fail "Fatal error: $_"
    Write-Fail $_.ScriptStackTrace
} finally {
    Write-Header "Cleanup"
    foreach ($cleanupPort in @($SimProcs.Keys)) { Stop-LaunchedSimulator -Port $cleanupPort }
    if ($script:HadFatalError) {
        Write-Fail "Phase 3 run finished WITH errors - review $LogFile"
    } else {
        Write-OK "Phase 3 run finished cleanly."
    }
    if ($LogStream) { $LogStream.Flush(); $LogStream.Close() }
}

if ($script:HadFatalError) { exit 1 } else { exit 0 }
