# Änderungsverlauf

Format angelehnt an [Keep a Changelog](https://keepachangelog.com/de/1.1.0/). Die Versionsnummer steht in `app/build.gradle.kts` (`versionName` / `versionCode`).

## Unveröffentlicht
- Lizenz: **MIT** (`LICENSE`).

## 1.5.0 (Code 7)
### Neu
- **Favoriten-Leiste** am unteren Rand (Vorlauf-Taste ⏩): Karten mit Nummer, Name, laufender Sendung und Fortschritt; OK startet, OK lange entfernt den Favoriten.
- **Anleitung mit Tastensymbolen** (*Einstellungen → Anleitung*): fünf Seiten, selbst gezeichnete Symbole für Pfeile, OK, Zurück, Menü, Play/Pause, Vor-/Rücklauf, CH+/CH−.
- **Kanaltasten vertauschen** (▲/▼ und CH±).
- **Umspringen am Listenende** (nur bei neuem Tastendruck, abschaltbar).
- **Zurücksetzen** (*Einstellungen zurücksetzen* / *Werkseinstellungen*) mit Sicherheitsabfrage.
- Einstellungen in **zehn Bereiche** gegliedert (Allgemein, Sender, Favoriten, Bedienung, Wiedergabe, Programmführer, Oberfläche, Anleitung, Zurücksetzen, Debugging).
- Rücklauf-Taste ⏪ öffnet die Programmübersicht.
### Geändert
- Senderwechsel (Versuch): lokaler TCP-Feed wird **vor** dem Player-Stopp geschlossen; Player-Ereignisse des alten Mediums werden verworfen. Das Log trennt `Feed zu … ms` und `LibVLC-Stop … ms`. Ob das die ~2 s Wartezeit verkürzt, ist offen.

## 1.4.0 (Code 6)
### Neu
- **Programmübersicht** im Vollbild (Raster: Sender × Zeit, Gelaufenes/Laufendes/Kommendes, Detailansicht); Öffnen mit langem OK, ▶ in der Senderliste oder aus den Einstellungen.
- **Favoriten** (★ per langem OK in Liste und Übersicht), Filter „nur Favoriten" (Menü-Taste), CH± überspringt Nicht-Favoriten.
### Behoben
- `MediaPlayer.stop()` blockierte beim Senderwechsel ~2 s den Haupt-Thread (Oberfläche hing); läuft jetzt auf dem Worker-Thread.

## 1.3.0 (Code 5)
### Neu
- Menü: **markieren → anklicken → bearbeiten** (Schalter als grüne/graue Kapsel, Bearbeiten-Modus amber); Wert ändert sich nicht mehr beim bloßen Markieren.
- **FRITZ!Box-Adresse** und **eigene Senderlisten-URL** (M3U mit `rtsp://`-Adressen) einstellbar; Zwischenspeicher je Quelle.
- Senderliste: kompakter, mit **Fortschrittsbalken, Restzeit, Uhr und Rollbalken**; schwebende Karten mit Rand.
- Version ohne „Code"-Zusatz; Credit „Entwickler: Samuel Zenker".
### Behoben
- Halb gezeichnete Ränder beim Einblenden von Menü und Senderliste (Animation über Hardware-Ebene, vollständiges Neuzeichnen).

## 1.2.1 (Code 4)
### Behoben
- Das Einstellungsmenü meldete sich nie als „offen": ▲▼ wechselte den Sender, Zurück beendete die App.
- Info-Leiste deutlich kompakter, mehr Abstand zum Bildrand.

## 1.2.0 (Code 3)
### Neu
- Architektur in Pakete getrennt (`ui`, `zap`, `playback`, `satip`, `channels`, `epg`, `settings`, `core`).
- **Schnelles Umschalten** mit Vorschau und Entprellung; nur der zuletzt gewählte Sender wird geladen, alte Sessions werden per TEARDOWN beendet (nie zwei Sessions gleichzeitig).
- **Senderliste** am rechten Rand, **Einstellungsmenü**, **Programmführer (XMLTV)** mit lokalem Cache und bedingter Aktualisierung.
- **Debug-Modus** (Standard aus); die mittlere Taste öffnet nicht mehr das Debug-Menü. Log-Abruf per ADB-Broadcast, optionaler Inaktivitäts-Timer.
- Zeitleiste (`TIMELINE`) je Sender-Start im Log.

## 1.1.0 (Code 2)
- Bild/Ton-Synchronisation verbessert (Start-Gate, verlustfreie TCP-Übergabe an LibVLC, Paket-Resequenzierung), einstellbarer Puffer und Audio-Versatz.
- Senderwechsel mit CH+/CH−, überarbeitetes Overlay, neues App-Icon und -Banner.

## 1.0.0 (Code 1)
- Erste lauffähige Version: eigener SAT>IP-Client (RTSP/RTP-UDP, NAT-Punch, Keep-Alive) und LibVLC als Player; Senderliste von der FRITZ!Box.
