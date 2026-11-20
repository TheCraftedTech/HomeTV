@echo off
rem ============================================================================
rem Home TV - Release-APK bauen  ->  build\HomeTV.apk
rem Alles bleibt im Projektordner (SDK, Gradle-Cache, ADB-Schluessel).
rem ============================================================================
setlocal
cd /d "%~dp0.."
set "ROOT=%CD%"

rem JDK 21 wird fuer Gradle 8.10 benoetigt (JDK 25 wird von dieser Gradle-Version nicht unterstuetzt).
if not defined HOMETV_JAVA_HOME set "HOMETV_JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
if not exist "%HOMETV_JAVA_HOME%\bin\java.exe" (
    echo [FEHLER] JDK 21 nicht gefunden: %HOMETV_JAVA_HOME%
    echo          Setze die Umgebungsvariable HOMETV_JAVA_HOME auf ein JDK 17-21.
    exit /b 1
)
set "JAVA_HOME=%HOMETV_JAVA_HOME%"
set "ANDROID_HOME=%ROOT%\.android-sdk"
set "ANDROID_SDK_ROOT=%ROOT%\.android-sdk"
set "GRADLE_USER_HOME=%ROOT%\.gradle-home"
set "ANDROID_USER_HOME=%ROOT%\.android-user"

if not exist "%ROOT%\keystore.properties" (
    echo [FEHLER] keystore.properties fehlt. Ohne den Signierschluessel kann keine Release-APK gebaut werden.
    echo          Siehe docs\build-and-install.md, Abschnitt "Signierschluessel".
    exit /b 1
)

echo [1/3] Baue Release-APK ...
call "%ROOT%\gradlew.bat" --no-daemon :app:assembleRelease %*
if errorlevel 1 (
    echo [FEHLER] Gradle-Build fehlgeschlagen.
    exit /b 1
)

echo [2/3] Kopiere APK nach build\HomeTV.apk ...
if not exist "%ROOT%\build" mkdir "%ROOT%\build"
copy /Y "%ROOT%\app\build\outputs\apk\release\app-release.apk" "%ROOT%\build\HomeTV.apk" >nul
if errorlevel 1 (
    echo [FEHLER] APK nicht gefunden: app\build\outputs\apk\release\app-release.apk
    exit /b 1
)

echo [3/3] Pruefe Signatur ...
"%ANDROID_HOME%\build-tools\35.0.0\apksigner.bat" verify --print-certs "%ROOT%\build\HomeTV.apk" | findstr /i "Signer DN SHA-256"

echo.
echo Fertig: %ROOT%\build\HomeTV.apk
for %%F in ("%ROOT%\build\HomeTV.apk") do echo Groesse: %%~zF Bytes
endlocal
