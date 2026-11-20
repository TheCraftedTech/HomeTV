# Home TV

Native Android-App (Kotlin, LibVLC) für **Amazon Fire TV**, die Live-TV direkt von einer **FRITZ!Box 6660 Cable**
(DVB-C, SAT>IP) wiedergibt – ohne zusätzlichen Streamingserver und ohne Transkodierung.

**Stand: Version 1.1.0 (Versionscode 2).** Die App öffnet beim Start *Das Erste HD* und wechselt mit **CH+/CH−** (Keycode 166/167) durch alle 97 Sender der FRITZ!Box-Liste. Kein EPG, keine Favoriten, keine Senderliste-Oberfläche.
Entwickelt von Samuel · Paket `de.thecraftedtech.hometv`.

> ⚠️ **Teststatus:** Version 1.0.0 lief auf dem Fire TV (Das Erste HD mit Bild und Ton). **Version 1.1.0 ist gebaut und am Laptop getestet, aber noch nicht auf dem Fire TV** (siehe „Teststatus").

## So funktioniert es (kurz)
Die FRITZ!Box stellt ihre Sender als **SAT>IP** bereit: RTSP (Port 554) + RTP/UDP mit MPEG-TS (H.264 + AC-3).
Die Senderlisten (`http://<box>/dvb/m3u/tvhd.m3u` + `tvsd.m3u`) liefern die Stream-Adressen. Ein kleiner eigener SAT>IP-Client in der App
(Handshake, NAT-Punch, Keep-Alive, Paket-Resequenzierung, Start-Gate, sauberes TEARDOWN) reicht den TS-Strom lokal (TCP-Loopback mit Rückstau, alternativ UDP) an **LibVLC** weiter, das Bild und Ton ausgibt und Audio/Video über die PCR-Uhr synchronisiert.
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
Start: Fire TV → Apps → **Home TV**. Fernbedienung: CH+/CH− = Sender wechseln, OK = Info-Overlay (▲▼ Puffer, ◀▶ Audio-Versatz), Play/Pause, Menü = Übergabemodus, Zurück = schließen/beenden – Tabelle in [docs/firetv-setup.md](docs/firetv-setup.md).
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
| `tools/udp_analyze.py` | UDP/RTP/TS-Messung: Verlust, Reordering, Jitter (PCR), Tuner-Status, TS-Fehler; `--dump` schreibt den Roh-TS |
| `tools/ts_sync_check.py`, `tools/ts_cc_events.py` | Audio/Video-Zeitstempel (PTS − PCR) bzw. Zeitpunkt von TS-Fehlern im Roh-Mitschnitt |
| `tools/make_icons.ps1` | erzeugt Banner, Launcher-Icon und Adaptive-Icon-Vordergrund (ffmpeg) |
| `scripts/pull-log.bat` | holt App-Log (STATS alle 10 s), logcat und UDP-Zähler vom Fire TV (rein lesend) |
| `app/src/test/.../LiveSatIpTest.kt` | Integrationstest gegen die echte Box (`HOMETV_LIVE_TEST=1`) |

Beispiele: `.venv\Scripts\python.exe scripts\test-stream.py --play`

## Teststatus (ehrlich getrennt)
| Ebene | Status |
|---|---|
| Version 1.0.0 auf dem Fire TV: Das Erste HD mit Bild und Ton | ✅ (vom Nutzer bestätigt); leichte A/V-Asynchronität und gelegentliche Blöcke beobachtet |
| Version 1.1.0: signierte Release-APK gebaut | ✅ `build\HomeTV.apk` |
| Unit-Tests (Resequenzer, TS-Inspektor, Playlist) | ✅ 16 Tests |
| SAT>IP-Client 1.1 gegen die echte Box (40 s, Laptop) | ✅ 0 verloren, 0 TS-Fehler, TEARDOWN 124 ms |
| Netz-/Zeitstempelmessungen (Laptop, WLAN) | ✅ siehe `docs/stream-analysis.md`, Abschnitt 12 |
| Version 1.1.0 auf dem Fire TV installiert | ⬜ offen (nur nach deiner Freigabe) |
| Senderwechsel, TCP-Loopback, Puffer, Audio-Versatz, Hardware-Decoder, A/V-Sync, Dauerlauf auf dem Fire TV | ⬜ **offen** |
| Darstellung des neuen App-Icons/Banners in der Fire-TV-Oberfläche | ⬜ offen |

## Bekannte Einschränkungen
- Keine Senderlisten-Oberfläche, kein EPG, keine Favoriten; Senderwechsel nur sequentiell (CH+/CH−, ▲▼).
- Die Standard-Fire-TV-Fernbedienung hat keine CH-Tasten; deshalb gelten ▲/▼ als Alias (bei geöffnetem Overlay stellen sie den Puffer ein).
- Pause = Standbild; Fortsetzen startet den Stream live neu (kein Timeshift).
- Mehrere Tonspuren/Audiodeskription/Untertitel sind nicht auswählbar.
- Hardwarebeschleunigung wird angefordert; ob sie aktiv ist, zeigt das Overlay („Decoder") – Auswertung per Log-Heuristik, auf dem Gerät noch nicht geprüft.
- Nach dem Einschalten/Senderwechsel ≈ 1,2 s zusätzliche Startzeit (Start-Gate) plus Puffer.
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
