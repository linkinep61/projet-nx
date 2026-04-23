@echo off
cd /d "%~dp0"
git push github main
echo.
echo ========================================
if %ERRORLEVEL% EQU 0 (
    echo PUSH SUCCESSFUL
) else (
    echo PUSH FAILED - check errors above
)
echo ========================================
pause
