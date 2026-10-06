@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-server-background.ps1" -OpenBrowser
if errorlevel 1 pause
