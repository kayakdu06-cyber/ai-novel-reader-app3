param(
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = 'D:\gptuser\tools\jdk'
$env:ANDROID_HOME = 'D:\gptuser\tools\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\gptuser\tools\android-sdk'
$env:ANDROID_USER_HOME = 'D:\gptuser\cache\android-user'
$env:GRADLE_USER_HOME = 'D:\gptuser\cache\gradle'
$env:TEMP = 'D:\gptuser\cache\temp'
$env:TMP = 'D:\gptuser\cache\temp'

foreach ($directory in @(
    $env:ANDROID_USER_HOME,
    $env:GRADLE_USER_HOME,
    $env:TEMP,
    'D:\gptuser\cache\gradle-tmp',
    'D:\gptuser\cache\kotlin-tmp'
)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

$gradleArguments = @(
    '--no-daemon',
    '--console=plain',
    'test',
    ':app:assembleDebug',
    ':app:processReleaseManifest'
)

if ($Offline) {
    $gradleArguments = @('--offline') + $gradleArguments
}

Push-Location $projectRoot
try {
    & '.\gradlew.bat' @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle verification failed with exit code $LASTEXITCODE."
    }

    & (Join-Path $PSScriptRoot 'security-scan.ps1') -ProjectRoot $projectRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Security scan failed with exit code $LASTEXITCODE."
    }

    & (Join-Path $PSScriptRoot 'verify-backup-exclusions.ps1') -ProjectRoot $projectRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Backup exclusion verification failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
