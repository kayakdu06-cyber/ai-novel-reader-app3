[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$keyPath = Join-Path $projectRoot ".codex\deepseek-key.local"

if (-not (Test-Path -LiteralPath $keyPath)) {
    throw "DeepSeek key is not configured for app2. Run scripts/set-deepseek-key.ps1 first."
}

$encryptedKey = (Get-Content -Raw -LiteralPath $keyPath).Trim()
$secureKey = ConvertTo-SecureString $encryptedKey
$keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)

try {
    [Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer))
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
}
