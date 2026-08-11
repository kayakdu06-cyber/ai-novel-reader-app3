[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$keyPath = Join-Path $projectRoot ".codex\deepseek-key.local"
$secureKey = Read-Host "Enter the DeepSeek API Key (input is hidden)" -AsSecureString
$encryptedKey = ConvertFrom-SecureString -SecureString $secureKey

Set-Content -LiteralPath $keyPath -Value $encryptedKey -Encoding UTF8
Write-Host "The DeepSeek API Key is encrypted and stored in the app2 isolation area."
