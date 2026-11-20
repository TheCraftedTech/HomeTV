# Player-Technologie – Entscheidung

Stand: 2026-11-20. Grundlage: `docs/stream-analysis.md` (Messungen an der FRITZ!Box 6660 Cable).

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
