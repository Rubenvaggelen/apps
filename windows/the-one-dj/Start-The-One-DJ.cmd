@echo off
setlocal
set "ROOT=%~dp0"
set "APPDIR=%ROOT%app"
set "PAGE=%APPDIR%\index.html"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%Update-The-One-DJ.ps1" >nul 2>&1
if not exist "%PAGE%" exit /b 1
set "URL=file:///%PAGE:\=/%"
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" (
  start "The One DJ" "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" --user-data-dir="%LOCALAPPDATA%\TheOneDJ\EdgeProfile" --allow-file-access-from-files --app="%URL%" --start-maximized
  exit /b 0
)
if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" (
  start "The One DJ" "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" --app="%URL%" --start-maximized
  exit /b 0
)
if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" (
  start "The One DJ" "%ProgramFiles%\Google\Chrome\Application\chrome.exe" --app="%URL%" --start-maximized
  exit /b 0
)
start "The One DJ" "%PAGE%"