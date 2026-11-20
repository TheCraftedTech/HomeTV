@echo off
rem ============================================================================
rem Home TV - APK per ADB auf den Fire TV installieren bzw. aktualisieren
rem   scripts\install-firetv.bat                 -> IP aus firetv-ip.txt
rem   scripts\install-firetv.bat 192.168.1.71    -> IP als Argument
rem   scripts\install-firetv.bat 192.168.1.71 start  -> danach App starten
rem Verwendet "adb install -r": App-Daten bleiben bei Updates erhalten.
rem WICHTIG: Dieses Skript installiert wirklich - nur bewusst ausfuehren.
rem ============================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
set "ADB=%ROOT%\tools\platform-tools\adb.exe"
set "APK=%ROOT%\build\HomeTV.apk"
set "PKG=de.thecraftedtech.hometv"
set "ANDROID_USER_HOME=%ROOT%\.android-user"
set "PATH=%ROOT%\tools\platform-tools;%PATH%"

if not exist "%APK%" (
    echo [FEHLER] APK nicht gefunden: %APK%
    echo          Zuerst scripts\build-release.bat ausfuehren.
    exit /b 1
)
if not exist "%ADB%" (
    echo [FEHLER] ADB nicht gefunden: %ADB%
    exit /b 1
)

set "IP=%~1"
if "%IP%"=="start" set "IP="
if "%IP%"=="" if exist "%ROOT%\firetv-ip.txt" set /p IP=<"%ROOT%\firetv-ip.txt"
if "%IP%"=="" (
    echo [FEHLER] Keine IP-Adresse. Trage sie in firetv-ip.txt ein oder gib sie als Argument an.
    exit /b 1
)
set "TARGET=%IP%:5555"

echo [1/4] Verbinde mit %TARGET% ...
"%ADB%" connect %TARGET%
ping -n 3 127.0.0.1 >nul
for /f "tokens=2" %%S in ('adb devices ^| findstr /C:"%TARGET%"') do set "STATE=%%S"
if not "%STATE%"=="device" (
    echo [FEHLER] Geraet nicht bereit ^(Status: "%STATE%"^). Siehe scripts\test-adb.bat und docs\firetv-setup.md.
    exit /b 1
)

echo [2/4] Geraet:
for /f "delims=" %%V in ('adb -s %TARGET% shell getprop ro.product.model') do echo       Modell: %%V

echo [3/4] Installiere %APK% ...
"%ADB%" -s %TARGET% install -r "%APK%"
if errorlevel 1 (
    echo.
    echo [FEHLER] Installation fehlgeschlagen. Bei "INSTALL_FAILED_UPDATE_INCOMPATIBLE" wurde die App
    echo          zuvor mit einem anderen Schluessel signiert ^(dann erst deinstallieren: adb uninstall %PKG%^).
    exit /b 1
)

echo [4/4] Ergebnis:
"%ADB%" -s %TARGET% shell "dumpsys package %PKG% | grep -E 'versionName|firstInstallTime|lastUpdateTime'"
echo.
echo Installation erfolgreich.

if /I "%~2"=="start" (
    echo Starte Home TV ...
    "%ADB%" -s %TARGET% shell am start -n %PKG%/.MainActivity
)
endlocal

