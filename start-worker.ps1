param(
    [switch]$EnableLive,
    [switch]$EnableThreads
)
$ErrorActionPreference = 'Stop'
$env:AUTOMATION_QUEUE_ENABLED = 'false'
$env:AUTOMATION_QUEUE_DISPATCH_ENABLED = 'false'
$env:AUTOMATION_QUEUE_LIVE_DISPATCH_ENABLED = 'false'
$env:PUBLISHER_ENABLED = 'false'
$env:THREADS_PUBLISHER_ENABLED = 'false'
$env:RABBITMQ_LISTENER_ENABLED = if ($EnableLive) { 'true' } else { 'false' }
$env:AUTOMATION_LIVE_ENABLED = if ($EnableLive) { 'true' } else { 'false' }
$env:AUTOMATION_FACEBOOK_WORKER_ENABLED = if ($EnableLive) { 'true' } else { 'false' }
$env:AUTOMATION_THREADS_WORKER_ENABLED = if ($EnableLive -and $EnableThreads) { 'true' } else { 'false' }
if ($EnableLive) {
    if ($env:FACEBOOK_PUBLISHING_MODE -ne 'LIVE' -or $env:META_BASE_URL -ne 'https://graph.facebook.com') {
        throw 'Set FACEBOOK_PUBLISHING_MODE=LIVE and META_BASE_URL=https://graph.facebook.com explicitly before enabling LIVE'
    }
}
Write-Host "[WORKER] LIVE: $EnableLive | Threads: $EnableThreads | HTTP disabled"
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven (mvn) is required; no mvnw.cmd exists in source archive.' }
mvn '-Dspring-boot.run.profiles=worker' spring-boot:run
