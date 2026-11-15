@echo off
cd /d "%~dp0"
echo started %date% %time% > "%~dp0update-log.txt"
call "%~dp0update-assistant.bat" >> "%~dp0update-log.txt" 2>&1
echo FINISHED %date% %time% >> "%~dp0update-log.txt"
