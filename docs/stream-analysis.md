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

## 11. Netz-Topologie und UDP-/NAT-Befund (wichtig für Fire TV)

Gemessen am 2026-11-20:

- Laptop (WLAN) `192.168.1.30/24`, Gateway `192.168.1.1`; die FRITZ!Box `192.168.200.1` liegt **einen Router weiter**
  (`tracert`: `192.168.1.1` → `192.168.200.1`). Der Fire TV (`192.168.1.71`, Ethernet) liegt im selben Netz wie der Laptop und
  erreicht die Box (Ping 1,9 ms, 0 % Verlust).
- Eigenes RTSP-Skript (`tools/rtsp_probe.py`): **ohne** Punch-Paket **0 RTP-Pakete in 6 s**; **mit** einem einzigen
  4-Byte-UDP-Paket vom Client-RTP-Port an den Server-RTP-Port: **5690 Pakete in 6 s (10,1 Mbit/s), Payload-Typ 33,
  erstes Paket nach 24 ms**. ffmpeg sendet solche Punch-Pakete automatisch – deshalb funktionierte ffmpeg von Anfang an.
- Ursache: Der Router `192.168.1.1` lässt unaufgefordertes UDP aus Richtung Box nicht durch (zustandsbehafteter NAT/Firewall).
  Das Punch-Paket erzeugt den Rückweg.
- Im VLC-Quellcode (`live555.cpp`, Zweig 3.0.x) habe ich keinen NAT-Punch gefunden; ob die live555-Bibliothek selbst einen
  sendet, ist ungeklärt. Ob die **VLC-App auf dem Fire TV** den Stream in diesem Netz abspielt, ist **ungetestet**.
- Konsequenz: Die Home-TV-App enthält einen eigenen SAT>IP-Client mit Punch (siehe `docs/player-technology.md`).

## 10. Folgerungen für die Player-Wahl

1. Der Stream ist **kein HTTP/HLS/DASH**, sondern SAT>IP-RTSP mit RTP-MPEG-TS über UDP.
2. Media3 `RtspMediaSource` kennt laut Quellcode (`RtpPayloadFormat`) **kein MP2T** → nicht direkt nutzbar.
3. Media3 spielt MPEG-TS (H.264 + AC-3) grundsätzlich; der Weg wäre ein eigener RTSP/RTP-Client in der App,
   der TS-Bytes an einen ExoPlayer-DataSource liefert, oder
4. LibVLC (VLC-Engine, SAT>IP/RTSP + AC-3 in Software).
Details und Entscheidung: `docs/player-architecture.md` (Phase 2).

## 12. Phase 3: Messungen zu UDP, Jitter, Zeitstempeln und Stream-Start

Alle Messungen vom Laptop (WLAN, `192.168.1.30`) mit den Werkzeugen `tools/udp_analyze.py`, `tools/ts_sync_check.py`,
`tools/ts_cc_events.py` (nur Standardbibliothek, rein lesend, sauberes TEARDOWN). Rohdaten (nicht in Git) in `tests/data/`.
**Das ist ausdrücklich nicht der Fire TV** – die entsprechenden Fire-TV-Messwerte liefert die neue App (Overlay + Log).
Die Angabe „UniFi Gateway" stammt von dir; ich habe das Gateway nicht angemeldet untersucht (Router 192.168.1.1 ist der erste Hop).

### 12.1 UDP/RTP-Pfad (Box → Gateway → Laptop)

| Messung | Ergebnis |
|---|---|
| Das Erste HD, 300 s | 296 832 RTP-Pakete, **0 verloren, 0 umsortiert, 0 doppelt**, 0 TS-Fehler, 10,4 Mbit/s |
| Das Erste HD, 120 s (Roh-Mitschnitt) | 100 192 Pakete, **0 verloren**, 2 umsortiert (Distanz klein), 0 doppelt |
| 6 Muxe × 100 s (386/394/402/418/450/474 MHz) | **0 RTP-Verluste auf allen sechs**, 6,2–9,4 Mbit/s |
| Ankunftsabstand (300 s) | Median 0,02 ms, p95 5,9 ms, p99 10,1 ms, **max 31,4 ms**; keine Lücke > 50 ms |
| Empfangsrate je Sekunde | 5,6 … 15,5 Mbit/s (stark variabel: VBR, I-Frame-Bursts), σ = 1,8 Mbit/s |
| PCR-Jitter (Ankunft gegen PCR, Mux-Takt) | p50 7,6 ms · p95 13,2 ms · p99 19,9 ms · p99,9 28,6 ms · **max 33,9 ms** (ZDFneo: p99 48 ms, arte: 30 ms) |
| Taktdrift Box ↔ Client | +9,3 ppm (300 s), +9,8 ppm (120 s) → ≈ 32 ms pro Stunde |

**Folgerung:** Auf dem gemessenen Pfad gibt es weder Paketverlust noch relevantes Reordering. Der gesamte Netzwerk-Jitter
bleibt unter ca. 50 ms. Ein Puffer von 3–5 s ist **für das Netzwerk nicht nötig**; er würde nur die Live-Verzögerung erhöhen.
(Er kann trotzdem sinnvoll sein, wenn auf dem Fire TV Decoder-/CPU-Stalls auftreten – das lässt sich nur dort messen.)
Der Router lässt – wie in Abschnitt 11 gezeigt – RTP nur nach einem Punch-Paket durch (zustandsbehaftete Firewall/NAT); mit Punch und
Wiederholung alle 5 s lief der Pfad in allen Läufen ohne Unterbrechung. Einstellungen am Gateway sind dafür **nicht** nötig.

