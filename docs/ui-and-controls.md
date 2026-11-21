# Oberfläche und Fernbedienung (Version 1.5.0)

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
| **▲ / CH+** | nächster Sender (Gedrückthalten: schneller durchschalten; am Ende springt es auf Sender 1) – mit *Kanaltasten vertauschen* umgekehrt |
| **▼ / CH−** | vorheriger Sender (ebenfalls umkehrbar) |
| **▶ (rechts)** | Senderliste öffnen (rechts eingeblendet) |
| **OK lange drücken** / Rücklauf-Taste ⏪ / Guide / TV | **Programmübersicht** (Vollbild-Raster) |
| **Vorlauf-Taste ⏩** | **Favoriten-Leiste** (nur Favoriten, Karten am unteren Rand) |
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
| **OK** (kurz) | ausgewählten Sender starten und Liste schließen |
| **OK lange drücken** | ★ Favorit ein/aus (der Sender wird dabei nicht gestartet) |
| **Menü** | Favoriten-Filter ein/aus (nur Favoriten ↔ alle Sender) |
| ▶ | Programmübersicht öffnen |
| ◀ / Zurück | Liste schließen, nichts ändern |
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

### Programmübersicht (Vollbild)
| Taste | Aktion |
|---|---|
| ▲ / ▼ / CH± | Sender wechseln (Zeile), Zeitpunkt bleibt |
| ◀ / ▶ | vorige / nächste Sendung des Senders (Lücken werden übersprungen); das Zeitfenster scrollt seitenweise mit |
| ⏪ / ⏩ (falls vorhanden) | ± 3 Stunden |
| Bild ↑/↓ | 8 Sender springen |
| **OK** (kurz) | Sender ansehen (die Übersicht schließt sich) |
| **OK lange drücken** | ★ Favorit ein/aus |
| **Menü** | Favoriten-Filter ein/aus |
| Zurück | schließen |

### Favoriten-Leiste (ab 1.5.0)
Karten für alle Favoriten (Nummer, Name, laufende Sendung, Fortschrittsbalken, ★), die gewählte ist größer und blau, der laufende Sender trägt „● LÄUFT". Das Fernsehbild bleibt sichtbar.
| Taste | Aktion |
|---|---|
| ◀ ▶ (auch ▲ ▼) | Favorit wählen; am Rand springt es auf die andere Seite |
| OK (kurz) | Sender ansehen |
| OK lange drücken | aus den Favoriten entfernen |
| Zurück / Menü | schließen (sonst nach der eingestellten Zeit automatisch) |

