@echo off
cd /d "C:\Users\guill\AppData\Local\Android\Sdk\platform-tools"
del /f "C:\Users\guill\StudioProjects\streamflix\lc_fresh.txt" 2>nul
adb -s emulator-5554 logcat -d -v brief > "C:\Users\guill\StudioProjects\streamflix\lc_fresh.txt" 2>&1
echo %date% %time% DONE >> "C:\Users\guill\StudioProjects\streamflix\lc_fresh.txt"
