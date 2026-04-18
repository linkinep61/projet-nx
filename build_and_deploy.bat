@echo off
cd /d C:\Users\guill\StudioProjects\streamflix
echo Stopping gradle daemons...
call gradlew.bat --stop 2>nul
echo Cleaning locked APK...
del /f /q app\build\outputs\apk\debug\app-debug.apk 2>nul
echo Building...
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED
    pause
    exit /b 1
)
echo Installing...
adb install -r app\build\outputs\apk\debug\app-debug.apk
echo Launching...
adb shell am start -n com.streamflixreborn.streamflix.debug/com.streamflixreborn.streamflix.activities.main.MainMobileActivity
echo Done! Starting logcat...
adb logcat -c
adb logcat > logcat_output.txt
pause
