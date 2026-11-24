<#
SocialHub - Dry Run all ten existing Excel imports. Tested against the supplied
OpenAPI schema (2026-11-23); NOT against the user's running localhost backend.

This script does NOT call the execution variant of auto-plan, create/update a
post/job, create/start a Bulk Run, or publish anything to Facebook.

Run: .\SocialHub_AutoPlan_10_Imports_DryRun.ps1
Optional: .\SocialHub_AutoPlan_10_Imports_DryRun.ps1 -Mode '<mode accepted by your Backend>'
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$WorkspaceId = 'baf06195-dd4c-40c2-b461-e9eef4046cfb',
    [string]$BearerToken = '',
    [string]$StartDate = (Get-Date).Date.AddDays(2).ToString('yyyy-MM-dd'),
    [string]$StartTime = '09:00',
    [ValidateRange(1,1440)][int]$IntervalMinutes = 90,
    [ValidateRange(1,100)][int]$MaxPostsPerDay = 5,
    [string]$Timezone = 'Asia/Ho_Chi_Minh',
    [string]$Mode = '',
    [string]$ReportPath = (Join-Path (Get-Location) 'socialhub-autoplan-10-imports-dryrun.csv')
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
    startDate=$StartDate; startTime=$StartTime; intervalMinutes=$IntervalMinutes;
    maxPostsPerDay=$MaxPostsPerDay; timezone=$Timezone;
    maxRows=100; dryRun=$true; confirmLive=$false
}
if (-not [string]::IsNullOrWhiteSpace($Mode)) { $payload.mode = $Mode }
Write-Host "AUTO-PLAN DRY RUN ONLY | imports=10 | 100 rows/import | date=$StartDate | time=$StartTime | timezone=$Timezone"
Write-Host 'No LIVE confirmation, no job creation, no publishing.'

$report = New-Object 'System.Collections.Generic.List[object]'
$distinctAccounts = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($imp in $imports) {
    $record = [ordered]@{
        Number=$imp.Number; File=$imp.File; ImportId=$imp.ImportId;
        ExpectedPageId=$imp.PageId; SocialAccountId=''; ActualPageId='';
        AccountCheck='UNVERIFIED'; SelectedRows=0; KeepOriginal=0;
        Rescheduled=0; AlreadyPlanned=0; Skipped=0; Blocked=0;
        NotSelected=0; ProposedJobs=0; NonReadyMediaRows='';
        ErrorCount=0; ErrorSamples=''; Result='ERROR'
    }
    try {
        $raw = Invoke-SH -Method POST -Path "/api/workspaces/$WorkspaceId/excel-imports/$($imp.ImportId)/auto-plan/batch" -Body $payload
        $data = Unwrap-Data $raw
        if ($null -eq $data -or $null -eq $data.PSObject.Properties['dryRun'] -or
            $data.dryRun -ne $true) {
            throw 'Safety stop: API response did not explicitly confirm dryRun=true.'
        }
        if ($data.importId -and [string]$data.importId -ne $imp.ImportId) {
            throw "Import ID mismatch, expected=$($imp.ImportId), got=$($data.importId)"
        }
        $accountId = [string]$data.socialAccountId
        $record.SocialAccountId = $accountId
        if (-not [string]::IsNullOrWhiteSpace($accountId)) {
            $null = $distinctAccounts.Add($accountId)
            if ($accountById.ContainsKey($accountId)) {
                $record.ActualPageId = [string]$accountById[$accountId].externalAccountId
                if ($record.ActualPageId) {
                    if ($record.ActualPageId -eq $imp.PageId) { $record.AccountCheck = 'MATCH' }
                    else { $record.AccountCheck = 'MISMATCH' }
                }
            }
        }
        foreach ($field in @('selectedRows','keepOriginal','rescheduled','alreadyPlanned','skipped','blocked','notSelected','createdJobs')) {
            if ($null -eq $data.PSObject.Properties[$field]) {
                throw "Missing $field from BatchPlanResult; cannot safely summarize."
            }
        }
        $record.SelectedRows = [int]$data.selectedRows
        $record.KeepOriginal = [int]$data.keepOriginal
        $record.Rescheduled = [int]$data.rescheduled
        $record.AlreadyPlanned = [int]$data.alreadyPlanned
        $record.Skipped = [int]$data.skipped
        $record.Blocked = [int]$data.blocked
        $record.NotSelected = [int]$data.notSelected
        $record.ProposedJobs = [int]$data.createdJobs
        if ($null -ne $data.PSObject.Properties['rows'] -and $null -ne $data.rows) {
            $record.NonReadyMediaRows = @($data.rows | Where-Object { $null -ne $_.PSObject.Properties['mediaReady'] -and $_.mediaReady -eq $false }).Count
        }
        $errors = @()
        if ($null -ne $data.errors) { $errors = @($data.errors) }
        $record.ErrorCount = $errors.Count
        $record.ErrorSamples = ($errors | Select-Object -First 3) -join ' || '
        $record.Result = if ($record.AccountCheck -eq 'MISMATCH') {'ACCOUNT_MISMATCH'} else {'DRY_RUN_OK'}
        Write-Host ("[{0:00}/10] selected={1} blocked={2} alreadyPlanned={3} proposed={4} account={5}" -f $imp.Number,$record.SelectedRows,$record.Blocked,$record.AlreadyPlanned,$record.ProposedJobs,$record.AccountCheck)
    } catch {
        $record.ErrorCount = 1
        $record.ErrorSamples = Describe-Error $_
        Write-Warning ("[{0:00}/10] {1}" -f $imp.Number, $record.ErrorSamples)
    }
    $report.Add([pscustomobject]$record)
}

$report | Export-Csv -Path $ReportPath -Encoding UTF8 -NoTypeInformation
$ok = @($report | Where-Object { $_.Result -eq 'DRY_RUN_OK' }).Count
$accountMismatches = @($report | Where-Object { $_.Result -eq 'ACCOUNT_MISMATCH' }).Count
$fail = @($report | Where-Object { $_.Result -eq 'ERROR' }).Count
$selectedTotal = ($report | Measure-Object -Property SelectedRows -Sum).Sum
$blockedTotal = ($report | Measure-Object -Property Blocked -Sum).Sum
$plannedTotal = ($report | Measure-Object -Property AlreadyPlanned -Sum).Sum
$proposedTotal = ($report | Measure-Object -Property ProposedJobs -Sum).Sum
Write-Host ''
Write-Host "SUMMARY: dryRunOK=$ok/10 | errors=$fail | accountMismatches=$accountMismatches | distinctSocialAccounts=$($distinctAccounts.Count)"
Write-Host "selected=$selectedTotal | blocked=$blockedTotal | alreadyPlanned=$plannedTotal | proposedJobs=$proposedTotal"
Write-Host "Report: $ReportPath"
Write-Host 'NO jobs, schedules, Bulk Runs, or Facebook posts were created by this script.'
if ($fail -gt 0 -or $accountMismatches -gt 0 -or $distinctAccounts.Count -ne 10) {
    Write-Warning 'Do not execute LIVE. Review errors, account binding, and proposed job counts first.'
}
