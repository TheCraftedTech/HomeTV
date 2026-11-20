# Bauen, Installieren, Starten, Aufräumen

## 1. APK bauen

```
scripts\build-release.bat
```
Ergebnis: **`build\HomeTV.apk`** (Application-ID `de.thecraftedtech.hometv`, **Version 1.1.0 / Code 2**, minSdk 22, targetSdk 34). Dieselbe Signatur wie 1.0.0 (gleicher Keystore) – ein Update mit `adb install -r` behält die App-Daten.
Das Skript setzt `JAVA_HOME` (JDK 21), `ANDROID_HOME`, `GRADLE_USER_HOME` und `ANDROID_USER_HOME` auf Ordner **im Projekt** –
nichts wird global installiert oder verändert. Es prüft am Ende die Signatur.

Voraussetzungen (alles schon vorhanden): JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21…`, sonst
`HOMETV_JAVA_HOME` setzen), lokales SDK in `.android-sdk\`, `keystore.properties` + `keystore\hometv-release.jks`.
Gradle 8.13 lädt der Wrapper beim ersten Lauf in `.gradle-home\`.

Andere FRITZ!Box-Adresse: `scripts\build-release.bat -PfritzboxHost=192.168.x.y`.

### Signierschlüssel (wichtig!)
- Datei: `keystore\hometv-release.jks`, Passwörter in `keystore.properties` – beide **nicht in Git** (`.gitignore`).
- **Sichere beides an einem zweiten Ort.** Updates der App lassen sich nur mit demselben Schlüssel installieren; bei Verlust
  muss die App erst deinstalliert werden (App-Daten gehen verloren).
- Zertifikat (SHA-256): `cbd3c2524c9a792f649e83683b165feb04c1dc5d935e0a5906bf9f8dc37383e5` (Hash nach jedem Build mit `apksigner verify --print-certs` prüfbar).

## 2. Installation auf dem Fire TV (manuell durch dich)

Voraussetzung: ADB-Debugging ist an und der Laptop ist freigegeben (siehe `docs/firetv-setup.md`; bei dir bereits erledigt).

**Weg A – Skript (empfohlen):**
```
scripts\test-adb.bat            (nur prüfen – installiert nichts)
scripts\install-firetv.bat      (installiert bzw. aktualisiert mit "adb install -r"; behält Einstellungen)
```
IP aus `firetv-ip.txt` (aktuell `192.168.1.134`) oder als Argument: `scripts\install-firetv.bat 192.168.1.134`.
Mit `start` als zweitem Argument startet die App direkt nach der Installation.

**Weg B – ADB von Hand:**
```
tools\platform-tools\adb.exe connect 192.168.1.134:5555
tools\platform-tools\adb.exe -s 192.168.1.134:5555 install -r build\HomeTV.apk
```
`-r` ersetzt eine vorhandene Version und **behält die App-Daten**.

**Weg C – ohne ADB:** APK mit der Fire-TV-App „Downloader" laden. Dafür müsste die APK über eine URL erreichbar sein –
dieses Projekt betreibt dafür bewusst keinen Server; Weg A/B ist einfacher.

## 3. App starten

- Auf dem Fire TV: Startbildschirm → *Apps* → **Home TV** (zuletzt installierte Apps stehen ganz hinten; „Alle Apps anzeigen").
- Oder per ADB: `tools\platform-tools\adb.exe -s 192.168.1.134:5555 shell am start -n de.thecraftedtech.hometv/.MainActivity`

Die App öffnet **Das Erste HD** (nach einem Update ggf. mit den zuletzt gespeicherten Einstellungen). Fernbedienung (vollständige Tabelle mit Keycodes: `docs/firetv-setup.md`, Abschnitt 4):

| Taste | Funktion |
|---|---|
| **CH+ / CH−** (Keycode 166 / 167) | nächster / vorheriger Sender (mit Umlauf, 97 Sender); ▲/▼ ohne Overlay als Alias |
| OK (Mitte) | Info-Overlay ein/aus (alle Messwerte); bei Fehleranzeige: erneut versuchen |
| ▲ ▼ (Overlay offen) | Puffer 1000 / 2000 / 3000 / 5000 ms |
| ◀ ▶ (Overlay offen) | Audio-Versatz −/+ 50 ms |
| Menü (≡) | Übergabemodus TCP → UDP → direkt |
| Play/Pause | Bild einfrieren / danach live neu starten |
| Zurück | Overlay schließen, sonst App beenden |

## 4. Was du auf dem Fernseher prüfen sollst

1. **Icon/Banner:** Wie sieht **Home TV** in der Fire-TV-App-Übersicht aus (Schriftzug „Home TV", blauer Strich, nicht abgeschnitten/verzerrt)? Und in *Einstellungen → Anwendungen*?
2. **Start:** Erscheint nach wenigen Sekunden **Das Erste HD**? (Mit dem Start-Gate + 2 s Puffer sind ca. 4–6 s zu erwarten.) OK drücken → Zeile „Bild nach" notieren.
3. **Synchronität:** Ist der Ton lippensynchron? Falls nicht: OK drücken, mit ◀ ▶ den Audio-Versatz verschieben, bis es passt, und den Wert notieren
   (Sprecher in Nachrichten gut geeignet; Tagesschau).
4. **Bildqualität:** Ruckler, Sprünge, kurze Aussetzer, Blockbildung/Kacheln? **Mindestens 5 Minuten** beobachten, auch gleich nach dem Einschalten (Start-Gate!).
5. **Puffer vergleichen:** Overlay offen, ▲ drücken (1000 → 2000 → 3000 → 5000 ms). Wird es mit größerem Puffer ruhiger? Wie stark stört die Verzögerung
   (Vergleich mit einer Uhr/zweitem TV)? Welcher Wert gefällt dir?
6. **Senderwechsel:** CH+ und CH− (falls deine Fernbedienung diese Tasten hat; sonst ▲/▼ ohne Overlay). Erscheint Ladekreis und Banner „Nr Name"? Wie lange bis zum
   Bild? Läuft es nach 10× schnellem Umschalten sauber weiter? Ein Sender ohne Bild (verschlüsselt) sollte „Kein Bild: <Sender>" zeigen.
7. **Overlay:** Zeigt es Sender, Modus, Video (h264 1280x720 50 fps), Audio (AC-3), **Decoder** (MediaCodec/Hardware?), Netz-/TS-/VLC-Zähler? Welcher *Modus* steht dort?
8. **Play/Pause**, **Zurück** (Overlay schließen → App beenden), erneutes Öffnen.
9. Danach `scripts\pull-log.bat` ausführen (oder mir erlauben, das Log per ADB zu lesen) – die `STATS`-Zeilen zeigen, wo Fehler entstehen (siehe `docs/firetv-setup.md`, Abschnitt 6).
## 5. Deinstallation der App
```
tools\platform-tools\adb.exe -s 192.168.1.134:5555 uninstall de.thecraftedtech.hometv
```
oder am Fire TV: Einstellungen → Anwendungen → Installierte Anwendungen verwalten → Home TV → Deinstallieren.

## 6. Lokale Werkzeuge wieder entfernen

Alles liegt im Projektordner; global wurde nichts installiert (nur vorhandene Programme – Python, JDK 21, Gradle 9.8, FFmpeg – wurden
benutzt, aber nicht verändert). Löschen (Explorer oder PowerShell, im Projektordner):

| Ordner/Datei | Inhalt | Größe (ca.) | Entfernen |
|---|---|---|---|
| `.android-sdk\` | Android SDK (Plattform 35/36, Build-Tools, cmdline-tools, platform-tools) | 0,7 GB | `Remove-Item .android-sdk -Recurse -Force` |
| `.gradle-home\` | Gradle 8.13, Abhängigkeits-Cache (inkl. LibVLC) | ca. 3,2 GB | `Remove-Item .gradle-home -Recurse -Force` |
| `.gradle\`, `app\build\` | Build-Zwischendateien | ca. 0,5 GB | löschen (`app\build\`, `.gradle\`, `.kotlin\`) |
| `tools\platform-tools\` | ADB (eigenständige Kopie) | 17 MB | vorher `tools\platform-tools\adb.exe kill-server` |
| `.android-user\` | ADB-Schlüssel dieses Projekts | klein | löschen (Freigabe am Fire TV muss dann neu erteilt werden) |
| `.venv\` | Python-Umgebung (leer, keine Pakete) | < 20 MB | löschen |
| `build\HomeTV.apk` | fertige APK | 46 MB | löschen |
| `keystore\`, `keystore.properties` | **Signierschlüssel – nur löschen, wenn du die App nie wieder aktualisieren willst** | klein | **vorher sichern** |

Zusätzlich beendet `tools\platform-tools\adb.exe kill-server` den ADB-Hintergrunddienst (Port 5037), und
`gradlew --stop` (oder Neustart) beendet Gradle-Daemons.
Der Gradle-Wrapper erzeugt im Projekt nur `gradlew.bat` und `gradle\wrapper\`.

