param(
    [Parameter(Mandatory=$true)][string]$BackendPath,
    [Parameter(Mandatory=$true)][string]$GroupId,
    [string]$TaskId = "",
    [string]$Api = "http://localhost:8081",
    [switch]$PrepareProfile,
    [switch]$Watch,
    [switch]$AllowLive,
    [ValidateRange(1,1440)][int]$MaxWaitMinutes = 120
)
$ErrorActionPreference = "Stop"
if ($GroupId -notmatch '^[0-9]+$') { throw "GroupId must be numeric (the actual Facebook group ID)." }
$workerDir = Join-Path $BackendPath "group-worker"
$python = Join-Path $workerDir ".venv\Scripts\python.exe"
$workerFile = Join-Path $workerDir "worker.py"
if (!(Test-Path -LiteralPath $python)) { throw "Install requirements into $workerDir\.venv first (see README)." }
if (!(Test-Path -LiteralPath $workerFile)) { throw "Worker file missing: $workerFile" }
$env:SOCIALHUB_API = $Api
$env:GROUP_WORKER_EXPECTED_GROUP_ID = $GroupId
$env:BROWSER_PROFILE = Join-Path $workerDir "browser-profile"
if ($PrepareProfile) {
    Write-Host "MANUAL BROWSER LOGIN ONLY: NO TASK CLAIMED OR POSTED" -ForegroundColor Cyan
    & $python $workerFile --prepare-profile
    exit $LASTEXITCODE
}
if (!$AllowLive) { throw "To allow ONE actual Group post, explicitly pass -AllowLive after verifying the group and task." }
if ($TaskId -notmatch '^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$') { throw "Pass one exact UUID in -TaskId" }
if ([string]::IsNullOrWhiteSpace($env:GROUP_WORKER_TOKEN)) { throw "Set GROUP_WORKER_TOKEN in this PowerShell process; it must match the Backend's token." }
$env:GROUP_WORKER_TARGET_TASK_ID = $TaskId
$env:GROUP_AUTO_PUBLISH_ENABLED = "true"
Write-Host "LIVE: exactly one task $TaskId for group $GroupId. No automatic retry." -ForegroundColor Yellow
if ($Watch) {
    & $python $workerFile --watch --poll-seconds 30 --max-wait-minutes $MaxWaitMinutes
} else {
    & $python $workerFile
}
exit $LASTEXITCODE
