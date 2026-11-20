# Oberfläche und Fernbedienung (Version 1.2.0)

Leitgedanke: Das Fernsehbild steht im Mittelpunkt; alles andere erscheint nur bei Bedarf, ist groß und ruhig, und die Fernbedienung verhält sich wie bei einem Fernseher.
**Status:** Die Oberfläche ist gebaut und kompiliert, die Logik (Senderwahl, Einstellungen, Menümodell) ist unit-getestet. **Wie sie auf dem Fernseher aussieht und sich anfühlt, habe ich nicht gesehen** –
das muss der Test auf dem Fire TV zeigen.

## Designsystem
Dunkle Flächen (Navy `#101626`), Akzentblau `#2E7BFF`, helle Schrift (`#F2F4F8`, gedämpft `#9AA3B5`), abgerundete Panels, Texte 16–44 sp für den 3-Meter-Abstand,
Einblenden/Ausblenden mit 180 ms (Alpha + leichte Verschiebung). Alle Bausteine in `ui/` (`Ui`, `InfoBarView`, `ChannelListView`, `MenuView`, `Overlays`).
Bewusst **keine** Android-Fokussteuerung: eine zentrale Tastenverteilung (`MainActivity.dispatchKeyEvent`) entscheidet pro Ebene – vorhersehbar, ohne Fokussprünge.

## Ebenen

