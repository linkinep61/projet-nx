@echo off
echo === STREAMFLIX BUILD & TEST ===
set ADB="C:\Users\guill\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set PROJECT=C:\Users\guill\StudioProjects\streamflix

echo.
echo [1/4] Checking TV connection...
%ADB% devices
echo.

echo [2/4] Building APK (TV layout)...
cd /d %PROJECT%
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED! Check errors above.
    pause
    exit /b 1
)
echo BUILD OK!
echo.

echo [3/4] Installing on TV...
%ADB% install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% NEQ 0 (
    echo INSTALL FAILED!
    pause
    exit /b 1
)
echo INSTALL OK!
echo.

echo [4/4] Starting app and capturing logs...
%ADB% shell am start -n com.streamflixreborn.streamflix.debug/com.streamflixreborn.streamflix.activities.main.MainTvActivity
echo.
echo Capturing logs to logcat.txt (press Ctrl+C to stop)...
%ADB% logcat -c
%ADB% logcat -s WiflixBypass,Cine24hBypass -v time > %PROJECT%\logcat.txt
