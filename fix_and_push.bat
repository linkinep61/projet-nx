@echo off
echo === Fix secret + Push ===
cd /d "C:\Users\guill\StudioProjects\streamflix"

echo [1/4] Retirer push_to_github.bat du suivi git...
git rm --cached push_to_github.bat

echo [2/4] Ajouter .gitignore mis a jour...
git add .gitignore

echo [3/4] Amender le commit...
git commit --amend --no-edit

echo [4/4] Push force vers GitHub...
git push github main --force

echo.
echo === TERMINE ===
pause
