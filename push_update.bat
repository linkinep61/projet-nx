@echo off
echo === Suppression UnJourUnFilm2Provider - v1.7.126 ===

cd /d "%~dp0"

git add -A
git commit -m "Suppression UnJourUnFilm2Provider (miroir inutile)"

echo === Push main ===
git push github main

echo === Suppression ancien tag v1.7.126 si existant ===
git tag -d v1.7.126 2>nul
git push github --delete v1.7.126 2>nul

echo === Creation tag v1.7.126 ===
git tag v1.7.126
git push github v1.7.126

echo === DONE ===
pause
