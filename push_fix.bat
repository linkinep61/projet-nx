@echo off
cd /d "%~dp0"
echo === Pushing fixed provider files to GitHub ===
echo.
echo Current branch and log:
git log --oneline -5
echo.
echo Pushing to github/main (force to overwrite web editor commits)...
git push github main --force
echo.
echo === Done ===
pause
