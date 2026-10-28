$ErrorActionPreference='Stop'
$base='https://rubenvanaggelen.com/the-one-remote-api/music.php'
$root='C:\TheOne\SharedMedia\Music'
$manifest=Join-Path $root 'Nieuwe downloads\.theone-folders.json'
$secretFile='C:\TheOne\SharedMedia\.hub-sync-secret'
$state='C:\TheOne\SharedMedia\Logs\folder-catalog.sig'
# Toon Nieuwe downloads alleen als er daadwerkelijk afspeelbare muziek aanwezig is.
# .stfolder, sync-manifests en andere technische bestanden tellen niet mee.
$folders=@('Ruben')
$audioExtensions=@('.mp3','.m4a','.aac','.flac','.wav','.ogg','.oga','.opus','.wma')
$inbox=Join-Path $root 'Nieuwe downloads'
$inboxTracks=@()
if(Test-Path -LiteralPath $inbox){
  $inboxTracks=@(Get-ChildItem -LiteralPath $inbox -File -Recurse -ErrorAction SilentlyContinue | Where-Object {
    $audioExtensions -contains $_.Extension.ToLowerInvariant()
  })
  if($inboxTracks.Count -gt 0){$folders += 'Nieuwe downloads'}
}
$canonical=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)

function Normalize-FolderPath([string]$p){
  if([string]::IsNullOrWhiteSpace($p)){return ''}
  $p=$p.Replace([char]61474,' ').Replace('?',' ')
  while($p.Contains('  ')){$p=$p.Replace('  ',' ')}
  return $p.Trim()
}

# Surface/Ruben manifest is authoritative for the canonical music folder tree.
if(Test-Path -LiteralPath $manifest){
  try{
    $doc=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
    foreach($folder in @($doc.folders)){
      $p=Normalize-FolderPath ([string]$folder)
      if($p -eq 'Ruben' -or $p.StartsWith('Ruben/')){
        $folders += $p
        if($p.StartsWith('Ruben/')){[void]$canonical.Add($p.Substring(6))}
      }
    }
  }catch{}
}

# Nieuwe downloads: toon alleen echte tijdelijke submappen.
$inbox=Join-Path $root 'Nieuwe downloads'
if(Test-Path -LiteralPath $inbox){
  $dirs=@(Get-ChildItem -LiteralPath $inbox -Directory -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending)
  foreach($dir in $dirs){
    $rel=$dir.FullName.Substring($inbox.Length).TrimStart('\').Replace('\','/')
    if(!$rel){continue}
    $rel=Normalize-FolderPath $rel
    if($canonical.Contains($rel)){
      $realFiles=@(Get-ChildItem -LiteralPath $dir.FullName -File -Recurse -ErrorAction SilentlyContinue | Where-Object {
        !$_.Name.StartsWith('~syncthing~') -and !$_.Name.StartsWith('._') -and $_.Name -ne '.DS_Store'
      })
      if($realFiles.Count -eq 0){
        Remove-Item -LiteralPath $dir.FullName -Recurse -Force -ErrorAction SilentlyContinue
      }
      continue
    }
    # Technische en lege submappen hoeven de lege inbox niet zichtbaar te houden.
    $dirPrefix=$dir.FullName.TrimEnd('\')+'\'
    $hasMusic=@($inboxTracks | Where-Object {
      $_.FullName.StartsWith($dirPrefix,[StringComparison]::OrdinalIgnoreCase)
    }).Count -gt 0
    if(!$hasMusic){continue}
    $folders += ('Nieuwe downloads/'+$rel)
  }
}

$folders=@($folders | Sort-Object -Unique)
$sig=[string]::Join("`n",$folders)
$hash=([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($sig)))).Replace('-','').ToLowerInvariant()
$old=if(Test-Path -LiteralPath $state){(Get-Content -LiteralPath $state -Raw).Trim()}else{''}
if($hash -eq $old){exit 0}
$secret=(Get-Content -LiteralPath $secretFile -Raw).Trim()
$loginBody=[Text.Encoding]::UTF8.GetBytes((@{secret=$secret}|ConvertTo-Json -Compress))
$login=Invoke-RestMethod -Uri ($base+'?action=hub-login') -Method Post -ContentType 'application/json; charset=utf-8' -Body $loginBody -TimeoutSec 30
$headers=@{Authorization=('Bearer '+$login.token)}
$body=@{folders=$folders}|ConvertTo-Json -Depth 5 -Compress
$bodyBytes=[Text.Encoding]::UTF8.GetBytes($body)
$r=Invoke-RestMethod -Uri ($base+'?action=hub-folders-sync') -Headers $headers -Method Post -ContentType 'application/json; charset=utf-8' -Body $bodyBytes -TimeoutSec 30
if($r.ok){Set-Content -LiteralPath $state -Value $hash -Encoding ASCII}
