# SocialHub: preview all ten imports; optional explicit creation of missing Jobs.
# Run without switches for read-only preview, or -Execute for guarded LIVE scheduling.
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$WorkspaceId = 'baf06195-dd4c-40c2-b461-e9eef4046cfb',
    [string]$BearerToken = '',
    [string]$StartDate = '2026-11-25',
    [string]$StartTime = '09:00',
    [ValidateRange(1,1440)][int]$IntervalMinutes = 90,
    [ValidateRange(1,100)][int]$MaxPostsPerDay = 5,
    [string]$Timezone = 'Asia/Ho_Chi_Minh',
    [string]$Mode = '',
    [string]$ReportPath = (Join-Path (Get-Location) 'socialhub-autoplan-10-imports-confirm-report.csv'),
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:Headers = @{ Accept = 'application/json' }
if ($BearerToken) { $script:Headers.Authorization = "Bearer $BearerToken" }

# Source of truth: the ten records returned by GET /api/workspaces/{workspaceId}/excel-imports.
# PageId is taken from the actual uploaded filename; verified against the
# connected social account returned by the API if that account is listed.
$imports = @(
    [pscustomobject]@{ Number=1;  ImportId='1216c7f2-e9ed-4660-909e-b14b4d5d3e24'; PageId='1026968390505000'; File='01_Cafe_quanh_Sai_Gon_1026968390505000.xlsx' }
    [pscustomobject]@{ Number=2;  ImportId='54aa08df-6395-4f3b-a112-f2c6b2a88524'; PageId='946702055190944';  File='02_Chuyen_Cafe_Sai_Gon_946702055190944.xlsx' }
    [pscustomobject]@{ Number=3;  ImportId='9e0582d7-0b41-4585-8845-714793e854ce'; PageId='997437103443726';  File='03_Hem_Cafe_SG_997437103443726.xlsx' }
    [pscustomobject]@{ Number=4;  ImportId='644fed76-ce9d-459d-b280-e315fc3d506c'; PageId='1018550027999398'; File='04_Lang_Thang_Cafe_SG_1018550027999398.xlsx' }
    [pscustomobject]@{ Number=5;  ImportId='2b36bc5a-d626-42b3-8371-9ee3dec0376a'; PageId='884633188075736';  File='05_Nhat_ky_Cafe_SG_884633188075736.xlsx' }
    [pscustomobject]@{ Number=6;  ImportId='28e5ef61-9d8f-4e0d-851e-31d9e5e5813f'; PageId='970246156167627';  File='06_Review_Cafe_Co_Tam_970246156167627.xlsx' }
    [pscustomobject]@{ Number=7;  ImportId='aa563f61-4875-4579-9e83-5e7313aeba1e'; PageId='854321041108746';  File='07_Sai_Gon_oi_i_Cafe_854321041108746.xlsx' }
    [pscustomobject]@{ Number=8;  ImportId='9db3ba92-f5de-486d-92db-0f226304d681'; PageId='859105647295646';  File='08_San_Cafe_Sai_Gon_859105647295646.xlsx' }
    [pscustomobject]@{ Number=9;  ImportId='f4d9cde5-bb11-443f-9c9d-6bad54b0376b'; PageId='960269540503363';  File='09_The_Daily_Cafe_960269540503363.xlsx' }
    [pscustomobject]@{ Number=10; ImportId='026b6256-b199-4142-86e6-4f710b37bd4c'; PageId='972931622567698';  File='10_Xe_May_Tiet_Kiem_Chi_Phi_972931622567698.xlsx' }
)

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
        Write-Verbose "CSRF endpoint: $($_.Exception.Message)"
    }
    $xsrf = $script:Session.Cookies.GetCookies($BaseUrl) |
        Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
    if ($null -ne $xsrf) {
        $script:Headers['X-XSRF-TOKEN'] = [System.Net.WebUtility]::UrlDecode($xsrf.Value)
    }
}

