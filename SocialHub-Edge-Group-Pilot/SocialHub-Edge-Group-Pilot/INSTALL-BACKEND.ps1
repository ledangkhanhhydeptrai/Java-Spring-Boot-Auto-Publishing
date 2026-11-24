param(
    [Parameter(Mandatory=$true)][string]$BackendPath
)
$ErrorActionPreference = "Stop"
$target = Join-Path $BackendPath 'src\main\java\com\socialhub\grouppublishingtask\worker\GroupWorkerController.java'
$source = Join-Path $PSScriptRoot 'backend\GroupWorkerController.java'
if (-not (Test-Path -LiteralPath $source)) { throw "Patch source missing: $source" }
if (-not (Test-Path -LiteralPath $target)) { throw "Backend source missing: $target" }
$old = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
$expected = @('ec9f4f5c2baea05766b36ac4d2d6527e53c167e69d1b38f2e2c22f4ab0a7b18e', 'cdd7dfe50be85b3df0c0606f235da02806c695cb1a37f5b7da3795700f98452c')
$new = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant()
if ($old -eq $new) {
    Write-Host 'Already installed. No file changed.' -ForegroundColor Green
    exit 0
}
if ($expected -notcontains $old) {
    throw "Unknown version: $old. NO changes made. Send your current GroupWorkerController.java for reconciliation."
}
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$backup = "$target.backup_$stamp"
Copy-Item -LiteralPath $target -Destination $backup -ErrorAction Stop
Copy-Item -LiteralPath $source -Destination $target -ErrorAction Stop
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
if ($actual -ne $new) {
    Copy-Item -LiteralPath $backup -Destination $target -Force
    throw 'Checksum mismatch after copy. Backup restored.'
}
Write-Host "Installed Group Worker read-only preview and READY guard." -ForegroundColor Green
Write-Host "Backup: $backup"
Write-Host "NO restart or LIVE post executed. Build/restart only when Threads and Fanpages are idle." -ForegroundColor Yellow
