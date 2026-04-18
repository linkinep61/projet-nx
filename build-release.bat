@echo off
echo ========================================
echo   Streamflix - Build Release APK
echo ========================================
echo.

cd /d "%~dp0"

:: Set JAVA_HOME to Android Studio's bundled JDK
if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
) else if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Using JAVA_HOME: %JAVA_HOME%
echo.
echo Syncing Gradle and building release APK...
echo.

call gradlew.bat assembleRelease --stacktrace

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo BUILD FAILED!
    pause
    exit /b 1
)

echo.
echo ========================================
echo   BUILD SUCCESS!
echo ========================================
echo.
echo APK location:
echo   app\build\outputs\apk\release\app-release.apk
echo.

:: Copy APK to project root for easy access
copy /y "app\build\outputs\apk\release\app-release.apk" "streamflix-release.apk" >nul 2>&1
if exist "streamflix-release.apk" (
    echo Copied to: streamflix-release.apk
    echo.
)

:: Ask to install on connected device
echo Do you want to install on connected device? (Y/N)
set /p INSTALL=
if /i "%INSTALL%"=="Y" (
    echo Installing...
    adb install -r "app\build\outputs\apk\release\app-release.apk"
    if %ERRORLEVEL% EQU 0 (
        echo.
        echo INSTALLED SUCCESSFULLY!
        echo Launching app...
        adb shell am start -n com.streamflixreborn.streamflix/.activities.main.MainMobileActivity
    ) else (
        echo Install failed. You may need to uninstall the debug version first:
        echo   adb uninstall com.streamflixreborn.streamflix.debug
        echo Then retry.
    )
)

echo.
pause
