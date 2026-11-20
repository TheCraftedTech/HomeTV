@echo off
rem ============================================================================
rem Home TV - Fernbedienungs-Tasten auf Kernel-Ebene anzeigen (rein lesend, Abbruch mit Strg+C)
rem
rem Zeigt jede Taste, die der Fire TV von der Fernbedienung empfaengt (getevent), z. B. KEY_POWER, KEY_VOLUMEUP,
rem KEY_CHANNELUP, KEY_MUTE. Damit laesst sich pruefen, ob eine Taste ueberhaupt am Fire TV ankommt:
rem   - erscheint KEY_POWER hier, aber die App sieht nichts -> Android behandelt Power systemintern (normal)
rem   - erscheint die Taste hier gar nicht -> die Fernbedienung sendet sie per Infrarot direkt an den Fernseher
rem Die App selbst protokolliert nur, was Android an sie weitergibt:
rem   adb logcat -s Key:I      (nach Abholen mit scripts\pull-log.bat auch im App-Log)
rem ============================================================================
setlocal
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
adb connect %IP%:5555 >nul
echo Druecke jetzt Tasten auf der Fernbedienung (Power, Lauter, Leiser, Stumm, CH+, CH-, Menue ...). Strg+C beendet.
echo.
adb -s %IP%:5555 shell "getevent -lt 2>/dev/null | grep -E 'KEY_|add device|name:'"
endlocal
