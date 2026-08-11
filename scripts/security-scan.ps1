[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$Canary = "",
    [string[]]$ArtifactPaths = @(),
    [switch]$SkipArtifacts
)

$ErrorActionPreference = "Stop"

$resolvedRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$allowedRoot = (Resolve-Path -LiteralPath "D:\gptuser").Path.TrimEnd("\") + "\"
if (-not ($resolvedRoot.TrimEnd("\") + "\").StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Security scanning is restricted to D:\gptuser."
}

$patterns = @(
    '(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])',
    'AIza[0-9A-Za-z_-]{30,}',
    '(?i)(api[_-]?key|authorization|bearer|client[_-]?secret|access[_-]?token)\s*[:=]\s*[\x22\x27]?[A-Za-z0-9._-]{20,}'
)
if ($Canary) {
    $patterns += [Regex]::Escape($Canary)
}

$findings = [System.Collections.Generic.List[string]]::new()
$rg = Get-Command rg -ErrorAction Stop
$sourceArguments = @(
    '--files-with-matches',
    '--hidden',
    '--no-ignore',
    '--pcre2',
    '--glob', '!.git/**',
    '--glob', '!**/build/**'
)

foreach ($pattern in $patterns) {
    $arguments = $sourceArguments + @('--regexp', $pattern, '--', $resolvedRoot)
    $matches = & $rg.Source @arguments 2>$null
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
        foreach ($match in $matches) {
            $findings.Add("source:$match")
        }
    } elseif ($exitCode -ne 1) {
        throw "rg failed while scanning source files (exit $exitCode)."
    }
}

if (-not $SkipArtifacts) {
    if ($ArtifactPaths.Count -eq 0) {
        $ArtifactPaths = @(
            Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -like '*\build\outputs\apk\*' } |
                Select-Object -ExpandProperty FullName
        )
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $latin1 = [Text.Encoding]::GetEncoding(28591)
    foreach ($artifactPath in $ArtifactPaths) {
        $resolvedArtifact = (Resolve-Path -LiteralPath $artifactPath).Path
        if (-not ($resolvedArtifact.TrimEnd("\") + "\").StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Artifact scanning is restricted to D:\gptuser: $resolvedArtifact"
        }
        $archive = [IO.Compression.ZipFile]::OpenRead($resolvedArtifact)
        try {
            foreach ($entry in $archive.Entries) {
                if ($entry.Length -eq 0) { continue }
                $stream = $entry.Open()
                try {
                    $memory = [IO.MemoryStream]::new()
                    $stream.CopyTo($memory)
                    $text = $latin1.GetString($memory.ToArray())
                    foreach ($pattern in $patterns) {
                        if ([Regex]::IsMatch($text, $pattern)) {
                            $findings.Add("artifact:$resolvedArtifact::$($entry.FullName)")
                            break
                        }
                    }
                } finally {
                    $stream.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    }
}

$uniqueFindings = @($findings | Sort-Object -Unique)
if ($uniqueFindings.Count -gt 0) {
    Write-Output ("POTENTIAL_SECRET_MATERIAL_FOUND_IN:`n" + ($uniqueFindings -join "`n"))
    exit 2
}

Write-Output "SECURITY_SCAN_OK"
Write-Output "SOURCE_ROOT=$resolvedRoot"
Write-Output "ARTIFACT_COUNT=$($ArtifactPaths.Count)"
exit 0