function Invoke-SH {
    param([ValidateSet('GET','POST')][string]$Method='GET',
          [Parameter(Mandatory=$true)][string]$Path, [object]$Body=$null)
    $opts = @{
        Uri = "$BaseUrl$Path"; Method = $Method; WebSession = $script:Session;
        Headers = $script:Headers; ErrorAction = 'Stop'; TimeoutSec = 120
    }
    if ($Method -eq 'POST') {
        Set-CsrfHeader
        $opts.ContentType = 'application/json; charset=utf-8'
        $opts.Body = ConvertTo-Json -InputObject $Body -Depth 12 -Compress
    }
    $res = Invoke-RestMethod @opts
    if ($null -ne $res -and $null -ne $res.PSObject.Properties['status'] -and
        [int]$res.status -ge 400) {
        throw "API status=$($res.status): $($res.message)"
    }
    return $res
}

function Unwrap-Data {
    param($Response)
    if ($null -ne $Response -and $null -ne $Response.PSObject.Properties['data'] -and
        $null -ne $Response.data) { return $Response.data }
    return $Response
}

function Describe-Error {
    param($ErrorRecord)
    $m = [string]$ErrorRecord.Exception.Message
    if ($ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
        $m += ' | ' + [string]$ErrorRecord.ErrorDetails.Message
    }
    return $m
}

if (-not $BearerToken) {
    $email = Read-Host 'SocialHub login email'
    $secret = Read-Host 'SocialHub password' -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    Set-CsrfHeader
    try { $null = Invoke-SH -Method POST -Path '/api/auth/login' -Body @{email=$email; password=$password} }
    finally { Remove-Variable password -ErrorAction SilentlyContinue }
}
$me = Unwrap-Data (Invoke-SH -Path '/api/auth/me')
Write-Host "Authenticated as: $($me.email) | role=$($me.role)"

# One GET for mappings; failure is reported, not silently treated as matching.
$accountById = @{}
try {
    $accountsResult = Unwrap-Data (Invoke-SH -Path "/api/workspaces/$WorkspaceId/social-accounts")
    $accountList = @($accountsResult)
    if ($null -ne $accountsResult -and $null -ne $accountsResult.PSObject.Properties['content']) {
        $accountList = @($accountsResult.content)
    }
    foreach ($account in $accountList) {
        if ($null -ne $account -and $account.id) {
            $accountById[[string]$account.id] = $account
        }
    }
    Write-Host "Social accounts available for binding check: $($accountById.Count)"
} catch {
    Write-Warning "Could not read social accounts for binding check: $(Describe-Error $_)"
}

$payload = @{
    mode='KEEP_FUTURE_RESCHEDULE_EXPIRED'
    startDate=$StartDate
    startTime=$StartTime
    intervalMinutes=$IntervalMinutes
    maxPostsPerDay=$MaxPostsPerDay
    timezone=$Timezone
    maxRows=100
    dryRun=$true
    confirmLive=$false
}
if (-not [string]::IsNullOrWhiteSpace($Mode)) { $payload.mode=$Mode }

Write-Host "Preparing 10 imports | date=$StartDate time=$StartTime | $MaxPostsPerDay jobs/day/account | $IntervalMinutes minutes apart"
Write-Host 'All 10 imports must pass preview and match their known Facebook Page IDs.'
Write-Host 'No database changes or Facebook requests in preview stage.'

