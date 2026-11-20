# Home TV

Native Android-App (Kotlin, LibVLC) für **Amazon Fire TV**, die Live-TV direkt von einer **FRITZ!Box 6660 Cable**
(DVB-C, SAT>IP) wiedergibt – ohne zusätzlichen Streamingserver und ohne Transkodierung.

**Stand: Prototyp 1.0.0.** Die App öffnet beim Start direkt *Das Erste HD*. Keine Senderliste, kein EPG, keine Favoriten.
Entwickelt von Samuel · Paket `de.thecraftedtech.hometv`.

> ⚠️ **Teststatus:** gebaut ✅ · Stream/Client am Laptop gegen die echte Box getestet ✅ ·
> **auf dem Fire TV installiert/abgespielt: noch nicht getestet** ⬜ (siehe „Teststatus").

## So funktioniert es (kurz)
Die FRITZ!Box stellt ihre Sender als **SAT>IP** bereit: RTSP (Port 554) + RTP/UDP mit MPEG-TS (H.264 + AC-3).
Die Senderliste (`http://<box>/dvb/m3u/tvhd.m3u`) liefert die Stream-Adressen. Ein kleiner eigener SAT>IP-Client in der App
(Handshake, NAT-Punch, Keep-Alive, sauberes TEARDOWN) reicht den TS-Strom lokal an **LibVLC** weiter, das Bild und Ton ausgibt.
Details: [docs/player-technology.md](docs/player-technology.md), Messungen: [docs/stream-analysis.md](docs/stream-analysis.md).

## Voraussetzungen
- Windows-Rechner mit **JDK 21** (`C:\Program Files\Eclipse Adoptium\jdk-21…`), Python 3 (nur für Hilfsskripte), FFmpeg/ffplay (optional).
- Alles Weitere liegt **im Projektordner**: Android SDK `.android-sdk\`, Gradle-Cache `.gradle-home\`, ADB `tools\platform-tools\`, ADB-Schlüssel `.android-user\`.
- Fire TV (getestet: Fire TV Stick 4K Max, Fire OS 8 / Android 11) mit aktiviertem **ADB-Debugging**: [docs/firetv-setup.md](docs/firetv-setup.md).
- FRITZ!Box mit DVB-C-TV, im Netz des Fire TV erreichbar (Standard-Adresse `192.168.200.1`).

## Bauen
```
scripts\build-release.bat        →  build\HomeTV.apk   (signiert, Version 1.0.0)
```
Details und Signierschlüssel: [docs/build-and-install.md](docs/build-and-install.md).

## Installieren / Starten
```
scripts\test-adb.bat             (Verbindung prüfen, installiert nichts)
scripts\install-firetv.bat       (adb install -r; IP aus firetv-ip.txt)
```
Start: Fire TV → Apps → **Home TV**. Fernbedienung: OK = Info-Overlay, Play/Pause, Zurück = schließen/beenden, Menü = Modus wechseln.
Eine genaue Anleitung samt Checkliste für den Fernseher: [docs/build-and-install.md](docs/build-and-install.md).

## Netzwerk-Konfiguration
- **FRITZ!Box-Adresse:** Standard `192.168.200.1` (`gradle.properties`, `fritzboxHost`); pro Build änderbar mit
  `scripts\build-release.bat -PfritzboxHost=192.168.x.y`.
- **Fire-TV-Adresse:** `firetv-ip.txt` (nicht in Git).
- **UDP:** Die Box liefert nur RTP über **UDP** (TCP wird abgelehnt). Steht ein Router mit NAT zwischen Fire TV und Box, muss ein
  „Punch"-Paket gesendet werden – das übernimmt der SAT>IP-Client der App (ohne Punch kamen in den Tests 0 Pakete an).
- Die App benötigt `INTERNET` und erlaubt Klartext-HTTP (Senderliste im lokalen Netz). Es werden keine Google Play Services benötigt.

## Hilfsprogramme
| Datei | Zweck |
|---|---|
| `scripts/test-stream.py` | M3U lesen, Das Erste HD finden, RTSP prüfen, optional ffplay (`--play`). Nur Standardbibliothek. |
| `tools/rtsp_probe.py` | roher RTSP-Dialog gegen die Box (`udp\|tcp`, `punch`) – zur Fehlersuche |
| `app/src/test/.../LiveSatIpTest.kt` | Integrationstest gegen die echte Box (`HOMETV_LIVE_TEST=1`) |

Beispiele: `.venv\Scripts\python.exe scripts\test-stream.py --play`

## Teststatus (ehrlich getrennt)
| Ebene | Status |
|---|---|
| Auf dem Laptop getestet (ffmpeg/ffplay, 120 s) | ✅ Bild + Ton, stabil im sauberen Lauf |
| SAT>IP-Client-Code gegen die echte Box (JVM, 30 s, TEARDOWN) | ✅ |
| Erfolgreich gebaut (signierte Release-APK) | ✅ `build\HomeTV.apk` |
| Auf dem Fire TV installiert | ⬜ nicht durchgeführt (erst nach deiner Freigabe) |
| Auf dem Fire TV wiedergegeben (Bild, Ton, Fernbedienung, Dauerlauf, HW-Dekodierung) | ⬜ **offen** |
| VLC-App auf dem Fire TV als Vergleich | ⬜ offen (VLC 3.6.5 ist installiert) |

## Bekannte Einschränkungen
- Nur *Das Erste HD*; Senderwechsel, Senderliste, EPG folgen erst nach der ersten erfolgreichen Wiedergabe.
- Pause = Standbild; Fortsetzen startet den Stream live neu (kein Timeshift).
- Mehrere Tonspuren/Audiodeskription/Untertitel sind nicht auswählbar.
- Hardwarebeschleunigung wird angefordert, ob sie aktiv ist, ist noch nicht verifiziert.
- Die Box kann nur eine begrenzte Zahl gleichzeitiger Streams; verwaiste Sessions laufen nach 60 s ab.
- Release ohne Code-Verkleinerung (R8 aus), APK ca. 46 MB wegen LibVLC (zwei ABIs).
- LibVLC steht unter LGPL 2.1 – bei Weitergabe der APK Lizenzhinweis beilegen.

## Projektstruktur
```
app/        Android-Modul (Kotlin)         docs/      Dokumentation
scripts/    build-/install-/test-Skripte    tools/     platform-tools (ADB), rtsp_probe.py
tests/      Testdaten (tests/data, nicht in Git)   build/   fertige APK
.android-sdk/ .gradle-home/ .android-user/ .venv/   lokale Werkzeuge (nicht in Git)
```
