[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'app.zhijuan.reader.debug'
$probeReceiver = "$packageName/app.zhijuan.reader.m0.Task048ForegroundProbeReceiver"
$adb = 'D:\gptuser\tools\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$preferenceFile = 'shared_prefs/task048-foreground-probe.xml'
$timeoutKey = 'data_sync_fgs_timeout_duration'
$jobId = 'task048-probe-job'

if ($Serial -notmatch '^emulator-[0-9]+$') {
    throw 'TASK-048 timeout probe only permits an explicitly named Android emulator.'
}

$env:JAVA_HOME = 'D:\gptuser\tools\jdk'
$env:ANDROID_HOME = 'D:\gptuser\tools\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\gptuser\tools\android-sdk'
$env:ANDROID_USER_HOME = 'D:\gptuser\cache\android-user'
$env:ANDROID_AVD_HOME = 'D:\gptuser\cache\android-user\avd'
$env:ANDROID_EMULATOR_HOME = 'D:\gptuser\cache\android-user'
$env:GRADLE_USER_HOME = 'D:\gptuser\cache\gradle'
$env:TEMP = 'D:\gptuser\cache\temp'
$env:TMP = 'D:\gptuser\cache\temp'

function Invoke-Adb {
    $adbArguments = @($args)
    $output = & $adb -s $Serial @adbArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed on $Serial`: $($adbArguments -join ' ')`n$($output -join "`n")"
    }
    return @($output)
}

function Read-ProbeState {
    return ((Invoke-Adb exec-out run-as $packageName cat $preferenceFile) -join "`n")
}

function Send-ProbeBroadcast {
    param([string]$Action)
    Invoke-Adb shell am broadcast -n $probeReceiver -a $Action | Out-Null
}

function Get-GenerationServiceDump {
    return ((Invoke-Adb shell dumpsys activity services $packageName) -join "`n")
}

function Wait-ForProbe {
    param(
        [string]$Status,
        [string]$Reason,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        Send-ProbeBroadcast 'app.zhijuan.reader.debug.task048.QUERY'
        Start-Sleep -Milliseconds 250
        try {
            $xml = Read-ProbeState
            $statusFound = $xml -match [Regex]::Escape("<string name=`"status`">$Status</string>")
            $reasonFound = [string]::IsNullOrEmpty($Reason) -or
                $xml -match [Regex]::Escape("<string name=`"reason`">$Reason</string>")
            if ($statusFound -and $reasonFound) { return $xml }
        } catch {
            # The async debug probe may still be committing its first state.
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for TASK-048 probe status $Status / $Reason."
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb was not found at $adb"
}
$deviceState = (Invoke-Adb get-state | Select-Object -First 1).Trim()
if ($deviceState -ne 'device') {
    throw "The selected emulator is not online. Current state: $deviceState"
}
$apiLevel = [int]((Invoke-Adb shell getprop ro.build.version.sdk | Select-Object -First 1).Trim())
if ($apiLevel -ne 35) {
    throw "TASK-048 timeout probe requires API 35; $Serial reports API $apiLevel."
}

Push-Location $projectRoot
try {
    & '.\gradlew.bat' --no-daemon --offline --console=plain :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Debug APK build failed.' }
} finally {
    Pop-Location
}

Invoke-Adb install -r $apk | Out-Null
$previousTimeout = (Invoke-Adb shell device_config get activity_manager $timeoutKey | Select-Object -First 1).Trim()
$configurationRestored = $false

function Restore-TimeoutConfiguration {
    if ($script:configurationRestored) { return }
    if ([string]::IsNullOrWhiteSpace($script:previousTimeout) -or $script:previousTimeout -eq 'null') {
        Invoke-Adb shell device_config delete activity_manager $timeoutKey | Out-Null
    } else {
        Invoke-Adb shell device_config put activity_manager $timeoutKey $script:previousTimeout | Out-Null
    }
    $script:configurationRestored = $true
}

try {
    Invoke-Adb shell pm clear $packageName | Out-Null
    Send-ProbeBroadcast 'app.zhijuan.reader.debug.task048.SEED'
    $seedDeadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 250
        try {
            $seedXml = Read-ProbeState
            if ($seedXml -match '<string name="status">READY</string>') { break }
        } catch {
            # Seed is asynchronous.
        }
    } while ([DateTime]::UtcNow -lt $seedDeadline)
    if ($seedXml -notmatch '<string name="status">READY</string>') {
        throw 'TASK-048 debug fixture did not reach READY.'
    }

    Invoke-Adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS $packageName | Out-Null
    Invoke-Adb shell device_config put activity_manager $timeoutKey 3000 | Out-Null
    Invoke-Adb shell am start -W -n "$packageName/app.zhijuan.reader.MainActivity" | Out-Null
    Send-ProbeBroadcast 'app.zhijuan.reader.debug.task048.START'
    $startDeadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 100
        $startXml = Read-ProbeState
        if ($startXml -match '<string name="status">START_REQUESTED</string>') { break }
        if ($startXml -match '<string name="status">START_REJECTED</string>') {
            throw 'Android rejected the in-app foreground-service start request.'
        }
    } while ([DateTime]::UtcNow -lt $startDeadline)
    if ($startXml -notmatch '<string name="status">START_REQUESTED</string>') {
        throw 'The debug fixture did not observe an in-app foreground-service start request.'
    }
    $foregroundDeadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $serviceDump = Get-GenerationServiceDump
        if ($serviceDump -match 'GenerationForegroundService' -and
            $serviceDump -match 'isForeground=true') { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $foregroundDeadline)
    if ($serviceDump -notmatch 'GenerationForegroundService' -or
        $serviceDump -notmatch 'isForeground=true') {
        throw 'The production generation service did not enter foreground execution.'
    }

    Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
    $startedAt = [DateTime]::UtcNow
    $stopDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 100
        $serviceDump = Get-GenerationServiceDump
        if ($serviceDump -notmatch 'GenerationForegroundService') { break }
    } while ([DateTime]::UtcNow -lt $stopDeadline)
    if ($serviceDump -match 'GenerationForegroundService') {
        throw 'Production foreground generation service did not stop after the configured timeout.'
    }

    $resultXml = Wait-ForProbe -Status 'PAUSED' -Reason 'SYSTEM_FGS_TIMEOUT' -TimeoutSeconds 5
    $observedMillis = [int]([DateTime]::UtcNow - $startedAt).TotalMilliseconds

    Write-Output 'TASK048_FGS_TIMEOUT_PROBE_OK'
    Write-Output "SERIAL=$Serial"
    Write-Output "API_LEVEL=$apiLevel"
    Write-Output 'CONFIGURED_TIMEOUT_MS=3000'
    Write-Output "TIMEOUT_OBSERVED_MS=$observedMillis"
    Write-Output "PAUSED_FOUND=$($resultXml -match '<string name=\"status\">PAUSED</string>')"
    Write-Output "SYSTEM_REASON_FOUND=$($resultXml -match 'SYSTEM_FGS_TIMEOUT')"
    Write-Output 'SERVICE_STOPPED=True'
} finally {
    Restore-TimeoutConfiguration
    & $adb -s $Serial shell am compat reset FGS_INTRODUCE_TIME_LIMITS $packageName 2>$null | Out-Null
}
