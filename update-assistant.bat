@echo off
rem Puts the corrected assistant live: 1) deploys the server function, 2) builds the app, 3) installs it on the phone.
setlocal
set ROOT=%~dp0
echo === 1/3 Deploying the PumpPay functions ===
cd /d "%ROOT%firebase-ingest"
call firebase deploy --only functions:pumppay
if errorlevel 1 ( echo DEPLOY FAILED - copy the error above to Claude. & goto :end )

echo === 2/3 Building the app ===
cd /d "%ROOT%ENERGYHURBV2\android"
set G=
if exist gradlew.bat set G=gradlew.bat
if not defined G for /d %%d in ("%USERPROFILE%\.gradle\wrapper\dists\gradle-8.7-bin\*") do if exist "%%d\gradle-8.7\bin\gradle.bat" set G="%%d\gradle-8.7\bin\gradle.bat"
if not defined G ( where gradle >nul 2>nul && set G=gradle )
if not defined G ( echo Gradle not found. Open ENERGYHURBV2\android in Android Studio and press Run instead. & goto :end )
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr" set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
call %G% assembleDebug
if errorlevel 1 ( echo BUILD FAILED - copy the first "e: " error lines above to Claude. & goto :end )

echo === 3/3 Installing on the phone ===
set APK=C:\Users\user\AppData\Local\PumpPayBuild\app\outputs\apk\debug\app-debug.apk
set ADB=adb
where adb >nul 2>nul || set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
%ADB% install -r "%APK%"
copy /y "%APK%" "%ROOT%ENERGYHURBV2\releases\PumpPay-debug.apk" >nul
echo === Refreshing the website upload zip ===
copy /y "%APK%" "%ROOT%landing-site\PumpPay.apk" >nul
powershell -NoProfile -Command "Compress-Archive -Path '%ROOT%landing-site\*' -DestinationPath '%ROOT%pumppay-upload-to-public_html.zip' -Force"
echo Done. Open PumpPay - Assistant and try: "what is my pump flow rate"
:end
endlocal
