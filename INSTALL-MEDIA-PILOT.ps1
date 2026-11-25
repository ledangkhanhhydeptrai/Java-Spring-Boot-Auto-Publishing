param(
  [Parameter(Mandatory=$true)][string]$BackendPath,
  [Parameter(Mandatory=$true)][string]$PilotPath,
  [switch]$Apply
)
$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$ext = Join-Path $PilotPath 'extension'
$javaDir = Join-Path $BackendPath 'src\main\java\com\socialhub\grouppublishingtask\worker'
if (!(Test-Path -LiteralPath (Join-Path $BackendPath 'src\main\java') -PathType Container)) { throw 'BackendPath is not Spring Boot project' }
if (!(Test-Path -LiteralPath (Join-Path $ext 'manifest.json') -PathType Leaf)) {throw 'PilotPath is not valid Edge Group Pilot'}
$manifest = Get-Content (Join-Path $ext 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.name -notlike '*Facebook Group One-Task Pilot') {throw 'Unexpected extension name, refusing to install'}
if ('debugger' -notin $manifest.permissions) {throw 'Manifest must include debugger permission. Stopping.'}
# Only accept byte-identical v10/v11 baselines; no overwrite of user-edited source.
$known = @{
 'bridge.py' = @('8143150FB043165E8344E6DF10CAC36DC8C3BE8F669C4984DEAF3ABFEB333975', '8C0C625D4B08B5C076C01C1B9D045F8A059B1D6E0662EDEAD373F33F6D6B955F')
 'background.js' = @('1DFC6F20E13A6B1EE9DBE70C9777BFC1CD46BA472B8FF4A31C07DCA0A6D66720', '2C88C19D41CD822C36A5CA444B49DD948316734B0635ABDA5468C5C38E6F7678')
 'content.js' = @('294D58D832B4C17CA07A5F0D201F4DFEE0EDE08A047F44784B2A5018A3285566', 'B87A6CF381E6961EB9B62F82C2CC9D388866B759ED490B1CCF08F9DC9430F4DD')
 'popup.js' = @('F2F9825B229D4ABFDE5EA73D70150415CBF3A5DD21B5ED981355D321B1D3D9B0', '9E1AC83F779752863C8011716F83BC44E0304F7517BDB3DB7F0522F1F3AE4698')
 'popup.html' = @('C8210E0625281535624126F9F029687B6F4E9C5B0BCF4D39A4C7D6A21259222C', 'B55516368876FF8A3A9CD7ED8FE9B01E10E82F0B7945F46D2A1CE2BA05FE5DF5')
}
$items = @(
 @{src='bridge\bridge.py';dst=(Join-Path $PilotPath 'bridge\bridge.py');type='replace'},
 @{src='extension\background.js';dst=(Join-Path $ext 'background.js');type='replace'},
 @{src='extension\content.js';dst=(Join-Path $ext 'content.js');type='replace'},
 @{src='extension\popup.js';dst=(Join-Path $ext 'popup.js');type='replace'},
 @{src='extension\popup.html';dst=(Join-Path $ext 'popup.html');type='replace'},
 @{src='backend\GroupWorkerMediaPreviewController.java';dst=(Join-Path $javaDir 'GroupWorkerMediaPreviewController.java');type='v11-reference'},
 @{src='backend\GroupWorkerVideoPilotController.java';dst=(Join-Path $javaDir 'GroupWorkerVideoPilotController.java');type='new'},
 @{src='Start-EdgeBridge-Media.ps1';dst=(Join-Path $PilotPath 'Start-EdgeBridge-Media.ps1');type='new'}
)
# Preflight every file before any write; do not touch DB or Worker state.
$changes=@()
foreach($entry in $items) {
  $src=Join-Path $base $entry.src
  $dst=$entry.dst
  if (!(Test-Path -LiteralPath $src -PathType Leaf)) {throw "Missing package file: $src"}
  $srcHash=(Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash
  if (Test-Path -LiteralPath $dst -PathType Leaf) {
    $dstHash=(Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash
    if ($srcHash -eq $dstHash) {
      Write-Host "Already installed: $dst" -ForegroundColor DarkGreen
      continue
    }
    if ($entry.type -eq 'replace') {
      $name=Split-Path -Path $dst -Leaf
      if ($dstHash -notin $known[$name]) {throw "Source differs from approved v10/v11: $dst; STOP (no files changed)."}
    } else {throw "Existing controller/starter differs: $dst; STOP (no files changed)."}
  }
  $changes+=@{src=$src;dst=$dst}
  Write-Host "Would install: $dst" -ForegroundColor Cyan
}
if (!$Apply) {
  Write-Host 'DRY RUN ONLY: no files changed. Use -Apply only after reviewing.' -ForegroundColor Yellow
  exit 0
}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
foreach ($entry in $changes) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Path $entry.dst -Parent) | Out-Null
  if (Test-Path -LiteralPath $entry.dst -PathType Leaf) {
    Copy-Item -LiteralPath $entry.dst -Destination "$($entry.dst).backup-$stamp" -Force
  }
  Copy-Item -LiteralPath $entry.src -Destination $entry.dst -Force
}
Write-Host 'Installed code ONLY. No tasks claimed, scheduled or published; reload Backend/Edge/Bridge at safe time.' -ForegroundColor Green
