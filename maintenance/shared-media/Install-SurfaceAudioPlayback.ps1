param([string]$SettingsPath=(Join-Path $env:LOCALAPPDATA 'Programs\The One Family\The One DJ\shared-media-settings.json'))
$ErrorActionPreference='Stop'
if(!(Test-Path -LiteralPath $SettingsPath)){throw 'DJ shared media settings missing'}
$env:Path=[Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')
if(!(Get-Command ffmpeg.exe -ErrorAction SilentlyContinue)){
  & winget.exe install --id Gyan.FFmpeg --exact --silent --accept-source-agreements --accept-package-agreements --disable-interactivity
  if($LASTEXITCODE -ne 0){throw 'FFmpeg installation failed'}
  $env:Path=[Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')
}
Get-Command ffmpeg.exe,ffprobe.exe -ErrorAction Stop|Out-Null
$root=Join-Path $env:LOCALAPPDATA 'TheOneAudioPlayback'
New-Item $root -ItemType Directory -Force|Out-Null
Copy-Item (Join-Path $PSScriptRoot 'Prepare-AudioPlayback.ps1') (Join-Path $root 'Prepare-AudioPlayback.ps1') -Force
$launcher=Join-Path $root 'Run-AudioPlayback.ps1'
$escapedSettings=$SettingsPath.Replace("'","''")
$template=@'
$ErrorActionPreference='Stop'
$env:Path=[Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')
$root=Join-Path $env:LOCALAPPDATA 'TheOneAudioPlayback'
& (Join-Path $root 'Prepare-AudioPlayback.ps1') -WorkerRoot $root -SettingsPath 'SETTINGS_PATH'
'@
$template.Replace('SETTINGS_PATH',$escapedSettings) | Set-Content -LiteralPath $launcher -Encoding UTF8
$action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$launcher+'"')
$trigger=New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 4)
Register-ScheduledTask -TaskName 'The One Audio Playback' -Action $action -Trigger $trigger -Settings $settings -Description 'Prepare compatible playback streams without changing original shared music' -Force|Out-Null
Start-ScheduledTask -TaskName 'The One Audio Playback'
