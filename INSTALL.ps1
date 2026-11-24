param(
    [Parameter(Mandatory=$true)][string]$BackendPath,
    [string]$FrontendPath
)

$ErrorActionPreference = "Stop"
$bundle = Split-Path -Parent $MyInvocation.MyCommand.Path
$specs = @(
    @{ Kind = "backend"; Rel = "group-worker\worker.py"; Sha = "30e4bdfda2d60245370bbc92cfd99be105e4a9d406ef4137cd0f403c85d60268"; NewSha = "7e02d56a9d7e49414d15d4ad7b879bc4a5fb20ea1f5d311135f3a931b08e1d10" }
    @{ Kind = "backend"; Rel = "src\main\java\com\socialhub\grouppublishingtask\worker\GroupWorkerController.java"; Sha = "cdd7dfe50be85b3df0c0606f235da02806c695cb1a37f5b7da3795700f98452c"; NewSha = "ec9f4f5c2baea05766b36ac4d2d6527e53c167e69d1b38f2e2c22f4ab0a7b18e" }
    @{ Kind = "backend"; Rel = "src\main\java\com\socialhub\grouppublishingtask\controller\GroupPublishingTaskController.java"; Sha = "4dc38c41e33b981c9eb3760d705e703891f71fee9c677d4aaaa9d8be56888480"; NewSha = "ce24b4e3caced853b1735a3f4bb576e6f65bf378ce306520233a849f6724d6e3" }
    @{ Kind = "frontend"; Rel = "src\features\group-publishing-tasks\components\GroupPublishingTaskFeature.tsx"; Sha = "f8d76b79a8973c77ebae598fb316a3fcb41bb93b27ad0c0d01fdbe0a59fe7cac"; NewSha = "7fd768a03f5cb3aa8b75fb8662142ad48c5e605bc88d0569ff0d28d487df700a" }
    @{ Kind = "frontend"; Rel = "src\features\group-publishing-tasks\types\groupPublishingTask.types.ts"; Sha = "6aab8f38f35672b37be8f77b046585abff5e61a337550286faba6f37b61ab03b"; NewSha = "20bc914c156f832ceb8ada41a9e9504722c782846b6298bf7ee81c51e5874fdf" }
)

$plan = @()
foreach ($spec in $specs) {
    if ($spec.Kind -eq "frontend" -and [string]::IsNullOrWhiteSpace($FrontendPath)) { continue }
    $base = if ($spec.Kind -eq "backend") { $BackendPath } else { $FrontendPath }
    $dest = Join-Path $base $spec.Rel
    $src = Join-Path (Join-Path $bundle $spec.Kind) $spec.Rel
    if (!(Test-Path -LiteralPath $dest -PathType Leaf)) { throw "Not found: $dest" }
    if (!(Test-Path -LiteralPath $src -PathType Leaf)) { throw "Patch file missing: $src" }
    $actual = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -eq $spec.NewSha) { Write-Host "Already patched: $dest"; continue }
    if ($actual -ne $spec.Sha) { throw "VERSION MISMATCH; no files changed. Provide current source: $dest`nExpected: $($spec.Sha)`nGot: $actual" }
    $plan += @{ Source = $src; Destination = $dest }
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
foreach ($item in $plan) {
    $backup = "$($item.Destination).bak.group-pilot.$stamp"
    Copy-Item -LiteralPath $item.Destination -Destination $backup -ErrorAction Stop
    Copy-Item -LiteralPath $item.Source -Destination $item.Destination -Force -ErrorAction Stop
    Write-Host "PATCHED $($item.Destination)" -ForegroundColor Green
    Write-Host "BACKUP  $backup"
}

Write-Host "Done: $($plan.Count) file(s) updated. No process was restarted and no post was published." -ForegroundColor Cyan
Write-Host "Do not restart Backend while Threads/Fanpage jobs are actively publishing." -ForegroundColor Yellow
