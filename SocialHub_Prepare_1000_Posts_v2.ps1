<#
SocialHub bulk post preparation using the OpenAPI spec provided on 2026-11-23.
- Default: READ-ONLY preview.
- -Execute: change ONLY matching DRAFT posts with NO existing Jobs to READY, then submit/approve via API.
- Loads publishing jobs once, matches by postId; does not infer jobs from API wrapper objects.
- Does NOT create Jobs, schedule posts, create/start Bulk Runs, or publish to Facebook.
- Filters exact SH-T10-A01..A10-P001..P100 identifiers in TOPIC, excluding other posts.
- Requires correct author/approver permissions and valid media, as enforced by Backend.

Examples (PowerShell, from the script folder):
  .\SocialHub_Prepare_1000_Posts.ps1
  .\SocialHub_Prepare_1000_Posts.ps1 -Execute
  .\SocialHub_Prepare_1000_Posts.ps1 -Execute -Limit 20
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$WorkspaceId = 'baf06195-dd4c-40c2-b461-e9eef4046cfb',
    [string]$BearerToken = '',
    [switch]$Execute,
    [ValidateRange(0,1000)][int]$Limit = 0,
    [ValidateRange(1,100)][int]$PageSize = 100,
    [ValidateRange(1,100)][int]$MaxErrors = 10,
    [string]$ReportPath = (Join-Path (Get-Location) 'socialhub-post-preparation-v2.csv')
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:Headers = @{ 'Accept' = 'application/json' }
if ($BearerToken) { $script:Headers['Authorization'] = "Bearer $BearerToken" }

