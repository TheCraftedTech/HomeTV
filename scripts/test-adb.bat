@echo off
rem ============================================================================
rem Home TV - ADB-Verbindung zum Fire TV testen (rein lesend, installiert nichts)
rem   scripts\test-adb.bat              -> IP aus firetv-ip.txt
rem   scripts\test-adb.bat 192.168.1.71 -> IP als Argument
rem ============================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
set "ADB=%ROOT%\tools\platform-tools\adb.exe"
set "ANDROID_USER_HOME=%ROOT%\.android-user"
set "PATH=%ROOT%\tools\platform-tools;%PATH%"

if not exist "%ADB%" (
    echo [FEHLER] ADB nicht gefunden: %ADB%
    echo          Siehe docs\firetv-setup.md ^(Platform-Tools lokal installieren^).
    exit /b 1
)

set "IP=%~1"
if "%IP%"=="" if exist "%ROOT%\firetv-ip.txt" set /p IP=<"%ROOT%\firetv-ip.txt"
if "%IP%"=="" (
    echo [FEHLER] Keine IP-Adresse. Trage die IP des Fire TV in firetv-ip.txt ein
    echo          ^(Fire TV: Einstellungen - Mein Fire TV - Info - Netzwerk^) oder gib sie als Argument an.
    exit /b 1
)
set "TARGET=%IP%:5555"

echo Verbinde mit %TARGET% ...
"%ADB%" connect %TARGET%
ping -n 3 127.0.0.1 >nul

for /f "tokens=2" %%S in ('adb devices ^| findstr /C:"%TARGET%"') do set "STATE=%%S"
if "%STATE%"=="" (
    echo [FEHLER] Keine Verbindung. Ist ADB-Debugging eingeschaltet und der Fire TV an?
    echo          Anleitung: docs\firetv-setup.md
    exit /b 1
)
if "%STATE%"=="unauthorized" (
    echo [HINWEIS] Das Geraet wartet auf deine Freigabe: Auf dem Fernseher erscheint
    echo           "USB-Debugging zulassen?" - mit "Immer von diesem Computer zulassen" bestaetigen,
    echo           dann dieses Skript erneut starten.
    exit /b 2
)
if not "%STATE%"=="device" (
    echo [FEHLER] Unerwarteter Status: %STATE%
    exit /b 1
)

echo.
echo === Verbindung OK ===
for %%P in (ro.product.model ro.build.version.release ro.build.version.sdk ro.build.version.name ro.product.cpu.abilist) do (
    for /f "delims=" %%V in ('adb -s %TARGET% shell getprop %%P') do echo   %%P = %%V
)
echo.
echo Installierte Apps (de.thecraftedtech.hometv / VLC):
"%ADB%" -s %TARGET% shell "pm list packages | grep -E 'hometv|videolan'"
endlocal

