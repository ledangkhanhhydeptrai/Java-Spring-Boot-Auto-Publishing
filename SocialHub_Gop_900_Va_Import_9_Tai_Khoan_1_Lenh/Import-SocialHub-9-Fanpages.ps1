# SocialHub: One command to import 9 separate 100-row Excel workbooks via the EXISTING API.
# No Facebook/Threads publishing, scheduling, media upload or backend modifications.
# Requires an account authorized as Workspace admin. Safe by default: -Import is required.
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$WorkspaceName = 'SocialHub Auto Test',
    [string]$DataDir = (Join-Path $PSScriptRoot 'excel_9'),
    [string]$Manifest = (Join-Path $PSScriptRoot 'manifest.csv'),
    [string]$PublishTime = '09:00',
    [switch]$Import
)
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
if (-not (Test-Path -LiteralPath $Manifest)) { throw "Missing manifest: $Manifest" }
if (-not (Test-Path -LiteralPath $DataDir)) { throw "Missing Excel directory: $DataDir" }
if ($PublishTime -notmatch '^([01][0-9]|2[0-3]):[0-5][0-9]$') { throw 'PublishTime must be HH:mm' }
$items = @(Import-Csv -LiteralPath $Manifest -Encoding UTF8)
if ($items.Count -ne 9) { throw "Expected 9 Fanpages, got $($items.Count)" }
if (@($items | Select-Object -ExpandProperty PageId -Unique).Count -ne 9 -or
    @($items | Select-Object -ExpandProperty SocialAccountId -Unique).Count -ne 9) {
    throw 'Duplicate Page ID / Social Account UUID in manifest.'
}
foreach($item in $items){
    $p = Join-Path $DataDir $item.File
    if (-not (Test-Path -LiteralPath $p)) { throw "Missing file: $p" }
    if (([IO.Path]::GetExtension($p)).ToLowerInvariant() -ne '.xlsx') { throw "Not xlsx: $p" }
}
Write-Host '=== SocialHub 9x100 Excel batch ==='
$items | Select-Object DisplayName, PageId, SocialAccountId, File | Format-Table -AutoSize
if (-not $Import) {
    Write-Host 'PLAN ONLY. To import 9 files with one command: .\Import-SocialHub-9-Fanpages.ps1 -Import'
    exit 0
}
Write-Warning 'This creates 900 real DRAFT Posts in the test DB if all nine imports succeed; it does NOT publish or schedule Posts.'
$confirmation = Read-Host 'Type IMPORT-900 to start'
if ($confirmation -cne 'IMPORT-900') { throw 'Cancelled: no imports executed' }
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Get-XsrfToken {
    $cookies = $session.Cookies.GetCookies($BaseUrl)
    $cookie = $cookies | Where-Object Name -eq 'XSRF-TOKEN' | Select-Object -First 1
    if (-not $cookie) { return '' }
    return [uri]::UnescapeDataString([string]$cookie.Value)
}
function Api {
    param([string]$Method, [string]$Path, $JsonBody = $null)
    $headers = @{ Accept = 'application/json' }
    $xsrf=Get-XsrfToken
    if ($xsrf) { $headers['X-XSRF-TOKEN']=$xsrf }
    $opts = @{ Method=$Method; Uri="$BaseUrl$Path"; WebSession=$session; Headers=$headers; ErrorAction='Stop' }
    if ($null -ne $JsonBody) {
        $opts['ContentType']='application/json; charset=utf-8'
        $opts['Body']=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $JsonBody -Depth 12 -Compress))
    }
    return Invoke-RestMethod @opts
}
function Unwrap($response) {
    if ($null -ne $response -and $response.PSObject.Properties.Name -contains 'data') { return $response.data }
    return $response
}
function Add-Utf8([IO.MemoryStream]$stream, [string]$s) {
    $bytes=[Text.Encoding]::UTF8.GetBytes($s)
    $stream.Write($bytes,0,$bytes.Length)
}
function Send-Xlsx {
    param([string]$WorkspaceId,[string]$SocialAccountId,[string]$FilePath)
    $boundary='----SocialHubBatch'+[guid]::NewGuid().ToString('N')
    $stream=New-Object IO.MemoryStream
    try {
        foreach ($kv in @(@('socialAccountId',$SocialAccountId), @('publishTime',$PublishTime), @('autoPublish','false'))) {
            Add-Utf8 $stream ("--$boundary`r`nContent-Disposition: form-data; name=`"$($kv[0])`"`r`n`r`n$($kv[1])`r`n")
        }
        $filename=[IO.Path]::GetFileName($FilePath)
        Add-Utf8 $stream ("--$boundary`r`nContent-Disposition: form-data; name=`"file`"; filename=`"$filename`"`r`nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`r`n`r`n")
        $content=[IO.File]::ReadAllBytes($FilePath)
        $stream.Write($content,0,$content.Length)
        Add-Utf8 $stream ("`r`n--$boundary--`r`n")
        $headers=@{ Accept='application/json' }
        $xsrf=Get-XsrfToken
        if ($xsrf) { $headers['X-XSRF-TOKEN']=$xsrf }
        return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/workspaces/$WorkspaceId/excel-imports" -WebSession $session -Headers $headers -ContentType "multipart/form-data; boundary=$boundary" -Body $stream.ToArray() -ErrorAction Stop
    } finally { $stream.Dispose() }
}
[void](Api 'GET' '/api/auth/csrf')
$email=Read-Host 'SocialHub login email'
$secure=Read-Host 'SocialHub login password' -AsSecureString
$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $password=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
try { [void](Api 'POST' '/api/auth/login' @{email=$email;password=$password}) }
finally { $password=$null }
[void](Api 'GET' '/api/auth/me')
$workspaces=@(Unwrap (Api 'GET' '/api/workspaces'))
$target=@($workspaces | Where-Object { $_.name -ceq $WorkspaceName })
if ($target.Count -ne 1) { throw "Expected exactly one Workspace '$WorkspaceName'; found $($target.Count)" }
$wid=[string]$target[0].id
$accounts=@(Unwrap (Api 'GET' "/api/workspaces/$wid/social-accounts"))
foreach($item in $items) {
    $matched=@($accounts | Where-Object { [string]$_.id -eq [string]$item.SocialAccountId })
    if ($matched.Count -ne 1 -or $matched[0].status -ne 'CONNECTED' -or
        $matched[0].platform -ne 'FACEBOOK_PAGE' -or
        [string]$matched[0].externalAccountId -ne [string]$item.PageId) {
        throw "STOP BEFORE IMPORT: account/page mismatch: $($item.DisplayName) ($($item.PageId))"
    }
}
# Local checkpoint helps avoid repeating already completed API calls.
$checkpointFile=Join-Path $PSScriptRoot 'import_900_checkpoint.csv'
$completed=@{}
if (Test-Path $checkpointFile) {
    foreach($row in (Import-Csv -LiteralPath $checkpointFile -Encoding UTF8)) {
        if ($row.ImportedRows -eq '100' -and $row.SkippedRows -eq '0') { $completed[$row.PageId]=$row }
    }
}
$records=New-Object System.Collections.Generic.List[object]
$existingRows=@()
if (Test-Path $checkpointFile) { $existingRows=@(Import-Csv -LiteralPath $checkpointFile -Encoding UTF8) }
foreach($r in $existingRows){$records.Add($r)}
foreach($item in $items){
    if ($completed.ContainsKey([string]$item.PageId)) {
        Write-Host "SKIP (checkpoint PASS): $($item.DisplayName)"
        continue
    }
    $path=Join-Path $DataDir $item.File
    Write-Host "IMPORTING 100 DRAFT: $($item.DisplayName) ($($item.PageId))"
    try {
        $res=Unwrap (Send-Xlsx -WorkspaceId $wid -SocialAccountId $item.SocialAccountId -FilePath $path)
    } catch {
        Write-Error "STOP: import failed for $($item.DisplayName): $($_.Exception.Message). Inspect import history and DB before rerun."
        throw
    }
    if ($null -eq $res.id) { throw 'Import response missing ID. Inspect DB before retry.' }
    $r=[pscustomobject]@{PageId=$item.PageId;DisplayName=$item.DisplayName;ImportId=$res.id;ImportedRows=$res.importedRows;SkippedRows=$res.skippedRows}
    $records.Add($r)
    $records | Export-Csv -Path $checkpointFile -NoTypeInformation -Encoding UTF8
    Write-Host "RESULT: imported=$($res.importedRows), skipped=$($res.skippedRows), importId=$($res.id)"
    if ([int]$res.importedRows -ne 100 -or [int]$res.skippedRows -ne 0) {
        throw "STOP: expected 100 imported, 0 skipped on $($item.DisplayName). No further import performed."
    }
}
Write-Host 'DONE: Nine-account import completed (or skipped from verified local checkpoint). Inspect PostgreSQL counts before scheduling.'
