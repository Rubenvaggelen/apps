param([int]$Port=18765)
$ErrorActionPreference='Stop'
$root=Join-Path $env:LOCALAPPDATA 'Programs\The One Family\The One DJ\app'
$imports=Join-Path $root 'imports'
$prefix="http://127.0.0.1:$Port/"
$listener=[System.Net.HttpListener]::new()
$listener.Prefixes.Add($prefix)
try{$listener.Start()}catch{exit 0}

function Mime([string]$p){
  switch([IO.Path]::GetExtension($p).ToLowerInvariant()){
    '.html' {'text/html; charset=utf-8'}
    '.js' {'application/javascript; charset=utf-8'}
    '.json' {'application/json; charset=utf-8'}
    '.css' {'text/css; charset=utf-8'}
    '.png' {'image/png'}
    '.jpg' {'image/jpeg'}
    '.jpeg' {'image/jpeg'}
    '.webp' {'image/webp'}
    '.mp3' {'audio/mpeg'}
    '.m4a' {'audio/mp4'}
    '.aac' {'audio/aac'}
    '.wav' {'audio/wav'}
    '.flac' {'audio/flac'}
    '.ogg' {'audio/ogg'}
    '.opus' {'audio/opus'}
    default {'application/octet-stream'}
  }
}
function JsonResponse($ctx,$obj,[int]$status=200){
  $json=$obj | ConvertTo-Json -Depth 8 -Compress
  $bytes=[Text.Encoding]::UTF8.GetBytes($json)
  $ctx.Response.StatusCode=$status
  $ctx.Response.ContentType='application/json; charset=utf-8'
  $ctx.Response.Headers['Cache-Control']='no-store'
  $ctx.Response.ContentLength64=$bytes.Length
  $ctx.Response.OutputStream.Write($bytes,0,$bytes.Length)
  $ctx.Response.OutputStream.Close()
}
function IsAudio([string]$p){
  return ([IO.Path]::GetExtension($p).ToLowerInvariant() -in @('.mp3','.wav','.flac','.m4a','.aac','.ogg','.oga','.opus','.aif','.aiff','.aifc','.caf','.wma','.alac','.mka','.ac3','.amr','.mp4','.m4v','.mov','.webm','.mkv'))
}
function SafeLocalPath([string]$p){
  if([string]::IsNullOrWhiteSpace($p)){return $null}
  try{
    $full=[IO.Path]::GetFullPath($p)
    $root=[IO.Path]::GetPathRoot($full)
    if([string]::IsNullOrWhiteSpace($root) -or !(Test-Path -LiteralPath $root)){return $null}
    return $full
  }catch{return $null}
}
function ManifestPath(){ Join-Path $imports 'shared-media.json' }
function ReadManifest(){
  $m=ManifestPath
  if(!(Test-Path $m)){ return @() }
  try{
    $v=Get-Content $m -Raw | ConvertFrom-Json
    if($v -is [System.Array]){ return @($v) }
    if($null -eq $v){ return @() }
    return @($v)
  }catch{return @()}
}
function WriteManifest($items){
  New-Item -ItemType Directory -Force -Path $imports | Out-Null
  $json = if(@($items).Count -eq 0){'[]'}else{@($items) | ConvertTo-Json -Compress}
  Set-Content -Path (ManifestPath) -Value $json -Encoding UTF8
}
function SafeImportPath([string]$name){
  if([string]::IsNullOrWhiteSpace($name)){return $null}
  $leaf=[IO.Path]::GetFileName($name)
  if($leaf -ne $name){return $null}
  $p=[IO.Path]::GetFullPath((Join-Path $imports $leaf))
  $base=[IO.Path]::GetFullPath($imports)
  if(!$p.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)){return $null}
  return $p
}

