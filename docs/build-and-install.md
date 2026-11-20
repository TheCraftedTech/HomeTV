# Bauen, Installieren, Starten, Aufräumen

## 1. APK bauen

```
scripts\build-release.bat
```
Ergebnis: **`build\HomeTV.apk`** (Application-ID `de.thecraftedtech.hometv`, **Version 1.2.0 / Code 3**, minSdk 22, targetSdk 34). Dieselbe Signatur wie 1.0.0/1.1.0 (gleicher Keystore, SHA-256 `cbd3c252…`, vor dem Bau gegen die installierte Version geprüft) – ein Update mit `adb install -r` behält die App-Daten.
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

Die App öffnet den zuletzt gesehenen Sender (Einstellung; sonst Das Erste HD). Bedienung: Kurzfassung in der README, vollständig in `docs/ui-and-controls.md`
(CH+/CH− oder ▲▼ = Sender, ▶ = Senderliste, ◀ oder Menü = Einstellungen, OK = Info-Leiste, Menü lang = Debug-Overlay nur bei aktivem Debug-Modus).

## 4. Was du auf dem Fernseher prüfen sollst (Version 1.2.0)

**Wichtig vorab:** Version 1.1.0 lief bei dir gut – bitte zuerst prüfen, dass 1.2.0 **nicht schlechter** ist (Bild nach ≈ 5 s, Ton synchron, keine Sprünge).

1. **Update:** `scripts\install-firetv.bat` (behält Einstellungen). Starten. Kommt das Bild? Wie lange dauert es (OK → Info-Leiste; mit Debug-Modus steht die genaue Zeit im Overlay/Log)?
2. **Icon/Banner:** Zeigt der Startbildschirm/„Apps" jetzt das neue Banner (Schriftzug „Home TV" mit blauem Strich)? Wenn nicht: Fire TV neu starten (*Einstellungen → Mein Fire TV → Neu starten*), danach ggf. Appstore-Cache leeren (siehe `firetv-limits.md`, Abschnitt 2).
3. **Senderwechsel:** CH+/CH− (falls vorhanden) oder ▲▼. Erscheint sofort Nummer + Name (+ Programm)? Dann **5× schnell** CH+ drücken: lädt nur der letzte Sender? Gedrückthalten: schaltet es zügig und kontrolliert durch?
4. **Senderliste:** ▶ drücken. Aktueller Sender markiert? ▲▼ scrollt flüssig (auch halten)? OK startet den Sender, Zurück schließt, ohne dass die Wiedergabe unterbrochen wird?
5. **Einstellungen:** ◀ bzw. Menü. Alle Bereiche erreichbar und gut lesbar? Audio-Versatz ändern, Puffer ändern (Sender startet beim Schließen neu).
6. **Debug:** OK öffnet **nur** die Info-Leiste (kein Debug-Menü mehr). Debug-Modus einschalten (*Einstellungen → Debugging*), danach **Menü lang** drücken → Overlay. Ausschalten → Overlay erscheint nie wieder.
7. **Programmführer (optional):** *Einstellungen → Programmführer → XMLTV-URL* eintragen. Wie viele Sender werden zugeordnet („Zugeordnete Sender")? Erscheint die laufende Sendung beim Umschalten und in der Senderliste?
8. **Tasten:** Kommt Power/Lauter/Leiser/Stumm bei der App an? (`scripts\key-monitor.bat` zeigt die Kernel-Ebene, im App-Log stehen die `Key`-Zeilen.) Was passiert, wenn du den **Fernseher** ausschaltest – bleibt der Fire TV an?
9. **Dauerbetrieb:** mindestens 5 Minuten schauen, besser 30+; danach `scripts\pull-log.bat` (oder mir das Lesen per ADB erlauben) – `STATS`-, `TIMELINE`- und `Key`-Zeilen zeigen, wo etwas hakt. Parallel `scripts\udp-monitor.ps1` für lokale UDP-Verluste.
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

