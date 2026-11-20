# Architektur (Version 1.4.0)

## Vorher (1.1.0)
Eine `MainActivity` (~450 Zeilen) enthielt Wiedergabe, Senderwechsel, Overlay, Tasten, Einstellungen und Logging. Neue Funktionen (Senderliste, EPG, Menü) hätten sie unwartbar gemacht.

## Jetzt: Bausteine mit klaren Grenzen

```
MainActivity (Host: Lebenszyklus, Tastenverteilung, verbindet die Bausteine)
 ├─ ui/            InfoBarView · ChannelListView · GuideView (Programmübersicht) · MenuView · Overlays (Debug, Lautstärke, Meldung) · Ui (Designsystem)
 ├─ zap/           ZapController (Vorschau + Entprellung) · HoldTracker (Halte-Beschleunigung)
 ├─ playback/      PlaybackManager (LibVLC, Zustände, Fallbacks, Messwerte) · TuneCoordinator (Abbruch/Serialisierung) · DecoderProbe
 ├─ satip/         SatIpSession (RTSP/RTP) · RtpResequencer · TsInspector · LocalFeed (TCP/UDP-Loopback)
 ├─ channels/      Channel · Playlist (Senderliste laden/cachen/parsen)
 ├─ epg/           XmltvParser · EpgMatcher · EpgCache · EpgData/Programme · EpgRepository
 ├─ settings/      AppSettings (+KeyValueStore) · SettingsModel/SettingItem (Menümodell) · MenuNavigator (Bedienlogik, ohne Android-Abhängigkeit)
 └─ core/          AppLog (Datei + logcat)        LogDumpReceiver (Log-Abruf per ADB)
```

Regeln:
- **Logik ohne Android-Abhängigkeit** (testbar): `ZapController`, `HoldTracker`, `TuneCoordinator`, `RtpResequencer`, `TsInspector`, `XmltvParser`, `EpgMatcher`, `EpgData/EpgCache`, `SettingsModel`, `AppSettings` (über `KeyValueStore`).
- **Android-nahe Teile** sind dünn: `PlaybackManager` (LibVLC), `EpgRepository` (Netzwerk, `Handler`-frei über Executor), Views, `MainActivity`.
- Die UI kennt keine Netzwerk-/RTSP-Details; die Wiedergabe kennt keine Views (nur das `VLCVideoLayout`, das ihr übergeben wird); das EPG kennt weder UI noch Player.

## Threads
| Thread | Aufgabe | Blockiert? |
|---|---|---|
| Haupt-Thread | UI, Tasten, `PlaybackManager`-Zustände, LibVLC-Aufrufe (`play`/`stop`), Timer | nie lange (nur `player.stop()`) |
| `hometv-play` (ein Thread, seriell) | `TEARDOWN` der alten Session (≤ 2 s), Senderliste laden | ja – deshalb nie im UI-Thread |
| `hometv-epg` (ein Thread) | EPG-Download + Parser (niedrige Priorität), Cache laden/schreiben | ja, Minuten möglich – blockiert **nicht** das Beenden von Sessions (eigener Thread) |
| `hometv-play` (Fortsetzung) | seit 1.4: auch `MediaPlayer.stop()` (blockierte im Haupt-Thread ~2 s) | ja |
| `satip-session` | RTSP-Handshake, Keep-Alive, Punch, TEARDOWN | Netzwerk |
| `satip-rtp` | UDP-Empfang → Resequenzer → TS-Inspektor → Feed (Priorität `URGENT_AUDIO`) | blockiert nur auf Socket |
| `feed-tcp` | Übergabe an LibVLC mit Rückstau | blockiert bei langsamem LibVLC (gewollt) |
| `decoder-probe` (kurz) | logcat auslesen, nur Debug-Modus | kurz |

