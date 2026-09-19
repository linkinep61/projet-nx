# Build debug + installation sur l'appareil branche, en une commande.
#
#   powershell -ExecutionPolicy Bypass -File outils\build-install.ps1
#   powershell -ExecutionPolicy Bypass -File outils\build-install.ps1 -Lancer
#
# -Lancer : demarre l'app apres l'installation.
#
# RAPPEL APP_LAYOUT (local.properties) : "mobile" pour un telephone,
# "tv" pour la Chromecast, "default" ou vide pour la detection auto.
# Le script affiche la valeur en cours avant de compiler.

param([switch]$Lancer)

$ErrorActionPreference = "Stop"
$racine = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location $racine

$layout = (Select-String -Path "$racine\local.properties" -Pattern '^APP_LAYOUT=' -ErrorAction SilentlyContinue).Line
Write-Output "APP_LAYOUT courant : $layout"

# adb n'est pas toujours dans le PATH selon le shell qui lance le script :
# on le prend dans le SDK declare par local.properties, sinon dans le PATH.
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

& "$racine\gradlew.bat" :app:assembleDebug --console=plain -q
if ($LASTEXITCODE -ne 0) { Write-Output "BUILD ECHOUE"; exit 1 }
Write-Output "BUILD OK"

& $adb install -r "$racine\app\build\outputs\apk\debug\app-debug.apk"
if ($LASTEXITCODE -ne 0) { Write-Output "INSTALLATION ECHOUEE"; exit 1 }

if ($Lancer) {
    & $adb shell monkey -p com.streamfr.app.debug -c android.intent.category.LAUNCHER 1 | Out-Null
    Write-Output "App lancee"
}
