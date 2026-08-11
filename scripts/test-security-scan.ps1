[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$expectedRoot = 'D:/gptuser/projects/ai-novel-reader-app3'
$actualRoot = (& git -C $projectRoot rev-parse --show-toplevel).Trim()
if ($actualRoot -ne $expectedRoot) {
    throw "Unexpected project root: $actualRoot"
}

$allowedRoot = (Resolve-Path -LiteralPath 'D:\gptuser').Path.TrimEnd('\') + '\'
$fixtureRoot = Join-Path 'D:\gptuser\cache\temp' ("zhijuan-security-scan-test-" + [Guid]::NewGuid().ToString('N'))
$scanner = Join-Path $PSScriptRoot 'security-scan.ps1'
$powerShell = (Get-Process -Id $PID).Path
$utf8WithoutBom = [Text.UTF8Encoding]::new($false)

function Invoke-Scanner {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,
        [string]$Canary = '',
        [string[]]$ArtifactPaths = @(),
        [switch]$SkipArtifacts
    )

    $arguments = @('-NoProfile', '-NonInteractive', '-File', $scanner, '-ProjectRoot', $Root)
    if ($Canary) {
        $arguments += @('-Canary', $Canary)
    }
    if ($ArtifactPaths.Count -gt 0) {
        $arguments += '-ArtifactPaths'
        $arguments += $ArtifactPaths
    }
    if ($SkipArtifacts) {
        $arguments += '-SkipArtifacts'
    }
    $output = & $powerShell @arguments 2>&1
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = @($output)
    }
}

function Assert-ExitCode {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Expected,
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Actual,
        [Parameter(Mandatory = $true)]
        [string]$Case
    )

    if ($Actual.ExitCode -ne $Expected) {
        throw "$Case expected exit $Expected but received $($Actual.ExitCode). Scanner output: $($Actual.Output -join ' | ')"
    }
}

New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
try {
    $reports = New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'reports') -Force
    [IO.File]::WriteAllText(
        (Join-Path $reports.FullName '2026-08-04-80-task-060-production-fts-schema.md'),
        'A normal task report must not look like a provider credential.',
        $utf8WithoutBom
    )
    Assert-ExitCode 0 (Invoke-Scanner -Root $fixtureRoot -SkipArtifacts) 'task report false-positive regression'

    $reportCanary = 'ZHIJUAN_REPORT_SECRET_CANARY_20260805'
    $reportCanaryPath = Join-Path $reports.FullName 'canary.md'
    [IO.File]::WriteAllText($reportCanaryPath, $reportCanary, $utf8WithoutBom)
    Assert-ExitCode 2 (Invoke-Scanner -Root $fixtureRoot -Canary $reportCanary -SkipArtifacts) 'reports directory coverage'
    Remove-Item -LiteralPath $reportCanaryPath -Force

    $providerLikePath = Join-Path $reports.FullName 'provider-like.md'
    [IO.File]::WriteAllText($providerLikePath, ('sk-' + ('A' * 24)), $utf8WithoutBom)
    Assert-ExitCode 2 (Invoke-Scanner -Root $fixtureRoot -SkipArtifacts) 'provider credential pattern'
    Remove-Item -LiteralPath $providerLikePath -Force

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $artifactCanary = 'ZHIJUAN_APK_SECRET_CANARY_20260805'
    $artifactSource = Join-Path $fixtureRoot 'artifact-source'
    New-Item -ItemType Directory -Path $artifactSource -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $artifactSource 'payload.txt'), $artifactCanary, $utf8WithoutBom)
    $artifactPath = Join-Path $fixtureRoot 'fixture.apk'
    [IO.Compression.ZipFile]::CreateFromDirectory($artifactSource, $artifactPath)
    Remove-Item -LiteralPath $artifactSource -Recurse -Force
    Assert-ExitCode 2 (
        Invoke-Scanner -Root $fixtureRoot -Canary $artifactCanary -ArtifactPaths $artifactPath
    ) 'APK artifact coverage'

    Write-Output 'SECURITY_SCAN_TESTS_OK'
    Write-Output 'CASES=4'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $resolvedFixture = (Resolve-Path -LiteralPath $fixtureRoot).Path
        if (-not ($resolvedFixture.TrimEnd('\') + '\').StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove unexpected fixture path: $resolvedFixture"
        }
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}

exit 0
