$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$app = Join-Path $root 'app'
$localPath = Join-Path $root 'version.json'
$api = 'https://api.github.com/repos/Rubenvaggelen/apps/contents/windows/the-one-dj'
$headers = @{ 'User-Agent'='The-One-DJ-Windows'; 'Accept'='application/vnd.github+json'; 'Cache-Control'='no-cache' }
$log = Join-Path $root 'update.log'

function Log([string]$m) { Add-Content $log "$(Get-Date -Format s) $m" }

function Read-RemoteFile([string]$name) {
  $url = $api + '/' + [Uri]::EscapeDataString($name) + '?ref=main'
  $meta = Invoke-RestMethod $url -Headers $headers -TimeoutSec 30
  return [Convert]::FromBase64String(($meta.content -replace '\s',''))
}

function Ensure-Branding {
  try {
    $masterRoot = Join-Path $root 'dj-logo.jpg'
    if (!(Test-Path -LiteralPath $masterRoot)) { return }
    New-Item -ItemType Directory -Force -Path $app | Out-Null
    Copy-Item -LiteralPath $masterRoot -Destination (Join-Path $app 'dj-logo.jpg') -Force

    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($masterRoot)
    try {
      foreach ($size in @(192,512)) {
        $bmp = New-Object System.Drawing.Bitmap($size,$size)
        try {
          $g = [System.Drawing.Graphics]::FromImage($bmp)
          try {
            $g.Clear([System.Drawing.Color]::Black)
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.DrawImage($img,0,0,$size,$size)
          } finally { $g.Dispose() }
          $bmp.Save((Join-Path $app ("icon-"+$size+".png")),[System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $bmp.Dispose() }
      }

      $bmp = New-Object System.Drawing.Bitmap(256,256)
      try {
        $g=[System.Drawing.Graphics]::FromImage($bmp)
        try {
          $g.Clear([System.Drawing.Color]::Black)
          $g.InterpolationMode=[System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
          $g.DrawImage($img,0,0,256,256)
        } finally { $g.Dispose() }
        $hIcon=$bmp.GetHicon()
        try {
          $icon=[System.Drawing.Icon]::FromHandle($hIcon)
          $fs=[IO.File]::Create((Join-Path $root 'The-One-DJ.ico'))
          try { $icon.Save($fs) } finally { $fs.Dispose(); $icon.Dispose() }
        } finally {
          if(-not ('TheOneNative' -as [type])) {
            Add-Type 'using System; using System.Runtime.InteropServices; public static class TheOneNative { [DllImport("user32.dll")] public static extern bool DestroyIcon(IntPtr hIcon); }'
          }
          [TheOneNative]::DestroyIcon($hIcon) | Out-Null
        }
      } finally { $bmp.Dispose() }
    } finally { $img.Dispose() }

    $ico=(Join-Path $root 'The-One-DJ.ico')+',0'
    $target=Join-Path $root 'Start-The-One-DJ.cmd'
    $shortcutPaths=@(
      (Join-Path ([Environment]::GetFolderPath('Desktop')) 'The One DJ.lnk'),
      (Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs\The One Family\The One DJ.lnk')
    )
    $ws=New-Object -ComObject WScript.Shell
    foreach($lnk in $shortcutPaths){
      New-Item -ItemType Directory -Force -Path (Split-Path $lnk) | Out-Null
      $sc=$ws.CreateShortcut($lnk)
      $sc.TargetPath=$target
      $sc.WorkingDirectory=$root
      $sc.IconLocation=$ico
      $sc.Description='The One DJ'
      $sc.Save()
    }

    $settings=Join-Path $env:LOCALAPPDATA 'TheOne\Windows\settings.json'
    if(Test-Path -LiteralPath $settings){
      try{
        $j=Get-Content -LiteralPath $settings -Raw | ConvertFrom-Json
        foreach($a in @($j.CustomApps)){
          if($a.Label -match '(?i)^DJ$|The One DJ' -or $a.ExePath -match '(?i)Start-The-One-DJ|The One DJ\.lnk'){
            $a.Label='The One DJ'
            $a.ExePath=$shortcutPaths[1]
          }
        }
        [IO.File]::WriteAllText($settings,($j|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
      }catch{ Log "branding settings warning: $($_.Exception.Message)" }
    }
    Start-Process "$env:SystemRoot\System32\ie4uinit.exe" -ArgumentList '-show' -WindowStyle Hidden -ErrorAction SilentlyContinue
  } catch { Log "branding warning: $($_.Exception.Message)" }
}

try {
  $meta = Invoke-RestMethod "$api/version.json?ref=main" -Headers $headers -TimeoutSec 30
  $remote = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($meta.content -replace '\s',''))) | ConvertFrom-Json
  $localBuild = 0
  if (Test-Path $localPath) {
    try { $localBuild = [int]((Get-Content $localPath -Raw | ConvertFrom-Json).build) } catch {}
  }

  if ([int]$remote.build -gt $localBuild) {
    $backup = Join-Path $root ("backup-before-"+$remote.build+"-"+(Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force -Path $backup | Out-Null
    foreach($old in @((Join-Path $app 'index.html'),(Join-Path $root 'Serve-The-One-DJ.ps1'),(Join-Path $root 'Start-The-One-DJ.cmd'),(Join-Path $root 'Update-The-One-DJ.ps1'),$localPath)){
      if(Test-Path -LiteralPath $old){ Copy-Item -LiteralPath $old -Destination (Join-Path $backup ([IO.Path]::GetFileName($old))) -Force }
    }

    New-Item -ItemType Directory -Force -Path $app | Out-Null
    $files = @($remote.files)
    if ($files.Count -eq 0) { $files = @('index.html') }

    foreach ($name in $files) {
      $name = [string]$name
      if ([string]::IsNullOrWhiteSpace($name)) { continue }
      $bytes = Read-RemoteFile $name
      if ($name -ieq 'index.html') { $dest = Join-Path $app 'index.html' }
      else { $dest = Join-Path $root ([IO.Path]::GetFileName($name)) }
      $tmp = "$dest.update"
      [IO.File]::WriteAllBytes($tmp,$bytes)
      Move-Item $tmp $dest -Force
    }

    $remote | ConvertTo-Json -Depth 6 | Set-Content $localPath -Encoding UTF8
    Log "updated from $localBuild to build $($remote.build): $($files -join ', ')"
  } else {
    Log "already current: build $localBuild"
  }

  Ensure-Branding
} catch {
  Log "update check failed: $($_.Exception.Message)"
}
