@echo off
setlocal
set "ROOT=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%Update-The-One-DJ.ps1" >nul 2>&1
if not exist "%ROOT%app\index.html" exit /b 1
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%ROOT%Serve-The-One-DJ.ps1"
timeout /t 1 /nobreak >nul
set "URL=http://127.0.0.1:18765/index.html"
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
