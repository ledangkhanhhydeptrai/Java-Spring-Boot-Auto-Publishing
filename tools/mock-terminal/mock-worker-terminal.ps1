param(
    [Parameter(Mandatory = $true)] [string]$LogFile,
    [Parameter(Mandatory = $true)] [string]$RunId,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 1000000)] [int]$Total
)

$ErrorActionPreference = 'Stop'

# VIEWER ONLY. Does not publish, change the database or call Meta APIs.
# Compatible with Windows PowerShell 5.1.
$mutex = $null
$ownsMutex = $false

$script:successKeys = @{}
$script:reviewKeys = @{}
$script:recent = New-Object 'System.Collections.Generic.List[string]'
$script:currentAccount = '-'
$script:currentRow = '-'
$script:currentStatus = 'WAITING FOR QUEUE'
$script:runFinished = $false
$script:runFailed = $false
$script:started = Get-Date
$script:lastActivity = [DateTime]::UtcNow
$script:readLineCount = 0
$script:queued = $false
$script:reportedCompleted = -1
$script:lastError = ''

function Field([string]$line, [string]$name) {
    $match = [regex]::Match($line, '(?:^|\s)' + [regex]::Escape($name) + '=([^\s]+)')
    if ($match.Success) { return $match.Groups[1].Value }
    return '-'
}

function Activity([string]$message) {
    $script:recent.Insert(0, ('{0}  {1}' -f (Get-Date -Format 'HH:mm:ss'), $message))
    while ($script:recent.Count -gt 6) { $script:recent.RemoveAt($script:recent.Count - 1) }
}

function ProcessLine([string]$line) {
    if (-not $line.Contains('run=' + $RunId)) { return }
    $script:lastActivity = [DateTime]::UtcNow

    $account = Field $line 'account'
    $row = Field $line 'row'
    $post = Field $line 'postId'
    $key = if ($post -ne '-') { $post } else { $account + '|' + $row }

    if ($line -match '\[MOCK WORKER\] PUBLISHED_SIMULATED\b') {
        if (-not $script:successKeys.ContainsKey($key)) {
            $script:successKeys[$key] = $true
            [void]$script:reviewKeys.Remove($key)
            Activity ('OK    {0}  row {1}' -f $account, $row)
        }
        $script:currentAccount = $account
        $script:currentRow = $row
        $script:currentStatus = 'PUBLISHED_SIMULATED'
    }
    elseif ($line -match '\[MOCK WORKER\] (DUPLICATE_SKIPPED|NEEDS_REVIEW)\b') {
        if (-not $script:reviewKeys.ContainsKey($key) -and -not $script:successKeys.ContainsKey($key)) {
            $script:reviewKeys[$key] = $true
            Activity ('REVIEW {0}  row {1}' -f $account, $row)
        }
        $script:currentAccount = $account
        $script:currentRow = $row
        $script:currentStatus = 'NEEDS_REVIEW'
    }
    elseif ($line -match '\[MOCK WORKER\] START\b') {
        $script:currentAccount = $account
        $script:currentRow = $row
        $script:currentStatus = 'STARTED'
    }
    elseif ($line -match '\[MOCK WORKER\] PUBLISHING\b') {
        $script:currentAccount = $account
        $script:currentRow = $row
        $script:currentStatus = 'PUBLISHING'
    }
    elseif ($line -match '\[MOCK LOAD\] SEED START\b') {
        $script:currentStatus = 'PREPARING'
        Activity 'Excel MOCK run created'
    }
    elseif ($line -match '\[MOCK LOAD\] SEED COMPLETE\b') {
        $script:queued = $true
        $script:currentStatus = 'QUEUED'
        Activity 'RabbitMQ enqueue completed'
    }
    elseif ($line -match '\[MOCK LOAD\] progress\b') {
        $match = [regex]::Match($line, '(?:^|\s)completed=(\d+)')
        if ($match.Success) { $script:reportedCompleted = [int]$match.Groups[1].Value }
    }
    elseif ($line -match '\[MOCK LOAD\] COMPLETE\b' -and $line -match 'source=EXCEL') {
        # COMPLETE is the existing backend's authoritative end-of-run marker.
        # Do not invent successful jobs merely because the run ended.
        $script:runFinished = $true
        $script:currentStatus = 'COMPLETED'
        Activity 'Backend reported run COMPLETE'
    }
    elseif ($line -match '\[MOCK LOAD\] (FAILED|ERROR)\b') {
        $script:runFailed = $true
        $script:currentStatus = 'ERROR'
        Activity 'Backend reported an error'
    }
}

