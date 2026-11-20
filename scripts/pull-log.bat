@echo off
rem ============================================================================
rem Home TV - Log und Messwerte vom Fire TV holen (rein lesend, installiert/aendert nichts)
rem   scripts\pull-log.bat              -> IP aus firetv-ip.txt
rem   scripts\pull-log.bat 192.168.1.134
rem Ergebnis: tests\data\firetv-hometv.log (App-Log inkl. "STATS"-Zeilen alle 10 s),
rem           tests\data\firetv-logcat.txt (logcat des Geraets), tests\data\firetv-udp-counters.txt
rem ============================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
set "ANDROID_USER_HOME=%ROOT%\.android-user"
set "PATH=%ROOT%\tools\platform-tools;%PATH%"
set "PKG=de.thecraftedtech.hometv"

set "IP=%~1"
if "%IP%"=="" if exist "%ROOT%\firetv-ip.txt" set /p IP=<"%ROOT%\firetv-ip.txt"
if "%IP%"=="" (
    echo [FEHLER] Keine IP. Trage sie in firetv-ip.txt ein oder gib sie als Argument an.
    exit /b 1
)
set "TARGET=%IP%:5555"
adb connect %TARGET% >nul
if not exist "%ROOT%\tests\data" mkdir "%ROOT%\tests\data"

echo [1/3] App-Log holen ...
adb -s %TARGET% pull /sdcard/Android/data/%PKG%/files/hometv.log "%ROOT%\tests\data\firetv-hometv.log"
adb -s %TARGET% pull /sdcard/Android/data/%PKG%/files/hometv.log.1 "%ROOT%\tests\data\firetv-hometv.log.1" >nul 2>&1

echo [2/3] logcat holen ...
adb -s %TARGET% logcat -d -v threadtime > "%ROOT%\tests\data\firetv-logcat.txt"

echo [3/3] UDP-Zaehler des Geraets (Verluste durch zu kleine Socket-Puffer: RcvbufErrors) ...
adb -s %TARGET% shell "cat /proc/net/snmp | grep -E '^Udp:'" > "%ROOT%\tests\data\firetv-udp-counters.txt"
type "%ROOT%\tests\data\firetv-udp-counters.txt"

echo.
echo Fertig. Die letzten STATS-Zeilen:
findstr /C:"I/Stats" "%ROOT%\tests\data\firetv-hometv.log" 2>nul
endlocal

