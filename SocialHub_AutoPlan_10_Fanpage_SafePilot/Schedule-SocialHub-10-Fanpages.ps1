# SocialHub 10 Fanpages: preview/prepare existing Excel imported posts for future LIVE publishing.
# Uses existing POST /api/workspaces/{workspaceId}/excel-imports/{importId}/auto-plan/one.
# NEVER calls Facebook/Threads, activates Publisher, creates Bulk Runs or edits DB directly.
# Defaults to preview; -Commit is opt-in; first run 1 row/Fanpage only.
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$WorkspaceName = 'SocialHub Auto Test',
    [string]$Manifest = (Join-Path $PSScriptRoot 'fanpages.csv'),
    [ValidateRange(1,100)][int]$RowsPerFanpage = 1,
    [ValidateRange(1,10)][int]$PostsPerDay = 2,
    [ValidateRange(1,1440)][int]$IntervalMinutes = 360,
    [string]$StartDate = ([DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(7)).Date.AddDays(1).ToString('yyyy-MM-dd')),
    [string]$StartTime = '09:00',
    [switch]$Commit,
    [switch]$ReviewedContent
)
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
if ($BaseUrl -ne 'http://localhost:8081') { throw 'Refusing non-local API. Run against the existing local SocialHub 8081 only.' }
if (-not (Test-Path -LiteralPath $Manifest)) { throw "Missing manifest: $Manifest" }
$items = @(Import-Csv -LiteralPath $Manifest -Encoding UTF8)
if ($items.Count -ne 10) { throw "Expected 10 Fanpages; got $($items.Count)" }
if (@($items | Select-Object -ExpandProperty PageId -Unique).Count -ne 10) { throw 'Duplicate PageIds in manifest' }
if (@($items | Select-Object -ExpandProperty File -Unique).Count -ne 10) { throw 'Duplicate source file names in manifest' }
if ($StartTime -notmatch '^([01][0-9]|2[0-3]):[0-5][0-9]$') { throw 'StartTime must be HH:mm' }
try { $start = [DateTime]::ParseExact("$StartDate $StartTime", 'yyyy-MM-dd HH:mm', [Globalization.CultureInfo]::InvariantCulture) }
catch { throw 'StartDate must be yyyy-MM-dd, StartTime HH:mm' }
if (($start.Hour * 60 + $start.Minute + ($PostsPerDay-1) * $IntervalMinutes + 45) -ge 1440) { throw 'Daily schedule crosses midnight' }
$first = [DateTimeOffset]::ParseExact(($start.ToString('yyyy-MM-ddTHH:mm:ss') + '+07:00'), 'yyyy-MM-ddTHH:mm:sszzz', [Globalization.CultureInfo]::InvariantCulture)
if ($first -le [DateTimeOffset]::UtcNow.AddMinutes(10)) { throw 'First scheduled slot must be at least 10 minutes in the future.' }
Write-Host '=== SocialHub 10 Fanpages Excel Auto Plan via existing API ==='
Write-Host "Mode: $(if ($Commit) {'CREATE PENDING JOBS'} else {'READ-ONLY PREVIEW'}) | Rows/Fanpage: $RowsPerFanpage | First slot: $first | Posts/day/page: $PostsPerDay"
Write-Warning 'The source posts have been imported as DRAFT with no dates. This uses auto-plan/one, not auto-plan/batch.'
Write-Warning 'Existing API will auto-submit + approve each Post on Commit and create real PENDING Jobs.'
Write-Warning 'The source files contain TEST text. Only Commit after verifying and authorizing the actual post contents.'
Write-Warning 'Ensure Backend DB is socialhub_test and ALL publishing workers are disabled before Commit.'
if ($Commit -and -not $ReviewedContent) { throw 'Commit blocked: include -ReviewedContent only after all selected content is reviewed and authorized for actual publication.' }

