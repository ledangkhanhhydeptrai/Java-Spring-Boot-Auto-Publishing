# SocialHub: bulk stage media from Excel-exported CSV through the existing REST API.
# Requires Windows PowerShell 5.1+ or PowerShell 7+. Does not edit database directly.
# Stage is the default. -PrepareJob creates a REAL scheduled PublishingJob; explicitly gated.
[CmdletBinding()]
param(
    [string]$CsvFile = '.\PILOT_2.csv',
    [ValidateSet('REEL','VIDEO','BOTH')][string]$Type = 'REEL',
    [ValidateRange(1,660)][int]$MaxRows = 1,
    [switch]$PrepareJob,
# Explicit permission to prepare one VIDEO pilot Job after operator preflight.
# This does NOT enable the backend VIDEO worker or publish anything by itself.
    [switch]$AllowVideoJobPreparation,
    [ValidateRange(5,43200)][int]$MinutesFromNow = 30,
    [string]$ExpectedPageId = '',
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$WorkspaceId = '5b98521c-1513-4039-936f-b17d8d8516db',
    [string]$CheckpointFile = '.\socialhub_media_checkpoint.json'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$CsvFile = (Resolve-Path $CsvFile).Path
if (-not (Test-Path $CsvFile)) { throw "CSV not found: $CsvFile" }
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Get-XsrfToken {
    $cookies = $session.Cookies.GetCookies($BaseUrl)
    $cookie = $cookies | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
    if ($null -eq $cookie) { return '' }
    return [System.Uri]::UnescapeDataString([string]$cookie.Value)
}
function Call-Api {
    param([string]$Method,[string]$Route,$Body=$null)
    $headers = @{ Accept = 'application/json' }
    $xsrf = Get-XsrfToken
    if ($xsrf) { $headers['X-XSRF-TOKEN'] = $xsrf }
    $args = @{ Uri = "$BaseUrl$Route"; Method = $Method; WebSession = $session; Headers = $headers; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $args['ContentType'] = 'application/json; charset=utf-8'
        $args['Body'] = [System.Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 12 -Compress))
    }
    return Invoke-RestMethod @args
}

# Authenticate without saving passwords/tokens in any output file.
Write-Host "SocialHub Excel API: $BaseUrl"
[void](Call-Api -Method 'GET' -Route '/api/auth/csrf')
$email = Read-Host 'Email đăng nhập SocialHub'
$secure = Read-Host 'Mật khẩu (không hiển thị)' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
try {
    [void](Call-Api -Method 'POST' -Route '/api/auth/login' -Body @{email=$email;password=$plain})
} finally { $plain = $null }
[void](Call-Api -Method 'GET' -Route '/api/auth/me')

$checkpoint = @{}
if (Test-Path $CheckpointFile) {
    $obj = Get-Content -Raw -Encoding UTF8 $CheckpointFile | ConvertFrom-Json
    foreach ($p in $obj.PSObject.Properties) { $checkpoint[$p.Name] = $p.Value }
}
function Save-Checkpoint {
    ConvertTo-Json -InputObject $checkpoint -Depth 15 | Set-Content -Path $CheckpointFile -Encoding UTF8
}

$all = @(Import-Csv -Path $CsvFile -Encoding UTF8)
$selected = @($all | Where-Object { $Type -eq 'BOTH' -or $_.media_type -eq $Type } | Select-Object -First $MaxRows)
if ($selected.Count -eq 0) { throw 'CSV has no rows matching media type.' }

