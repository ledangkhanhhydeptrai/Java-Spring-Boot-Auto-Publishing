param(
    [Parameter(Mandatory=$true)][string]$StartAfterUtc,
    [switch]$EnableLiveDispatch
)
$ErrorActionPreference = "Stop"
try { [void]([DateTimeOffset]::Parse($StartAfterUtc).ToUniversalTime()) }
catch { throw "StartAfterUtc must be valid ISO timestamp, e.g. 2026-11-16T09:00:00Z" }
if ($StartAfterUtc -notmatch '(Z|[+-]\d\d:\d\d)$') { throw "Specify timezone in StartAfterUtc" }
$env:AUTOMATION_QUEUE_START_AFTER = ([DateTimeOffset]::Parse($StartAfterUtc).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))
$env:AUTOMATION_QUEUE_ENABLED = if ($EnableLiveDispatch) { 'true' } else { 'false' }
$env:AUTOMATION_QUEUE_DISPATCH_ENABLED = if ($EnableLiveDispatch) { 'true' } else { 'false' }
$env:AUTOMATION_QUEUE_LIVE_DISPATCH_ENABLED = if ($EnableLiveDispatch) { 'true' } else { 'false' }
$env:AUTOMATION_LIVE_ENABLED = 'true'
$env:PUBLISHER_ENABLED = 'false'
$env:THREADS_PUBLISHER_ENABLED = 'false'
$env:RABBITMQ_LISTENER_ENABLED = 'false'
$env:AUTOMATION_FACEBOOK_WORKER_ENABLED = 'false'
$env:AUTOMATION_THREADS_WORKER_ENABLED = 'false'
Write-Host "[API] Live dispatch enabled: $EnableLiveDispatch | UTC cutoff: $env:AUTOMATION_QUEUE_START_AFTER"
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven (mvn) is required; no mvnw.cmd exists in source archive.' }
mvn '-Dspring-boot.run.profiles=api' spring-boot:run
