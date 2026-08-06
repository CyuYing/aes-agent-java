@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0packaging\build-portable.ps1"
if errorlevel 1 (
  echo.
  pause
  exit /b 1
)
echo.
pause
