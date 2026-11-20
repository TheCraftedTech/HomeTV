# Stream-Analyse FRITZ!Box 6660 Cable – Das Erste HD

Stand: 2026-11-20. Alle Werte stammen aus echten Messungen am Entwicklungsrechner (Windows, FFmpeg 8.1)
gegen `192.168.200.1`. **Nichts davon wurde bisher auf einem Fire TV getestet.**

## 1. Erreichbarkeit

| Prüfung | Ergebnis |
|---|---|
| Ping 192.168.200.1 | erreichbar |
| HTTP :80 | 200, Weboberfläche (Login-Seite) |
| HTTPS :443 | Port offen, Zertifikat nicht vertrauenswürdig (selbstsigniert) |
| 49000 / 49443 | offen (TR-064 – nicht verwendet) |
| RTSP :554 | offen – **hier liegen die TV-Streams** |

## 2. Playlist

AVM-Weg (Wissensdatenbank „Fernsehen mit dem VLC media player"): zwei getrennte Listen für SD und HD.
Beide sind **ohne Login** per HTTP-GET abrufbar:

- `http://192.168.200.1/dvb/m3u/tvhd.m3u` – 35 Sender, `audio/x-mpegurl`
- `http://192.168.200.1/dvb/m3u/tvsd.m3u` – 62 Sender, `audio/x-mpegurl`

Lokal gespeichert unter `tests/data/` (`tvhd.m3u`, `tvsd.m3u`, zusammengeführt `tv.m3u`; Ordner ist per
`.gitignore` ausgeschlossen, da netzwerkspezifisch).

Struktur der Einträge (alle 97 identisch aufgebaut):

```
#EXTINF:0,Das Erste HD
#EXTVLCOPT:network-caching=1000
rtsp://192.168.200.1:554/?avm=1&freq=386&bw=8&msys=dvbc&mtype=256qam&sr=6900&specinv=0&pids=0,16,17,18,20,5100,5101,1170,1176,2171,2172,5102,5103,5104,5105,5107
```

- Nur `#EXTINF` (Name, Dauer 0) und `#EXTVLCOPT:network-caching=1000`.
- **Keine** Logos, **keine** Gruppen, **keine** `tvg-*`-Metadaten.
- Alle Adressen nutzen `rtsp://`, kein HTTP-Streaming.
- Die PID-Liste (`pids=`) ist pro Sender fest: PAT/SDT/EIT (0,16,17,18,20) + Video/Audio/Untertitel des Senders.

## 3. Ausgewählter Sender

`Das Erste HD` (35-Sender-HD-Liste, Eintrag 1). Eine SD-Variante „Das Erste" existiert in `tvsd.m3u` **nicht**
(Suche nach „Erste"/„ARD": nur `ARD alpha HD` in der HD-Liste). Ein SD-Fallback ist für diesen Sender daher nicht möglich.

Stream-URL: siehe `tests/data/tv.m3u` Zeile 4 (Parameter: 386 MHz, 8 MHz Bandbreite, DVB-C, 256-QAM, 6900 kS/s).

## 4. Netzwerk / Protokoll

| Eigenschaft | Wert |
|---|---|
| Server | `SatIPServer:1` (die FRITZ!Box arbeitet als **SAT>IP-Server**) |
| Protokoll | RTSP 1.0 (Steuerung) + RTP/UDP (Daten) |
| Port | 554 (RTSP); RTP-Serverports 5000–5003 (variiert), Client-Ports frei wählbar |
| RTSP-Methoden | OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN (**kein GET_PARAMETER**) |
| Transport | **nur UDP unicast**. `RTP/AVP/TCP;interleaved` wird mit `461 Unkown` abgelehnt |
| SDP | `m=video 0 RTP/AVP 33` – statischer Payload-Typ 33 = **MPEG-2 TS über RTP**, kein `rtpmap`, kein SPS/PPS |
| Session | `Session: <id>;timeout=60` – Keep-Alive nötig |
| Stream-ID | SETUP-Antwort liefert `com.ses.streamID: <n>`; `PLAY`/`TEARDOWN` gehen an `rtsp://192.168.200.1:554/stream=<n>` |
| Content-Type | `application/sdp` (DESCRIBE) |
| Tuner-Status (SDP) | `tuner=1,240,1,7,…` → Signalpegel 240/255, Lock = 1, Qualität 7 (von 15) |

Ablauf gemessen (siehe `tests/data/rtsp-dialog-udp.txt`):
`OPTIONS → DESCRIBE → SETUP (client_port=a-a+1) → PLAY rtsp://…/stream=<id> → RTP/UDP → TEARDOWN`.

## 5. Video

| Eigenschaft | Wert |
|---|---|
| Codec | H.264 / AVC, Profil **High**, Level 4.0 |
| Auflösung | 1280×720, SAR 1:1 (16:9) |
| Bildrate | 50 fps, progressiv |
| Pixelformat / Farbraum | yuv420p, BT.709 |
| Container | MPEG-TS (188-Byte-Pakete, in RTP) |
| Gesamt-Bitrate | ≈ 6,7 Mbit/s (Mux inkl. 3 Tonspuren; 6,76 Mbit/s im 60-s-Mitschnitt) |

## 6. Audio

| PID-Spur | Codec | Kanäle | Rate | Bitrate | Sprache |
|---|---|---|---|---|---|
| Hauptton | AC-3 | 2 (stereo) | 48 kHz | 448 kbit/s | deu |
| Audiodeskription | AC-3 | 2 | 48 kHz | 192 kbit/s | deu (visual impaired) |
| Hörgeschädigte | AC-3 | 2 | 48 kHz | 192 kbit/s | deu (hearing impaired) |

Zusätzlich: DVB-Teletext, DVB-Untertitel, EPG-Datenstrom. **Kein AAC** – der Ton ist ausschließlich AC-3 (Dolby Digital).
Wichtig für Fire TV: Der Player muss AC-3 dekodieren oder durchreichen können.

Tatsächlich Ton vorhanden: `volumedetect` über 60 s → mittlerer Pegel −26,9 dB, Spitze −6,8 dB.

## 7. Wiedergabetests am Laptop

Getestet mit `ffmpeg`/`ffplay` (VLC ist nicht installiert).

| Test | Ergebnis |
|---|---|
| 60 s Mitschnitt per `ffmpeg -rtsp_transport udp -c copy` | OK. Echtzeit (Speed 1,05×), 2965 Videoframes, 59,3 s Spanne, **kein Zeitstempelsprung > 0,08 s**, keine Lücke > 0,1 s |
| Vollständige Dekodierung des Mitschnitts | 2 Meldungen `mmco: unref short failure` – nur am Anfang (Einstieg mitten in die GOP), danach fehlerfrei |
| Standbild bei t = 30 s | Echtes Bild mit ARD-Logo (`tests/data/frame-30s.png`) |
| Zeit bis zum ersten dekodierten Bild | 1,61 s / 1,58 s / 1,65 s (3 Läufe, `probesize 2 MB`) |
| `ffprobe` mit Standardwerten | ≈ 10 s (wartet auf Analyse der ~90 Mux-Programme; für den Player irrelevant) |
| ffplay (Fenster) | ca. 50 s synchron (A-V ≈ −0,02 s, 0 verworfene Frames), danach Stillstand (siehe 8) |

**Wichtig:** Ein Erfolg am Laptop beweist keine Fire-TV-Kompatibilität.

## 8. Stabilität – Auffälligkeiten (in Untersuchung)

Mehrere Folgeläufe sind unregelmäßig hängen geblieben (ffplay nach ≈ 50 s; ein ffmpeg-Lauf nach 12,8 s;
ein weiterer lieferte gar keine Daten). Die Box hielt zu diesem Zeitpunkt nachweislich weitere Streams
(DESCRIBE listete u. a. einen fremden 402-MHz-Stream), und ich hatte mehrere Clients **hart beendet,
ohne TEARDOWN**. Verwaiste Sessions lagen also wahrscheinlich vor. Ursache nicht abschließend belegt –
siehe Abschnitt 9 (Ergebnis des sauberen Dauertests). Die Rohdaten dieser Läufe liegen in
`tests/data/ffplay-60s.log` und `tests/data/ffmpeg-150s.log`.

## 9. Ergebnis Dauertest (sauberer Lauf)

Nach Ablauf der verwaisten Sessions (DESCRIBE zeigte nur noch den Leerlauf-Tuner) erneuter Lauf mit regulärem
Ende (`-t 120`, ffmpeg sendet TEARDOWN):

- Dateigröße wächst gleichmäßig (3,8–10 MiB je 10 s), **kein Stillstand**.
- 120,12 s Dauer, 5945 Videoframes (Spanne 119,0 s), **größte Lücke 0,080 s**, keine Lücke > 0,1 s.
- Bitrate Video + Hauptton: ≈ 4,6 Mbit/s (inhaltsabhängig; im ersten Lauf 6,8 Mbit/s).
- Nur 2× `mmco: unref short failure` beim Einstieg, sonst keine Dekodierfehler.

**Einordnung:** Die Aussetzer aus Abschnitt 8 traten nur auf, nachdem ich Clients hart beendet hatte.
Das stützt die Hypothese „verwaiste SAT>IP-Sessions blockieren/stören den Stream", **bewiesen ist sie nicht**
(die Box ist laut DESCRIBE auch von anderen Clients genutzt worden). Für die App folgt daraus:

- **Immer sauber `TEARDOWN` senden** (auch bei `onStop`/`onDestroy`).
- **Keep-Alive** innerhalb des 60-s-Session-Timeouts (OPTIONS, da GET_PARAMETER nicht angeboten wird).
- Beim Start verwaiste Sessions durch Warten/Retry tolerieren (ggf. erneuter Verbindungsversuch nach ~60 s).

## Zusammenfassung der Testebenen

| Ebene | Status |
|---|---|
| Auf dem Laptop getestet (ffmpeg/ffplay) | ✅ Bild + Ton, 120 s stabil im sauberen Lauf |
| Erfolgreich gebaut (APK) | ⬜ noch nicht begonnen |
| Auf dem Fire TV installiert | ⬜ nicht getestet (kein Gerät/IP bekannt) |
| Auf dem Fire TV wiedergegeben | ⬜ nicht getestet |

Nicht getestet: VLC (nicht installiert), Bild/Ton in einem sichtbaren Fenster (ffplay-Lauf wurde nicht
visuell beobachtet; Bild-Nachweis stammt aus dem extrahierten Standbild), Aussetzer über Wi-Fi.

## 10. Folgerungen für die Player-Wahl

1. Der Stream ist **kein HTTP/HLS/DASH**, sondern SAT>IP-RTSP mit RTP-MPEG-TS über UDP.
2. Media3 `RtspMediaSource` kennt laut Quellcode (`RtpPayloadFormat`) **kein MP2T** → nicht direkt nutzbar.
3. Media3 spielt MPEG-TS (H.264 + AC-3) grundsätzlich; der Weg wäre ein eigener RTSP/RTP-Client in der App,
   der TS-Bytes an einen ExoPlayer-DataSource liefert, oder
4. LibVLC (VLC-Engine, SAT>IP/RTSP + AC-3 in Software).
Details und Entscheidung: `docs/player-architecture.md` (Phase 2).