$previews = New-Object 'System.Collections.Generic.List[object]'
$report = New-Object 'System.Collections.Generic.List[object]'
$distinctAccounts = New-Object 'System.Collections.Generic.HashSet[string]'
$valid = $true
foreach ($imp in $imports) {
    $row = [ordered]@{
        Number=$imp.Number; ImportId=$imp.ImportId; File=$imp.File
        PageId=$imp.PageId; SocialAccountId=''; Preview='ERROR'
        Eligible=0; Existing=0; Skipped=0; Blocked=0; CreatedJobs=0
        Execution='NOT_RUN'; Error=''
    }
    try {
        $raw = Invoke-SH -Method POST -Path "/api/workspaces/$WorkspaceId/excel-imports/$($imp.ImportId)/auto-plan/batch" -Body $payload
        $data = Unwrap-Data $raw
        if ($null -eq $data -or $data.dryRun -ne $true) { throw 'Expected a dryRun=true response' }
        if ([string]$data.importId -ne $imp.ImportId) { throw 'Import ID mismatch' }
        $accountId = [string]$data.socialAccountId
        if ([string]::IsNullOrWhiteSpace($accountId)) { throw 'Missing socialAccountId' }
        if (-not $accountById.ContainsKey($accountId)) { throw "Social account not found: $accountId" }
        $account = $accountById[$accountId]
        if ([string]$account.externalAccountId -ne $imp.PageId) { throw 'External Facebook Page ID mismatch' }
        if ([string]$account.status -ne 'CONNECTED') { throw 'Social account is not CONNECTED' }
        if ([string]$account.platform -ne 'FACEBOOK_PAGE') { throw 'Social account platform is not FACEBOOK_PAGE' }
        if (-not $distinctAccounts.Add($accountId)) { throw 'A social account is repeated across imports' }
        if ([string]::IsNullOrWhiteSpace([string]$data.previewToken)) { throw 'Missing previewToken' }
        if ($null -eq $data.rows) { throw 'Missing preview rows' }

        $actions = @($data.rows | Where-Object { $_.action -in @('RESCHEDULED','KEEP_ORIGINAL') })
        $badMedia = @($actions | Where-Object { $_.mediaReady -ne $true })
        $needsApproval = @($actions | Where-Object { $_.automaticApprovalRequired -eq $true })
        $pending = [int]$data.rescheduled + [int]$data.keepOriginal
        $already = [int]$data.alreadyPlanned
        $skipped = [int]$data.skipped
        $blocked = [int]$data.blocked
        if ($blocked -ne 0 -or [int]$data.notSelected -ne 0) { throw "Blocked=$blocked or notSelected=$($data.notSelected)" }
        if ($badMedia.Count -gt 0 -or $needsApproval.Count -gt 0) { throw "Media not ready=$($badMedia.Count); missing approvals=$($needsApproval.Count)" }
        if ([int]$data.selectedRows -ne $pending) { throw 'Selected rows do not match eligible actions' }
        if ($actions.Count -ne $pending) { throw 'Eligible action rows do not match summary' }
        if ($pending + $already + $skipped -ne 100) { throw "Expected 100 source rows; got pending=$pending existing=$already skipped=$skipped" }
        if ($null -ne $data.errors -and @($data.errors).Count -gt 0) { throw ('Backend errors: ' + (@($data.errors) -join '; ')) }

        $row.SocialAccountId = $accountId
        $row.Preview = 'OK'
        $row.Eligible = $pending
        $row.Existing = $already
        $row.Skipped = $skipped
        $row.Blocked = $blocked
        $previews.Add([pscustomobject]@{ Import=$imp; AccountId=$accountId; Data=$data; NewJobs=$pending })
        Write-Host ('[{0:00}/10] PREVIEW PASS | newJobs={1} existing={2} skipped={3} | page MATCH' -f $imp.Number,$pending,$already,$skipped)
    } catch {
        $valid = $false
        $row.Error = Describe-Error $_
        Write-Warning ('[{0:00}/10] PREVIEW FAILED: {1}' -f $imp.Number,$row.Error)
    }
    $report.Add([pscustomobject]$row)
}

