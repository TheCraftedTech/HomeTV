# Home TV

Native Android-App (Kotlin, LibVLC) für **Amazon Fire TV**, die Live-TV direkt von einer **FRITZ!Box 6660 Cable** (DVB-C, SAT>IP) wiedergibt – ohne zusätzlichen Streamingserver und ohne Transkodierung.
Entwickelt von Samuel · Paket `de.thecraftedtech.hometv` · **Version 1.3.0**.

> ⚠️ **Teststatus:** Version 1.1.0 lief auf dem Fire TV (Bild, Ton, Synchronisation, ≈ 5 s bis zum Bild, gute Qualität). **Version 1.3.0 ist gebaut und ihre Logik unit-getestet (112 Tests), aber noch nicht auf dem Fire TV installiert.**
> Die neue Oberfläche habe ich nicht auf einem Fernseher gesehen. Details unter „Teststatus".

## Funktionen
- Live-TV von der FRITZ!Box (97 Sender), Start mit dem zuletzt gesehenen Sender (oder Das Erste HD).
- **Senderwechsel** mit CH+/CH− (und ▲/▼), Gedrückthalten = schneller durchschalten; sofortige Vorschau (Nummer, Name, aktuelle Sendung), geladen wird erst nach kurzer Entprellung → bei schnellem Umschalten startet genau **ein** Sender, alte Sessions werden sauber beendet.
- **Senderliste** am rechten Rand (▶): alle Sender mit Nummer, Name und – wenn verfügbar – laufender Sendung; die Wiedergabe läuft beim Auswählen weiter.
- **Programmführer (EPG)** über eine frei einstellbare **XMLTV-URL**: lokal gecacht, bedingte Aktualisierung, Anzeige in Info-Leiste und Senderliste. Keine voreingestellte URL.
- **Einstellungsmenü** (◀ oder Menü): Allgemein, Wiedergabe, Programmführer, Oberfläche, Debugging. Einträge werden markiert, mit OK angeklickt und dann bearbeitet; die **FRITZ!Box-Adresse** und eine **eigene Senderlisten-URL** (M3U mit rtsp-Adressen) sind einstellbar.
- **Debug-Modus** (Standard aus): technisches Overlay nur per langem Druck auf die Menü-Taste; die mittlere Taste öffnet nur die Info-Leiste. Technische Logs werden immer geschrieben.
- Zuverlässige SAT>IP-Wiedergabe: eigener SAT>IP-Client (NAT-Punch, Keep-Alive, TEARDOWN, Paket-Resequenzierung, Start-Gate), TCP-Loopback mit Rückstau, LibVLC mit Hardware-Decoder und PCR-synchronisierter A/V-Wiedergabe.

## Bedienung (Kurzfassung)
| Taste | Aktion |
|---|---|
| ▲/CH+ · ▼/CH− | Sender wechseln (halten = schneller) |
| ▶ | Senderliste · **OK** in der Liste: Sender starten |
| ◀ oder Menü | Einstellungen |
| OK | Info-Leiste mit Programm ein/aus · im Menü: Eintrag anklicken (Schalter umlegen, Wert bearbeiten) |
| Play/Pause | Standbild / live neu starten |
| Zurück | schließen, sonst beenden |
| Menü lang (Debug-Modus an) | Debug-Overlay |
Vollständig: [docs/ui-and-controls.md](docs/ui-and-controls.md). Was Fire OS an Apps weitergibt (Power-, Lautstärke-, Home-Taste): [docs/firetv-limits.md](docs/firetv-limits.md).

## So funktioniert es (kurz)
Die FRITZ!Box stellt ihre Sender als **SAT>IP** bereit: RTSP (Port 554) + RTP/UDP mit MPEG-TS (H.264 + AC-3). Die Senderlisten (`http://<box>/dvb/m3u/tvhd.m3u` + `tvsd.m3u`) liefern die Stream-Adressen.
Ein kleiner eigener SAT>IP-Client reicht den TS-Strom lokal an **LibVLC** weiter. Architektur: [docs/architecture.md](docs/architecture.md) · Player-Entscheidung und Messungen: [docs/player-technology.md](docs/player-technology.md), [docs/stream-analysis.md](docs/stream-analysis.md) · EPG: [docs/epg.md](docs/epg.md).

