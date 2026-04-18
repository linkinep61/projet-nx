@echo off
echo === Fix workflow scope + Push ===
cd /d "C:\Users\guill\StudioProjects\streamflix"

echo [1/4] Fetch depuis GitHub...
git fetch github

echo [2/4] Restaurer .github/ depuis le remote...
git checkout github/main -- .github/

echo [3/4] Amender le commit...
git add .github/
git commit --amend --no-edit

echo [4/4] Push force vers GitHub...
git push github main --force

echo.
echo === TERMINE ===
pause
