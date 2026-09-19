# Journaux cibles, pour le debogage sans bruit.
#
#   powershell -ExecutionPolicy Bypass -File outils\logs-tf1.ps1
#   powershell -ExecutionPolicy Bypass -File outils\logs-tf1.ps1 -Tags "RadioPicker","CarRadio"
#   powershell -ExecutionPolicy Bypass -File outils\logs-tf1.ps1 -Relancer -Attente 60
#
# -Relancer : vide le journal, relance l'app, attend, puis affiche.

param(
    [string[]]$Tags = @("TF1JwtRefresher", "TF1GigyaSession", "ReplayAuth", "TF1Resolver"),
    [switch]$Relancer,
    [int]$Attente = 45
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

if ($Relancer) {
    & $adb logcat -c
    & $adb shell monkey -p com.streamfr.app.debug -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Output "App relancee, attente de $Attente s..."
    Start-Sleep -Seconds $Attente
}

$filtres = @()
foreach ($t in $Tags) { $filtres += "${t}:*" }
$filtres += "AndroidRuntime:E"

& $adb logcat -d -s @filtres | Select-Object -Last 60
