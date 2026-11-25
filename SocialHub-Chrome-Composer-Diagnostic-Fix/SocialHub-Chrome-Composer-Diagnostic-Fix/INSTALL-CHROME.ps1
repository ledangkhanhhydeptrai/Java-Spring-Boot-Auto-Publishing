param(
  [string]$ExtensionPath
)
$ErrorActionPreference = 'Stop'

$source = Join-Path $PSScriptRoot 'extension\content.js'
$expectedPatchHash = 'F8C97C58006D83012B2FE531F415D950FFB3D2E2FCAAE78027F7090AFADE6D9D'
$allowedHashes = @(
  'ED25AF1BEFB713AE9C499CA904C9BD2C0A2EBB93AF575A5B85F8F04E2BCCFF0B',
  'B125E6C8E2BF8C0EC921512CCE9A167C58ECC77FEA96E7FFFD371488F2B64947'
)
if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $expectedPatchHash) {
  throw 'Package content.js checksum mismatch; installation aborted.'
}

function Verify-Extension([string]$folder) {
  if (-not $folder -or -not (Test-Path -LiteralPath (Join-Path $folder 'manifest.json') -PathType Leaf)) { return $false }
  try {
    $manifest = Get-Content -LiteralPath (Join-Path $folder 'manifest.json') -Raw | ConvertFrom-Json
    return $manifest.name -like 'SocialHub*Facebook Group One-Task Pilot*' -and
      (Test-Path -LiteralPath (Join-Path $folder 'content.js') -PathType Leaf)
  } catch { return $false }
}

# Inspect the unpacked-extension paths recorded by active Chrome profiles.
# Never guess a different unpacked folder or modify Chrome preferences.
if (-not $ExtensionPath) {
  $root = Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data'
  if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw 'Cannot find Chrome profile. Specify -ExtensionPath from chrome://extensions > Details > Loaded from.'
  }
  $candidates = @()
  foreach ($folder in (Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue)) {
    if ($folder.Name -ne 'Default' -and $folder.Name -notmatch '^Profile \d+$') { continue }
    $prefsPath = Join-Path $folder.FullName 'Preferences'
    if (-not (Test-Path -LiteralPath $prefsPath)) { continue }
    try {
      $prefs = Get-Content -LiteralPath $prefsPath -Raw | ConvertFrom-Json
      if (-not $prefs.extensions.settings) { continue }
      foreach ($item in $prefs.extensions.settings.PSObject.Properties) {
        $record = $item.Value
        $path = $record.path
        if ($path -and -not [System.IO.Path]::IsPathRooted($path)) {
          $path = Join-Path $folder.FullName $path
        }
        if (Verify-Extension $path) {
          $candidates += [pscustomobject]@{Profile=$folder.Name;Path=$path;ID=$item.Name}
        }
      }
    } catch {
      Write-Warning "Could not read Chrome Preferences in $($folder.Name): $($_.Exception.Message)"
    }
  }
  $uniquePaths = @($candidates | Select-Object -ExpandProperty Path -Unique)
  if ($uniquePaths.Count -ne 1) {
    Write-Host "Found $($uniquePaths.Count) SocialHub unpacked Chrome extension paths:"
    $candidates | Format-Table -AutoSize | Out-Host
    throw 'Choose the active Chrome extension folder shown in chrome://extensions > Details > Loaded from; rerun with -ExtensionPath <folder>. No changes made.'
  }
  $ExtensionPath = $uniquePaths[0]
}
if (-not (Verify-Extension $ExtensionPath)) { throw "Not the SocialHub Pilot extension folder: $ExtensionPath" }

$target = Join-Path $ExtensionPath 'content.js'
$oldHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
Write-Host "Chrome extension source: $ExtensionPath"
Write-Host "Existing SHA256: $oldHash"
if ($oldHash -eq $expectedPatchHash) {
  Write-Host 'Already patched, no file changed.'
  exit 0
}
if ($oldHash -notin $allowedHashes) {
  throw 'Unknown content.js version; refusing to overwrite. No changes made.'
}
$backup = "$target.bak-$(Get-Date -Format yyyyMMdd-HHmmss)"
Copy-Item -LiteralPath $target -Destination $backup
try {
  Copy-Item -LiteralPath $source -Destination $target -Force
  $installed = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
  if ($installed -ne $expectedPatchHash) { throw 'Installed content.js checksum mismatch' }
} catch {
  Copy-Item -LiteralPath $backup -Destination $target -Force
  throw
}
Write-Host "PATCH INSTALLED: $target"
Write-Host "Backup: $backup"
Write-Host "SHA256: $expectedPatchHash"
Write-Host 'Reload ONE SocialHub Pilot extension in chrome://extensions; close Facebook composer, Ctrl+F5 the group tab.'
Write-Host 'Then Preview > Fill only. NO live publish. Backend and Bridge unchanged.'
