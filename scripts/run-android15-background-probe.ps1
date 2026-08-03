[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'app.zhijuan.reader.debug'
$serviceComponent = "$packageName/app.zhijuan.reader.m0.M0DataSyncProbeService"
$activityComponent = "$packageName/app.zhijuan.reader.MainActivity"
$adb = 'D:\gptuser\tools\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$preferenceFile = 'shared_prefs/m0-recovery-probe.xml'
$timeoutKey = 'data_sync_fgs_timeout_duration'

$env:JAVA_HOME = 'D:\gptuser\tools\jdk'
$env:ANDROID_HOME = 'D:\gptuser\tools\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\gptuser\tools\android-sdk'
$env:ANDROID_USER_HOME = 'D:\gptuser\cache\android-user'
$env:ANDROID_AVD_HOME = 'D:\gptuser\cache\android-avd'
$env:ANDROID_EMULATOR_HOME = 'D:\gptuser\cache\android-user'
$env:GRADLE_USER_HOME = 'D:\gptuser\cache\gradle'
$env:TEMP = 'D:\gptuser\cache\temp'
$env:TMP = 'D:\gptuser\cache\temp'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & $adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return @($output)
}

function Read-ProbeState {
    $output = Invoke-Adb exec-out run-as $packageName cat $preferenceFile
    return ($output -join "`n")
}

function Wait-ForState {
    param(
        [string]$Expected,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $xml = Read-ProbeState
            $stateElement = "<string name=`"state`">$Expected</string>"
            if ($xml -match [Regex]::Escape($stateElement)) {
                return $xml
            }
        } catch {
            # The preferences file may not exist during the first few hundred milliseconds.
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for probe state $Expected."
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb was not found at $adb"
}
$deviceState = (Invoke-Adb get-state | Select-Object -First 1).Trim()
if ($deviceState -ne 'device') {
    throw "An online Android device is required. Current state: $deviceState"
}
$apiLevel = [int]((Invoke-Adb shell getprop ro.build.version.sdk | Select-Object -First 1).Trim())
if ($apiLevel -ne 35) {
    throw "This probe requires Android 15/API 35; connected API is $apiLevel."
}

Push-Location $projectRoot
try {
    & '.\gradlew.bat' --no-daemon --offline --console=plain :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed." }
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
    Invoke-Adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS $packageName | Out-Null
    Invoke-Adb shell device_config put activity_manager $timeoutKey 3000 | Out-Null
    Invoke-Adb shell pm clear $packageName | Out-Null

    $timeoutStartedAt = [DateTime]::UtcNow
    $startOutput = Invoke-Adb shell am start-foreground-service -n $serviceComponent
    if (($startOutput -join "`n") -match 'Error|Exception') {
        throw "Foreground service did not start: $($startOutput -join "`n")"
    }
    Wait-ForState -Expected 'RUNNING' -TimeoutSeconds 5 | Out-Null
    $timeoutXml = Wait-ForState -Expected 'PAUSED_BY_SYSTEM_TIMEOUT' -TimeoutSeconds 15
    $timeoutObservedMillis = [int]([DateTime]::UtcNow - $timeoutStartedAt).TotalMilliseconds

    Restore-TimeoutConfiguration
    Invoke-Adb shell pm clear $packageName | Out-Null
    Invoke-Adb shell am start-foreground-service -n $serviceComponent | Out-Null
    Wait-ForState -Expected 'RUNNING' -TimeoutSeconds 5 | Out-Null
    Invoke-Adb shell am force-stop $packageName | Out-Null
    Start-Sleep -Seconds 1
    $pidAfterStop = (& $adb shell pidof $packageName 2>$null) -join ''
    if (-not [string]::IsNullOrWhiteSpace($pidAfterStop)) {
        throw "Process remained alive after force-stop: $pidAfterStop"
    }
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', $activityComponent) | Out-Null
    $recoveryXml = Wait-ForState -Expected 'RECOVERY_REQUIRED' -TimeoutSeconds 5

    Write-Output 'ANDROID15_BACKGROUND_PROBE_OK'
    Write-Output "API_LEVEL=$apiLevel"
    Write-Output "CONFIGURED_TIMEOUT_MS=3000"
    Write-Output "TIMEOUT_OBSERVED_MS=$timeoutObservedMillis"
    Write-Output "TIMEOUT_STATE_FOUND=$($timeoutXml -match 'PAUSED_BY_SYSTEM_TIMEOUT')"
    Write-Output "PROCESS_GONE_AFTER_FORCE_STOP=True"
    Write-Output "RECOVERY_STATE_FOUND=$($recoveryXml -match 'RECOVERY_REQUIRED')"
} finally {
    Restore-TimeoutConfiguration
    & $adb shell am compat reset FGS_INTRODUCE_TIME_LIMITS $packageName 2>$null | Out-Null
}
