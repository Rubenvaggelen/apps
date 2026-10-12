param(
 [ValidateSet('Probe','Dark','Light')][string]$Action='Probe',
 [ValidateRange(0,3600)][int]$AutoRestoreSeconds=0
)
$ErrorActionPreference='Stop'
$dir=Join-Path $env:LOCALAPPDATA 'TheOne\SafeDark'
$state=Join-Path $dir 'active.json'
$stop=Join-Path $dir 'stop.signal'
$overlay='C:\TheOne\Tools\SafeDark-Overlay.ps1'
function Get-Active {
 if(!(Test-Path $state)){return $false}
 try {
  $data=Get-Content $state -Raw | ConvertFrom-Json
  $p=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$data.pid) -ErrorAction Stop
  return ($p -and $p.CommandLine -match 'SafeDark-Overlay.ps1')
 }catch{return $false}
}
if($Action -eq 'Probe'){
 $brightness=Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness -ErrorAction SilentlyContinue|
 Select-Object -First 1 -ExpandProperty CurrentBrightness
 Write-Output ('SAFE_DARK_PROBE AVAILABLE='+[bool](Test-Path $overlay)+' ACTIVE='+[bool](Get-Active)+' BRIGHTNESS='+$brightness)
 exit 0
}
if($Action -eq 'Light'){
 if(Get-Active){
  New-Item -ItemType Directory -Path $dir -Force | Out-Null
  Set-Content -LiteralPath $stop -Value 'dismiss'
  for($n=0;$n -lt 16;$n++){
   if(-not (Get-Active)){break}
   Start-Sleep -Milliseconds 250
  }
 }
 if(Get-Active){throw 'Safe dark overlay did not dismiss in time'}
 Write-Output 'SAFE_DARK_LIGHT_RESTORED'
 exit 0
}
$session=(Get-Process -Id $PID).SessionId
if($session -notin @(Get-Process explorer -ErrorAction SilentlyContinue|Select-Object -ExpandProperty SessionId)){
 throw 'Safe dark must run in the current desktop session'
}
if(Get-Active){Write-Output 'SAFE_DARK_ALREADY_ACTIVE';exit 0}
$power=Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue
if($power -and $power.BatteryStatus -ne 2){throw 'Safe dark requires charger; refusing on battery'}
New-Item -ItemType Directory -Path $dir -Force | Out-Null
if(Test-Path $state){Remove-Item $state -Force}
$arguments='-STA -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$overlay+'" -AutoRestoreSeconds '+$AutoRestoreSeconds
$p=Start-Process 'powershell.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru
for($i=0;$i -lt 20;$i++){
 if(Get-Active){
  Write-Output ('SAFE_DARK_ACTIVE PID='+$p.Id+' AUTO_RESTORE_SEC='+$AutoRestoreSeconds)
  exit 0
 }
 if($p.HasExited){throw ('SafeDark overlay failed, exit code='+$p.ExitCode)}
 Start-Sleep -Milliseconds 250
}
throw 'SafeDark did not activate within 5s'