```
┌ Wiedergabe (LibVLC-Video) ───────────────────────────────────────────┐
│  Ladeanzeige (Mitte) / Fehleranzeige (Mitte)      Lautstärke (oben rechts)
│                                                                         │
│  Einstellungen ◄ (links)                              ► Senderliste (rechts)
│                                                                         │
│  Debug-Overlay (oben links, nur Debug-Modus)                            │
│  ┌ Info-Leiste (unten): Nr · Sender · Status ─ Jetzt/Zeit/Fortschritt ─ Danach ┐
│  Meldung (unten Mitte)                                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Tastenbelegung

### Wiedergabe (nichts geöffnet)
| Taste | Aktion |
|---|---|
| **▲ / CH+** | nächster Sender (Gedrückthalten: schneller durchschalten) |
| **▼ / CH−** | vorheriger Sender |
| **▶ (rechts)** / Guide / TV | Senderliste öffnen (rechts eingeblendet) |
| **◀ (links)** | Einstellungen öffnen (links eingeblendet) |
| **OK** | Info-Leiste ein/aus (bei Fehleranzeige: erneut versuchen) |
| **Menü** (kurz) | Einstellungen |
| **Menü** (lang, nur mit aktivem Debug-Modus) | Debug-Overlay ein/aus |
| **Play/Pause** | Standbild; erneut = live neu starten (kein Timeshift) |
| **Zurück** | Info-Leiste schließen; sonst App beenden |
| Lauter/Leiser/Stumm | Lautstärke-Anzeige und App-Stumm – **nur falls die Tasten die App erreichen** (siehe `firetv-limits.md`) |
| Home | Systemtaste, nicht abfangbar |
| Power | wird nicht an Apps geliefert (siehe `firetv-limits.md`) |

### Senderliste (rechter Rand)
| Taste | Aktion |
|---|---|
| ▲ / ▼ / CH± | Auswahl bewegen (Gedrückthalten beschleunigt; **die Wiedergabe läuft weiter**) |
| Bild ↑/↓ | 8 Einträge springen |
| **OK** | ausgewählten Sender starten und Liste schließen |
| ◀ / Zurück | Liste schließen, nichts ändern |
| Menü | Einstellungen statt Liste |
Schließt sich automatisch nach 15 s Inaktivität (einstellbar: 10/15/30/60 s/nie).

### Einstellungen (links)
| Taste | Aktion |
|---|---|
| ▲ / ▼ | Bereich bzw. Eintrag wählen |
| ▶ / OK | in die Einträge wechseln / Eintrag aktivieren |
| ◀ ▶ | Wert ändern (Schalter, Auswahl, Audio-Versatz in 50-ms-Schritten) |
| ◀ | bei nicht änderbaren Einträgen zurück zu den Bereichen |
| Zurück | Einträge → Bereiche → schließen |
| OK bei Textfeld (XMLTV-URL) | Eingabedialog mit Bildschirmtastatur |

### Debug-Overlay (nur wenn Debug-Modus an **und** Menü lang gedrückt)
▲▼ Puffer (1000/2000/3000/5000 ms), ◀▶ Audio-Versatz ∓/± 50 ms, OK/Zurück schließen, CH± wechselt weiter den Sender.

## Senderwechsel („schnelles Umschalten")
1. Jeder Druck auf CH+/CH−/▲/▼ ändert sofort die **vorgemerkte Auswahl** und blendet Nummer, Sendername und (bei aktivem Programmführer) die aktuelle Sendung ein – **noch bevor** etwas geladen wird.
2. Geladen wird erst, wenn **300 ms** (einstellbar 150–800 ms) keine weitere Taste kam. Fünfmal CH+ innerhalb kurzer Zeit startet also genau **einen** Sender, den fünften.
3. Gedrückthalten: erster Schritt sofort, dann 250 ms, ab 0,8 s 160 ms, ab 1,8 s 110 ms, ab 3 s 70 ms Abstand (nie schneller als die Anzeige folgen kann); geladen wird erst nach dem Loslassen/Innehalten.
4. Kehrt die Auswahl zum laufenden Sender zurück, wird nichts neu geladen.
5. Wird ein Wechsel angefordert, während ein anderer noch lädt, bricht der alte ab (Handshake-Abbruch zwischen den RTSP-Schritten), die alte Session wird per TEARDOWN beendet, **erst danach** startet die neue.
   Es laufen nie zwei Streams gleichzeitig (Tests: `TuneCoordinatorTest`, `ZapControllerTest`).

## Info-Leiste
Unten, 5 s sichtbar (3/5/8/12 s einstellbar), bleibt während des Ladens stehen und blendet nach dem ersten Bild aus. Zeigt Nummer, Name, Status (Lädt/Live/Pausiert),
und – wenn der Programmführer aktiv ist – laufende Sendung mit Zeit, Fortschrittsbalken, Kurzbeschreibung und „Danach". Ohne Programmführer erscheint nur Nummer und Name.
Aktualisiert sich alle 20 s (Fortschritt, Wechsel der Sendung).

## Einstellungsmenü
| Bereich | Einträge |
|---|---|
| Allgemein | Version · Sender (Anzahl/Quelle) · Beim Start letzter Sender · **Automatisch beenden nach** (Aus/1–12 h) · Senderliste neu laden |
| Wiedergabe | Puffer (1000/2000/3000/5000 ms) · Audio-Versatz · Übergabemodus (TCP/UDP/direkt) · Start-Gate · Umschalt-Wartezeit |
| Programmführer | Status · Ein/Aus · XMLTV-URL · Jetzt aktualisieren · Letzte Aktualisierung · Zugeordnete Sender · Zwischenspeicher · Automatisch aktualisieren · Intervall · Tage im Voraus · Zwischenspeicher löschen |
| Oberfläche | Info-Leiste Anzeigedauer · Senderliste schließt nach · Programm beim Umschalten · Programm in der Senderliste |
| Debugging | **Debug-Modus (Standard: aus)** · Debug-Overlay anzeigen · Bedienhinweis · Log-Datei |

Wiedergabe-Einstellungen (Puffer/Modus/Gate) wirken beim Schließen des Menüs (der aktuelle Sender startet dann neu); der Audio-Versatz wirkt sofort.
Alle bisherigen Einstellungen (Puffer, Audio-Versatz, Übergabemodus) behalten ihre Speicherschlüssel – ein Update auf 1.2.0 übernimmt sie.

## Debug-Modus
Standard **aus**. Ohne ihn erscheint nie ein Debug-Overlay; die mittlere Taste (OK) öffnet nur die Info-Leiste. Eingeschaltet wird er in *Einstellungen → Debugging* (oder per ADB: `--ez debug true`).
Geöffnet wird das Overlay dann durch **langes Drücken der Menü-Taste** (oder *Debug-Overlay anzeigen* im Menü) – eine Aktion, die im normalen Betrieb nie versehentlich passiert.
Die technischen Logs (`hometv.log`: Start, Tasten mit Gerätename/Scancode, `TIMELINE` je Sender-Start, `STATS` alle 10 s, Fehler) werden **immer** geschrieben, unabhängig vom Debug-Modus.
Nur das ausführliche VLC-Log (`-vv`, Decoder-Erkennung) läuft ausschließlich im Debug-Modus (spart CPU und überschwemmt logcat nicht); es greift ab dem nächsten Wiedergabestart.
