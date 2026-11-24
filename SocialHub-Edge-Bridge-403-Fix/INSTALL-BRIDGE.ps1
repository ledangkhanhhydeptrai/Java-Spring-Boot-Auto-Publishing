param(
    [Parameter(Mandatory = $true)]
    [string]$PilotPath
)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'bridge\bridge.py'
$target = Join-Path $PilotPath 'bridge\bridge.py'
$expectedOld = 'a0e23d124fd3f947eaae19bbbad0270ae12344bc6487b1907f9e3fd97a3bfc3b'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'Patch bridge.py is missing.' }
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Pilot bridge.py not found: $target" }
$sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$currentHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
if ($currentHash -eq $sourceHash) {
    Write-Host 'Bridge fix already installed. No file changed.' -ForegroundColor Green
    exit 0
}
if ($currentHash -ne $expectedOld) {
    throw "Unknown bridge.py version (SHA256 $currentHash). Stop: no files changed."
}
$backup = "$target.bak-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
Copy-Item -LiteralPath $target -Destination $backup -ErrorAction Stop
Copy-Item -LiteralPath $source -Destination $target -Force -ErrorAction Stop
$after = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
if ($after -ne $sourceHash) {
    Copy-Item -LiteralPath $backup -Destination $target -Force
    throw 'File validation failed; original restored.'
}
Write-Host "Bridge fix installed: $target" -ForegroundColor Green
Write-Host "Backup: $backup"
Write-Host 'Stop/restart ONLY the Edge Bridge process to apply. Backend can keep running.'
