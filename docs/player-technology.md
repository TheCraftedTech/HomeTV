# Player-Technologie – Entscheidung

Stand: 2026-11-20 (Version 1.1.0, Phase 3 unten ab „Phase 3"). Grundlage: `docs/stream-analysis.md` (Messungen an der FRITZ!Box 6660 Cable).

## Entscheidung

**LibVLC (offizielle `org.videolan.android:libvlc-all:3.7.7`) als Decoder/Renderer**, kombiniert mit einem
**kleinen eigenen SAT>IP-Client in Kotlin** (`SatIpSession.kt`), der den Stream beschafft und lokal an LibVLC übergibt.
Als automatischer Fallback kann LibVLC die `rtsp://`-Adresse auch **direkt** öffnen (Modus „direkt").

## Anforderungen aus den Messungen

| Messung | Folge |
|---|---|
| SAT>IP über RTSP, RTP/UDP, MPEG-TS (Payload-Typ 33) | Player muss RTSP + RTP-MP2T können |
| TCP-interleaved wird mit RTSP 461 abgelehnt | nur UDP möglich |
| H.264 High 720p50 | Hardware-Decoder (MediaCodec) oder schneller Software-Decoder |
| AC-3 Stereo (+2 Zusatzspuren) | Player muss AC-3 dekodieren oder durchreichen |
| Session-Timeout 60 s, nur `OPTIONS` als Keep-Alive | Keep-Alive nötig |
| verwaiste Sessions störten die Box | sauberes `TEARDOWN` nötig |
| Router vor der Box filtert unaufgefordertes UDP (0 Pakete ohne Punch, ~10 Mbit/s mit Punch) | NAT-Punch nötig |

## Verglichene Kandidaten

| Kandidat | Ergebnis | Begründung |
|---|---|---|
| **Media3 `RtspMediaSource`** | ❌ scheidet aus | Quellcode `RtpPayloadFormat` kennt AC3, AMR, H263/4/5, MPEG4-Varianten, Opus, PCM, VP8/9 – **kein MP2T**. Zusätzlich verlangt Media3 SPS/PPS im SDP, unser SDP hat weder `rtpmap` noch `fmtp` mit Codec-Daten. (Per Quellcode belegt, nicht auf dem Gerät ausprobiert.) |
| **Media3 ExoPlayer + eigener RTSP/RTP-Client** | möglich, nicht gewählt | ExoPlayer spielt MPEG-TS, aber AC-3 hängt vom Dolby-Decoder des jeweiligen Fire TV ab (Media3 hat ohne FFmpeg-Extension keinen Software-AC-3-Decoder). Mehr Risiko für „Bild ohne Ton". Bleibt die Alternative, falls LibVLC auf dem Gerät scheitert. |
| **LibVLC direkt auf `rtsp://`** | als Fallback eingebaut | VLC/live555 unterstützt SAT>IP; Keep-Alive per `OPTIONS` (VLC nutzt `GET_PARAMETER` nur, wenn der Server es anbietet). Im VLC-Quellcode (`modules/access/live555.cpp`) habe ich **keinen NAT-Punch** gefunden – in deinem Netz (Router vor der Box) könnte das zu „0 Paketen" führen. Auf dem Fire TV ungetestet. |
| **LibVLC + eigener SAT>IP-Client (gewählt)** | ✅ | Der eigene Client erledigt genau die Punkte, die in unserem Netz kritisch sind (Punch, Keep-Alive, TEARDOWN, UDP-Empfang); LibVLC übernimmt Demux, H.264-Hardwaredekodierung, AC-3 (Software-Decoder inklusive) und Rendering. |

## Wie die Kombination arbeitet

```
FRITZ!Box ──RTSP/TCP:554──► SatIpSession (OPTIONS, DESCRIBE, SETUP, Punch, PLAY, Keep-Alive, TEARDOWN)
          ──RTP/UDP──────► SatIpSession (RTP-Header entfernen) ──UDP 127.0.0.1:<port>──► LibVLC (udp://@127.0.0.1:<port>)
                                                                                          └► H.264 (MediaCodec) + AC-3 → Bild/Ton
```

- Kein zusätzlicher Server: alles läuft im Prozess der App; die lokale UDP-Verbindung bleibt auf `127.0.0.1`.
- `PLAY`/`TEARDOWN` gehen an `rtsp://<box>:554/stream=<ID>` (ID aus `com.ses.streamID` der SETUP-Antwort).
- Punch: ein kleines UDP-Paket vom Client-RTP-Port an den Server-RTP-Port (wie ffmpeg), danach alle 5 s wiederholt.
- Keep-Alive: `OPTIONS` mit `Session`-Header alle `timeout/2` (hier 30 s).
- Beim Beenden (`onStop`) zuerst TEARDOWN, dann Player-Freigabe. Gemessen: 274 ms.
- Stillstandserkennung: kommen > 8 s keine RTP-Daten, wird bis zu 2× neu gestartet, dann erscheint eine Fehlermeldung.
- Fallback: kein Bild nach 15 s oder Fehler im SAT>IP-Client → einmal automatisch Wechsel auf „LibVLC direkt"
  (und umgekehrt). **MENU**-Taste schaltet den Modus manuell um.

## Prüfpunkte der Aufgabenstellung

| Punkt | Stand |
|---|---|
| Kompatibilität mit dem Android-Projekt | ✅ Build mit AGP 8.9.3 / Gradle 8.13 / Kotlin 2.2.10 / compileSdk 36. LibVLC 3.7.7 verlangt compileSdk 36 und bringt `kotlin-stdlib 2.2.10` mit – deshalb diese Versionen. `minSdk 22`, `targetSdk 34`. |
| Fire-OS-/Android-Kompatibilität | Gerät: Fire OS 8.1.6 = Android 11 (API 30) → innerhalb `minSdk 22`…`targetSdk 34`. Lauffähigkeit auf dem Gerät **noch nicht getestet**. |
| CPU-Architekturen | Gerät meldet nur `armeabi-v7a` (32 Bit). APK enthält `armeabi-v7a` + `arm64-v8a` (andere Fire-TV-Modelle); x86 ist herausgefiltert. |
| APK-Größe | ca. 46 MB (beide ABIs). Nur `armeabi-v7a` würde sie etwa halbieren – aktuell bewusst nicht gemacht, damit die APK auf allen Fire-TV-Modellen läuft. |
| Lizenz | LibVLC-Android: **LGPL 2.1** (laut Maven-POM). Dynamisch gelinkte Bibliothek, unverändert eingebunden → für privaten Gebrauch unkritisch. Bei einer Weitergabe der APK: LGPL-Hinweis + Bezugsquelle des LibVLC-Codes (`code.videolan.org/videolan/libvlcjni`) beilegen und den Austausch der Bibliothek ermöglichen. |
| Hardwarebeschleunigung | App ruft `media.setHWDecoderEnabled(true, false)` (Hardware bevorzugt, nicht erzwungen). **Ob wirklich MediaCodec benutzt wird, ist noch nicht ermittelt.** Die API liefert dafür kein Flag; die App startet LibVLC mit `-vv` – im Log (`adb logcat -s VLC`) erscheinen Zeilen mit `mediacodec`, wenn der Hardware-Decoder aktiv ist. |
| RTSP sauber schließen | ✅ im eigenen Client (TEARDOWN) – gegen die echte Box getestet (Laptop-JVM), siehe unten. Im Modus „direkt" übernimmt LibVLC das TEARDOWN (ungetestet). |

## Was tatsächlich getestet wurde

| Test | Ergebnis |
|---|---|
| Playlist laden/parsen mit dem App-Code gegen die echte Box (`LiveSatIpTest`, JVM auf dem Laptop) | ✅ „Das Erste HD" gefunden |
| SAT>IP-Client (selber Code wie in der App) gegen die echte Box, 30 s | ✅ 15 167 Datagramme, 5,32 Mbit/s, erstes Paket nach 120 ms, 0 ungültige TS-Datagramme (alle 188-Byte-Pakete beginnen mit `0x47`), 2 verlorene RTP-Pakete |
| TEARDOWN | ✅ 274 ms; DESCRIBE danach zeigt keinen verwaisten Stream |
| Release-Build, Signatur | ✅ siehe `docs/build-and-install.md` |
| **LibVLC-Wiedergabe auf dem Fire TV** | ⬜ **noch nicht getestet** |
| **Hardware-Dekodierung auf dem Fire TV** | ⬜ nicht ermittelt |
| **AC-3-Ton auf dem Fire TV** | ⬜ nicht getestet |
| **Fernbedienung** | ⬜ nicht getestet |

Beim Testen wurde ein echter Fehler gefunden und behoben: In `Socket().apply { connect(InetSocketAddress(host, port)) }` löste `port`
zu `Socket.getPort()` (= 0) auf → Verbindungsfehler. Dieser Fehler wäre auf dem Fire TV genauso aufgetreten.

## Offene Punkte / Risiken

1. LibVLC-Verhalten mit `udp://@127.0.0.1` auf dem Fire TV (Puffergröße `network-caching=800`) – ggf. nachjustieren.
2. Pause bei Live-TV: Die App friert das Bild ein; beim Fortsetzen startet sie den Stream **neu live** (kein Timeshift).
3. Falls beide Modi ohne Bild bleiben: Alternative „Media3 ExoPlayer + eigener Client" (siehe Tabelle) prüfen.

---

# Phase 3 (Version 1.1.0): Synchronisation, Puffer, Übergabe, Senderwechsel

Ausgangslage: Das Erste HD läuft auf dem Fire TV (Version 1.0.0, UDP-Loopback). Beobachtet wurden leichte A/V-Asynchronität sowie
gelegentliche Sprünge, kurze Aussetzer und Blockbildung. **Auf dem Fire TV selbst konnte ich in dieser Runde nichts messen**
(App nicht gestartet, logcat-Puffer des Geräts hatte die frühere Sitzung bereits überschrieben); alle Aussagen unten sind entweder am Laptop
gemessen, aus dem LibVLC-/VLC-Aufbau abgeleitet oder als „ungetestet" markiert.

## A/V-Synchronisation – was geprüft wurde

| Frage | Befund |
|---|---|
| Werden MPEG-TS-Zeitstempel berücksichtigt? | Ja. Wir verändern den TS-Inhalt nicht (nur RTP-Header entfernen); PCR/PTS/DTS gehen unverändert an LibVLC, dessen Eingangsuhr (PCR) Audio und Video ausrichtet. |
| Gemeinsame Zeitbasis in der Quelle? | Ja (Abschnitt 12.2 in `stream-analysis.md`): Video und alle AC-3-Spuren tragen PTS auf der PCR-Basis, keine PTS-Sprünge im Audio. |
| Unterschiedliche Puffer für Audio und Video? | In unserem Code **nein**: ein einziger Eingangspuffer (`network-caching`/`tcp-caching`) vor dem Demuxer; Audio-/Video-Decoder-Puffer verwaltet LibVLC. Keine getrennten Puffer eingebaut (würde die Ausrichtung gefährden). |
| Verzögerung durch die RTP-Verarbeitung? | Nein im Normalfall: Pakete in Reihenfolge werden sofort weitergegeben (kein zusätzlicher Puffer). Nur bei einer Lücke wird ≤ 60 ms (max. 48 Pakete) auf ein nachkommendes Paket gewartet. |
| Reihenfolge der Pakete? | Neu: `RtpResequencer` bringt Pakete in Sequenzreihenfolge (8 Unit-Tests: Reordering, Verlust, Burstverlust, Duplikate, Nummernumlauf, Timeout). Laptop-Messung: 0–2 umsortierte Pakete je 100 000. |
| Paketverluste / unregelmäßige Ankunft? | Laptop-Pfad: 0 Verluste in > 17 Minuten, Jitter ≤ 50 ms (p99). Verluste/Jitter auf dem Fire TV: App misst und zeigt sie (Overlay, Log). |
| Wird die Wiedergabe durch Decoder oder Netzwerk verzögert? | Aus den Messungen nicht eindeutig ableitbar – deshalb Zähler an jeder Stufe (siehe Overlay/Log). Hinweis aus der Quelle: Hauptton kommt nur 58–100 ms vor seiner Wiedergabezeit an. |
| Lippensynchronität konkret | Kann nur am Gerät beurteilt werden. Dafür neu: **Audio-Versatz einstellbar** (◀ ▶ bei geöffnetem Overlay, ±50 ms, gespeichert; `MediaPlayer.setAudioDelay`). Der eingestellte Wert steht im Overlay und im Log, damit sich ein sinnvoller Standardwert ableiten lässt. |

Entscheidung: **keine eigene Synchronisationslogik**; nur (1) unveränderte Zeitstempel, (2) geordnete, vollständige Lieferung, (3) ein einheitlicher
Eingangspuffer, (4) einstellbarer Audio-Versatz als Messinstrument/Notnagel.

## Lokaler Übergabeweg: UDP- vs. TCP-Loopback

Die Bitrate schwankt zwischen 5,6 und 15,5 Mbit/s mit Bursts. Beim UDP-Loopback (Version 1.0.0) liest LibVLC aus einem Socket, dessen Puffer unter
Android auf ca. 0,2–0,4 s begrenzt ist; stockt LibVLC länger (Decoder-Start, GC, CPU-Last des schwachen Fire-TV-SoC), gehen Datagramme **lautlos verloren** –
sichtbar als Blockbildung. **Ob das auf dem Fire TV passiert, ist ungemessen; es ist eine Hypothese**, die sich anhand der neuen Zähler prüfen lässt
(App: TS-Fehler 0, aber VLC „korrupt/Diskont." > 0 → Verlust hinter unserer Zählstelle).

Neu: **TCP-Loopback** (`tcp://127.0.0.1:<port>`, Standard). Verlustfrei mit Rückstau: ein elastischer App-Puffer (24 MiB ≈ 20 s Daten) fängt Stau ab,
Überlauf wird gezählt (`Feed ... verworfen`). Das LibVLC-Modul `tcp` ist in der Bibliothek enthalten (geprüft in `libvlc.so`). Ob es sich auf dem
Fire TV tatsächlich so verhält, ist **ungetestet**; schlägt es fehl, wechselt die App vor dem ersten Bild automatisch TCP → UDP → direkt und merkt sich den
funktionierenden Weg.

| Modus | Pfad | Status |
|---|---|---|
| `tcp` (Standard) | Box → SAT>IP-Client → TCP-Loopback → LibVLC | neu, ungetestet auf dem Fire TV |
| `udp` | Box → SAT>IP-Client → UDP-Loopback → LibVLC | lief auf dem Fire TV (v1.0.0) |
| `direct` | LibVLC öffnet `rtsp://` selbst | Fallback, auf dem Fire TV ungetestet |

## Puffer

**Einstellungen** (LibVLC-Optionen, keine eigene Pufferverwaltung): `:network-caching=N` (UDP/RTSP-Eingang) und `:tcp-caching=N` (TCP-Eingang) – das ist
der `pts-delay`, mit dem LibVLC den Eingangstakt (PCR) puffert. Voreinstellung **2000 ms**; Presets **1000 / 2000 / 3000 / 5000 ms**, umschaltbar mit ▲▼ bei
geöffnetem Overlay (Neustart des Senders), per ADB mit `--ei cacheMs N`.

Was die Messungen für die Wahl hergeben:

| Größe | Wert | Bedeutung für den Puffer |
|---|---|---|
| Netzwerk-Jitter (PCR, Laptop) | p99 ≤ 48 ms, max 34 ms (386 MHz) | schon wenige 100 ms genügen |
| Mux-Versatz Audio-Vorausschau | min 58 ms | Eingangspuffer darf nicht in die Nähe dieses Werts fallen |
| Taktdrift Box ↔ Gerät | 9–10 ppm ≈ 32 ms/h | auch bei 5 h nur ≈ 160 ms – irrelevant gegenüber ≥ 1000 ms |
| Start-Beschädigung der Box | bei 6 von 10 Starts, ≈ 1 s | wird durch das Start-Gate behandelt, nicht durch den Puffer |
| Stalls auf dem Fire TV | **ungemessen** | nur hier hilft ein großer Puffer (er deckt Stillstände bis zu seiner Länge ab) |

Die zusätzliche Live-Verzögerung entspricht dem Puffer (+ Box ≈ 0,1–0,2 s + Start-Gate beim Einschalten ≈ 1,2 s). Die Startzeit wächst ebenfalls mit dem Puffer.
**Ich habe die Werte 1/2/3/5 s nicht mit LibVLC am Gerät verglichen** (Gerätetest lag nicht in meiner Hand; ein Desktop-VLC wurde nicht installiert). Die App
misst dafür „Bild nach X s" je Sender/Puffer, zählt VLC-Verwerfungen und loggt alles; die Entscheidung über den endgültigen Wert sollte nach dem Test auf dem Fernseher fallen.
Begründung für 2000 ms als Ausgangswert: 2 × den VLC-Standard Reserve gegen Stalls, ohne die Verzögerung auf 5 s zu treiben.

## Start-Gate

Siehe `stream-analysis.md`, Abschnitt 12.3: Daten gehen erst an LibVLC, wenn ≥ 1,2 s seit dem ersten Paket vergangen sind und 0,5 s lang kein TS-Fehler auftrat (spätestens nach 3 s).
Verhindert, dass der Decoder auf einem beschädigten ersten GOP startet. Kosten: ≈ 1,2 s Startzeit.

## Senderwechsel

- Senderliste: live von der FRITZ!Box (`/dvb/m3u/tvhd.m3u` + `tvsd.m3u`, HD zuerst, dann SD – dieselbe Struktur wie `tests/data/tv.m3u`, 97 Sender). Rückfall:
  Zwischenspeicher der letzten Abfrage, dann beim Build mitgelieferte Kopie von `tests/data/tv.m3u`. **Keine URLs im Code.**
- **CH+ (Keycode 166)**: nächster Sender, **CH− (167)**: vorheriger, mit Umlauf. Zusätzlich ▲/▼ als Aliase, weil die normale Fire-TV-Fernbedienung keine CH-Tasten hat
  (bei geöffnetem Overlay stellen ▲/▼ den Puffer um). Jede gedrückte Taste wird mit Keycode protokolliert (Log + Overlay „Taste").
- Ablauf je Wechsel: Player stoppen → alte Session per TEARDOWN beenden (Hintergrund-Thread, ≤ 2 s) → erst dann neue Session. Veraltete Wechsel brechen ab
  (`generation`-Zähler): schnelles Umschalten startet nur die zuletzt gewählte Session, es bleiben keine parallelen Streams zurück.
- Anzeige: dezenter Ladekreis + Sendername während des Wechsels; Banner „Nr  Name" 3 s lang nach jedem Wechsel; Sendername im Overlay.
- Verschlüsselte/unempfangbare Sender: nach ≈ 10 s + Puffer Fehlermeldung „Kein Bild: <Sender>", CH+/CH− funktioniert weiter.

## Anzeigen im Overlay (OK-Taste) und im Log

Alle Werte stammen aus der App bzw. LibVLC; nichts ist geschätzt.

| Anzeige | Quelle |
|---|---|
| Sender, Liste, Modus, Puffer | App-Zustand |
| Video (Codec, Auflösung, fps), Audio (Codec, Kanäle, Rate, Sprache) | LibVLC-Tracks (`IMedia.VideoTrack/AudioTrack`) |
| Decoder (Hardware/Software, Codec-Name) | Auswertung der eigenen `logcat`-Zeilen (LibVLC mit `-vv`): `mediacodec…` ⇒ MediaCodec (Hardware), nur `avcodec` ⇒ Software, sonst „unbekannt". Heuristik, **ungetestet auf dem Gerät**; die Rohzeilen stehen im Log (`Decoder`). |
| Netz: Mbit/s, RTP-Pakete, Jitter | Zähler im SAT>IP-Client, Jitter nach RFC 3550 gegen RTP-Zeitstempel |
| RTP: verloren / umsortiert / spät-doppelt | `RtpResequencer` |
| TS: Fehler / Pakete / Sync | `TsInspector` (Continuity Counter je PID, Sync-Byte) im gelieferten Strom |
| UDP-Puffer | tatsächlich gewährter Empfangspuffer (`getReceiveBufferSize`) |
| Feed: wartend / verworfen | lokaler Übergabepuffer |
| Datenzeit / VLC-Zeit | PCR-Zeit der empfangenen Daten bzw. `MediaPlayer.getTime()` – Differenz grob = Datenvorsprung vor der Wiedergabeposition (bei UDP ist der Startpunkt verschieden; Näherung) |
| Start-Gate | Wartezeit/verworfene Daten |
| VLC: korrupt, Diskontinuität, Bilder (verloren), Audio verloren | `IMedia.Stats` |
| Bild nach X s | Zeit vom Sender-Start bis zum ersten Videobild |
| Audio-Versatz | einstellbar, gespeichert |

**„Aktuelle Wiedergabeverzögerung" ist nicht direkt messbar** (es gibt keine Referenzuhr der Sendung). Angezeigt wird der eingestellte Puffer als zusätzliche Verzögerung;
die Differenz Datenzeit − VLC-Zeit ist nur eine Näherung. Zum Nachprüfen: Sendung mit der Uhr eines zweiten Fernsehers vergleichen.

Alle 10 s schreibt die App eine `STATS`-Zeile in `/sdcard/Android/data/de.thecraftedtech.hometv/files/hometv.log` (abholen: `scripts\pull-log.bat`).

## Tests in Phase 3 (tatsächlich durchgeführt)

| Test | Ergebnis |
|---|---|
| Unit-Tests: Resequenzer (8), TS-Inspektor (5), Playlist (3, inkl. echter `tv.m3u`: 97 Sender, Das Erste HD an Position 1) | ✅ bestanden |
| Live-Integrationstest gegen die Box (SAT>IP-Client 1.1 mit Resequenzer, Start-Gate, TEARDOWN; Laptop, 40 s) | ✅ 36 476 RTP-Pakete, 9,33 Mbit/s, **0 verloren**, 2 umsortiert (korrekt einsortiert), 0 spät/doppelt, TS-Fehler 0, Sync-Fehler 0, RFC-3550-Jitter 0,91 ms, größte Lücke 28 ms, TEARDOWN 124 ms; Senderliste live HD=35, SD=62 |
| Netzmessungen (Laptop): 300 s, 120 s, 6 Muxe | ✅ Abschnitt 12 in `stream-analysis.md` |
| Fernbedienung (166/167), Overlay, Pufferumschaltung, Audio-Versatz, TCP-Loopback, Hardware-Decoder, A/V-Sync auf dem Fire TV | ⬜ **ungetestet** |
| LibVLC auf dem Laptop (Puffervergleich) | ⬜ nicht durchgeführt (Desktop-VLC wurde nicht installiert) |