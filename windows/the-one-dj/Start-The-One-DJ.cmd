@echo off
setlocal
set "ROOT=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%Update-The-One-DJ.ps1" >nul 2>&1
if not exist "%ROOT%app\index.html" exit /b 1
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%ROOT%Serve-The-One-DJ.ps1"
powershell.exe -NoProfile -Command "$ready=$false; for($i=0;$i -lt 20;$i++){try{$r=Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18765/local-media/drives -TimeoutSec 1; if($r.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Milliseconds 500};if(!$ready){exit 1}"
if errorlevel 1 (
  echo The One DJ mediaserver kon niet starten.
  pause
  exit /b 1
)
for /f %%B in ('powershell.exe -NoProfile -Command "try{(Get-Content '%ROOT%version.json' -Raw|ConvertFrom-Json).build}catch{0}"') do set "BUILD=%%B"
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -ErrorAction SilentlyContinue ^| Where-Object { ($_.Name -eq 'msedge.exe' -or $_.Name -eq 'chrome.exe') -and $_.CommandLine -match 'TheOneDJ' } ^| ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1
set "URL=http://127.0.0.1:18765/index.html?build=%BUILD%"
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" (
  start "The One DJ" "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" --user-data-dir="%LOCALAPPDATA%\TheOneDJ\EdgeProfile" --app="%URL%" --start-fullscreen
  exit /b 0
)
if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" (
  start "The One DJ" "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" --user-data-dir="%LOCALAPPDATA%\TheOneDJ\EdgeProfile" --app="%URL%" --start-fullscreen
  exit /b 0
)
if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" (
  start "The One DJ" "%ProgramFiles%\Google\Chrome\Application\chrome.exe" --user-data-dir="%LOCALAPPDATA%\TheOneDJ\ChromeProfile" --app="%URL%" --start-fullscreen
  exit /b 0
)
start "" "%URL%"
