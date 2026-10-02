param([string]$OnlyFolder='',[switch]$Loop)
$ErrorActionPreference='Stop'
$mutex=New-Object Threading.Mutex($false,'Global\TheOneAudioPlayback')
if(!$mutex.WaitOne(0)){exit 0}
$base='https://rubenvanaggelen.com/the-one-remote-api/music.php'
$cache='C:\TheOne\SharedMedia\PlaybackCache'
$log='C:\TheOne\SharedMedia\Logs\audio-playback.log'
New-Item $cache,(Split-Path $log) -ItemType Directory -Force|Out-Null
function Log($message){Add-Content $log ((Get-Date).ToString('u')+' '+$message);Write-Output $message}
function Run-Pass {
  $ffmpeg=(Get-Command ffmpeg.exe -ErrorAction Stop).Source
  $ffprobe=(Get-Command ffprobe.exe -ErrorAction Stop).Source
  $secret=(Get-Content 'C:\TheOne\SharedMedia\.hub-sync-secret' -Raw).Trim()
  $login=Invoke-RestMethod ($base+'?action=hub-login') -Method Post -ContentType 'application/json' -Body (@{secret=$secret}|ConvertTo-Json -Compress) -TimeoutSec 30
  $headers=@{Authorization=('Bearer '+$login.token)}
  $read=Invoke-RestMethod ($base+'?action=browse-login') -TimeoutSec 30
  $catalog=Invoke-RestMethod ($base+'?action=catalog') -Headers @{Authorization=('Bearer '+$read.token)} -TimeoutSec 60
  $exts=@('.wma','.aif','.aiff','.aifc','.caf','.alac','.mka','.ac3','.amr','.ape','.wv','.tta','.dsf','.dff','.m4a','.aac','.flac','.wav','.ogg','.oga','.opus')
  $tracks=@();$seen=@{}
  foreach($stick in $catalog.sticks){foreach($file in $stick.files){
    if(!$file.cached -or !($exts -contains [IO.Path]::GetExtension($file.path).ToLowerInvariant())){continue}
    if($OnlyFolder -and $file.path -notlike ('*'+$OnlyFolder+'*')){continue}
    $sha=[string]$file.sha256;if($sha -notmatch '^[a-fA-F0-9]{64}$' -or $seen.ContainsKey($sha)){continue}
    $seen[$sha]=$true;$tracks+=@{File=$file;Device=$stick.device_id;Stick=$stick.stick_id;Sha=$sha.ToLowerInvariant()}
  }}
  $tracks=@($tracks|Sort-Object @{Expression={if($_.File.path -match 'Draiston'){0}else{1}}},@{Expression={$_.File.path}})
  $complete=0;$failed=0
  Log ('PASS tracks='+$tracks.Count)
  foreach($track in $tracks){
    $file=$track.File;$sha=$track.Sha
    try{
      $ready=Invoke-RestMethod ($base+'?action=playback-status&source_sha256='+$sha) -Headers $headers -TimeoutSec 30
      if($ready.ready){$complete++;continue}
      $source=Join-Path $cache ($sha+[IO.Path]::GetExtension($file.path))
      $output=Join-Path $cache ($sha+'.mp3')
      if(!(Test-Path $source)){
        $local=Join-Path 'C:\TheOne\SharedMedia\Music' (($file.path -replace '^Ruben/','').Replace('/','\'))
        if((Test-Path -LiteralPath $local) -and (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant() -eq $sha){
          Copy-Item -LiteralPath $local -Destination $source
        }else{
          $url=$base+'?action=stream&raw=1&token='+[Uri]::EscapeDataString($read.token)+'&device='+[Uri]::EscapeDataString($track.Device)+'&stick='+[Uri]::EscapeDataString($track.Stick)+'&path='+[Uri]::EscapeDataString($file.path)
          $client=New-Object Net.WebClient
          try{$client.DownloadFile($url,$source)}finally{$client.Dispose()}
        }
      }
      if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha){Remove-Item $source;throw 'source checksum mismatch'}
      if(!(Test-Path $output)){
        $partial=Join-Path $cache ($sha+'.encoding.mp3')
        $previousErrorPreference=$ErrorActionPreference
        $ErrorActionPreference='Continue'
        try{ & $ffmpeg -nostdin -hide_banner -loglevel error -y -i $source -map 0:a:0 -vn -af 'asetpts=N/SR/TB' -codec:a libmp3lame -q:a 2 -ar 44100 -ac 2 $partial 2>>$log }
        finally{$ErrorActionPreference=$previousErrorPreference}
        if($LASTEXITCODE -ne 0){Remove-Item $partial -ErrorAction SilentlyContinue;throw 'audio conversion failed'}
        $originalDuration=[double](& $ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $source)
        $playbackDuration=[double](& $ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $partial)
        if([Math]::Abs($originalDuration-$playbackDuration) -gt 1){Remove-Item $partial;throw 'audio duration mismatch'}
        Move-Item $partial $output -Force
      }
      $size=(Get-Item $output).Length;$playbackSha=(Get-FileHash $output -Algorithm SHA256).Hash.ToLowerInvariant()
      $stream=[IO.File]::OpenRead($output);$chunk=Join-Path $cache 'upload-chunk.bin';$offset=0L
      try{
        $buffer=New-Object byte[] 2097152
        while(($n=$stream.Read($buffer,0,$buffer.Length)) -gt 0){
          $out=[IO.File]::Create($chunk);try{$out.Write($buffer,0,$n)}finally{$out.Dispose()}
          $raw=& curl.exe -sS --fail --max-time 120 --retry 2 -H ('Authorization: Bearer '+$login.token) -F ('source_sha256='+$sha) -F ('sha256='+$playbackSha) -F ('offset='+$offset) -F ('size='+$size) -F ('device_id='+$track.Device) -F ('stick_id='+$track.Stick) -F ('path='+$file.path) -F ('file=@'+$chunk+';type=audio/mpeg') ($base+'?action=playback-upload')
          if($LASTEXITCODE -ne 0){throw 'playback upload failed'}
          $response=($raw|Out-String)|ConvertFrom-Json
          if(!$response.ok -or [int64]$response.offset -ne ($offset+$n)){throw 'playback upload incomplete'}
          $offset+=$n
        }
      }finally{$stream.Dispose();Remove-Item $chunk -ErrorAction SilentlyContinue}
      if(!$response.ready){throw 'playback not published'}
      $complete++;Log ('READY '+$file.path)
      Remove-Item $source -ErrorAction SilentlyContinue
    }catch{$failed++;Log ('FAILED '+$file.path+' : '+$_.Exception.Message)}
  }
  Log ('DONE ready='+$complete+' failed='+$failed+' total='+$tracks.Count)
}
try{
  do{try{Run-Pass}catch{Log ('PASS FAILED: '+$_.Exception.Message)};if($Loop){Start-Sleep -Seconds 300}}while($Loop)
}finally{$mutex.ReleaseMutex();$mutex.Dispose()}
