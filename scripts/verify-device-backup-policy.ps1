[CmdletBinding()]
param(
    [string]$PackageName = 'app.zhijuan.reader.debug',
    [string]$AdbPath = 'D:\gptuser\tools\android-sdk\platform-tools\adb.exe',
    [string]$ApkPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'app\build\outputs\apk\debug\app-debug.apk'),
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "adb not found: $AdbPath"
}

$adbTarget = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $adbTarget = @('-s', $Serial)
}

$devices = & $AdbPath devices
if (($devices -join "`n") -notmatch "`tdevice") {
    throw 'No ready Android device or emulator is connected.'
}

$packagePath = & $AdbPath @adbTarget shell pm path $PackageName 2>&1
if ($LASTEXITCODE -ne 0 -or ($packagePath -join "`n") -notmatch '^package:') {
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        throw "Package is not installed and APK was not found: $PackageName / $ApkPath"
    }
    $installResult = & $AdbPath @adbTarget install -r $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0 -or ($installResult -join "`n") -notmatch 'Success') {
        throw "Unable to install APK for backup-policy verification.`n$($installResult -join "`n")"
    }
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $result = & $AdbPath @adbTarget shell bmgr backupnow $PackageName 2>&1
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$resultText = $result -join "`n"
if ($resultText -notmatch 'Backup is not allowed') {
    throw "Android Backup Manager did not reject the package as expected.`n$resultText"
}

Write-Output 'DEVICE_BACKUP_POLICY_OK'
Write-Output "PACKAGE=$PackageName"
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    Write-Output "DEVICE_SERIAL=$Serial"
}
Write-Output 'BACKUP_MANAGER_RESULT=Backup is not allowed'