function RenderDashboard {
    $success = $script:successKeys.Count
    $review = $script:reviewKeys.Count
    $processed = [Math]::Min($Total, ($success + $review))
    $remaining = [Math]::Max(0, ($Total - $processed))
    $percent = [Math]::Min(100, [Math]::Floor(100.0 * $processed / $Total))
    $width = 38
    $fill = [Math]::Min($width, [Math]::Floor($width * $percent / 100.0))
    $bar = ('#' * $fill) + ('-' * ($width - $fill))
    $elapsed = (Get-Date) - $script:started
    $elapsedText = '{0:00}:{1:00}:{2:00}' -f [Math]::Floor($elapsed.TotalHours), $elapsed.Minutes, $elapsed.Seconds

    try { Clear-Host } catch { }  # Never fail publishing visibility because of a UI clear.
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
    Write-Host '  SOCIALHUB  |  EXCEL MOCK PUBLISHING DASHBOARD' -ForegroundColor Green
    Write-Host '  SIMULATED ONLY  |  NO FACEBOOK / META API CALLS' -ForegroundColor Yellow
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
    Write-Host ('  RUN       : {0}' -f $RunId) -ForegroundColor Gray
    Write-Host ('  DEST      : MOCK_PAGE_001') -ForegroundColor Gray
    Write-Host ('  ACCOUNTS  : 100 synthetic (maximum)') -ForegroundColor Gray
    Write-Host ('  ELAPSED   : {0}' -f $elapsedText) -ForegroundColor Gray
    Write-Host ('-' * 70) -ForegroundColor DarkGray
    Write-Host ('  TOTAL     : {0,5}    SUCCESS : {1,5}' -f $Total, $success) -ForegroundColor Green
    Write-Host ('  REVIEW    : {0,5}    PENDING : {1,5}' -f $review, $remaining) -ForegroundColor Yellow
    Write-Host ('  [{0}] {1,3}%' -f $bar, $percent) -ForegroundColor Cyan
    Write-Host ('-' * 70) -ForegroundColor DarkGray
    Write-Host ('  ACCOUNT   : {0}' -f $script:currentAccount) -ForegroundColor White
    Write-Host ('  EXCEL ROW : {0}' -f $script:currentRow) -ForegroundColor White
    Write-Host ('  STATUS    : {0}' -f $script:currentStatus) -ForegroundColor Cyan
    Write-Host ('-' * 70) -ForegroundColor DarkGray
    Write-Host '  RECENT ACTIVITY' -ForegroundColor White
    if ($script:recent.Count -eq 0) { Write-Host '  Waiting for worker events...' -ForegroundColor DarkGray }
    else { foreach ($item in $script:recent) { Write-Host ('  ' + $item) -ForegroundColor Gray } }
    Write-Host ('-' * 70) -ForegroundColor DarkGray
    if ($script:runFinished) {
        Write-Host '  BACKEND REPORTED COMPLETE - viewer closing shortly' -ForegroundColor Green
        if ($processed -lt $Total) {
            Write-Host '  NOTE: Not every terminal event was observed; verify DB/run status.' -ForegroundColor Yellow
        }
    }
    elseif ($script:runFailed) { Write-Host '  RUN ERROR - check IntelliJ logs' -ForegroundColor Red }
    else { Write-Host '  LIVE VIEW  |  UI refresh ~500 ms' -ForegroundColor DarkCyan }
    Write-Host ('=' * 70) -ForegroundColor DarkCyan
}

try {
    if ($RunId.Length -lt 8) { throw 'RunId must contain at least 8 characters.' }
    $Host.UI.RawUI.WindowTitle = 'SocialHub | MOCK Dashboard | ' + $RunId.Substring(0, 8)
    $mutexName = 'Local\SocialHubExcelMock_' + $RunId.Replace('-', '')
    $mutex = New-Object System.Threading.Mutex($false, $mutexName)
    try { $ownsMutex = $mutex.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { Write-Host '[INFO] Viewer is already open for this run.'; return }

    Activity 'Dashboard started'
    RenderDashboard

    while (-not $script:runFinished -and -not $script:runFailed) {
        $updated = $false
        if (Test-Path -LiteralPath $LogFile -PathType Leaf) {
            # Re-read with an index so events emitted before PowerShell opened aren't lost.
            # Reading a whole small demo log is intentional; no byte-offset/UTF-8 slicing bugs.
            $lines = @(Get-Content -LiteralPath $LogFile -Encoding UTF8 -ErrorAction Stop)
            if ($lines.Count -lt $script:readLineCount) { $script:readLineCount = 0 }
            for ($i = $script:readLineCount; $i -lt $lines.Count; $i++) {
                $line = [string]$lines[$i]
                if (-not $line.Contains('run=' + $RunId)) { continue }
                ProcessLine $line
                $updated = $true
                if ($script:runFinished -or $script:runFailed) { break }
            }
            $script:readLineCount = $lines.Count
        }

        if ($updated) { RenderDashboard }
        if ($script:runFinished -or $script:runFailed) { break }

        if (([DateTime]::UtcNow - $script:lastActivity).TotalMinutes -ge 10) {
            $script:currentStatus = 'IDLE TIMEOUT'
            Activity 'No matching events for 10 minutes'
            RenderDashboard
            Write-Host '  WARNING: Viewer timeout. Check IntelliJ and run status.' -ForegroundColor Yellow
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if ($script:runFinished) { RenderDashboard; Start-Sleep -Seconds 3 }
    elseif ($script:runFailed) { RenderDashboard; Start-Sleep -Seconds 5 }
}
catch {
    Write-Host ('[DASHBOARD ERROR] ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Backend/worker are independent of this viewer.' -ForegroundColor Yellow
    Start-Sleep -Seconds 5
}
finally {
    if ($ownsMutex -and $null -ne $mutex) { try { $mutex.ReleaseMutex() } catch { } }
    if ($null -ne $mutex) { $mutex.Dispose() }
}