while($listener.IsListening){
  try{
    $ctx=$listener.GetContext()
    $path=$ctx.Request.Url.AbsolutePath

    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/local-media/drives'){
      $drives=@(Get-PSDrive -PSProvider FileSystem | ForEach-Object {
        [pscustomobject]@{name=$_.Name;path=$_.Root;description=$_.Description}
      })
      JsonResponse $ctx @{ok=$true;drives=$drives}
      continue
    }

    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/local-media/list'){
      $requested=[Uri]::UnescapeDataString([string]$ctx.Request.QueryString['path'])
      $full=SafeLocalPath $requested
      if(!$full -or !(Test-Path -LiteralPath $full -PathType Container)){
        JsonResponse $ctx @{ok=$false;error='folder not found'} 404
        continue
      }
      $dirs=@(Get-ChildItem -LiteralPath $full -Directory -Force -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{name=$_.Name;path=$_.FullName}
      })
      $filesOut=@(Get-ChildItem -LiteralPath $full -File -Force -ErrorAction SilentlyContinue | Where-Object {IsAudio $_.FullName} | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{name=$_.Name;path=$_.FullName;size=$_.Length}
      })
      JsonResponse $ctx @{ok=$true;path=$full;directories=$dirs;files=$filesOut}
      continue
    }

    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/local-media/scan'){
      $requested=[Uri]::UnescapeDataString([string]$ctx.Request.QueryString['path'])
      $full=SafeLocalPath $requested
      if(!$full -or !(Test-Path -LiteralPath $full -PathType Container)){
        JsonResponse $ctx @{ok=$false;error='folder not found'} 404
        continue
      }
      $rows=@(Get-ChildItem -LiteralPath $full -Recurse -File -Force -ErrorAction SilentlyContinue | Where-Object {IsAudio $_.FullName} | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{name=$_.Name;path=$_.FullName;size=$_.Length}
      })
      JsonResponse $ctx @{ok=$true;path=$full;files=$rows}
      continue
    }

    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/local-media/file'){
      $requested=[Uri]::UnescapeDataString([string]$ctx.Request.QueryString['path'])
      $full=SafeLocalPath $requested
      if(!$full -or !(Test-Path -LiteralPath $full -PathType Leaf) -or !(IsAudio $full)){
        $ctx.Response.StatusCode=404;$ctx.Response.Close();continue
      }
      $bytes=[IO.File]::ReadAllBytes($full)
      $ctx.Response.ContentType=Mime $full
      $ctx.Response.ContentLength64=$bytes.Length
      $ctx.Response.Headers['Cache-Control']='no-store'
      $ctx.Response.OutputStream.Write($bytes,0,$bytes.Length)
      $ctx.Response.OutputStream.Close()
      continue
    }

    if($ctx.Request.HttpMethod -eq 'POST' -and $path -eq '/imports/clear'){
      $items=@(ReadManifest)
      foreach($name in $items){
        $p=SafeImportPath ([string]$name)
        if($p -and (Test-Path $p -PathType Leaf)){
          Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue
        }
      }
      WriteManifest @()
      $ctx.Response.StatusCode=204
      $ctx.Response.Close()
      continue
    }

    if($ctx.Request.HttpMethod -eq 'POST' -and $path -eq '/imports/remove'){
      $name=[string]$ctx.Request.QueryString['name']
      $p=SafeImportPath $name
      if($p -and (Test-Path $p -PathType Leaf)){
        Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue
      }
      $items=@(ReadManifest | Where-Object { [string]$_ -ne $name })
      WriteManifest $items
      $ctx.Response.StatusCode=204
      $ctx.Response.Close()
      continue
    }

    $raw=[Uri]::UnescapeDataString($path.TrimStart('/'))
    if([string]::IsNullOrWhiteSpace($raw)){$raw='index.html'}
    $full=[IO.Path]::GetFullPath((Join-Path $root $raw))
    $rootFull=[IO.Path]::GetFullPath($root)
    if(!$full.StartsWith($rootFull,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path $full -PathType Leaf)){
      $ctx.Response.StatusCode=404;$ctx.Response.Close();continue
    }
    $bytes=[IO.File]::ReadAllBytes($full)
    $ctx.Response.ContentType=Mime $full
    $ctx.Response.ContentLength64=$bytes.Length
    $ctx.Response.Headers['Cache-Control']='no-store'
    $ctx.Response.OutputStream.Write($bytes,0,$bytes.Length)
    $ctx.Response.OutputStream.Close()
  }catch{}
}