# Session and CSRF from the existing import script; do not hard-code credentials or OAuth tokens.
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Get-XsrfToken {
    $cookie = $session.Cookies.GetCookies($BaseUrl) | Where-Object Name -eq 'XSRF-TOKEN' | Select-Object -First 1
    if (-not $cookie) { return '' }
    return [uri]::UnescapeDataString([string]$cookie.Value)
}
function Api {
    param([string]$Method,[string]$Path,$JsonBody=$null)
    $headers = @{ Accept='application/json' }
    $xsrf = Get-XsrfToken
    if ($xsrf) { $headers['X-XSRF-TOKEN'] = $xsrf }
    $args = @{ Method=$Method; Uri="$BaseUrl$Path"; WebSession=$session; Headers=$headers; ErrorAction='Stop' }
    if ($null -ne $JsonBody) {
        $args['ContentType']='application/json; charset=utf-8'
        $args['Body']=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $JsonBody -Depth 8 -Compress))
    }
    return Invoke-RestMethod @args
}
function Unwrap($response) {
    if ($null -ne $response -and $response.PSObject.Properties.Name -contains 'data') { return $response.data }
    return $response
}
# Authenticate for authoritative listing even in preview: no write operation before -Commit.
[void](Api 'GET' '/api/auth/csrf')
$email = Read-Host 'SocialHub login email'
$secure = Read-Host 'SocialHub login password' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
try { [void](Api 'POST' '/api/auth/login' @{email=$email; password=$password}) }
finally { $password=$null; $secure=$null }
[void](Api 'GET' '/api/auth/me')
$workspaces = @(Unwrap (Api 'GET' '/api/workspaces'))
$matchWorkspace = @($workspaces | Where-Object { $_.name -ceq $WorkspaceName })
if ($matchWorkspace.Count -ne 1) { throw "Expected one workspace '$WorkspaceName', found $($matchWorkspace.Count)" }
$wid = [string]$matchWorkspace[0].id
$accounts = @(Unwrap (Api 'GET' "/api/workspaces/$wid/social-accounts"))
$imports = @(Unwrap (Api 'GET' "/api/workspaces/$wid/excel-imports"))
$targets = New-Object System.Collections.Generic.List[object]
foreach ($item in $items) {
    $a = @($accounts | Where-Object {
        [string]$_.externalAccountId -eq [string]$item.PageId -and
        $_.platform -eq 'FACEBOOK_PAGE' -and $_.status -eq 'CONNECTED'
    })
    if ($a.Count -ne 1) { throw "STOP: Page $($item.PageId) not found exactly once as CONNECTED FACEBOOK_PAGE" }
    if (-not [string]::IsNullOrWhiteSpace([string]$item.SocialAccountId) -and [string]$item.SocialAccountId -ne [string]$a[0].id) {
        throw "STOP: UUID mismatch on $($item.DisplayName)"
    }
    $i = @($imports | Where-Object { $_.fileName -ceq $item.File -and [int]$_.importedRows -eq 100 -and [int]$_.skippedRows -eq 0 })
    if ($i.Count -ne 1) { throw "STOP: Expected exactly one 100-row import for $($item.File), got $($i.Count)" }
    $targets.Add([pscustomobject]@{ DisplayName=$item.DisplayName; PageId=$item.PageId; SocialAccountId=[string]$a[0].id; ImportId=[string]$i[0].id; ImportedRows=100 })
}
Write-Host "Verified Workspace: $WorkspaceName ($wid)"
$targets | Format-Table DisplayName, PageId, ImportId -AutoSize
$plan = New-Object System.Collections.Generic.List[object]
$pageIndex = 0
foreach ($page in $targets) {
    for ($idx=0; $idx -lt $RowsPerFanpage; $idx++) {
        # Stagger accounts by 5 minutes so ten Pages are not all due at once.
        $date = $start.AddDays([int][Math]::Floor($idx / $PostsPerDay)).AddMinutes((($idx % $PostsPerDay) * $IntervalMinutes) + ($pageIndex * 5))
        $publishAt = $date.ToString('yyyy-MM-ddTHH:mm:ss') + '+07:00'
        $plan.Add([pscustomobject]@{ Fanpage=$page.DisplayName; PageId=$page.PageId; ImportId=$page.ImportId; RowNumber=($idx+2); PublishAt=$publishAt })
    }
    $pageIndex++
}
Write-Host "Prepared $($plan.Count) source-row slots. First sample:"
$plan | Select-Object -First 12 Fanpage, RowNumber, PublishAt | Format-Table -AutoSize
if (-not $Commit) { Write-Host 'PREVIEW ONLY: No Job created, no approval changed. To execute, add -Commit -ReviewedContent.'; exit 0 }
Write-Warning "You are about to create up to $($plan.Count) REAL pending publishing jobs and approve associated Posts. No network request to Meta is made by this script."
$confirmation = Read-Host "Type APPROVE-AND-SCHEDULE-$($plan.Count) to proceed"
if ($confirmation -cne "APPROVE-AND-SCHEDULE-$($plan.Count)") { throw 'Cancelled before any scheduling requests' }
$log = Join-Path $PSScriptRoot 'schedule_results.csv'
$results = New-Object System.Collections.Generic.List[object]
foreach ($item in $plan) {
    $at = [uri]::EscapeDataString($item.PublishAt)
    $path = "/api/workspaces/$wid/excel-imports/$($item.ImportId)/auto-plan/one?sourceRowNumber=$($item.RowNumber)&publishAt=$at&confirmLive=true"
    Write-Host "SCHEDULING $($item.Fanpage) row $($item.RowNumber) at $($item.PublishAt)"
    try {
        $r = Unwrap (Api 'POST' $path)
        $status = if ([int]$r.planned -eq 1 -and [int]$r.blocked -eq 0) {'JOB_CREATED'} elseif ([int]$r.alreadyPlanned -eq 1 -and [int]$r.blocked -eq 0) {'ALREADY_EXISTS'} else {'BLOCKED'}
        $note = (@($r.errors) -join '; ')
    } catch {
        $status='HTTP_ERROR'
        $note=$_.Exception.Message
    }
    $results.Add([pscustomobject]@{PageId=$item.PageId;ImportId=$item.ImportId;RowNumber=$item.RowNumber;PublishAt=$item.PublishAt;Status=$status;Note=$note})
    $results | Export-Csv -LiteralPath $log -NoTypeInformation -Encoding UTF8
    if ($status -notin @('JOB_CREATED','ALREADY_EXISTS')) { throw "STOP at $($item.Fanpage) row $($item.RowNumber): $status $note. Inspect DB before retry; do NOT create new Jobs manually." }
    Start-Sleep -Milliseconds 150
}
Write-Host "DONE: $($plan.Count) rows completed (created or already existed). Log: $log"
Write-Host 'Check DB: PENDING Jobs per Facebook Page, approval states, schedule spread and zero duplicates BEFORE enabling any LIVE worker.'
