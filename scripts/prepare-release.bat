@echo off
setlocal

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-release.ps1"
exit /b %ERRORLEVEL%
