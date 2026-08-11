[CmdletBinding()]
param(
    [string]$Prompt,
    [string]$TaskPacketPath,
    [switch]$ValidateOnly,
    [switch]$DryRun,
    [ValidateSet("low", "high", "max")]
    [string]$ReasoningEffort = "max",
    [ValidateRange(1, 45)]
    [int]$MaxRunMinutes = 15,
    [switch]$PatchProposalOnly,
    [switch]$NoTotalTokenLimit,
    [ValidateRange(100000, 5000000)]
    [long]$MaxTotalTokens = 1000000
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process)

    if (-not $Process -or $Process.HasExited) {
        return
    }

    & (Join-Path $env:SystemRoot "System32\taskkill.exe") /PID $Process.Id /T /F 2>&1 | Out-Null
    $Process.WaitForExit(5000) | Out-Null
}

function Get-LatestRunState {
    param(
        [string]$JsonLogPath,
        [string]$CodexHome,
        [datetime]$StartedAt
    )

    $state = [ordered]@{
        Event = $null
        ThreadId = $null
        TotalTokens = $null
        InputTokens = $null
        CachedInputTokens = $null
        OutputTokens = $null
        ReasoningOutputTokens = $null
    }

    if (Test-Path -LiteralPath $JsonLogPath) {
        $recentLines = @(Get-Content -LiteralPath $JsonLogPath -Tail 100 -ErrorAction SilentlyContinue)
        for ($index = $recentLines.Count - 1; $index -ge 0; $index--) {
            try {
                $entry = $recentLines[$index] | ConvertFrom-Json
                $entryType = [string]$entry.type

                if (-not $state.ThreadId -and $entryType -eq "thread.started") {
                    $state.ThreadId = [string]$entry.thread_id
                }

                if (-not $state.Event) {
                    if ($entryType -like "item.*" -and $entry.item) {
                        $state.Event = "$entryType`:$($entry.item.type)"
                    }
                    elseif ($entryType) {
                        $state.Event = $entryType
                    }
                }

                if ($null -eq $state.TotalTokens -and $entryType -eq "turn.completed" -and $entry.usage) {
                    $usage = $entry.usage
                    $state.InputTokens = [long]$usage.input_tokens
                    $state.CachedInputTokens = [long]$usage.cached_input_tokens
                    $state.OutputTokens = [long]$usage.output_tokens
                    $state.ReasoningOutputTokens = if ($usage.PSObject.Properties["reasoning_output_tokens"]) {
                        [long]$usage.reasoning_output_tokens
                    }
                    else {
                        $null
                    }
                    $state.TotalTokens = if ($usage.PSObject.Properties["total_tokens"]) {
                        [long]$usage.total_tokens
                    }
                    else {
                        $state.InputTokens + $state.OutputTokens
                    }
                }
            }
            catch {
                continue
            }
        }
    }

    $sessionRoot = Join-Path $CodexHome "sessions"
    if (Test-Path -LiteralPath $sessionRoot) {
        $startedCutoff = $StartedAt.AddSeconds(-2)
        $sessionFiles = @(Get-ChildItem -LiteralPath $sessionRoot -Filter "rollout-*.jsonl" -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -ge $startedCutoff } |
            Sort-Object LastWriteTime -Descending)

        if ($state.ThreadId) {
            $matchingSession = @($sessionFiles | Where-Object Name -Like "*$($state.ThreadId)*") | Select-Object -First 1
            if ($matchingSession) {
                $sessionFiles = @($matchingSession)
            }
        }

        foreach ($sessionFile in $sessionFiles) {
            $sessionLines = @(Get-Content -LiteralPath $sessionFile.FullName -Tail 120 -ErrorAction SilentlyContinue)
            for ($index = $sessionLines.Count - 1; $index -ge 0; $index--) {
                try {
                    $entry = $sessionLines[$index] | ConvertFrom-Json
                    if ($entry.type -ne "event_msg" -or
                        $entry.payload.type -ne "token_count" -or
                        -not $entry.payload.info.total_token_usage) {
                        continue
                    }

                    $usage = $entry.payload.info.total_token_usage
                    $state.TotalTokens = [long]$usage.total_tokens
                    $state.InputTokens = [long]$usage.input_tokens
                    $state.CachedInputTokens = [long]$usage.cached_input_tokens
                    $state.OutputTokens = [long]$usage.output_tokens
                    $state.ReasoningOutputTokens = [long]$usage.reasoning_output_tokens
                    break
                }
                catch {
                    continue
                }
            }
            if ($null -ne $state.TotalTokens) {
                break
            }
        }
    }

    return [pscustomobject]$state
}

$projectRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$expectedRoot = "D:\gptuser\projects\ai-novel-reader-app2"
$gitRoot = (& git -C $projectRoot rev-parse --show-toplevel).Trim().Replace("/", "\")

if ($projectRoot -ne $expectedRoot -or $gitRoot -ne $expectedRoot) {
    throw "Repository isolation check failed. Expected '$expectedRoot', project='$projectRoot', git='$gitRoot'."
}

if ($Prompt -and $TaskPacketPath) {
    throw "Use either -Prompt or -TaskPacketPath, not both."
}

$dataRoot = "D:\gptuser"
$cacheRoot = Join-Path $dataRoot "cache"
$codexHome = Join-Path $cacheRoot "codex\ai-novel-reader-app2"
$tempRoot = Join-Path $cacheRoot "temp\ai-novel-reader-app2"
$gradleHome = Join-Path $cacheRoot "gradle"
$logRoot = Join-Path $dataRoot "logs\ai-novel-reader-app2\deepseek"
$sandboxMode = if ($PatchProposalOnly) { "read-only" } else { "workspace-write" }
$additionalWritableRoots = if ($PatchProposalOnly) { @() } else { @($codexHome, $tempRoot, $gradleHome) }
$totalTokenLimitLabel = if ($NoTotalTokenLimit) { "none" } else { [string]$MaxTotalTokens }

foreach ($path in @($codexHome, $tempRoot, $gradleHome, $logRoot)) {
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}

$env:CODEX_HOME = $codexHome
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
$env:GRADLE_USER_HOME = $gradleHome

$desktopCodexRoot = Join-Path $env:LOCALAPPDATA "OpenAI\Codex\bin"
$bundledCodex = Get-ChildItem -LiteralPath $desktopCodexRoot -Filter "codex.exe" -Recurse -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($bundledCodex) {
    $codexExecutable = $bundledCodex.FullName
}
else {
    $codexExecutable = (Get-Command codex -ErrorAction Stop).Source
}

$modelCatalog = Join-Path $projectRoot ".codex\models.json"
$keyReader = Join-Path $projectRoot "scripts\get-deepseek-key.ps1"
$keyPath = Join-Path $projectRoot ".codex\deepseek-key.local"
$childLauncher = Join-Path $projectRoot "scripts\invoke-deepseek-codex-child.ps1"
$authArgs = "['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File','$keyReader']"

$configOverrides = @(
    "model='deepseek-v4-flash'",
    "model_provider='deepseek-app2'",
    "model_reasoning_effort='$ReasoningEffort'",
    "model_catalog_json='$modelCatalog'",
    "model_providers.deepseek-app2.name='DeepSeek V4 Flash (app2 only)'",
    "model_providers.deepseek-app2.base_url='https://api.deepseek.com'",
    "model_providers.deepseek-app2.wire_api='responses'",
    "model_providers.deepseek-app2.requires_openai_auth=false",
    "model_providers.deepseek-app2.supports_websockets=false",
    "model_providers.deepseek-app2.auth.command='powershell.exe'",
    "model_providers.deepseek-app2.auth.args=$authArgs",
    "model_providers.deepseek-app2.auth.cwd='$projectRoot'",
    "model_providers.deepseek-app2.auth.timeout_ms=5000",
    "model_providers.deepseek-app2.auth.refresh_interval_ms=0",
    "windows.sandbox='unelevated'",
    "features.plugins=false",
    "features.remote_plugin=false",
    "features.plugin_sharing=false",
    "approval_policy='never'"
)

$commonArgs = @("-C", $projectRoot)
foreach ($override in $configOverrides) {
    $commonArgs += @("-c", $override)
}

if ($ValidateOnly) {
    $rawCatalog = & $codexExecutable @commonArgs debug models
    if ($LASTEXITCODE -ne 0) {
        throw "Codex rejected the isolated DeepSeek configuration."
    }

    $catalog = $rawCatalog | ConvertFrom-Json
    $deepSeekModel = $catalog.models | Where-Object slug -eq "deepseek-v4-flash"
    if (-not $deepSeekModel) {
        throw "The isolated DeepSeek model is missing from the effective model catalog."
    }

    $execHelp = (& $codexExecutable exec --help) -join "`n"
    foreach ($requiredOption in @("workspace-write", "--add-dir", "--json")) {
        if ($execHelp -notmatch [regex]::Escape($requiredOption)) {
            throw "The bundled Codex CLI does not support required option '$requiredOption'."
        }
    }

    if (-not (Test-Path -LiteralPath $childLauncher)) {
        throw "The bounded DeepSeek child launcher is missing."
    }

    $sandboxProbePath = Join-Path $tempRoot "validate-windows-sandbox-probe.txt"
    $sandboxProbeCommand = @"
`$probe = '$sandboxProbePath'
`$expected = 'ZHIJUAN_WINDOWS_SANDBOX_OK'
try {
    Set-Content -LiteralPath `$probe -Value `$expected -NoNewline -Encoding ascii
    `$actual = Get-Content -LiteralPath `$probe -Raw -Encoding ascii
    if (`$actual -ne `$expected) { exit 11 }
    Remove-Item -LiteralPath `$probe -Force
    if (Test-Path -LiteralPath `$probe) { exit 12 }
    exit 0
}
finally {
    Remove-Item -LiteralPath `$probe -Force -ErrorAction SilentlyContinue
}
"@
    & $codexExecutable sandbox -P ":workspace" -C $tempRoot powershell.exe -NoProfile -NonInteractive -Command $sandboxProbeCommand
    if ($LASTEXITCODE -ne 0 -or (Test-Path -LiteralPath $sandboxProbePath)) {
        throw "The Windows restricted-token sandbox write/read/delete probe failed."
    }

    Write-Host "Isolation valid: $($deepSeekModel.display_name), input=$($deepSeekModel.input_modalities -join ','), sandbox=workspace-write."
    Write-Host "Windows restricted-token sandbox probe valid."
    Write-Host "Limits valid: timeout=${MaxRunMinutes}m, total-token-cap=$totalTokenLimitLabel, reasoning=$ReasoningEffort."
    Write-Host "Writable root: $projectRoot. Additional: $($additionalWritableRoots -join '; '). Logs: $logRoot."
    exit 0
}

$taskPrompt = $Prompt
if ($TaskPacketPath) {
    $resolvedTaskPacket = (Resolve-Path -LiteralPath $TaskPacketPath).Path
    if (-not $resolvedTaskPacket.StartsWith($projectRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "The task packet must be inside '$projectRoot'."
    }
    $taskPrompt = Get-Content -LiteralPath $resolvedTaskPacket -Raw -Encoding UTF8
}

if ([string]::IsNullOrWhiteSpace($taskPrompt)) {
    throw "A bounded text task is required. Use -Prompt or -TaskPacketPath."
}

if ($taskPrompt.Length -gt 60000) {
    throw "The task packet is too large ($($taskPrompt.Length) characters). Maximum: 60000."
}

$resourceGuard = @"

## Mandatory resource guard

- Work only inside D:\gptuser\projects\ai-novel-reader-app2. Do not access another project copy.
- Treat this packet as the complete scope. Read only files named here or directly required by a named code reference; do not recursively scan unrelated documentation or history.
- Do not reread the same large file, rerun a completed command, or retry a sandbox failure repeatedly.
- Do not use network tools, inspect secrets, invoke the Zhijuan App's real generation APIs, or change formal task-completion status.
- If a required write or build is blocked once, stop and report the exact blocker instead of spending more tokens proving it repeatedly.
- Keep the final handoff concise and follow the repository's required handoff headings.
"@
$patchProposalGuard = if ($PatchProposalOnly) {
    @"

- This is a read-only patch-proposal run. Do not invoke apply_patch or edit any file.
- Return a complete, minimal apply_patch-compatible patch in the final handoff. Sol will apply it and run tests.
- Do not run Gradle or any command that needs a writable cache.
"@
}
else {
    ""
}
$guardedPrompt = $taskPrompt.TrimEnd() + $resourceGuard + $patchProposalGuard

$runId = "{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), ([guid]::NewGuid().ToString("N").Substring(0, 8))
$jsonLogPath = Join-Path $logRoot "$runId.events.jsonl"
$errorLogPath = Join-Path $logRoot "$runId.stderr.log"
$finalMessagePath = Join-Path $logRoot "$runId.final.md"
$summaryPath = Join-Path $logRoot "$runId.summary.json"
$invocationPath = Join-Path $tempRoot "$runId.arguments.json"
$promptPath = Join-Path $tempRoot "$runId.prompt.txt"

$execArgs = @($commonArgs) + @(
    "exec",
    "--ignore-user-config",
    "--sandbox", $sandboxMode
)
foreach ($writableRoot in $additionalWritableRoots) {
    $execArgs += @("--add-dir", $writableRoot)
}
$execArgs += @(
    "--json",
    "--color", "never",
    "--output-last-message", $finalMessagePath,
    "-"
)

if ($DryRun) {
    Write-Host "Dry run only; DeepSeek was not called."
    Write-Host "Repository: $projectRoot"
    Write-Host "Sandbox: $sandboxMode"
    Write-Host "Windows sandbox: unelevated (restricted token)"
    Write-Host "Read-only patch proposal: $([bool]$PatchProposalOnly)"
    Write-Host "Additional writable roots: $($additionalWritableRoots -join '; ')"
    Write-Host "CODEX_HOME: $codexHome"
    Write-Host "Logs: $logRoot"
    Write-Host "Limits: timeout=${MaxRunMinutes}m, total-token-cap=$totalTokenLimitLabel, reasoning=$ReasoningEffort"
    Write-Host "Prompt characters after guard: $($guardedPrompt.Length)"
    exit 0
}

if (-not (Test-Path -LiteralPath $keyPath)) {
    throw "The DeepSeek API Key is not configured. Run scripts\set-deepseek-key.ps1 first."
}

$startedAt = Get-Date
$deadline = $startedAt.AddMinutes($MaxRunMinutes)
$childProcess = $null
$runLockStream = $null
$runLockPath = Join-Path $codexHome "deepseek-run.lock"
$stopReason = $null
$lastReportedEvent = $null
$lastHeartbeat = $startedAt.AddSeconds(-30)
$latestState = [pscustomobject]@{ TotalTokens = $null }

try {
    try {
        $runLockStream = [System.IO.File]::Open(
            $runLockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    }
    catch [System.IO.IOException] {
        throw "Another bounded DeepSeek run is already active for app开发2."
    }

    [System.IO.File]::WriteAllText($promptPath, $guardedPrompt, (New-Object System.Text.UTF8Encoding($false)))
    @($execArgs) | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $invocationPath -Encoding UTF8

    $env:ZHIJUAN_DEEPSEEK_CODEX = $codexExecutable
    $env:ZHIJUAN_DEEPSEEK_ARGUMENTS = $invocationPath
    $env:ZHIJUAN_DEEPSEEK_PROMPT = $promptPath

    $childStartParameters = @{
        FilePath = "powershell.exe"
        ArgumentList = @("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", $childLauncher)
        RedirectStandardOutput = $jsonLogPath
        RedirectStandardError = $errorLogPath
        WindowStyle = "Hidden"
        PassThru = $true
    }
    $childProcess = Start-Process @childStartParameters
    # PowerShell 5.1 can lose ExitCode for a very short-lived process unless its
    # native handle is opened before the process exits.
    $null = $childProcess.Handle

    Write-Host "DeepSeek started with bounded access. Run: $runId"
    Write-Host "Live log: $jsonLogPath"

    while (-not $childProcess.HasExited) {
        Start-Sleep -Seconds 2
        $childProcess.Refresh()
        $latestState = Get-LatestRunState -JsonLogPath $jsonLogPath -CodexHome $codexHome -StartedAt $startedAt

        if ($latestState.Event -and $latestState.Event -ne $lastReportedEvent) {
            if ($null -ne $latestState.TotalTokens) {
                Write-Host "Progress: $($latestState.Event), total tokens=$($latestState.TotalTokens)."
            }
            else {
                Write-Host "Progress: $($latestState.Event)."
            }
            $lastReportedEvent = $latestState.Event
        }

        if (-not $NoTotalTokenLimit -and $null -ne $latestState.TotalTokens -and $latestState.TotalTokens -ge $MaxTotalTokens) {
            $stopReason = "token_limit"
            Write-Warning "Stopping DeepSeek: total token cap $MaxTotalTokens reached."
            Stop-ProcessTree -Process $childProcess
            break
        }

        if ((Get-Date) -ge $deadline) {
            $stopReason = "timeout"
            Write-Warning "Stopping DeepSeek: ${MaxRunMinutes}-minute timeout reached."
            Stop-ProcessTree -Process $childProcess
            break
        }

        if (((Get-Date) - $lastHeartbeat).TotalSeconds -ge 15) {
            $elapsed = [math]::Round(((Get-Date) - $startedAt).TotalMinutes, 1)
            $logBytes = if (Test-Path -LiteralPath $jsonLogPath) { (Get-Item -LiteralPath $jsonLogPath).Length } else { 0 }
            Write-Host "Heartbeat: ${elapsed}m elapsed, event-log=$logBytes bytes."
            $lastHeartbeat = Get-Date
        }
    }

    $childProcess.WaitForExit()
    $childProcess.Refresh()
    $latestState = Get-LatestRunState -JsonLogPath $jsonLogPath -CodexHome $codexHome -StartedAt $startedAt
    $childExitCode = $childProcess.ExitCode
    $launcherExitCode = if ($stopReason -eq "timeout") {
        124
    }
    elseif ($stopReason -eq "token_limit") {
        125
    }
    else {
        $childExitCode
    }

    $summary = [ordered]@{
        run_id = $runId
        repository = $projectRoot
        model = "deepseek-v4-flash"
        reasoning_effort = $ReasoningEffort
        sandbox = $sandboxMode
        windows_sandbox = "unelevated"
        patch_proposal_only = [bool]$PatchProposalOnly
        additional_writable_roots = $additionalWritableRoots
        started_at = $startedAt.ToString("o")
        ended_at = (Get-Date).ToString("o")
        max_run_minutes = $MaxRunMinutes
        max_total_tokens = if ($NoTotalTokenLimit) { $null } else { $MaxTotalTokens }
        stop_reason = $stopReason
        exit_code = $launcherExitCode
        child_exit_code = $childExitCode
        usage = [ordered]@{
            total_tokens = $latestState.TotalTokens
            input_tokens = $latestState.InputTokens
            cached_input_tokens = $latestState.CachedInputTokens
            output_tokens = $latestState.OutputTokens
            reasoning_output_tokens = $latestState.ReasoningOutputTokens
        }
        event_log = $jsonLogPath
        error_log = $errorLogPath
        final_message = $finalMessagePath
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

    if ($stopReason) {
        [Console]::Error.WriteLine("DeepSeek stopped by guard '$stopReason'. Summary: $summaryPath")
        exit $launcherExitCode
    }

    if ($childExitCode -ne 0) {
        [Console]::Error.WriteLine("DeepSeek exited with code $childExitCode. Summary: $summaryPath")
        exit $launcherExitCode
    }

    Write-Host "DeepSeek completed. Summary: $summaryPath"
    if ($null -ne $latestState.TotalTokens) {
        Write-Host "Usage: total=$($latestState.TotalTokens), cached-input=$($latestState.CachedInputTokens), output=$($latestState.OutputTokens)."
    }
    exit 0
}
finally {
    if ($childProcess -and -not $childProcess.HasExited) {
        Stop-ProcessTree -Process $childProcess
    }
    if ($runLockStream) {
        $runLockStream.Dispose()
    }
    Remove-Item -LiteralPath $invocationPath, $promptPath -Force -ErrorAction SilentlyContinue
    Remove-Item Env:ZHIJUAN_DEEPSEEK_CODEX, Env:ZHIJUAN_DEEPSEEK_ARGUMENTS, Env:ZHIJUAN_DEEPSEEK_PROMPT -ErrorAction SilentlyContinue
}
