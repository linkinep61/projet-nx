@echo off
set ADB=C:\Users\guill\AppData\Local\Android\Sdk\platform-tools\adb.exe

echo ============================================
echo === LuluVdoExtractor Logs ===
echo ============================================
%ADB% logcat -d -s LuluVdoExtractor
echo.

echo ============================================
echo === PlayerDebug (state + errors) ===
echo ============================================
%ADB% logcat -d -s PlayerDebug
echo.

echo ============================================
echo === Player Errors (ExoPlayer / Media3) ===
echo ============================================
%ADB% logcat -d | findstr /i "onPlayerError PlaybackException PlayerMobileFragment PlayerNetwork"
echo.

echo ============================================
echo === HTTP Errors (DataSource) ===
echo ============================================
%ADB% logcat -d | findstr /i "HttpDataSource InvalidResponseCode UnexpectedLoader IOException cdn-tnmr"
echo.

echo ============================================
echo === HLS Loading ===
echo ============================================
%ADB% logcat -d | findstr /i "HlsMediaSource HlsPlaylistTracker HlsSampleStream"
echo.

echo ============================================
echo === All LuluVdo mentions ===
echo ============================================
%ADB% logcat -d | findstr /i "LuluVdo"
echo.

pause