## Senderwechsel und Abbruch
```
Taste ──► ZapController.step()   ──► onPreview(index)  (sofort: Info-Leiste mit EPG, nichts geladen)
          └─ Entprellung 300 ms ──► onCommit(index)    (nur der zuletzt gewählte Sender)
                                      └─► PlaybackManager.tune(index)
                                            ├─ Player.stop()                          (Haupt-Thread)
                                            └─ TuneCoordinator.tune()  → gen++        (Haupt-Thread)
                                                  worker: alte Session.stopBlocking()  (TEARDOWN, hometv-play)
                                                  main  : if (gen == aktuelle gen) neue Session starten
```
- **Generation:** Jede Anforderung erhöht den Zähler. Veraltete Starts werden verworfen, *bevor* sie etwas öffnen; veraltete LibVLC-Ereignisse und Session-Callbacks tragen die Generation und werden ignoriert.
- **Abbruch laufender Verbindungsversuche:** `SatIpSession.stop()` setzt ein Flag, das zwischen OPTIONS/DESCRIBE/SETUP/Punch/PLAY geprüft wird (`ensureRunning`); danach TEARDOWN (falls die Session schon eine ID hat).
- **Keine parallelen Sessions:** Beenden und Starten laufen auf demselben seriellen Worker; die neue Session entsteht erst nach dem Beenden der alten (Test `neverTwoSessionsAtOnce`).
- **Aufräumen:** `onStop`/Bildschirm aus → `PlaybackManager.detach()` (TEARDOWN, Feed schließen, Player freigeben) – synchron, bevor der Prozess pausiert.

## Lebenszyklus
`onStart`: Receiver für Bildschirm an/aus, `attach` (LibVLC), Senderliste laden (oder bestehende nutzen), Sender starten.
`onStop`/`ACTION_SCREEN_OFF`: Wiedergabe beenden, alle Ebenen schließen, Timer entfernen. `ACTION_SCREEN_ON`: Wiedergabe neu starten, falls die Activity gestartet ist.
Der Inaktivitäts-Timer (optional) beendet die Wiedergabe und die Activity nach N Stunden ohne Taste.

## Beibehalten (nicht verändert)
Die SAT>IP-Pipeline (Handshake, NAT-Punch, Keep-Alive, TEARDOWN, Resequenzer, Start-Gate 1,2 s/0,5 s/3 s, TCP-Loopback, `network-caching`/`tcp-caching` 2000 ms, Hardware-Decoder bevorzugt, Audio-Versatz-Funktion,
Fallback-Kette TCP → UDP → direkt) wurde aus Version 1.1.0 übernommen, die auf dem Fire TV mit Bild, Ton und Synchronisation bestätigt wurde. Geändert wurde nur:
1. VLC-Log `-vv` nur noch im Debug-Modus (spart CPU, entlastet logcat).
2. Handshake-Abbruch zwischen den RTSP-Schritten, Start-Gate abschaltbar (Standard an), Zeitstempel der Startphasen.
3. Einstellungsschlüssel `cacheMs`, `audioDelayMs`, `transport` unverändert → bestehende Werte bleiben erhalten.

## Testabdeckung (132 Unit-Tests + 2 Live-Tests)
| Bereich | Tests |
|---|---|
| Resequenzer, TS-Inspektor | `ResequencerTest` (8), `TsInspectorTest` (5) |
| Senderliste | `PlaylistTest` (3), `PlaylistAttributeTest` (1) |
| Senderwechsel/Zap | `ZapControllerTest` (9, virtuelle Uhr), `HoldTrackerTest` (3), `TuneCoordinatorTest` (6: nur neuester startet, Stop vor Start, nie zwei Sessions, `stopNow`, Generationen) |
| Einstellungen | `AppSettingsTest` (6), `SettingsModelTest` (10): Defaults, Persistenz, Schlüssel-Kompatibilität 1.1, Wertebereiche, Debug-Sperre |
| Programmübersicht, Favoriten | `GuideNavigatorTest` (9), `ChannelFilterTest` (3), `FavoritesSettingsTest` (3), `ZapFavouritesTest` (4: CH± überspringt Nicht-Favoriten, `select` nie gefiltert) |
| Menü-Bedienung, Senderquelle | `MenuNavigatorTest` (14: markieren ändert nichts, OK klickt an, Bearbeiten-Modus, Zurück-Ebenen), `SourceSettingsTest` (4), `PlaylistSourceTest` (6, lokaler Testserver), `RemainingTextTest` (1) |
| EPG | `XmltvTimeTest` (5), `XmltvParserTest` (5), `EpgMatcherTest` (9), `EpgDataTest` (5), `EpgRepositoryTest` (10) |
| Gegen die echte FRITZ!Box (nur mit `HOMETV_LIVE_TEST=1`) | `LiveSatIpTest` (2) |
**Nicht automatisch getestet:** die Views (Darstellung, Animationen, Fokus-/Tastenverhalten auf dem Gerät) und die LibVLC-Anbindung – dafür fehlt ein Emulator/Gerät in dieser Umgebung.
