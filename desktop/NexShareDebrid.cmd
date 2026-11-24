@echo off
rem NexShare Debrid (Windows) - launcher
setlocal
set "PS1=%~dp0NexShareDebrid.ps1"
if not exist "%PS1%" (
    echo Nao encontrei "%PS1%".
    pause
    exit /b 1
)
start "" /min powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%PS1%"
exit /b 0
