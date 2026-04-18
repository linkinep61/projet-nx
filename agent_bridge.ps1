# === AGENT BRIDGE v3 - Claude <-> Android Studio ===
# Watches trigger.txt for commands, executes them, writes results.
# Auto-manages logcat: starts after launch, rotates files, filters relevant logs.

$PROJECT = "C:\Users\guill\StudioProjects\streamflix"
$ADB = "C:\Users\guill\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$TRIGGER = "$PROJECT\trigger.txt"
$BUILD_LOG = "$PROJECT\build_output.txt"
$LOGCAT = "$PROJECT\logcat2.txt"
$STATUS = "$PROJECT\agent_status.txt"
$PKG = "com.streamflixreborn.streamflix.debug"

# Set JAVA_HOME to Android Studio's bundled JDK
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Kill any existing logcat processes and clean locked files
Get-Process | Where-Object { $_.ProcessName -eq "adb" -and $_.MainWindowTitle -eq "" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
Remove-Item "$PROJECT\logcat.txt" -Force -ErrorAction SilentlyContinue
Remove-Item "$PROJECT\logcat2.txt" -Force -ErrorAction SilentlyContinue

# Clear old files
"" | Set-Content $TRIGGER -NoNewline
"" | Set-Content $BUILD_LOG -NoNewline
"READY" | Set-Content $STATUS -NoNewline

Write-Host "=== AGENT BRIDGE v3 ACTIVE ===" -ForegroundColor Green
Write-Host "Watching for commands in trigger.txt..." -ForegroundColor Cyan
Write-Host "Commands: BUILD, INSTALL, LAUNCH, LOGCAT, FULL, STOP_LOGCAT" -ForegroundColor Cyan
Write-Host ""

$logcatProcess = $null

function Start-Logcat {
    if ($logcatProcess -and !$logcatProcess.HasExited) {
        $logcatProcess.Kill()
        Start-Sleep -Seconds 1
    }
    & $ADB logcat -c 2>$null
    "" | Set-Content $LOGCAT -NoNewline
    $script:logcatProcess = Start-Process -FilePath $ADB -ArgumentList "logcat -v time" -RedirectStandardOutput $LOGCAT -NoNewWindow -PassThru
    Write-Host "  Logcat PID: $($script:logcatProcess.Id)" -ForegroundColor Cyan
}

function Stop-Logcat {
    if ($script:logcatProcess -and !$script:logcatProcess.HasExited) {
        $script:logcatProcess.Kill()
        Write-Host "  Logcat stopped." -ForegroundColor Cyan
    }
    $script:logcatProcess = $null
}

while ($true) {
    Start-Sleep -Milliseconds 500

    # Auto-restart logcat if it died unexpectedly while it should be running
    if ($logcatProcess -and $logcatProcess.HasExited) {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Logcat process died, restarting..." -ForegroundColor Yellow
        Start-Logcat
    }

    if (!(Test-Path $TRIGGER)) { continue }

    $cmd = (Get-Content $TRIGGER -Raw -ErrorAction SilentlyContinue)
    if ($null -eq $cmd) { continue }
    $cmd = $cmd.Trim().ToUpper()
    if ($cmd -eq "" -or $cmd -eq "DONE") { continue }

    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Command: $cmd" -ForegroundColor Yellow
    "RUNNING: $cmd" | Set-Content $STATUS -NoNewline

    switch -Wildcard ($cmd) {
        "BUILD" {
            Write-Host "  Building..." -ForegroundColor Cyan
            "BUILDING..." | Set-Content $BUILD_LOG -NoNewline
            Set-Location $PROJECT
            $buildResult = & cmd /c "gradlew.bat assembleDebug 2>&1"
            $buildResult | Out-File $BUILD_LOG -Encoding ascii
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  BUILD OK!" -ForegroundColor Green
                "BUILD_OK" | Set-Content $STATUS -NoNewline
            } else {
                Write-Host "  BUILD FAILED!" -ForegroundColor Red
                "BUILD_FAILED" | Set-Content $STATUS -NoNewline
            }
        }
        "INSTALL" {
            Write-Host "  Installing on device..." -ForegroundColor Cyan
            $result = & $ADB install -r "$PROJECT\app\build\outputs\apk\debug\app-debug.apk" 2>&1
            $result | Out-File "$PROJECT\install_output.txt" -Encoding ascii
            if ("$result" -match "Success") {
                Write-Host "  INSTALL OK!" -ForegroundColor Green
                "INSTALL_OK" | Set-Content $STATUS -NoNewline
            } else {
                Write-Host "  INSTALL FAILED: $result" -ForegroundColor Red
                "INSTALL_FAILED" | Set-Content $STATUS -NoNewline
            }
        }
        "LAUNCH" {
            Write-Host "  Launching app..." -ForegroundColor Cyan
            & $ADB shell am force-stop $PKG 2>$null
            Start-Sleep -Seconds 1
            & $ADB shell am start -n "$PKG/com.streamflixreborn.streamflix.activities.main.MainTvActivity" 2>&1
            Write-Host "  App launched, starting logcat automatically..." -ForegroundColor Cyan
            Start-Logcat
            "LAUNCHED" | Set-Content $STATUS -NoNewline
        }
        "LOGCAT" {
            Write-Host "  Starting logcat..." -ForegroundColor Cyan
            Start-Logcat
            "LOGCAT_RUNNING" | Set-Content $STATUS -NoNewline
        }
        "STOP_LOGCAT" {
            Stop-Logcat
            "LOGCAT_STOPPED" | Set-Content $STATUS -NoNewline
        }
        "FULL" {
            Write-Host "  FULL: Build + Install + Launch + Logcat" -ForegroundColor Magenta

            # Stop logcat during build
            Stop-Logcat

            # Build
            Write-Host "  [1/4] Building..." -ForegroundColor Cyan
            Set-Location $PROJECT
            $buildResult = & cmd /c "gradlew.bat assembleDebug 2>&1"
            $buildResult | Out-File $BUILD_LOG -Encoding ascii
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  BUILD FAILED!" -ForegroundColor Red
                "BUILD_FAILED" | Set-Content $STATUS -NoNewline
                "DONE" | Set-Content $TRIGGER -NoNewline
                continue
            }
            Write-Host "  [1/4] Build OK!" -ForegroundColor Green

            # Install
            Write-Host "  [2/4] Installing..." -ForegroundColor Cyan
            $result = & $ADB install -r "$PROJECT\app\build\outputs\apk\debug\app-debug.apk" 2>&1
            $result | Out-File "$PROJECT\install_output.txt" -Encoding ascii
            if ("$result" -notmatch "Success") {
                Write-Host "  INSTALL FAILED: $result" -ForegroundColor Red
                "INSTALL_FAILED" | Set-Content $STATUS -NoNewline
                "DONE" | Set-Content $TRIGGER -NoNewline
                continue
            }
            Write-Host "  [2/4] Install OK!" -ForegroundColor Green

            # Launch
            Write-Host "  [3/4] Launching..." -ForegroundColor Cyan
            & $ADB shell am force-stop $PKG 2>$null
            Start-Sleep -Seconds 1
            & $ADB shell am start -n "$PKG/com.streamflixreborn.streamflix.activities.main.MainTvActivity" 2>&1
            Write-Host "  [3/4] Launched!" -ForegroundColor Green

            # Logcat (auto)
            Write-Host "  [4/4] Starting logcat..." -ForegroundColor Cyan
            Start-Logcat
            Write-Host "  [4/4] Logcat running!" -ForegroundColor Green

            "FULL_DONE" | Set-Content $STATUS -NoNewline
        }
    }

    "DONE" | Set-Content $TRIGGER -NoNewline
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Done." -ForegroundColor Green
    Write-Host ""
}
