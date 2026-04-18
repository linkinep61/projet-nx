@echo off
echo Stopping Gradle daemons...
cd /d C:\Users\guill\StudioProjects\streamflix
call gradlew.bat --stop 2>nul
echo Waiting 3 seconds...
timeout /t 3 /nobreak >nul
echo Deleting locked APK...
del /f /q app\build\outputs\apk\debug\app-debug.apk 2>nul
echo Done! You can now build from Android Studio.
pause