$newTotal = ($report | Measure-Object -Property Eligible -Sum).Sum
$alreadyTotal = ($report | Measure-Object -Property Existing -Sum).Sum
$skipTotal = ($report | Measure-Object -Property Skipped -Sum).Sum
Write-Host ''
Write-Host "SUMMARY PREVIEW: valid=$valid | distinctAccounts=$($distinctAccounts.Count) | newJobs=$newTotal | existing=$alreadyTotal | skipped=$skipTotal"
if (-not $valid -or $distinctAccounts.Count -ne 10 -or $previews.Count -ne 10) {
    $report | Export-Csv -Path $ReportPath -NoTypeInformation -Encoding UTF8
    throw 'Safety stop: at least one preview/account did not pass; no Jobs created.'
}
if (-not $Execute) {
    $report | Export-Csv -Path $ReportPath -NoTypeInformation -Encoding UTF8
    Write-Host "PREVIEW ONLY. Report: $ReportPath"
    Write-Host 'To explicitly schedule, run again with -Execute; this will request manual confirmation.'
    return
}
if ($newTotal -eq 0) {
    Write-Host 'No missing Jobs; nothing to schedule.'
    $report | Export-Csv -Path $ReportPath -NoTypeInformation -Encoding UTF8
    return
}

Write-Warning "LIVE SCHEDULING: You are about to create $newTotal real Publishing Jobs for 10 connected Facebook Pages."
Write-Warning "If your LIVE worker is active, these Jobs can publish actual posts automatically at their scheduled times starting $StartDate."
Write-Warning 'The script does not START any Bulk Run. Still, confirm the scheduled content is authorized for all 10 pages.'
$expected = "CREATE $newTotal JOBS"
$answer = Read-Host "Type exactly '$expected' to authorize scheduling"
if ($answer -cne $expected) {
    $report | Export-Csv -Path $ReportPath -NoTypeInformation -Encoding UTF8
    Write-Host 'Cancelled. No Jobs created.'
    return
}

$createdTotal = 0
foreach ($item in $previews) {
    $imp = $item.Import
    $entry = @($report | Where-Object { $_.ImportId -eq $imp.ImportId })[0]
    if ($item.NewJobs -eq 0) { $entry.Execution = 'NOTHING_TO_CREATE'; continue }
    $confirmPayload = @{}
    foreach ($k in $payload.Keys) { $confirmPayload[$k]=$payload[$k] }
    $confirmPayload.dryRun = $false
    $confirmPayload.confirmLive = $true
    $confirmPayload.previewToken = [string]$item.Data.previewToken
    try {
        $confirmed = Unwrap-Data (Invoke-SH -Method POST -Path "/api/workspaces/$WorkspaceId/excel-imports/$($imp.ImportId)/auto-plan/batch" -Body $confirmPayload)
        if ($null -eq $confirmed -or $confirmed.dryRun -ne $false) { throw 'Confirmation response did not say dryRun=false' }
        if ([string]$confirmed.importId -ne $imp.ImportId -or [string]$confirmed.socialAccountId -ne $item.AccountId) { throw 'Confirmation responded with wrong import/account' }
        $entry.CreatedJobs = [int]$confirmed.createdJobs
        $createdTotal += [int]$confirmed.createdJobs
        if ([int]$confirmed.blocked -ne 0 -or ($null -ne $confirmed.errors -and @($confirmed.errors).Count -gt 0) -or [int]$confirmed.createdJobs -ne $item.NewJobs) {
            throw "Partial result: created=$($confirmed.createdJobs), expected=$($item.NewJobs), blocked=$($confirmed.blocked), errors=$(@($confirmed.errors) -join '; ')"
        }
        $entry.Execution = 'CREATED'
        Write-Host ('[{0:00}/10] CONFIRMED | created={1}' -f $imp.Number,$entry.CreatedJobs)
    } catch {
        $entry.Execution = 'FAILED_OR_PARTIAL'
        $entry.Error = Describe-Error $_
        Write-Warning ('[{0:00}/10] ERROR: {1}' -f $imp.Number,$entry.Error)
        Write-Warning 'Stopping now. Some Jobs may already exist. The next run will preview them and avoid duplication.'
        break
    } finally {
        $report | Export-Csv -Path $ReportPath -NoTypeInformation -Encoding UTF8
    }
}
Write-Host "SUMMARY EXECUTION: createdJobs=$createdTotal | see report: $ReportPath"
Write-Host 'No Facebook Bulk Run was created or started by this script.'
