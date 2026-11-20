@echo off
rem Starts Blink from this source folder (no admin rights needed).
cd /d "%~dp0"
start "" "%~dp0node_modules\electron\dist\electron.exe" "%~dp0."
