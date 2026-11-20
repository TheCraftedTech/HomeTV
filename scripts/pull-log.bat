@echo off
rem ============================================================================
rem Home TV - Log und Messwerte vom Fire TV holen (rein lesend, installiert/aendert nichts)
rem   scripts\pull-log.bat              -> IP aus firetv-ip.txt
rem   scripts\pull-log.bat 192.168.1.134
rem Ergebnis in tests\data\:
rem   firetv-hometv.log      App-Log (Start, Tasten "Key", TIMELINE je Sender-Start, STATS alle 10 s, Fehler)
rem   firetv-logcat.txt      logcat des Geraets
rem   firetv-udp-counters.txt  UDP-Zaehler (RcvbufErrors = lokal verworfene Pakete)
rem Voraussetzung: Home TV ab Version 1.2.0 ist installiert (enthaelt den Log-Abruf-Empfaenger).
rem ============================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
set "ANDROID_USER_HOME=%ROOT%\.android-user"
set "PATH=%ROOT%\tools\platform-tools;%PATH%"

set "IP=%~1"
if "%IP%"=="" if exist "%ROOT%\firetv-ip.txt" set /p IP=<"%ROOT%\firetv-ip.txt"
if "%IP%"=="" (
    echo [FEHLER] Keine IP. Trage sie in firetv-ip.txt ein oder gib sie als Argument an.
    exit /b 1
)
set "TARGET=%IP%:5555"
adb connect %TARGET% >nul
if not exist "%ROOT%\tests\data" mkdir "%ROOT%\tests\data"

echo [1/3] App-Log abrufen (Broadcast an Home TV, Ausgabe ins logcat) ...
adb -s %TARGET% logcat -c -b main
adb -s %TARGET% shell am broadcast -n de.thecraftedtech.hometv/.LogDumpReceiver -a de.thecraftedtech.hometv.DUMP_LOG --ei bytes 150000 >nul
ping -n 3 127.0.0.1 >nul
adb -s %TARGET% logcat -d -v raw -s HomeTVLog:I > "%ROOT%\tests\data\firetv-hometv.log"

echo [2/3] logcat des Geraets holen ...
adb -s %TARGET% logcat -d -v threadtime > "%ROOT%\tests\data\firetv-logcat.txt"

echo [3/3] UDP-Zaehler des Geraets ...
adb -s %TARGET% shell "cat /proc/net/snmp | grep -E '^Udp:'" > "%ROOT%\tests\data\firetv-udp-counters.txt"
type "%ROOT%\tests\data\firetv-udp-counters.txt"

echo.
echo Fertig: tests\data\firetv-hometv.log
echo Wichtige Zeilen:
findstr /C:"TIMELINE" /C:"STATS" /C:"Fehler" "%ROOT%\tests\data\firetv-hometv.log" 2>nul
endlocal
