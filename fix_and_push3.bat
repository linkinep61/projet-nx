@echo off
echo === Fix workflow scope - Squash approach ===
cd /d "C:\Users\guill\StudioProjects\streamflix"

echo [1/6] Fetch depuis GitHub...
git fetch github

echo [2/6] Soft reset vers github/main...
git reset --soft github/main

echo [3/6] Restaurer .github/ depuis le remote...
git checkout github/main -- .github/

echo [4/6] Stage tout...
git add -A

echo [5/6] Nouveau commit unique...
git commit -m "Fix: LuluVdo unpacker, Lpayer trusted click, 1Jour1Film parsing, Wiflix posters, FrenchStream crash, TMDB timeouts, remove AfterDark deps, disable UnJourUnFilm2"

echo [6/6] Push force vers GitHub...
git push github main --force

echo.
echo === TERMINE ===
pause
