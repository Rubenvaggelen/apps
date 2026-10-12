param([ValidateSet('Probe','On','Off')][string]$Action='Probe')
$ErrorActionPreference='Stop'
$legacy='C:\TheOne\Tools\Screen-Control-v2.ps1'
$safe='C:\TheOne\Tools\SafeDark-Control.ps1'
if(!(Test-Path $legacy) -or !(Test-Path $safe)) {throw 'SafeDark dependencies not installed'}
switch($Action){
 'Probe' {
  & $legacy -Action Probe
  if($LASTEXITCODE -and $LASTEXITCODE -ne 0){throw 'Base display probe failed'}
  & $safe -Action Probe
  Write-Output 'SAFE_DARK_ROUTING_READY MODERN_STANDBY_SCREEN_OFF_DISABLED'
 }
 'Off' {
  # OFF is intentionally mapped to a BLACK WINDOW, never SC_MONITORPOWER.
  # Hardware display must stay logically on to avoid Modern Standby suspension.
  $result=(& $safe -Action Dark -AutoRestoreSeconds 0 2>&1|Out-String)
  if($result -notmatch 'SAFE_DARK_(ACTIVE|ALREADY_ACTIVE)'){throw ('SafeDark failed: '+$result)}
  Write-Output 'COMMAND_ACCEPTED ACTION=Off MODE=SAFE_DARK_NOT_PHYSICAL_OFF REMOTE_KEPT_ACTIVE'
 }
 'On' {
  $result=(& $safe -Action Light 2>&1 | Out-String)
  if($result -notmatch 'SAFE_DARK_LIGHT_RESTORED'){throw ('SafeDark restore failed: '+$result)}
  & $legacy -Action On
  Write-Output 'COMMAND_ACCEPTED ACTION=On MODE=SAFE_DARK_RESTORED'
 }
}
