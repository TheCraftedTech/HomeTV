# Oberfläche und Fernbedienung (Version 1.3.0)

Leitgedanke: Das Fernsehbild steht im Mittelpunkt; alles andere erscheint nur bei Bedarf, ist groß und ruhig, und die Fernbedienung verhält sich wie bei einem Fernseher.
**Status:** Die Oberfläche ist gebaut und kompiliert, die Logik (Senderwahl, Einstellungen, Menümodell) ist unit-getestet. **Wie sie auf dem Fernseher aussieht und sich anfühlt, habe ich nicht gesehen** –
das muss der Test auf dem Fire TV zeigen.

## Designsystem
Dunkle Flächen (Navy `#101626`), Akzentblau `#2E7BFF`, helle Schrift (`#F2F4F8`, gedämpft `#9AA3B5`), abgerundete Panels, Texte 12–28 sp (ab 1.3.0 bewusst kompakt: Menü und Senderliste zeigen viel mehr Zeilen, auf großen Fernsehern bleibt das gut lesbar),
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

### Einstellungen (links) – seit 1.3.0: markieren, anklicken, bearbeiten
Drei Ebenen, jeweils farblich unterscheidbar: **blau = markiert** (nur Auswahl, ändert nichts), **amber mit Rahmen und ◄ Wert ► = angeklickt/Bearbeiten-Modus**, Schalter als **grüne „Ein"-/graue „Aus"-Kapsel**.
| Ebene | Taste | Aktion |
|---|---|---|
| Bereiche | ▲ / ▼ | Bereich wählen |
| | ▶ / OK | in die Einträge wechseln |
| | ◀ / Zurück / Menü | Einstellungen schließen |
| Einträge | ▲ / ▼ | Eintrag markieren (**ändert nie einen Wert**) |
| | OK | **Schalter:** sofort Ein/Aus · **Auswahl/Zahl:** Bearbeiten-Modus · **Textfeld:** Eingabedialog · **Aktion:** ausführen |
| | ◀ / Zurück | zurück zu den Bereichen |
| Bearbeiten | ◀ ▶ (oder ▼ ▲) | Wert ändern (Audio-Versatz in 50-ms-Schritten) |
| | OK / Zurück | Bearbeiten beenden (Wert bleibt) |
Die Bedienlogik steckt in `settings/MenuNavigator` (ohne Android, 14 Unit-Tests); gehaltenes OK/Zurück löst nichts mehrfach aus. Unten im Menü steht je nach Ebene und Eintrag eine Tastenzeile.

### Debug-Overlay (nur wenn Debug-Modus an **und** Menü lang gedrückt)
▲▼ Puffer (1000/2000/3000/5000 ms), ◀▶ Audio-Versatz ∓/± 50 ms, OK/Zurück schließen, CH± wechselt weiter den Sender.

## Senderwechsel („schnelles Umschalten")
1. Jeder Druck auf CH+/CH−/▲/▼ ändert sofort die **vorgemerkte Auswahl** und blendet Nummer, Sendername und (bei aktivem Programmführer) die aktuelle Sendung ein – **noch bevor** etwas geladen wird.
2. Geladen wird erst, wenn **300 ms** (einstellbar 150–800 ms) keine weitere Taste kam. Fünfmal CH+ innerhalb kurzer Zeit startet also genau **einen** Sender, den fünften.
3. Gedrückthalten: erster Schritt sofort, dann 250 ms, ab 0,8 s 160 ms, ab 1,8 s 110 ms, ab 3 s 70 ms Abstand (nie schneller als die Anzeige folgen kann); geladen wird erst nach dem Loslassen/Innehalten.
4. Kehrt die Auswahl zum laufenden Sender zurück, wird nichts neu geladen.
5. Wird ein Wechsel angefordert, während ein anderer noch lädt, bricht der alte ab (Handshake-Abbruch zwischen den RTSP-Schritten), die alte Session wird per TEARDOWN beendet, **erst danach** startet die neue.
   Es laufen nie zwei Streams gleichzeitig (Tests: `TuneCoordinatorTest`, `ZapControllerTest`).

## Senderliste (ab 1.3.0)
Schwebende Karte rechts (340 dp breit, 20 dp Randabstand, Rand und abgerundete Ecken – man sieht auch bei Overscan, wo sie endet). Je Sender eine Zeile mit Nummer und Name;
mit aktivem Programmführer zusätzlich die laufende Sendung, ein **Fortschrittsbalken** und die **Restzeit** („noch 23 min", „noch 1:05 h"). Oben rechts die **Uhrzeit**; ein **Rollbalken** zeigt die Position.
Uhr und Balken aktualisieren sich alle 20 s, solange die Liste offen ist. Der blaue Streifen links markiert den laufenden Sender.