## Voraussetzungen
- Windows-Rechner mit **JDK 21** (`C:\Program Files\Eclipse Adoptium\jdk-21…`), Python 3 (nur Hilfsskripte), FFmpeg/ffplay (optional).
- Alles Weitere liegt **im Projektordner**: Android SDK `.android-sdk\`, Gradle-Cache `.gradle-home\`, ADB `tools\platform-tools\`, ADB-Schlüssel `.android-user\`.
- Fire TV (getestet: Fire TV Stick 4K Max, Fire OS 8 / Android 11) mit **ADB-Debugging**: [docs/firetv-setup.md](docs/firetv-setup.md).
- FRITZ!Box mit DVB-C-TV, im Netz des Fire TV erreichbar (Standard `192.168.200.1`).

## Bauen und Installieren
```
scripts\build-release.bat        →  build\HomeTV.apk   (signiert mit dem vorhandenen Release-Schlüssel, Version 1.3.0)
scripts\test-adb.bat             (Verbindung prüfen, installiert nichts)
scripts\install-firetv.bat       (adb install -r – behält die Einstellungen; IP aus firetv-ip.txt)
scripts\pull-log.bat             (Log, TIMELINE/STATS und UDP-Zähler vom Fire TV holen)
```
Genaue Schritte, Signierschlüssel, Checkliste für den Fernseher und Entfernen der lokalen Werkzeuge: [docs/build-and-install.md](docs/build-and-install.md).

## Netzwerk-Konfiguration
- **FRITZ!Box-Adresse:** Standard `192.168.200.1` (`gradle.properties`, `fritzboxHost`; pro Build: `scripts\build-release.bat -PfritzboxHost=192.168.x.y`).
- **Fire-TV-Adresse:** `firetv-ip.txt` (nicht in Git; ändert sich per DHCP – feste Reservierung im Router empfohlen).
- **UDP:** Die Box liefert RTP nur über UDP. Steht ein Router mit zustandsbehafteter Firewall dazwischen, braucht der Client einen „Punch" (macht die App; ohne ihn kamen in den Tests 0 Pakete an).

## Hilfsprogramme
| Datei | Zweck |
|---|---|
| `scripts/test-stream.py` | M3U lesen, Das Erste HD finden, RTSP prüfen, optional ffplay (`--play`) |
| `scripts/pull-log.bat`, `scripts/udp-monitor.ps1`, `scripts/key-monitor.bat` | Log/Messwerte, UDP-Verluste live, Fernbedienungstasten auf Kernel-Ebene (alle rein lesend) |
| `tools/udp_analyze.py`, `tools/ts_sync_check.py`, `tools/ts_cc_events.py`, `tools/startup_timeline.py`, `tools/rtsp_probe.py` | Messwerkzeuge gegen die Box (Verlust, Jitter, Zeitstempel, Startzeit, RTSP-Dialog) |
| `tools/make_icons.ps1` | erzeugt Banner und Icons (ffmpeg) |

## Teststatus (ehrlich getrennt)
| Ebene | Status |
|---|---|
| Version 1.1.0 auf dem Fire TV: Bild, Ton, Synchronisation, Startzeit ≈ 5 s, Senderwechsel grundsätzlich | ✅ (vom Nutzer bestätigt) |
| Unit-Tests: Senderwechsel/Abbruch, Zap-Entprellung, Halte-Beschleunigung, Einstellungen, Menümodell, XMLTV-Parser, Senderzuordnung, EPG-Cache und -Repository (lokaler HTTP-Server), Resequenzer, TS-Inspektor, Playlist | ✅ **112 Tests** |
| SAT>IP-Client gegen die echte FRITZ!Box (Laptop) | ✅ (1.1; 1.2 übernimmt den Client, Live-Test nur mit `HOMETV_LIVE_TEST=1`) |
| Version 1.3.0: signierte Release-APK gebaut | ✅ `build\HomeTV.apk` |
| Version 1.3.0 auf dem Fire TV installiert | ⬜ offen (nur durch dich) |
| Neue Oberfläche (Info-Leiste, Senderliste, Menü, Animationen, Tastenverhalten, Hold-Zapping), Programmführer mit echter XMLTV-Quelle, Inaktivitäts-Timer | ⬜ **ungetestet auf dem Fire TV** |
| Power-/Lautstärke-/Stumm-Tasten in der App | ⬜ ungeprüft; laut Amazon nicht an Apps (siehe `firetv-limits.md`) |
| Icon-Aktualisierung nach dem Update | ⬜ offen (Ursache liegt laut Befund im Launcher-/Appstore-Cache, nicht in der APK) |
| Längerer Dauerbetrieb, Puffervergleich 1–5 s, sehr häufiger Senderwechsel | ⬜ offen |

## Bekannte Einschränkungen
- Keine mehrtägige Programmübersicht (TV-Guide) – nur laufende/nächste Sendung; manuelle EPG-Zuordnung nur per ADB (`--es epgMap`), noch keine Oberfläche dafür.
- Die FRITZ!Box-Senderliste enthält keine Logos und keine `tvg-id`; EPG-Zuordnung läuft über Namen (siehe `epg.md`). Senderlogos sind vorbereitet (`tvg-logo`), werden aber noch nicht geladen/angezeigt.
- Power-, Lautstärke- und Home-Taste erreichen Apps unter Fire OS nicht; bei ausgeschaltetem Fernseher bleibt der Fire TV an (CEC „automatische Geräteabschaltung" aus) – siehe `firetv-limits.md`, dort auch der optionale Inaktivitäts-Timer.
- Pause = Standbild; Fortsetzen startet den Stream live neu (kein Timeshift). Mehrere Tonspuren/Untertitel nicht wählbar.
- Nach dem Einschalten/Senderwechsel ≈ 1,5 s Start-Gate + Puffer (≈ 5 s bis zum Bild).
- Release ohne Code-Verkleinerung (R8 aus), APK ca. 46 MB wegen LibVLC (zwei ABIs). LibVLC steht unter LGPL 2.1 – bei Weitergabe Lizenzhinweis beilegen.

## Projektstruktur
```
app/        Android-Modul (Kotlin, Pakete: ui, zap, playback, satip, channels, epg, settings, core)
docs/       Dokumentation              scripts/  build-/install-/Mess-Skripte      tools/  Messwerkzeuge, platform-tools (ADB)
tests/      Testdaten (tests/data, nicht in Git)         build/  fertige APK
.android-sdk/ .gradle-home/ .android-user/ .venv/ keystore/   lokale Werkzeuge und Schlüssel (nicht in Git)
```
