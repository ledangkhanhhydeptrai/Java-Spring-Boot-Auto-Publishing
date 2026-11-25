param(
  [Parameter(Mandatory=$true)][string]$BackendPath,
  [Parameter(Mandatory=$true)][string]$PilotPath,
  [switch]$Apply
)
$ErrorActionPreference='Stop'
$backendSrc = Join-Path $PSScriptRoot 'backend\GroupWorkerMediaPreviewController.java'
$backendDst = Join-Path $BackendPath 'src\main\java\com\socialhub\grouppublishingtask\worker\GroupWorkerMediaPreviewController.java'
$files = @(
  @{Source=(Join-Path $PSScriptRoot 'bridge\bridge.py'); Target=(Join-Path $PilotPath 'bridge\bridge.py')},
  @{Source=(Join-Path $PSScriptRoot 'extension\background.js'); Target=(Join-Path $PilotPath 'extension\background.js')},
  @{Source=(Join-Path $PSScriptRoot 'extension\content.js'); Target=(Join-Path $PilotPath 'extension\content.js')},
  @{Source=(Join-Path $PSScriptRoot 'extension\popup.js'); Target=(Join-Path $PilotPath 'extension\popup.js')},
  @{Source=(Join-Path $PSScriptRoot 'extension\popup.html'); Target=(Join-Path $PilotPath 'extension\popup.html')},
  @{Source=(Join-Path $PSScriptRoot 'Start-EdgeBridge-Images.ps1'); Target=(Join-Path $PilotPath 'Start-EdgeBridge-Images.ps1')},
  @{Source=$backendSrc;Target=$backendDst}
)
foreach ($entry in $files) {
  if (!(Test-Path -LiteralPath $entry.Source -PathType Leaf)) {throw "Missing packaged source: $($entry.Source)"}
}
if (!(Test-Path -LiteralPath (Join-Path $BackendPath 'src\main\java') -PathType Container)) {throw 'Backend root invalid'}
if (!(Test-Path -LiteralPath (Join-Path $PilotPath 'extension\manifest.json') -PathType Leaf)) {throw 'Pilot root invalid'}
$manifest = Get-Content (Join-Path $PilotPath 'extension\manifest.json') -Raw | ConvertFrom-Json
if ($manifest.name -notlike '*Facebook Group One-Task Pilot') {throw 'Unexpected extension; refuse to patch'}
# Exact SHA256 of the Edge v10 files supplied in the user's ZIP.
# Never overwrite any newer local edits without reconciling first.
$originalHashes = @{
  'bridge.py'     = '8143150FB043165E8344E6DF10CAC36DC8C3BE8F669C4984DEAF3ABFEB333975'
  'background.js' = '1DFC6F20E13A6B1EE9DBE70C9777BFC1CD46BA472B8FF4A31C07DCA0A6D66720'
  'content.js'    = '294D58D832B4C17CA07A5F0D201F4DFEE0EDE08A047F44784B2A5018A3285566'
  'popup.js'      = 'F2F9825B229D4ABFDE5EA73D70150415CBF3A5DD21B5ED981355D321B1D3D9B0'
  'popup.html'    = 'C8210E0625281535624126F9F029687B6F4E9C5B0BCF4D39A4C7D6A21259222C'
}
# Preflight every file BEFORE writing any of them.
$changes = @()
foreach ($entry in $files) {
  $srcHash=(Get-FileHash -LiteralPath $entry.Source -Algorithm SHA256).Hash
  if (Test-Path -LiteralPath $entry.Target) {
    $destHash=(Get-FileHash -LiteralPath $entry.Target -Algorithm SHA256).Hash
    if ($destHash -eq $srcHash) {
      Write-Host "Unchanged: $($entry.Target)" -ForegroundColor DarkGreen
      continue
    }
    $baseName=Split-Path $entry.Target -Leaf
    if ($originalHashes.ContainsKey($baseName) -and $destHash -ne $originalHashes[$baseName]) {
      throw "Local source differs from the ZIP supplied by user: $($entry.Target). STOP: no files modified."
    }
    if ($entry.Target -eq $backendDst) {
      throw "New backend controller already exists but differs: $backendDst. STOP: no files modified."
    }
  }
  $changes += $entry
  Write-Host "Will write: $($entry.Target)" -ForegroundColor Cyan
}
if ($Apply) {
  $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
  foreach ($entry in $changes) {
    New-Item -ItemType Directory -Force -Path (Split-Path $entry.Target -Parent) | Out-Null
    if (Test-Path -LiteralPath $entry.Target) {
      Copy-Item -LiteralPath $entry.Target -Destination "$($entry.Target).backup-$stamp" -Force
    }
    Copy-Item -LiteralPath $entry.Source -Destination $entry.Target -Force
  }
  Write-Host 'Files installed. Build/restart Backend only at safe time, reload Edge extension, restart Bridge. NO tasks changed.' -ForegroundColor Green
} else {
  Write-Host 'DRY RUN ONLY: No changes were made. Use -Apply after reviewing.' -ForegroundColor Yellow
}
