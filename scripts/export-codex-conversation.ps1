param(
    [Parameter(Mandatory = $true)]
    [string]$SourceJsonl,

    [Parameter(Mandatory = $true)]
    [string]$OutputMarkdown,

    [Parameter(Mandatory = $true)]
    [string]$ThreadId,

    [string]$Title = "Codex conversation backup"
)

$ErrorActionPreference = "Stop"

function Protect-Secrets {
    param([string]$Text)

    if ([string]::IsNullOrEmpty($Text)) {
        return $Text
    }

    $protected = $Text
    $protected = [regex]::Replace(
        $protected,
        '(?i)\bsk-[a-z0-9_-]{16,}\b',
        '[REDACTED_API_KEY]'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?i)(authorization\s*[:=]\s*bearer\s+)[^\s"'';,]+',
        '$1[REDACTED_TOKEN]'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?i)((?:api[_-]?key|access[_-]?token|secret)\s*[:=]\s*)["'']?[^\s,"'';,]+',
        '$1[REDACTED_SECRET]'
    )
    return $protected
}

$sourcePath = [System.IO.Path]::GetFullPath($SourceJsonl)
$outputPath = [System.IO.Path]::GetFullPath($OutputMarkdown)

if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "Conversation source does not exist: $sourcePath"
}

if (-not $outputPath.StartsWith('D:\gptuser\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Output must stay under D:\gptuser: $outputPath"
}

$outputDirectory = Split-Path -Parent $outputPath
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

$writer = [System.IO.StreamWriter]::new(
    $outputPath,
    $false,
    [System.Text.UTF8Encoding]::new($false)
)

$messageCount = 0
$redactionCount = 0

try {
    $writer.WriteLine("# $Title")
    $writer.WriteLine()
    $writer.WriteLine("- Thread ID: ``$ThreadId``")
    $writer.WriteLine("- Exported at: $([DateTimeOffset]::Now.ToString('yyyy-MM-dd HH:mm:ss zzz'))")
    $writer.WriteLine("- Scope: user messages and visible Codex messages; reasoning and tool output are excluded")
    $writer.WriteLine("- Security: API keys and token-shaped secrets are replaced with redaction markers")
    $writer.WriteLine()

    foreach ($line in [System.IO.File]::ReadLines($sourcePath, [System.Text.Encoding]::UTF8)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }

        try {
            $record = $line | ConvertFrom-Json
        } catch {
            continue
        }

        if ($record.type -ne 'event_msg') {
            continue
        }

        $eventType = [string]$record.payload.type
        if ($eventType -notin @('user_message', 'agent_message')) {
            continue
        }

        $rawMessage = [string]$record.payload.message
        if ([string]::IsNullOrWhiteSpace($rawMessage)) {
            continue
        }

        $safeMessage = Protect-Secrets -Text $rawMessage
        if ($safeMessage -ne $rawMessage) {
            $redactionCount++
        }

        $speaker = if ($eventType -eq 'user_message') { 'User' } else { 'Codex' }
        $phase = if ($eventType -eq 'agent_message' -and $record.payload.phase) {
            " ($($record.payload.phase))"
        } else {
            ''
        }

        $writer.WriteLine("## $speaker$phase")
        $writer.WriteLine()
        $writer.WriteLine($safeMessage.TrimEnd())
        $writer.WriteLine()
        $messageCount++
    }

    $writer.WriteLine("---")
    $writer.WriteLine()
    $writer.WriteLine("Export summary: $messageCount visible messages; $redactionCount message(s) contained secret-shaped text and were redacted.")
} finally {
    $writer.Dispose()
}

Write-Output "Conversation export complete: $messageCount messages, $redactionCount redacted message(s)."
