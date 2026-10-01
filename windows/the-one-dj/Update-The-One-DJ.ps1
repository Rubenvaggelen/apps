$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$app = Join-Path $root 'app'
$localPath = Join-Path $root 'version.json'
$api = 'https://api.github.com/repos/Rubenvaggelen/apps/contents/windows/the-one-dj'
$headers = @{ 'User-Agent'='The-One-DJ-Windows'; 'Accept'='application/vnd.github+json' }
$log = Join-Path $root 'update.log'

function Read-RemoteFile([string]$name) {
  $meta = Invoke-RestMethod "$api/$name?ref=main" -Headers $headers
  return [Convert]::FromBase64String(($meta.content -replace '\s',''))
}

try {
  $meta = Invoke-RestMethod "$api/version.json?ref=main" -Headers $headers
  $remote = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String(($meta.content -replace '\s',''))
  ) | ConvertFrom-Json

  $localBuild = 0
  if (Test-Path $localPath) {
    try { $localBuild = [int]((Get-Content $localPath -Raw | ConvertFrom-Json).build) } catch {}
  }

  if ([int]$remote.build -gt $localBuild) {
    New-Item -ItemType Directory -Force -Path $app | Out-Null
    $files = @($remote.files)
    if ($files.Count -eq 0) { $files = @('index.html') }

    foreach ($name in $files) {
      $name = [string]$name
      if ([string]::IsNullOrWhiteSpace($name)) { continue }

      $bytes = Read-RemoteFile $name
      if ($name -ieq 'index.html') {
        $dest = Join-Path $app 'index.html'
      } else {
        $dest = Join-Path $root ([IO.Path]::GetFileName($name))
      }

      $tmp = "$dest.update"
      [IO.File]::WriteAllBytes($tmp,$bytes)
      Move-Item $tmp $dest -Force
    }

    $remote | ConvertTo-Json -Depth 6 | Set-Content $localPath -Encoding UTF8
    Add-Content $log "$(Get-Date -Format s) updated to build $($remote.build): $($files -join ', ')"
  }
} catch {
  Add-Content $log "$(Get-Date -Format s) update check failed: $($_.Exception.Message)"
}
