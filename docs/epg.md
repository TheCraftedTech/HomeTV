# Programmführer (EPG) – XMLTV

**Status:** Parser, Zuordnung, Cache und Aktualisierung sind implementiert und getestet (Unit-Tests und ein Ende-zu-Ende-Test gegen einen lokalen HTTP-Server).
**Nicht getestet:** eine echte XMLTV-Quelle (du hast bisher keine Adresse genannt, und ich habe bewusst keine erfunden oder voreingestellt) sowie die Darstellung auf dem Fire TV.

## Einrichten
*Einstellungen → Programmführer → XMLTV-URL* → OK (Eingabedialog mit Bildschirmtastatur; auch Sprachsteuerung der Fernbedienung funktioniert dort) → *Speichern*.
Danach wird sofort geladen. Alternativ per ADB, ohne Tastatureingabe:
```
adb -s <IP>:5555 shell am start -n de.thecraftedtech.hometv/.MainActivity --es epgUrl "https://…/epg.xml.gz"
```
Unterstützt: `http(s)://`, Weiterleitungen (bis 5), `.xml` und `.xml.gz` (gzip wird am Dateianfang erkannt, auch wenn Endung/Header fehlen).

## Was gespeichert wird
Es wird **nicht** die ganze Datei behalten. Beim Lesen (SAX-Streaming) werden nur Sendungen der **zugeordneten Sender** im Fenster *jetzt − 6 h … jetzt + N Tage* (Standard 3, einstellbar 1–7) aufgehoben, Beschreibung auf 600 Zeichen gekürzt.
Ablage: `files/epg.bin` (kompaktes Binärformat, atomar über Temp-Datei geschrieben). Ein beschädigter oder fehlender Cache ergibt „keine Daten", nie einen Absturz.

Messung (Laptop, JVM, synthetisch): 8,8 MB XMLTV mit 23 232 Sendungen → 251 ms, ca. 4 MB Heap-Zuwachs, 108 Sendungen behalten. Auf dem Fire TV ist die CPU deutlich langsamer; deshalb läuft der Parser
in einem Hintergrund-Thread mit niedriger Priorität und die automatische Aktualisierung erst **25 s nach dem Start**, wenn die Wiedergabe läuft.

## Zuordnung der EPG-Sender zu den SAT>IP-Sendern
Die M3U der FRITZ!Box enthält **keine** `tvg-id`/`tvg-name`, nur den Sendernamen. Deshalb (erste Übereinstimmung gewinnt):
1. manuelle Zuordnung (Einstellung `epgOverrides`, „Sendername=XMLTV-ID", eine pro Zeile; Eingabe derzeit nur per ADB: `--es epgMap "Das Erste HD=DasErste.de"`, mehrere Zeilen mit `\n`)
2. `tvg-id` der M3U = XMLTV-Kanal-ID (falls eine andere M3U-Quelle sie liefert)
3. `tvg-name` = Anzeigename in der XMLTV-Datei
4. Sendername = Anzeigename, **normalisiert** (Kleinschreibung, Umlaute, Satzzeichen, Qualitätszusatz HD/SD/UHD/FHD/4K entfernt): „Das Erste HD" = „Das Erste"
5. Sendername = normalisierte XMLTV-ID („DasErste.de" → „daserste")
6. eindeutige Wort-Teilmenge (nur bei genau einem Kandidaten und ≥ 4 Zeichen) – bewusst vorsichtig, um Fehlzuordnungen zu vermeiden

HD- und SD-Variante eines Senders nutzen dieselben Daten. Das Menü zeigt „Zugeordnete Sender: X von 97". Nicht zugeordnete Sender zeigen „Keine Programminformation".
**Noch offen:** eine Bedienoberfläche zum manuellen Zuordnen (die Speicherung und Auswertung sind vorhanden). Wie viele der 97 FRITZ!Box-Sender tatsächlich zugeordnet werden, hängt von der XMLTV-Quelle ab und ist ungetestet.

## Aktualisierung
- **Automatisch** (Einstellung, Standard an): beim Start der Wiedergabe, wenn noch nie aktualisiert wurde, die Daten älter als das Intervall sind (Standard 12 h; 6/12/24/48) oder die URL geändert wurde.
- **Manuell:** *Jetzt aktualisieren* (erzwingt vollen Download).
- **Bedingte Abfrage:** `If-None-Match`/`If-Modified-Since` mit ETag/Last-Modified der letzten Antwort – ändert sich die Quelle nicht, antwortet der Server `304` und es wird nichts geladen oder verarbeitet.
- **Kein Download beim Senderwechsel:** Abfragen („läuft gerade/danach") lesen nur den Speicher (Binärsuche).
- **Fehler:** URL nicht erreichbar, TLS-Fehler, Zeitüberschreitung, defekte gzip-/XML-Datei oder „keine Sendungen zugeordneter Sender" ergeben eine Fehlermeldung im Status; die **zuletzt gespeicherten Daten bleiben aktiv** und werden weiter angezeigt
  („Fehler: … – gespeicherte Daten vom … werden weiter verwendet").
- Status im Menü: Keine URL / Deaktiviert / Lade und verarbeite … X von Y MB / OK – N Sendungen / Aktuell (Quelle unverändert) / Fehler …; dazu „Letzte Aktualisierung" und „Zwischenspeicher".

## Zeiten
XMLTV-Zeiten „YYYYMMDDHHMMSS ±HHMM" werden in UTC umgerechnet (auch Sommerzeit-Offsets, gekürzte Formen und „Z"); ohne Offset gilt UTC (XMLTV-Vorgabe). Die Anzeige erfolgt in der lokalen Zeitzone des Geräts (Format 24 h).

## Anzeige
- **Beim Senderwechsel** sofort in der Info-Leiste (vor dem Bild): Sendername, Nummer, laufende Sendung mit Beginn/Ende, Fortschritt, Kurzbeschreibung und „Danach" – unabhängig von der SAT>IP-Wiedergabe (schaltbar: *Programm beim Umschalten*).
- **In der Senderliste**: zweite Zeile je Sender mit der laufenden Sendung (schaltbar: *Programm in der Senderliste*).
- Eine mehrtägige Programmübersicht (TV-Guide) ist **nicht** Teil dieser Version; Datenmodell und Cache halten aber bereits mehrere Tage vor.

## Tests
`XmltvTimeTest` (Zeitzonen, Sommerzeit, Schaltjahr, Fehlerfälle), `XmltvParserTest` (Entities, DTD wird nicht geladen, gzip, Fenster, Zuordnung, defektes XML),
`EpgMatcherTest` (Normalisierung, Strategien, Priorität, Overrides, HD/SD), `EpgDataTest` (jetzt/danach, Cache-Rundlauf, defekter Cache, atomares Ersetzen),
`EpgRepositoryTest` (Download, gzip, ETag/304, Cache nach „App-Neustart", Fehler behalten alte Daten, ungültiges XML, nichts zugeordnet, Aus/ohne URL, Intervall, Cache löschen, Großdatei-Streaming).
