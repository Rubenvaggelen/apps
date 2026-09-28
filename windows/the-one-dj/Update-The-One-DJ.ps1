$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$app = Join-Path $root 'app'
$localPath = Join-Path $root 'version.json'
$api = 'https://api.github.com/repos/Rubenvaggelen/apps/contents/windows/the-one-dj'
$headers = @{ 'User-Agent'='The-One-DJ-Windows'; 'Accept'='application/vnd.github+json' }
$log = Join-Path $root 'update.log'
try {
  $meta = Invoke-RestMethod "$api/version.json?ref=main" -Headers $headers
  $remote = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($meta.content -replace '\s',''))) | ConvertFrom-Json
  $localBuild = 0
  if (Test-Path $localPath) { try { $localBuild = [int]((Get-Content $localPath -Raw | ConvertFrom-Json).build) } catch {} }
  if ([int]$remote.build -gt $localBuild) {
    $fileMeta = Invoke-RestMethod "$api/index.html?ref=main" -Headers $headers
    $bytes = [Convert]::FromBase64String(($fileMeta.content -replace '\s',''))
    $tmp = Join-Path $root 'index.html.update'
    [IO.File]::WriteAllBytes($tmp,$bytes)
    $hash = (Get-FileHash $tmp -Algorithm SHA256).Hash
    if ($remote.sha256 -and $hash -ne $remote.sha256) { throw "Update hash klopt niet: $hash" }
    Move-Item $tmp (Join-Path $app 'index.html') -Force
    $remote | ConvertTo-Json -Depth 6 | Set-Content $localPath -Encoding UTF8
    Add-Content $log "$(Get-Date -Format s) updated to build $($remote.build)"
  }
} catch { Add-Content $log "$(Get-Date -Format s) update check failed: $($_.Exception.Message)" }