function Set-CsrfHeader {
    if ($BearerToken) { return }
    try {
        $csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -Method Get -WebSession $script:Session -ErrorAction Stop
        if ($csrf.data -is [string] -and $csrf.data.Length -gt 0) {
            $script:Headers['X-XSRF-TOKEN'] = $csrf.data
        } elseif ($csrf.data -and $csrf.data.token) {
            $script:Headers['X-XSRF-TOKEN'] = $csrf.data.token
        }
    } catch {
        Write-Verbose "Could not read CSRF endpoint: $($_.Exception.Message)"
    }
    $xsrf = $script:Session.Cookies.GetCookies($BaseUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
    if ($null -ne $xsrf) {
        $script:Headers['X-XSRF-TOKEN'] = [System.Net.WebUtility]::UrlDecode($xsrf.Value)
    }
}

function Invoke-SH {
    param(
        [ValidateSet('GET','POST','PUT')][string]$Method = 'GET',
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null
    )
    $options = @{
        Uri = "$BaseUrl$Path"; Method = $Method;
        WebSession = $script:Session; Headers = $script:Headers;
        ErrorAction = 'Stop'; TimeoutSec = 60
    }
    if ($Method -ne 'GET') {
        Set-CsrfHeader
        if ($null -ne $Body) {
            $options['ContentType'] = 'application/json; charset=utf-8'
            $options['Body'] = (ConvertTo-Json -InputObject $Body -Depth 12 -Compress)
        }
    }
    $res = Invoke-RestMethod @options
    if ($null -ne $res -and $null -ne $res.PSObject.Properties['status']) {
        if ($res.status -is [int] -and $res.status -ge 400) { throw "API returned status=$($res.status): $($res.message)" }
    }
    return $res
}

if (-not $BearerToken) {
    $email = Read-Host 'SocialHub login email'
    $secret = Read-Host 'SocialHub password' -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    Set-CsrfHeader
    try {
        $null = Invoke-SH -Method POST -Path '/api/auth/login' -Body @{ email = $email; password = $password }
    } finally { Remove-Variable password -ErrorAction SilentlyContinue }
}

try {
    $me = Invoke-SH -Path '/api/auth/me'
    if ($me.data) { Write-Host "Authenticated as: $($me.data.email) | role=$($me.data.role)" }
} catch {
    throw "Cannot authenticate to SocialHub. Check credentials/session/CSRF or Bearer token: $($_.Exception.Message)"
}

$rows = New-Object 'System.Collections.Generic.List[object]'
$page = 0
while ($true) {
    $response = Invoke-SH -Path "/api/workspaces/$WorkspaceId/posts?page=$page&size=$PageSize"
    if (-not $response.data -or -not $response.data.content) {
        if ($page -eq 0) { throw 'Post list returned no page data; check API response or workspace.' }
        break
    }
    foreach ($post in @($response.data.content)) { $rows.Add($post) }
    $page++
    if ($page -ge [int]$response.data.totalPages) { break }
    if ($page -gt 500) { throw 'Pagination safety stop (over 500 pages).' }
}

$pattern = '\bSH-T10-A(0[1-9]|10)-P(\d{3})\b'
# PowerShell's -match operator overwrites the special $Matches variable; do not use that name here.
$targetPosts = New-Object 'System.Collections.Generic.List[object]'
foreach ($post in $rows) {
    $m = [regex]::Match([string]$post.topic, $pattern)
    if ($m.Success) {
        $number = [int]$m.Groups[2].Value
        if ($number -ge 1 -and $number -le 100) {
            $targetPosts.Add([pscustomobject]@{ Post = $post; Key = $m.Value; Group = $m.Groups[1].Value; Number = $number })
        }
    }
}
$byGroup = $targetPosts | Group-Object Group | Sort-Object Name
Write-Host "Total posts in workspace: $($rows.Count)"
Write-Host "Matching SH-T10 posts: $($targetPosts.Count)"
foreach ($g in $byGroup) { Write-Host ("Fanpage A{0}: {1} posts" -f $g.Name, $g.Count) }
$duplicateIds = @($targetPosts | Group-Object Key | Where-Object Count -gt 1)
$validGroups = ($byGroup.Count -eq 10 -and @($byGroup | Where-Object Count -ne 100).Count -eq 0)
$uniquePosts = (@($targetPosts | Select-Object -ExpandProperty Post | Select-Object -ExpandProperty id -Unique).Count -eq 1000)
if ($targetPosts.Count -ne 1000 -or $duplicateIds.Count -gt 0 -or -not $validGroups -or -not $uniquePosts) {
    throw 'Safety stop: expected exactly 1000 unique SH-T10 posts (A01-A10, 100 distinct identifiers each). No posts changed.'
}

# Fetch the workspace-level publishing jobs ONCE (OpenAPI: GET /api/workspaces/{workspaceId}/publishing-jobs).
# Do not rely on interpreting /api/posts/{postId}/jobs (an empty/wrapped response caused false positives).
$existingPostIds = New-Object 'System.Collections.Generic.HashSet[string]'
$allJobs = New-Object 'System.Collections.Generic.List[object]'
$jobPage = 0
while ($true) {
    $jobResponse = Invoke-SH -Path "/api/workspaces/$WorkspaceId/publishing-jobs?page=$jobPage&size=$PageSize"
    $jobPayload = $jobResponse
    if ($null -ne $jobResponse -and $null -ne $jobResponse.PSObject.Properties['data']) {
        $jobPayload = $jobResponse.data
    }
    if ($null -eq $jobPayload -or $null -eq $jobPayload.PSObject.Properties['content']) {
        throw 'Safety stop: publishing-jobs endpoint did not return a page with content; cannot safely identify existing Jobs.'
    }
    foreach ($j in @($jobPayload.content)) {
        if ($null -ne $j) {
            if ([string]::IsNullOrWhiteSpace([string]$j.postId)) {
                throw 'Safety stop: a publishing job has no postId; cannot safely identify linked Posts.'
            }
            $allJobs.Add($j)
            $null = $existingPostIds.Add([string]$j.postId)
        }
    }
    $jobPage++
    if ($null -eq $jobPayload.PSObject.Properties['totalPages']) {
        throw 'Safety stop: publishing-jobs pagination does not provide totalPages.'
    }
    if ($jobPage -ge [int]$jobPayload.totalPages) { break }
    if ($jobPage -gt 500) { throw 'Safety stop: publishing-jobs pagination exceeded 500 pages.' }
}
Write-Host "Existing Publishing Jobs in workspace: $($allJobs.Count) | distinct Post IDs: $($existingPostIds.Count)"
$linkedTargets = @($targetPosts | Where-Object { $existingPostIds.Contains([string]$_.Post.id) }).Count
Write-Host "SH-T10 Posts linked to Publishing Jobs: $linkedTargets"

$targets = @($targetPosts | Sort-Object Group, Number)
if ($Limit -gt 0) { $targets = @($targets | Select-Object -First $Limit) }
Write-Host "Target count: $($targets.Count) | Mode: $(if ($Execute) {'EXECUTE'} else {'PREVIEW ONLY'})"
$report = New-Object 'System.Collections.Generic.List[object]'
$failures = 0
foreach ($item in $targets) {
    $p = $item.Post
    $rec = [ordered]@{ Key = $item.Key; PostId = $p.id; InitialStatus = $p.status; FinalStatus = $p.status; ApprovalStatus = ''; Result = 'SKIPPED'; Error = '' }
    try {
        if ($p.status -notin @('DRAFT','READY')) {
            $rec.Result = 'SKIP_NON_DRAFT_OR_READY'
        } elseif ($p.scheduledAt) {
            $rec.Result = 'SKIP_ALREADY_SCHEDULED'
        } elseif ([string]::IsNullOrWhiteSpace([string]$p.content)) {
            $rec.Result = 'SKIP_EMPTY_CONTENT'
        } elseif ($existingPostIds.Contains([string]$p.id)) {
            $rec.Result = 'SKIP_EXISTING_JOBS'
        } elseif (-not $Execute) {
            $rec.Result = 'PREVIEW_ELIGIBLE'
        } else {
            # Backend independently validates approval, media, and queued/scheduled constraints.
            if ($p.status -eq 'DRAFT') {
                $payload = @{ topic = $p.topic; content = $p.content; status = 'READY' }
                $updated = Invoke-SH -Method PUT -Path "/api/workspaces/$WorkspaceId/posts/$($p.id)" -Body $payload
                $rec.FinalStatus = $updated.data.status
                if ($updated.data.status -ne 'READY') { throw "Unexpected post status after update: $($updated.data.status)" }
            }
            $approval = Invoke-SH -Path "/api/posts/$($p.id)/approval/status"
            if ($approval.approvalStatus -ne 'APPROVED') {
                if ($approval.approvalStatus -ne 'PENDING') {
                    $approval = Invoke-SH -Method POST -Path "/api/posts/$($p.id)/approval/submit"
                }
                if ($approval.approvalStatus -ne 'APPROVED') {
                    $approval = Invoke-SH -Method POST -Path "/api/posts/$($p.id)/approval/approve"
                }
            }
            $rec.ApprovalStatus = [string]$approval.approvalStatus
            if ($approval.approvalStatus -ne 'APPROVED') { throw "Approval response not APPROVED: $($approval.approvalStatus)" }
            $rec.Result = 'READY_AND_APPROVED'
        }
    } catch {
        $failures++
        $rec.Result = 'FAILED'
        $rec.Error = $_.Exception.Message
        Write-Warning ("{0}: {1}" -f $item.Key, $rec.Error)
    }
    $report.Add([pscustomobject]$rec)
    if ($Execute -and $failures -ge $MaxErrors) {
        Write-Warning "Safety stop: reached $MaxErrors failures; remaining posts not processed."
        break
    }
}
$report | Export-Csv -Path $ReportPath -NoTypeInformation -Encoding UTF8
Write-Host "Results:"
$report | Group-Object Result | Sort-Object Name | ForEach-Object { Write-Host ("  {0}: {1}" -f $_.Name, $_.Count) }
Write-Host "Report: $ReportPath"
if (-not $Execute) { Write-Host 'This was PREVIEW ONLY; use -Execute to make the described changes.' }
Write-Host 'This script does NOT create publishing jobs, schedules, Facebook runs, or external posts.'
Write-Host 'Verify approval/media readiness and account-to-post assignment before any LIVE bulk publishing.'
