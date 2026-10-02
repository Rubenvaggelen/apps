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
  if([IO.Path]::GetFileName($p).StartsWith('._')){return $false}
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


$sharedSettingsPath=Join-Path (Split-Path $root -Parent) 'shared-media-settings.json'
$script:sharedToken=''
$script:sharedTokenUntil=[DateTime]::MinValue
function SharedSettings(){
  if(!(Test-Path -LiteralPath $sharedSettingsPath)){throw 'Shared Media configuration missing'}
  return Get-Content -LiteralPath $sharedSettingsPath -Raw | ConvertFrom-Json
}
function SharedToken([bool]$force=$false){
  if(!$force -and $script:sharedToken -and [DateTime]::UtcNow -lt $script:sharedTokenUntil){return $script:sharedToken}
  $settings=SharedSettings
  $login=Invoke-RestMethod -Uri ($settings.endpoint+'?action=login') -Method Post -ContentType 'application/json' -Body (@{pin=$settings.pin}|ConvertTo-Json -Compress) -TimeoutSec 20
  if(!$login.token){throw 'Shared Media login failed'}
  $script:sharedToken=[string]$login.token
  $seconds=3600;if($login.expires_in){$seconds=[int]$login.expires_in}
  $script:sharedTokenUntil=[DateTime]::UtcNow.AddSeconds([Math]::Max(30,$seconds-120))
  return $script:sharedToken
}
function SharedCatalog(){
  $settings=SharedSettings
  for($attempt=0;$attempt -lt 2;$attempt++){
    $token=SharedToken ($attempt -gt 0)
    try{return Invoke-RestMethod -Uri ($settings.endpoint+'?action=catalog') -Headers @{Authorization=('Bearer '+$token)} -TimeoutSec 30}
    catch{if($attempt -eq 1 -or !$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401){throw}}
  }
}
function DjDevice(){return 'windows-'+([regex]::Replace([Environment]::MachineName,'[^\p{L}\p{N}._-]','-').Trim('-')).ToLowerInvariant()}
function DjReceiptPath(){return Join-Path (Split-Path $root -Parent) 'dj-inbox-receipts.json'}
function SaveDjReceipts($rows){
  $path=DjReceiptPath;$map=@{}
  if(!$rows.Count -and (Test-Path -LiteralPath $path)){return}
  $stored=@()
  if(Test-Path -LiteralPath $path){
    if((Get-Item -LiteralPath $path).Length -gt 16777216){throw 'DJ receipt archive needs recovery'}
    $stored=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
  }
  foreach($row in @($stored)+@($rows)){
    if($row.id -isnot [string] -or !$row.source -or $row.source.kind -isnot [string]){continue}
    $source=@{kind=[string]$row.source.kind}
    foreach($key in @('device','stick','path','name')){if($row.source.$key -is [string]){$source[$key]=[string]$row.source.$key}}
    $plain=@{id=[string]$row.id;name=[string]$row.name;source=$source}
    if($row.queue_id -is [string]){$plain.queue_id=[string]$row.queue_id}
    $map[[string]$row.id]=$plain
  }
  $json=ConvertTo-Json -InputObject @($map.Values) -Depth 8 -Compress
  [IO.File]::WriteAllText($path+'.tmp',$json,[Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath ($path+'.tmp') -Destination $path -Force
}

function DjInbox(){
  $settings=SharedSettings;$token=SharedToken
  $control=$null
  try{
    $state=Invoke-RestMethod -Uri ($settings.endpoint+'?action=dj-playlist-state&request_device_id='+[Uri]::EscapeDataString((DjDevice))) -Headers @{Authorization=('Bearer '+$token)} -TimeoutSec 15
    $control=$state.control
  }catch{throw 'DJ control unavailable'}
  $controlPath=Join-Path (Split-Path $root -Parent) 'dj-inbox-control.json'
  $previous=$null
  if(Test-Path -LiteralPath $controlPath){$previous=Get-Content -LiteralPath $controlPath -Raw|ConvertFrom-Json}
  if($control.clear_id -and $previous.clear_id -ne $control.clear_id){
    [IO.File]::WriteAllText((DjReceiptPath),'[]',[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($controlPath,($control|ConvertTo-Json -Depth 8 -Compress),[Text.UTF8Encoding]::new($false))
  }
  $items=New-Object Collections.Generic.List[object]
  try{
    $queue=Invoke-RestMethod -Uri ($settings.endpoint+'?action=dj-queue-list&include_delivered=1&request_device_id='+[Uri]::EscapeDataString((DjDevice))) -Headers @{Authorization=('Bearer '+$token)} -TimeoutSec 15
    foreach($item in $queue.items){
      if(!$item.id -or !$item.device_id -or !$item.stick_id -or !$item.path){continue}
      $name=[string]$item.name;if(!$name){$name=[IO.Path]::GetFileName([string]$item.path)}
      $items.Add(@{id=('queue-'+$item.id);queue_id=$item.id;name=$name;source=@{kind='shared';device=$item.device_id;stick=$item.stick_id;path=$item.path}})
    }
  }catch{}
  SaveDjReceipts ($items.ToArray())
  $items.Clear()
  $receipts=Get-Content -LiteralPath (DjReceiptPath) -Raw|ConvertFrom-Json;foreach($receipt in $receipts){if($receipt.id -and $receipt.source){$items.Add($receipt)}}
  # The old Windows background worker may already have consumed the queue.
  # Recover its manifest as references without reading any audio files.
  $names=if($control.clear_id){@()}else{@(ReadManifest)}
  if($names.Count -gt 0){
    $map=@{}
    try{
      if(!$script:inboxCatalogUntil -or [DateTime]::UtcNow -gt $script:inboxCatalogUntil){
        $script:inboxCatalog=SharedCatalog;$script:inboxCatalogUntil=[DateTime]::UtcNow.AddSeconds(60)
      }
      $sha=[Security.Cryptography.SHA256]::Create()
      try{
        foreach($stick in $script:inboxCatalog.sticks){foreach($file in $stick.files){
          $identity=[string]$stick.device_id+"`n"+[string]$stick.stick_id+"`n"+[string]$file.path
          $hash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($identity)))).Replace('-','').ToLowerInvariant().Substring(0,12)
          $map[$hash]=@{kind='shared';device=$stick.device_id;stick=$stick.stick_id;path=$file.path}
        }}
      }finally{$sha.Dispose()}
    }catch{}
    foreach($name in $names){
      $p=SafeImportPath ([string]$name);if(!$p -or !(Test-Path -LiteralPath $p)){continue}
      $prefix=([string]$name).Split('-')[0]
      $source=$map[$prefix]
      if(!$source){$source=@{kind='import';name=[string]$name}}
      $clean=[regex]::Replace([string]$name,'^[0-9a-f]{12}-','')
      $items.Add(@{id=('legacy-'+[string]$name);name=$clean;source=$source})
    }
  }
  return @{items=@($items.ToArray());control=$control}
}
function DjAck([string]$id){
  if(!$id){throw 'Missing queue item'}
  $settings=SharedSettings;$token=SharedToken
  $null=Invoke-RestMethod -Uri ($settings.endpoint+'?action=dj-queue-ack') -Method Post -Headers @{Authorization=('Bearer '+$token)} -ContentType 'application/json' -Body (@{id=$id;request_device_id=(DjDevice)}|ConvertTo-Json -Compress) -TimeoutSec 15
}
function SharedFile($ctx){
  $settings=SharedSettings
  $device=[string]$ctx.Request.QueryString['device'];$stick=[string]$ctx.Request.QueryString['stick'];$track=[string]$ctx.Request.QueryString['path']
  if(!$device -or !$stick -or !$track){JsonResponse $ctx @{error='missing track reference'} 400;return}
  for($attempt=0;$attempt -lt 2;$attempt++){
    $token=SharedToken ($attempt -gt 0)
    $url=$settings.endpoint+'?action=stream&token='+[Uri]::EscapeDataString($token)+'&device='+[Uri]::EscapeDataString($device)+'&stick='+[Uri]::EscapeDataString($stick)+'&path='+[Uri]::EscapeDataString($track.Replace('\','/'))
    $request=[Net.HttpWebRequest]::Create($url);$request.Timeout=30000;$request.ReadWriteTimeout=60000
    try{$response=$request.GetResponse();break}catch{if($attempt -eq 1 -or !$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401){throw}}
  }
  try{
    $ctx.Response.StatusCode=[int]$response.StatusCode
    $ctx.Response.ContentType=$response.ContentType
    $ctx.Response.Headers['Cache-Control']='no-store'
    if($response.ContentLength -ge 0){$ctx.Response.ContentLength64=$response.ContentLength}else{$ctx.Response.SendChunked=$true}
    $input=$response.GetResponseStream()
    try{$input.CopyTo($ctx.Response.OutputStream)}finally{$input.Dispose()}
  }finally{$response.Close();$ctx.Response.Close()}
}

while($listener.IsListening){
  try{
    $ctx=$listener.GetContext()
    $path=$ctx.Request.Url.AbsolutePath
    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/shared-media/inbox'){
      try{JsonResponse $ctx (DjInbox)}catch{JsonResponse $ctx @{error='DJ inbox unavailable'} 502}
      continue
    }
    if($ctx.Request.HttpMethod -eq 'POST' -and $path -eq '/shared-media/inbox-ack'){
      try{DjAck ([string]$ctx.Request.QueryString['id']);JsonResponse $ctx @{ok=$true}}catch{JsonResponse $ctx @{error='DJ delivery acknowledgment failed'} 502}
      continue
    }

    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/shared-media/catalog'){
      try{$catalog=SharedCatalog;JsonResponse $ctx $catalog}catch{JsonResponse $ctx @{error='Shared Media unavailable'} 502}
      continue
    }
    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/shared-media/file'){
      try{SharedFile $ctx}catch{try{JsonResponse $ctx @{error='Track unavailable'} 502}catch{$ctx.Response.Close()}}
      continue
    }
    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/local-media/drives'){
      $drives=@(Get-PSDrive -PSProvider FileSystem | ForEach-Object {
        [pscustomobject]@{name=$_.Name;path=$_.Root;description=$_.Description}
      })
      JsonResponse $ctx @{ok=$true;drives=$drives}
      continue
    }

    if($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/local-media/list'){
      $requested=[string]$ctx.Request.QueryString['path']
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
      $requested=[string]$ctx.Request.QueryString['path']
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
      $requested=[string]$ctx.Request.QueryString['path']
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