### Anleitung (Einstellungen → Anleitung → Tastenbelegung anzeigen)
Fünf Seiten (Fernsehbild, Senderliste, Programmübersicht, Favoriten-Leiste, Einstellungen) mit **selbst gezeichneten Tastensymbolen** (Pfeile, OK, Zurück, Menü, Play/Pause, Vor-/Rücklauf, CH+/CH−; lange Druck = amberfarbener Ring + „lang"). ◀ ▶ blättert, Zurück schließt. Die Seite „Fernsehbild" passt sich an *Kanaltasten vertauschen* an.

### Listen-Navigation
In Senderliste, Programmübersicht, Favoriten-Leiste und Einstellungen **springt ▲ ganz oben auf das Ende und umgekehrt – aber nur bei einem neuen Tastendruck**, nicht beim Gedrückthalten (sonst schießt man beim schnellen Scrollen über das Ende hinaus). Abschaltbar: *Einstellungen → Bedienung → Am Listenende umspringen*. Das Umschalten im Fernsehbild springt immer um. Bild ↑/↓ springen nie um.

## Senderwechsel („schnelles Umschalten")
1. Jeder Druck auf CH+/CH−/▲/▼ ändert sofort die **vorgemerkte Auswahl** und blendet Nummer, Sendername und (bei aktivem Programmführer) die aktuelle Sendung ein – **noch bevor** etwas geladen wird.
2. Geladen wird erst, wenn **300 ms** (einstellbar 150–800 ms) keine weitere Taste kam. Fünfmal CH+ innerhalb kurzer Zeit startet also genau **einen** Sender, den fünften.
3. Gedrückthalten: erster Schritt sofort, dann 250 ms, ab 0,8 s 160 ms, ab 1,8 s 110 ms, ab 3 s 70 ms Abstand (nie schneller als die Anzeige folgen kann); geladen wird erst nach dem Loslassen/Innehalten.
4. Kehrt die Auswahl zum laufenden Sender zurück, wird nichts neu geladen.
5. Wird ein Wechsel angefordert, während ein anderer noch lädt, bricht der alte ab (Handshake-Abbruch zwischen den RTSP-Schritten), die alte Session wird per TEARDOWN beendet, **erst danach** startet die neue.
   Es laufen nie zwei Streams gleichzeitig (Tests: `TuneCoordinatorTest`, `ZapControllerTest`).

## Programmübersicht und Favoriten (ab 1.4.0)
**Programmübersicht:** Links die Sender (Nummer, ★, Name), rechts die Sendungen auf einer Zeitachse mit **3 Stunden Sichtfenster**; Zeitleiste mit 30-Minuten-Marken, ein roter Strich markiert „jetzt".
*Bereits gelaufene* Sendungen sind gedimmt, die *laufende* hat eine hellere Fortschrittsfläche, *kommende* sind normal; die gewählte Sendung ist blau mit weißem Rahmen. Unten stehen Sender, Titel, Zeit und Dauer, Status
(„BEREITS GELAUFEN" / „LÄUFT · noch 23 min" / „BEGINNT UM 21:45") und – wenn vorhanden – die Beschreibung. Auswahlbereich: 6 Stunden zurück bis 7 Tage voraus; tatsächlich vorhanden ist, was die XMLTV-Datei und die Einstellung „Tage im Voraus" hergeben (Standard 3).
Ohne XMLTV-URL (oder bei nicht zugeordneten Sendern) zeigt die Übersicht die Sender mit „Keine Programminformation" und einem Hinweis; Favoriten und Umschalten funktionieren auch dann.
**Öffnen:** OK lange drücken (bei laufendem Fernsehbild), ▶ in der Senderliste, Guide-/TV-Taste, oder *Einstellungen → Programmführer → Programmübersicht öffnen*.
**Favoriten:** In Senderliste und Programmübersicht **OK lange drücken** (★ vor dem Namen). Mit der **Menü-Taste** schaltet man dort den Filter „nur Favoriten" ein/aus (Einstellungen → Oberfläche → *Nur Favoriten* ist dasselbe).
Bei aktivem Filter zeigen Liste und Übersicht nur Favoriten und **CH+/CH− (▲▼) überspringen alle anderen Sender**; OK in der Liste/Übersicht startet jeden angezeigten Sender. Gibt es noch keine Favoriten, wirkt der Filter nicht (alle Sender bleiben sichtbar).
Gespeichert werden die Sendernamen (bleiben bei Listenänderungen erhalten); *Einstellungen → Oberfläche → Alle Favoriten entfernen* löscht sie.
Die Übersicht ist komplett selbst gezeichnet (eine View statt hunderter Zellen); die Raster-Navigation steckt in `epg/GuideNavigator` (9 Unit-Tests). Wie sie auf dem Fernseher aussieht, habe ich **nicht gesehen**.

## Senderliste (ab 1.3.0)
Schwebende Karte rechts (340 dp breit, 20 dp Randabstand, Rand und abgerundete Ecken – man sieht auch bei Overscan, wo sie endet). Je Sender eine Zeile mit Nummer und Name;
mit aktivem Programmführer zusätzlich die laufende Sendung, ein **Fortschrittsbalken** und die **Restzeit** („noch 23 min", „noch 1:05 h"). Oben rechts die **Uhrzeit**; ein **Rollbalken** zeigt die Position.
Uhr und Balken aktualisieren sich alle 20 s, solange die Liste offen ist. Der blaue Streifen links markiert den laufenden Sender.

## Info-Leiste
Unten, 5 s sichtbar (3/5/8/12 s einstellbar), bleibt während des Ladens stehen und blendet nach dem ersten Bild aus. Zeigt Nummer, Name, Status (Lädt/Live/Pausiert),
und – wenn der Programmführer aktiv ist – laufende Sendung mit Zeit, Fortschrittsbalken, Kurzbeschreibung und „Danach". Ohne Programmführer erscheint nur Nummer und Name.
Aktualisiert sich alle 20 s (Fortschritt, Wechsel der Sendung).

## Einstellungsmenü (zehn Bereiche seit 1.5.0)
| Bereich | Einträge |
|---|---|
| Allgemein | Version (nur die Nummer) · **Entwickler: Samuel Zenker** · Beim Start letzter Sender · Automatisch beenden nach (Aus/1–12 h) |
| Sender | Senderliste (Anzahl/Quelle) · **FRITZ!Box-Adresse** (Standard 192.168.200.1) · **Eigene Senderlisten-URL** (leer = FRITZ!Box) · Senderliste neu laden |
| Favoriten | Favoriten öffnen · Nur Favoriten · Anzahl · Alle Favoriten entfernen (mit Sicherheitsabfrage) |
| Bedienung | **Kanaltasten vertauschen** · **Am Listenende umspringen** · Umschalt-Wartezeit · Senderliste schließt nach |
| Wiedergabe | Puffer (1000/2000/3000/5000 ms) · Audio-Versatz · Übergabemodus (TCP/UDP/direkt) · Start-Gate |
| Programmführer | Programmübersicht öffnen · Status · Ein/Aus · XMLTV-URL · Jetzt aktualisieren · Letzte Aktualisierung · Zugeordnete Sender · Zwischenspeicher · Automatisch aktualisieren · Intervall · Tage im Voraus · Zwischenspeicher löschen |
| Oberfläche | Info-Leiste Anzeigedauer · Programm beim Umschalten · Programm in der Senderliste |
| Anleitung | **Tastenbelegung anzeigen** (mit Symbolen) · Kurzübersicht der wichtigsten Tasten |
| Zurücksetzen | **Einstellungen zurücksetzen** (Favoriten + letzter Sender bleiben) · **Werkseinstellungen** (alles, inkl. Favoriten und Programmdaten) – beide mit Sicherheitsabfrage, Standardauswahl „Abbrechen" |
| Debugging | **Debug-Modus (Standard: aus)** · Debug-Overlay anzeigen · Bedienhinweis · Log-Datei |

**Kanaltasten vertauschen:** ▲/CH+ schaltet dann zum vorigen, ▼/CH− zum nächsten Sender (gilt im Fernsehbild; Listen bleiben unverändert, dort wandert ▲ immer nach oben).

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
Die technischen Logs (`hometv.log`: Start, Tasten mit Gerätename/Scancode, `TIMELINE` je Sender-Start, `STATS` alle 10 s, Fehler) werden nur geschrieben, wenn *Einstellungen → Debugging → Log-Datei schreiben* **Ein** ist (Standard: Aus, um den Flash-Speicher des Fire TV zu schonen) – unabhängig vom Debug-Modus. Zum Abholen mit `scripts\pull-log.bat` zuerst einschalten, das Problem nachstellen, dann abholen.
Nur das ausführliche VLC-Log (`-vv`, Decoder-Erkennung) läuft ausschließlich im Debug-Modus (spart CPU und überschwemmt logcat nicht); es greift ab dem nächsten Wiedergabestart.
