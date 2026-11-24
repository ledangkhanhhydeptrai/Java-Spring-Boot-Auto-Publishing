param(
    [Parameter(Mandatory = $true)]
    [string]$FrontendPath,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$frontend = (Resolve-Path -LiteralPath $FrontendPath).Path
$source = Join-Path $PSScriptRoot 'src\features\excel-imports'
$target = Join-Path $frontend 'src\features\excel-imports'

if (-not (Test-Path -LiteralPath (Join-Path $frontend 'package.json'))) {
    throw "Không thấy package.json tại $frontend"
}
foreach ($relative in @('components\GoogleSheetsPreviewPanel.tsx', 'types\googleSheets.types.ts', 'hooks\useGoogleSheetsPreview.ts')) {
    if (-not (Test-Path -LiteralPath (Join-Path $target $relative))) {
        throw "Thiếu thành phần Google Sheets Preview hiện có: $relative. Dừng để tránh ghi đè sai dự án."
    }
}

$files = @(
    'components\GoogleSheetsPreviewPanel.tsx',
    'components\GoogleSheetsReadinessPanel.tsx',
    'utils\googleSheetsAudit.ts',
    'utils\googleSheetsReadiness.ts'
)
$backup = Join-Path (Split-Path -Parent $frontend) ("SocialHub-Preflight-Backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null

foreach ($relative in $files) {
    $old = Join-Path $target $relative
    $new = Join-Path $source $relative
    if (-not (Test-Path -LiteralPath $new)) { throw "Patch bị thiếu: $relative" }
    if (Test-Path -LiteralPath $old) {
        $oldBackup = Join-Path $backup $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $oldBackup) -Force | Out-Null
        Copy-Item -LiteralPath $old -Destination $oldBackup -Force
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $old) -Force | Out-Null
    Copy-Item -LiteralPath $new -Destination $old -Force
    Write-Host "Applied: $relative" -ForegroundColor Cyan
}
Write-Host "Backup: $backup" -ForegroundColor Yellow
if (-not $SkipBuild) {
    Push-Location $frontend
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "npm run build thất bại (exit code $LASTEXITCODE). Backup: $backup" }
        Write-Host "BUILD SUCCESS. Vào /excel-imports và chạy Đọc toàn bộ & Kiểm tra." -ForegroundColor Green
    } finally { Pop-Location }
} else {
    Write-Host "Đã bỏ qua build; hãy chạy npm run build trong frontend." -ForegroundColor Yellow
}
