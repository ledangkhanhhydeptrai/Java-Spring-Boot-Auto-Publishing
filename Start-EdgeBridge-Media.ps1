param(
  [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9-]{36}$')][string]$TaskId,
  [ValidatePattern('^[0-9]+$')][string]$GroupId='1326570106277759',
  [string]$Api='http://127.0.0.1:8081',
  [string]$AllowedMediaHosts='res.cloudinary.com'
)
$ErrorActionPreference='Stop'
if ($Api -notin @('http://127.0.0.1:8081','http://localhost:8081')) {throw 'Bridge must reach the local SocialHub Backend on port 8081'}
if ($GroupId -ne '1326570106277759') {throw 'This pilot is pinned to the existing test Group; refusing other IDs'}
$env:SOCIALHUB_API=$Api
$env:GROUP_WORKER_TARGET_TASK_ID=$TaskId.ToLowerInvariant()
$env:GROUP_WORKER_EXPECTED_GROUP_ID=$GroupId
$env:GROUP_MEDIA_ALLOWED_HOSTS=$AllowedMediaHosts
if (-not $env:GROUP_WORKER_TOKEN) {
  $s=Read-Host 'Enter SAME GROUP_WORKER_TOKEN configured in Backend' -AsSecureString
  $env:GROUP_WORKER_TOKEN=[System.Net.NetworkCredential]::new('',$s).Password
}
if (-not $env:GROUP_WORKER_TOKEN) {throw 'Missing worker token'}
Write-Host "One-task Group Media Pilot (TEXT/IMAGE/MP4): $TaskId (NO automatic publishing)" -ForegroundColor Cyan
Write-Host 'NO automatic publishing. VIDEO can be uploaded only when one exact READY Task is approved and due.' -ForegroundColor Yellow
Write-Host 'Backend Run Configuration MUST have same GROUP_WORKER_TARGET_TASK_ID and GROUP_WORKER_EXPECTED_GROUP_ID.'
py -3 (Join-Path $PSScriptRoot 'bridge\bridge.py')
