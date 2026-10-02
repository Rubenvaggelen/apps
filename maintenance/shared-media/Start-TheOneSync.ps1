$exe='C:\TheOne\Syncthing\syncthing.exe'
$stHome='C:\TheOne\Syncthing'
$sync=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.ExecutablePath -eq $exe }
if(!$sync){ Start-Process -FilePath $exe -ArgumentList @('-H',$stHome,'serve','--no-browser','--no-console','--no-restart') -WindowStyle Hidden }
$collector=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*CollectorLoop.ps1*' }
if(!$collector){ Start-Process powershell.exe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','C:\TheOne\SharedMedia\CollectorLoop.ps1') -WindowStyle Hidden }
$publisher=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*Publish-NewDownloadsLoop.ps1*' }
if(!$publisher){ Start-Process powershell.exe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','C:\TheOne\SharedMedia\Publish-NewDownloadsLoop.ps1') -WindowStyle Hidden }
$mover=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*Process-HubMovesLoop.ps1*' }
if(!$mover){ Start-Process powershell.exe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','C:\TheOne\SharedMedia\Process-HubMovesLoop.ps1') -WindowStyle Hidden }
$folderApply=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*Apply-FolderManifestLoop.ps1*' }
if(!$folderApply){ Start-Process powershell.exe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','C:\TheOne\SharedMedia\Apply-FolderManifestLoop.ps1') -WindowStyle Hidden }
$folderPublish=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*Publish-HubFoldersLoop.ps1*' }
if(!$folderPublish){ Start-Process powershell.exe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','C:\TheOne\SharedMedia\Publish-HubFoldersLoop.ps1') -WindowStyle Hidden }

$boeng=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*Upload-BoengSanie.ps1*' }
if(!$boeng -and (Test-Path -LiteralPath 'C:\TheOne\SharedMedia\Upload-BoengSanie.ps1')){ Start-Process powershell.exe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','C:\TheOne\SharedMedia\Upload-BoengSanie.ps1') -WindowStyle Hidden }

$audioPlayback=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*Prepare-AudioPlayback.ps1*' -and $_.Name -eq 'powershell.exe' }
if(!$audioPlayback -and (Test-Path -LiteralPath 'C:\TheOne\SharedMedia\Prepare-AudioPlayback.ps1')){ Start-Process powershell.exe -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','C:\TheOne\SharedMedia\Prepare-AudioPlayback.ps1','-Loop') -WindowStyle Hidden }