Hinweis zu Bursts: Die Rate schwankt bis 15,5 Mbit/s; ein Empfangs-Socket muss das Maximum aushalten. Auf Android ist der
Socket-Puffer durch `net.core.rmem_max` begrenzt; die App fordert 4 MiB an, **misst den gewährten Wert** und zeigt ihn im Overlay
(`UDP-Puffer`), liest in einem Thread mit hoher Priorität und gibt die Daten über einen elastischen Puffer an LibVLC weiter.

### 12.2 Zeitstempel im MPEG-TS (Audio/Video-Synchronisation der Quelle)

Roh-Mitschnitt Das Erste HD, 120 s, Original-PCR/PTS (`tools/ts_sync_check.py`):

| PID | Spur | PTS − PCR bei Ankunft (min / Median / max) | PTS-Auffälligkeiten |
|---|---|---|---|
| 5101 | Video H.264 (PCR-PID) | 257 / 702 / 938 ms | 1 Sprung in 5993 PES |
| 5102 | Audio AC-3 (Hauptton) | **58** / 80 / 100 ms | keine (Schritt 192 ms je PES) |
| 5103 | Audio AC-3 | 133 / 152 / 171 ms | keine |
| 5107 | Audio AC-3 | 131 / 151 / 171 ms | keine |

- Audio und Video tragen **gemeinsame PTS auf der PCR-Zeitbasis**; die Quelle ist in sich synchron (keine PTS-Sprünge im Audio).
- Das Video wird im Mux **550–620 ms früher** gesendet, als es dem Audio entspricht; der Hauptton kommt nur **58–100 ms vor seiner
  Wiedergabezeit** an. Konsequenz: Der Player muss alle Spuren an der PCR-Uhr ausrichten und mindestens den Jitter (≤ 50 ms) plus
  die Audio-Vorausschau (58 ms) abdecken – **jede zusätzliche Verzögerung, die nur das Audio trifft, erzeugt sofort Versatz**.
  Das spricht dafür, die Synchronisation LibVLC zu überlassen (PCR-Uhr), keine getrennten Puffer für Audio und Video einzubauen
  und nur einen **einheitlichen** Eingangspuffer (`network-caching`/`tcp-caching`) zu verwenden.
- Ein Mindest-Eingangspuffer von etwa 200–300 ms genügt der Quelle; VLC-Standard 1000 ms hat reichlich Reserve.

### 12.3 Fehlende TS-Pakete: nur beim Stream-Start

TS-Continuity-Fehler (= fehlende TS-Pakete im Strom, obwohl alle RTP-Pakete ankamen, also **vor dem Netzwerk** entstanden):

| Mux | Fehler gesamt (100 s) | davon in den ersten ~1 s | danach |
|---|---|---|---|
| 386 Das Erste HD (120 s-Mitschnitt) | 12 | 12 (bei 0,0 s und 0,93 s) | **0** |
| 386 Das Erste HD (300 s / 100 s Läufe) | 0 / 0 | – | 0 |
| 402 MDR Sachsen HD (90 s Mitschnitt) | 9 | 9 (bei 0,93 s) | **0** |
| 474 ZDFneo HD (90 s Mitschnitt) | 0 | – | 0 |
| 394 tagesschau24 / 418 arte / 450 ZDF | 6 / 9 / 0 | (nicht zeitlich aufgelöst; gleiches Muster erwartet) | – |

- Der laufende Strom ist sauber (0 Fehler über Minuten); **bei 6 von 10 gemessenen Stream-Starts** enthält der Strom aber kurz nach dem Start
  (in den zeitlich aufgelösten Fällen bei ≈ 0,93 s nach dem ersten PCR, auf zwei verschiedenen Muxen) Lücken in mehreren PIDs inkl. Video; 4 Starts blieben fehlerfrei.
  Ein Decoder, der sofort einsteigt, kann dann auf einem beschädigten ersten GOP beginnen → mögliche **Blockbildung beim Einschalten/Senderwechsel**.
- Gegenmaßnahme in der App (**Start-Gate**, `SatIpSession`): Daten gehen erst an LibVLC, wenn ≥ 1,2 s seit dem ersten Paket vergangen
  sind **und** 0,5 s lang kein TS-Fehler mehr auftrat (spätestens nach 3 s). Kosten: ca. 1,2 s zusätzliche Startzeit pro Sender.
- Der Tuner meldet während der Läufe Pegel 100–106/255, Lock = 1, **Qualität 13/15** (DESCRIBE auf die Stream-URL, `tuner=`-Feld);
  der frühere Leerlaufwert „240 / 7" aus Abschnitt 4 gehört zu einem unbenutzten Tuner und sagt nichts über den Empfang.
  Die Kabelqualität ist laut diesen Werten unauffällig.

### 12.4 Was daraus (nicht) folgt

- Die vom Nutzer beobachteten gelegentlichen Sprünge/Blöcke **mitten** in der Wiedergabe sind mit diesen Messungen **nicht** durch
  Quelle, Kabel oder Netzwerk (Laptop-Pfad) erklärbar. Offen bleiben Ursachen auf dem Fire TV: Empfangssocket/Thread, lokale Übergabe an
  LibVLC (UDP-Loopback kann überlaufen), Hardware-Decoder, A/V-Uhr in LibVLC, WLAN/Ethernet-Pfad des Fire TV.
- Die App erfasst deshalb jetzt getrennt: RTP-Verluste/Reordering (Netz), TS-Fehler im gelieferten Strom (Quelle/Netz),
  Feed-Überlauf (lokal) und LibVLC-Zähler (`demuxCorrupted`, `demuxDiscontinuity`, `lostPictures`, `lostAbuffers`). Aus dem Vergleich dieser
  Zahlen im Log (`STATS`-Zeilen alle 10 s) folgt, **wo** ein Fehler entsteht.
