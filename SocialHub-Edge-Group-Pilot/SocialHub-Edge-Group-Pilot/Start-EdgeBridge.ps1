param(
  [string]$Api = 'http://127.0.0.1:8081'
)
$ErrorActionPreference = 'Stop'
$env:SOCIALHUB_API = $Api
$env:GROUP_WORKER_TARGET_TASK_ID = '0c6ecb4c-ffe1-4f45-97d6-a5dfa9a1e73b'
$env:GROUP_WORKER_EXPECTED_GROUP_ID = '1326570106277759'
if (-not $env:GROUP_WORKER_TOKEN) {
  $s = Read-Host 'Enter the SAME GROUP_WORKER_TOKEN configured in IntelliJ Backend' -AsSecureString
  $env:GROUP_WORKER_TOKEN = [System.Net.NetworkCredential]::new('', $s).Password
}
if (-not $env:GROUP_WORKER_TOKEN) {throw 'Empty GROUP_WORKER_TOKEN; refusing to start.'}
Write-Host 'Starting one-task Group Edge bridge (NO automatic publishing)' -ForegroundColor Cyan
py -3 (Join-Path $PSScriptRoot 'bridge\bridge.py')
