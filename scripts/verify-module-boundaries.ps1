[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$expectedRoot = 'D:/gptuser/projects/ai-novel-reader-app3'
$actualRoot = (& git rev-parse --show-toplevel).Trim()
if ($actualRoot -cne $expectedRoot) { throw "WRONG_ROOT:$actualRoot" }

$expected = [ordered]@{
    ':core' = @()
    ':data' = @(':core')
    ':provider' = @(':core', ':data')
    ':feature:connection' = @(':core', ':data', ':provider')
    ':feature:creation' = @(':core', ':data')
    ':feature:generation' = @(':core', ':data', ':provider')
    ':feature:reader' = @(':core', ':data')
    ':feature:library' = @(':core', ':data')
    ':feature:template' = @(':core', ':data', ':feature:creation')
    ':app' = @(
        ':feature:connection',
        ':feature:creation',
        ':feature:generation',
        ':feature:reader',
        ':feature:library',
        ':feature:template'
    )
}

function Get-ModulePath([string]$module) {
    return $module.TrimStart(':').Replace(':', [IO.Path]::DirectorySeparatorChar)
}

function Get-ProductionProjectDependencies([string]$buildFile) {
    $text = Get-Content -LiteralPath $buildFile -Raw
    return [regex]::Matches(
        $text,
        '(?m)^\s*implementation\(project\("(?<module>:[^"]+)"\)\)'
    ) | ForEach-Object { $_.Groups['module'].Value }
}

$configured = Select-String -LiteralPath 'settings.gradle.kts' -Pattern 'include\("(?<module>:[^"]+)"\)' -AllMatches |
    ForEach-Object { $_.Matches } |
    ForEach-Object { $_.Groups['module'].Value }
$configuredSorted = @($configured | Sort-Object)
$expectedSorted = @($expected.Keys | Sort-Object)
if (Compare-Object $configuredSorted $expectedSorted) {
    throw "Configured module set differs from the explicit ten-module plan."
}

foreach ($module in $expected.Keys) {
    $path = Get-ModulePath $module
    $buildFile = Join-Path $path 'build.gradle.kts'
    if (-not (Test-Path -LiteralPath $buildFile)) { throw "Missing build file for $module" }
    $actualDependencies = @(Get-ProductionProjectDependencies $buildFile | Sort-Object)
    $expectedDependencies = @($expected[$module] | Sort-Object)
    if (Compare-Object $actualDependencies $expectedDependencies) {
        throw "$module production dependencies are $($actualDependencies -join ', '); expected $($expectedDependencies -join ', ')."
    }

    if ($module -like ':feature:*') {
        $productionRoot = Join-Path $path 'src/main/kotlin'
        $productionFiles = @(Get-ChildItem -LiteralPath $productionRoot -Recurse -File -Filter '*.kt' -ErrorAction SilentlyContinue)
        if ($productionFiles.Count -eq 0) { throw "$module is an empty feature module." }
    }
}

$foreignSourceSet = Get-ChildItem -Recurse -File -Filter 'build.gradle.kts' |
    Select-String -Pattern '(?:srcDir|srcDirs)\([^\r\n]*\.\.[^\r\n]*\)'
if ($foreignSourceSet) { throw 'A module sourceSet points outside its own directory.' }

$coreAndroidImports = Get-ChildItem 'core/src' -Recurse -File -Filter '*.kt' |
    Select-String -Pattern '^import (android|androidx)\.'
if ($coreAndroidImports) { throw ':core contains Android imports.' }

if (Test-Path -LiteralPath 'engine') { throw 'Legacy :engine directory still exists.' }

# Kahn topological check. Dependencies are edges from dependency to consumer.
$inDegree = @{}
$consumers = @{}
foreach ($module in $expected.Keys) {
    $inDegree[$module] = @($expected[$module]).Count
    $consumers[$module] = [Collections.Generic.List[string]]::new()
}
foreach ($module in $expected.Keys) {
    foreach ($dependency in $expected[$module]) { $consumers[$dependency].Add($module) }
}
$queue = [Collections.Generic.Queue[string]]::new()
foreach ($module in $expected.Keys) { if ($inDegree[$module] -eq 0) { $queue.Enqueue($module) } }
$visited = 0
while ($queue.Count -gt 0) {
    $module = $queue.Dequeue()
    $visited++
    foreach ($consumer in $consumers[$module]) {
        $inDegree[$consumer]--
        if ($inDegree[$consumer] -eq 0) { $queue.Enqueue($consumer) }
    }
}
if ($visited -ne $expected.Count) { throw 'Project dependency graph contains a cycle.' }

Write-Output "MODULE_BOUNDARY_CHECK_OK"
Write-Output "MODULE_COUNT=$($expected.Count)"
Write-Output "FEATURE_TO_FEATURE_EXCEPTION=:feature:template -> :feature:creation"
Write-Output "APP_PRODUCTION_DEPENDENCIES=FEATURES_ONLY"
Write-Output "DEPENDENCY_GRAPH=ACYCLIC"
