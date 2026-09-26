@echo off
where.exe powershell.exe >nul 2>nul
if errorlevel 1 (
    >&2 echo java-agent: powershell.exe was not found.
    exit /b 9009
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-java-agent.ps1" %*
exit /b %ERRORLEVEL%
