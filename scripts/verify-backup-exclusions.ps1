[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$allowedRoot = (Resolve-Path -LiteralPath 'D:\gptuser').Path.TrimEnd('\') + '\'
if (-not ($resolvedRoot.TrimEnd('\') + '\').StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup policy verification is restricted to D:\gptuser.'
}

$androidNamespace = 'http://schemas.android.com/apk/res/android'
$expectedDomains = @(
    'root',
    'file',
    'database',
    'sharedpref',
    'external',
    'device_root',
    'device_file',
    'device_database',
    'device_sharedpref'
)

function Assert-ApplicationPolicy([string]$manifestPath) {
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        throw "Manifest not found: $manifestPath"
    }
    [xml]$manifest = Get-Content -LiteralPath $manifestPath -Encoding UTF8 -Raw
    $application = $manifest.manifest.application
    if ($application.GetAttribute('allowBackup', $androidNamespace) -ne 'false') {
        throw "android:allowBackup must remain false in $manifestPath"
    }
    if ($application.GetAttribute('dataExtractionRules', $androidNamespace) -ne '@xml/data_extraction_rules') {
        throw "android:dataExtractionRules is missing or overridden in $manifestPath"
    }
    if ($application.GetAttribute('fullBackupContent', $androidNamespace) -ne '@xml/backup_rules') {
        throw "android:fullBackupContent is missing or overridden in $manifestPath"
    }
}

function Assert-Excludes([System.Xml.XmlNodeList]$nodes, [string]$label) {
    $actualDomains = @($nodes | ForEach-Object {
        if ($_.GetAttribute('path') -ne '.') {
            throw "$label contains a non-root exclusion path for domain $($_.GetAttribute('domain'))."
        }
        $_.GetAttribute('domain')
    })
    $missing = @($expectedDomains | Where-Object { $_ -notin $actualDomains })
    $unexpected = @($actualDomains | Where-Object { $_ -notin $expectedDomains })
    $duplicates = @($actualDomains | Group-Object | Where-Object Count -ne 1 | Select-Object -ExpandProperty Name)
    if ($missing.Count -gt 0 -or $unexpected.Count -gt 0 -or $duplicates.Count -gt 0) {
        throw "$label does not exclude exactly the nine Android backup domains. Missing=$($missing -join ',') Unexpected=$($unexpected -join ',') Duplicate=$($duplicates -join ',')"
    }
}

$manifestPaths = @(
    (Join-Path $resolvedRoot 'app\src\main\AndroidManifest.xml'),
    (Join-Path $resolvedRoot 'app\build\intermediates\merged_manifests\debug\processDebugManifest\AndroidManifest.xml'),
    (Join-Path $resolvedRoot 'app\build\intermediates\merged_manifests\release\processReleaseManifest\AndroidManifest.xml')
)
foreach ($manifestPath in $manifestPaths) {
    Assert-ApplicationPolicy $manifestPath
}

$modernPath = Join-Path $resolvedRoot 'app\src\main\res\xml\data_extraction_rules.xml'
[xml]$modern = Get-Content -LiteralPath $modernPath -Encoding UTF8 -Raw
if ($modern.'data-extraction-rules'.'cloud-backup'.GetAttribute('disableIfNoEncryptionCapabilities') -ne 'true') {
    throw 'Cloud backup must also require encryption capability as defense in depth.'
}
if ($modern.SelectNodes('/data-extraction-rules//include').Count -ne 0) {
    throw 'System backup rules must not include any app data.'
}
Assert-Excludes ($modern.SelectNodes('/data-extraction-rules/cloud-backup/exclude')) 'Android 12+ cloud backup'
Assert-Excludes ($modern.SelectNodes('/data-extraction-rules/device-transfer/exclude')) 'Android 12+ device transfer'

$legacyPath = Join-Path $resolvedRoot 'app\src\main\res\xml\backup_rules.xml'
[xml]$legacy = Get-Content -LiteralPath $legacyPath -Encoding UTF8 -Raw
if ($legacy.SelectNodes('/full-backup-content/include').Count -ne 0) {
    throw 'Legacy backup rules must not include any app data.'
}
Assert-Excludes ($legacy.SelectNodes('/full-backup-content/exclude')) 'Android 11 and lower backup/transfer'

Write-Output 'BACKUP_EXCLUSION_POLICY_OK'
Write-Output 'ALLOW_BACKUP=false'
Write-Output 'MODERN_MODES=cloud-backup,device-transfer'
Write-Output "EXCLUDED_DOMAINS=$($expectedDomains.Count)"
Write-Output 'MANIFEST_VARIANTS=source,debug,release'
