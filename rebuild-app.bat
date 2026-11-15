@echo off
rem Rebuilds the app and installs it on the phone. No server deploy.
setlocal
cd /d "%~dp0ENERGYHURBV2\android"
set G=
if exist gradlew.bat set G=gradlew.bat
if not defined G for /d %%d in ("%USERPROFILE%\.gradle\wrapper\dists\gradle-8.7-bin\*") do if exist "%%d\gradle-8.7\bin\gradle.bat" set G="%%d\gradle-8.7\bin\gradle.bat"
if not defined G ( where gradle >nul 2>nul && set G=gradle )
if not defined G ( echo Gradle not found. & goto :end )
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr" set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
call %G% assembleDebug
if errorlevel 1 ( echo BUILD FAILED & goto :end )
set APK=C:\Users\user\AppData\Local\PumpPayBuild\app\outputs\apk\debug\app-debug.apk
set ADB=adb
where adb >nul 2>nul || set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
%ADB% install -r "%APK%"
copy /y "%APK%" "%~dp0ENERGYHURBV2\releases\PumpPay-debug.apk" >nul
echo Done.
:end
endlocal