## Info-Leiste
Unten, 5 s sichtbar (3/5/8/12 s einstellbar), bleibt während des Ladens stehen und blendet nach dem ersten Bild aus. Zeigt Nummer, Name, Status (Lädt/Live/Pausiert),
und – wenn der Programmführer aktiv ist – laufende Sendung mit Zeit, Fortschrittsbalken, Kurzbeschreibung und „Danach". Ohne Programmführer erscheint nur Nummer und Name.
Aktualisiert sich alle 20 s (Fortschritt, Wechsel der Sendung).

## Einstellungsmenü
| Bereich | Einträge |
|---|---|
| Allgemein | Version (nur die Nummer) · **Entwickler: Samuel Zenker** · Sender (Anzahl/Quelle) · **FRITZ!Box-Adresse** (Standard 192.168.200.1) · **Eigene Senderlisten-URL** (leer = FRITZ!Box) · Beim Start letzter Sender · **Automatisch beenden nach** (Aus/1–12 h) · Senderliste neu laden |
| Wiedergabe | Puffer (1000/2000/3000/5000 ms) · Audio-Versatz · Übergabemodus (TCP/UDP/direkt) · Start-Gate · Umschalt-Wartezeit |
| Programmführer | Status · Ein/Aus · XMLTV-URL · Jetzt aktualisieren · Letzte Aktualisierung · Zugeordnete Sender · Zwischenspeicher · Automatisch aktualisieren · Intervall · Tage im Voraus · Zwischenspeicher löschen |
| Oberfläche | Info-Leiste Anzeigedauer · Senderliste schließt nach · Programm beim Umschalten · Programm in der Senderliste |
| Debugging | **Debug-Modus (Standard: aus)** · Debug-Overlay anzeigen · Bedienhinweis · Log-Datei |

Wiedergabe-Einstellungen (Puffer/Modus/Gate) wirken beim Schließen des Menüs (der aktuelle Sender startet dann neu); der Audio-Versatz wirkt sofort.
Alle bisherigen Einstellungen (Puffer, Audio-Versatz, Übergabemodus) behalten ihre Speicherschlüssel – ein Update übernimmt sie.

### Senderquelle
Standard ist die Senderliste der FRITZ!Box (`http://<Adresse>/dvb/m3u/tvhd.m3u` + `tvsd.m3u`). Ändern kannst du **die FRITZ!Box-Adresse** (nur Host/IP; „http://…" und Pfade werden entfernt) oder trägst eine **eigene M3U-URL** (http/https) ein; dann hat sie Vorrang.
Wichtig: Die Einträge der eigenen Liste müssen **`rtsp://`-SAT>IP-Adressen** sein, weil der eingebaute SAT>IP-Client genau diese abspielt – andere Einträge (z. B. `http://…ts`) werden ignoriert, eine Liste ganz ohne rtsp-Sender wird mit Meldung abgelehnt.
Nach dem Speichern wird die Liste sofort neu geladen. Gibt es den bisherigen Sender in der neuen Liste nicht, startet der Standardsender (Das Erste HD, sonst Nr. 1). Je Quelle gibt es einen eigenen Zwischenspeicher; die mitgelieferte Kopie dient nur als Notnagel für die Standard-FRITZ!Box.
Nur mit der FRITZ!Box getestet (Laptop und Fire TV, Version 1.1); eine fremde M3U-Quelle habe ich nicht, getestet ist der Ladevorgang daher nur gegen einen lokalen Testserver.

## Debug-Modus
Standard **aus**. Ohne ihn erscheint nie ein Debug-Overlay; die mittlere Taste (OK) öffnet nur die Info-Leiste. Eingeschaltet wird er in *Einstellungen → Debugging* (oder per ADB: `--ez debug true`).
Geöffnet wird das Overlay dann durch **langes Drücken der Menü-Taste** (oder *Debug-Overlay anzeigen* im Menü) – eine Aktion, die im normalen Betrieb nie versehentlich passiert.
Die technischen Logs (`hometv.log`: Start, Tasten mit Gerätename/Scancode, `TIMELINE` je Sender-Start, `STATS` alle 10 s, Fehler) werden **immer** geschrieben, unabhängig vom Debug-Modus.
Nur das ausführliche VLC-Log (`-vv`, Decoder-Erkennung) läuft ausschließlich im Debug-Modus (spart CPU und überschwemmt logcat nicht); es greift ab dem nächsten Wiedergabestart.