# Validate account mapping through the existing Social Accounts API.
$accountResponse = Call-Api -Method 'GET' -Route "/api/workspaces/$WorkspaceId/social-accounts"
$accounts = if ($accountResponse.PSObject.Properties.Name -contains 'data') { @($accountResponse.data) } else { @($accountResponse) }
foreach ($row in $selected) {
    if ([string]::IsNullOrWhiteSpace($row.social_account_id) -or $row.media_type -notin @('REEL','VIDEO')) {
        throw "Invalid media/account: $($row.code)"
    }
    if (-not $row.media_url.StartsWith('https://res.cloudinary.com/') -or -not $row.media_url.Contains('.mp4')) {
        throw "Expected Cloudinary MP4: $($row.code)"
    }
    $found = @($accounts | Where-Object { $_.id -eq $row.social_account_id })
    if ($found.Count -ne 1) { throw "Social Account not found in workspace: $($row.code)" }
    if ($found[0].status -ne 'CONNECTED' -or $found[0].externalAccountId -ne $row.facebook_page_id) {
        throw "Account/Page mismatch for $($row.code). STOP; check DB and OAuth binding."
    }
    if ($PrepareJob -and $row.facebook_page_id -ne $ExpectedPageId) {
        throw "LIVE safety: expected Page ID '$ExpectedPageId', CSV Page ID '$($row.facebook_page_id)'. No Job created."
    }
}
if ($PrepareJob) {
    if ([string]::IsNullOrWhiteSpace($ExpectedPageId)) { throw 'Must provide -ExpectedPageId when preparing LIVE Jobs.' }
    if (@($selected | Where-Object { $_.media_type -eq 'VIDEO' }).Count -gt 0) {
        if (-not $AllowVideoJobPreparation) {
            throw 'VIDEO is disabled by default. For one controlled pilot Job, use -AllowVideoJobPreparation after confirming the backend VIDEO single-LIVE path and exact target Page.'
        }
        if ($selected.Count -ne 1 -or $MaxRows -ne 1 -or $Type -ne 'VIDEO') {
            throw 'VIDEO pilot guard: only -Type VIDEO -MaxRows 1 may prepare a VIDEO Job.'
        }
        Write-Warning 'VIDEO pilot: this only schedules ONE real Job. Before it becomes due, set backend to SINGLE LIVE using its NEW exact job ID, the intended allowed Page ID, and enable app.facebook.video-publishing-enabled=true. Do not keep the old Reel Job ID.'
    }
    $answer = Read-Host 'This will approve and SCHEDULE real jobs. Type PREPARE-LIVE to continue'
    if ($answer -cne 'PREPARE-LIVE') { throw 'Cancelled, no Jobs prepared.' }
}

foreach ($row in $selected) {
    $key = [string]$row.code
    $state = if ($checkpoint.ContainsKey($key)) { $checkpoint[$key] } else { $null }
    if ($null -eq $state) {
        $p = Call-Api -Method 'POST' -Route "/api/workspaces/$WorkspaceId/posts" -Body @{
            topic = $row.topic; content = $row.content; status='DRAFT'; scheduledAt=$null
        }
        $postId = if ($null -ne $p.data -and $p.data.id) { [string]$p.data.id } else { [string]$p.id }
        if (-not $postId) { throw "Post created but no ID returned for $key; inspect backend before retry." }
        $state = @{ postId=$postId; mediaDone=$false; jobId=''; phase='DRAFT' }
        $checkpoint[$key] = $state
        Save-Checkpoint
        Write-Host "CREATED DRAFT $key => $postId"
    }
    $postId = [string]$state.postId
    if (-not $state.mediaDone) {
        $media = Call-Api -Method 'POST' -Route "/api/posts/$postId/media" -Body @{
            sourceUrl=$row.media_url; publicUrl=$row.media_url;
            mediaType=$row.media_type; displayOrder=0
        }
        $state.mediaDone = $true
        $state.phase = 'MEDIA_READY'
        Save-Checkpoint
        Write-Host "MEDIA READY $key ($($row.media_type))"
    }
    if ($PrepareJob -and -not $state.jobId) {
        [void](Call-Api -Method 'POST' -Route "/api/posts/$postId/approval/submit")
        [void](Call-Api -Method 'POST' -Route "/api/posts/$postId/approval/approve")
        $scheduled = (Get-Date).AddMinutes($MinutesFromNow).ToString('yyyy-MM-ddTHH:mm:sszzz')
        [void](Call-Api -Method 'PUT' -Route "/api/workspaces/$WorkspaceId/posts/$postId" -Body @{
            topic=$row.topic; content=$row.content; status='SCHEDULED';scheduledAt=$scheduled
        })
        $jobResponse = Call-Api -Method 'POST' -Route "/api/posts/$postId/jobs" -Body @{
            socialAccountId=$row.social_account_id; scheduledAt=$scheduled
        }
        $jobId = if ($null -ne $jobResponse.data -and $jobResponse.data.id) { [string]$jobResponse.data.id } else { [string]$jobResponse.id }
        if (-not $jobId) { throw "Job may have been created for $postId but no ID returned. Inspect DB before retry." }
        $state.jobId=$jobId; $state.phase='JOB_SCHEDULED';Save-Checkpoint
        Write-Host "SCHEDULED $key job=$jobId at=$scheduled"
        Write-Host 'NOTE: REEL/VIDEO jobs do NOT run in Bulk mode. Before publishing, configure SINGLE LIVE with exact new Job ID, Page allowlist, and media-type feature flag.'
    }
    Start-Sleep -Milliseconds 250
}
Write-Host "COMPLETED rows=$($selected.Count) checkpoint=$CheckpointFile"
Write-Host 'Default mode only creates DRAFT posts and correct media. No Facebook publishing is triggered by the script itself.'
