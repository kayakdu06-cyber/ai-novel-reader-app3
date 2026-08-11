[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$codexExecutable = $env:ZHIJUAN_DEEPSEEK_CODEX
$argumentsPath = $env:ZHIJUAN_DEEPSEEK_ARGUMENTS
$promptPath = $env:ZHIJUAN_DEEPSEEK_PROMPT

foreach ($requiredValue in @($codexExecutable, $argumentsPath, $promptPath)) {
    if ([string]::IsNullOrWhiteSpace($requiredValue)) {
        throw "The bounded DeepSeek launcher environment is incomplete."
    }
}

if (-not (Test-Path -LiteralPath $codexExecutable -PathType Leaf)) {
    throw "The configured Codex executable does not exist."
}
if (-not (Test-Path -LiteralPath $argumentsPath -PathType Leaf)) {
    throw "The temporary argument file does not exist."
}
if (-not (Test-Path -LiteralPath $promptPath -PathType Leaf)) {
    throw "The temporary prompt file does not exist."
}

$arguments = @(Get-Content -LiteralPath $argumentsPath -Raw -Encoding UTF8 | ConvertFrom-Json)
$prompt = Get-Content -LiteralPath $promptPath -Raw -Encoding UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$prompt | & $codexExecutable @arguments
exit $LASTEXITCODE
