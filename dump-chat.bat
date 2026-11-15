@echo off
rem Copies what is on the phone's PumpPay Assistant screen into chat-dump\ so the replies can be reviewed.
rem Before running: open the PumpPay app on the phone, go to Assistant, scroll to the NEWEST message.
setlocal
set ADB=adb
where adb >nul 2>nul || set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set OUT=%~dp0chat-dump
if not exist "%OUT%" mkdir "%OUT%"
%ADB% devices > "%OUT%\devices.txt" 2>&1
type "%OUT%\devices.txt"
%ADB% shell dumpsys window | findstr /i "mCurrentFocus" > "%OUT%\focus.txt"
for /L %%i in (1,1,25) do (
  echo page %%i
  %ADB% shell uiautomator dump /sdcard/pp_ui.xml >nul 2>nul
  %ADB% pull /sdcard/pp_ui.xml "%OUT%\ui_%%i.xml" >nul 2>nul
  %ADB% shell screencap -p /sdcard/pp_s.png
  %ADB% pull /sdcard/pp_s.png "%OUT%\screen_%%i.png" >nul 2>nul
  rem drag down = show earlier messages
  %ADB% shell input swipe 500 700 500 1500 400
  timeout /t 1 /nobreak >nul
)
%ADB% shell rm /sdcard/pp_ui.xml /sdcard/pp_s.png
echo.
echo Done. Files are in %OUT% - tell Claude it has finished.
endlocal
