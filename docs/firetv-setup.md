# Fire TV einrichten (IP-Adresse, ADB-Debugging, VLC-Vergleich)

Stand: 2026-11-20. Dein Gerät laut ADB: **Amazon Fire TV Stick 4K Max (AFTKRT, „karat")**,
Fire OS 8.1.6.0 (Android 11, API 30), 32-Bit-ARM (`armeabi-v7a`), Ethernet-Verbindung, Display 1920×1080
(Panel 3840×2160). IP-Adresse aktuell: **192.168.1.134** (steht in `firetv-ip.txt`; am 2026-11-20 hat sie sich von `192.168.1.71` geändert – DHCP).

## 1. IP-Adresse des Fire TV ermitteln

**Am Fire TV selbst (zuverlässigster Weg):**
Einstellungen → *Mein Fire TV* → *Info* → *Netzwerk* → dort stehen *IP-Adresse*, *MAC-Adresse* und der Name des Netzes.
(Je nach Fire-OS-Version heißt der Punkt *Mein Fire TV* oder *Gerät & Software*.)

**Über die FRITZ!Box / den Router:** In der Geräteliste deines Routers (Heimnetz → Netzwerk) taucht der Fire TV als
„Amazon …" bzw. `AFT…` auf. Dafür ist eine Anmeldung nötig – das habe ich nicht versucht.

**Wie ich ihn gefunden habe (ohne Zugangsdaten, ohne Scan):**
1. Netzwerk des Laptops bestimmt: WLAN `192.168.1.0/24`; die FRITZ!Box liegt einen Router weiter (`192.168.200.1`).
2. Per mDNS (Multicast-Anfrage) antworteten 24 Geräte (Sonos, Shelly, Drucker, Home Assistant …), kein
   Fire-TV-Dienst (`_amzn-wplay`), vermutlich weil das Gerät im Standby war.
3. Nur die Hosts aus der ARP-Tabelle (26) wurden auf TCP-Port 5555 geprüft → ausschließlich `192.168.1.71` war offen.
4. `adb connect` bestätigte: Modell `AFTKRT`, Hersteller Amazon.

Eine Datei `firetv-ip.txt` im Projektstamm hält die Adresse für die Skripte. Ändert sich die IP (DHCP), dort einfach anpassen.
**Empfehlung (nicht von mir umgesetzt):** im Router/Gateway dem Fire TV eine feste DHCP-Reservierung geben, dann bleibt die Adresse stabil. Ich habe am
Gateway nichts verändert.

**Wie die neue Adresse gefunden wurde (2026-11-20, ohne Anmeldung, ohne Scan):**
1. ARP-Tabelle des Laptops: die alte MAC (`28-24-C9-B4-A7-99`) war nicht mehr enthalten.
2. mDNS-Multicast-Anfrage: 24 Geräte antworteten; genau eines nannte sich `adb-…`/`Android`/`AFJ…` und bot `_matterd` an → `192.168.1.134`.
3. TCP-Port 5555 nur auf den ARP-/mDNS-Hosts geprüft → nur `192.168.1.134` offen.
4. `adb connect` bestätigte: `AFTKRT`, Fire OS 8.1.6.0, Home TV 1.0.0 installiert.

## 2. ADB-Debugging aktivieren

Das ist bei deinem Gerät **bereits geschehen** (Port 5555 offen, Freigabe erteilt, `adb devices` zeigt `device`).
Falls es später erneut nötig ist:

1. **Einstellungen** öffnen (Zahnrad im Startbildschirm).
2. **Mein Fire TV** (bzw. *Gerät & Software*) öffnen.
3. **Entwickleroptionen** öffnen.
   *Nicht sichtbar?* Dann: *Info* öffnen, den **Gerätenamen** (z. B. „Fire TV Stick 4K Max") **7× hintereinander mit OK
   drücken** („Du bist jetzt Entwickler"), zurückgehen – jetzt erscheinen die Entwickleroptionen.
4. **ADB-Debugging: Ein**. Die Warnung bestätigen.
5. (Nur für Sideloading über andere Wege, z. B. „Downloader"-App: **Apps aus unbekannten Quellen: Ein**.)
6. Beim ersten Zugriff vom Laptop erscheint auf dem Fernseher **„USB-Debugging zulassen?"** → Haken bei
   *Immer von diesem Computer zulassen* setzen → **OK**.

Der ADB-Schlüssel des Laptops liegt projektlokal in `.android-user/` (nicht im Windows-Profil, nicht in Git).
Wer die Schlüssel löscht, muss die Freigabe am Fernseher neu erteilen.

**Verbindung testen:** `scripts\test-adb.bat` (rein lesend, installiert nichts) – zeigt Modell, Android-/Fire-OS-Version,
CPU-ABI und ob Home TV/VLC installiert sind.

## 3. VLC auf dem Fire TV als Vergleich

**Status:** Die offizielle VLC-App ist bereits installiert (`org.videolan.vlc`, Version **3.6.5-1**).
Ich habe VLC **nicht gestartet** und nichts darin getestet – das Ergebnis des Vergleichs steht also noch aus.

**Warum der Vergleich wertvoll ist:** Er zeigt, ob die VLC-Engine die SAT>IP-Streams in *deinem* Netz ohne Hilfe
abspielt (insbesondere wegen der UDP/NAT-Besonderheit, siehe `docs/stream-analysis.md`, Abschnitt 11).

**Anleitung A – Stream-Adresse direkt öffnen (am einfachsten):**
1. VLC auf dem Fire TV starten.
2. Seitenleiste → **Streams** (bzw. *Netzwerkstream öffnen*; bei manchen Versionen unter *Durchsuchen* → *Netzwerk*).
3. Diese Adresse eingeben (mit der Bildschirmtastatur; sie ist lang):

```
rtsp://192.168.200.1:554/?avm=1&freq=386&bw=8&msys=dvbc&mtype=256qam&sr=6900&specinv=0&pids=0,16,17,18,20,5100,5101,1170,1176,2171,2172,5102,5103,5104,5105,5107
```
4. **Wiedergabe**. Erwartung: Bild und Ton von Das Erste HD nach wenigen Sekunden.

**Anleitung B – per ADB-Befehl (spart das Tippen; startet nur VLC, installiert nichts):**
```
tools\platform-tools\adb.exe -s 192.168.1.71:5555 shell am start -a android.intent.action.VIEW -d "rtsp://192.168.200.1:554/?avm=1&freq=386&bw=8&msys=dvbc&mtype=256qam&sr=6900&specinv=0&pids=0,16,17,18,20,5100,5101,1170,1176,2171,2172,5102,5103,5104,5105,5107" -n org.videolan.vlc/.StartActivity
```
(Wenn `StartActivity` nicht passt, den Teil `-n …` weglassen – Android fragt dann, mit welcher App geöffnet werden soll.)
Ich führe das nur aus, wenn du es ausdrücklich möchtest.

**Anleitung C – M3U:** Die Playlist liegt auf der Box unter `http://192.168.200.1/dvb/m3u/tvhd.m3u`.
In VLC unter *Streams* diese HTTP-Adresse öffnen → die Senderliste erscheint, *Das Erste HD* auswählen.

**Wie das Ergebnis zu deuten ist:**
| VLC-Ergebnis | Bedeutung |
|---|---|
| Bild + Ton | VLC-Engine kommt im Netz zurecht; der „direkte" Modus meiner App sollte ebenfalls laufen |
| Ladekreis, nie Bild | wahrscheinlich NAT/UDP-Problem (kein Punch) – der Modus „SAT>IP-Client" der App ist dann der richtige |
| Fehlermeldung | Meldung notieren (Fehlertext) |

## 4. Fernbedienung und Tasten (Version 1.2.0)

Die vollständige Belegung steht in `docs/ui-and-controls.md`. Kurzfassung:

| Taste | Keycode | Funktion (Wiedergabe) |
|---|---:|---|
| ▲ / CH+ | 19 / 166 | nächster Sender (Gedrückthalten: schneller) |
| ▼ / CH− | 20 / 167 | vorheriger Sender |
| ▶ | 22 | Senderliste (rechts) |
| ◀ / Menü | 21 / 82 | Einstellungen (links) |
| OK | 23 | Info-Leiste ein/aus (**öffnet kein Debug-Menü mehr**) |
| Menü lang | 82 | Debug-Overlay – nur bei aktivem Debug-Modus |
| Play/Pause | 85 | Standbild / live neu starten |
| Zurück | 4 | Info-Leiste schließen, sonst App beenden |

Die Standard-Fernbedienung des Fire TV Stick 4K Max meldet laut Tastenlayout auch `CHANNEL_UP/DOWN` (402/403), `VOLUME_*`, `VOLUME_MUTE` und `POWER` – welche davon bei einer App ankommen, steht in `docs/firetv-limits.md`
(Power, Lautstärke und Home erreichen Drittanbieter-Apps laut Amazon nicht). Jede Taste, die ankommt, wird mit Keycode, Scancode und Gerätename im App-Log protokolliert (`Key`-Zeilen).

Tasten testen ohne Fernbedienung (per ADB):
```
tools\platform-tools\adb.exe -s 192.168.1.134:5555 shell input keyevent 166     (CH+)
tools\platform-tools\adb.exe -s 192.168.1.134:5555 shell input keyevent 167     (CH-)
```
Kernel-Ebene (welche Tasten empfängt der Fire TV überhaupt?): `scripts\key-monitor.bat`.

## 5. Tests per ADB (Start-Extras, ohne neue APK)

```
adb -s 192.168.1.134:5555 shell am start -n de.thecraftedtech.hometv/.MainActivity --es transport udp --ei cacheMs 3000 --ei audioDelayMs 0 --es channel "ZDF HD" --ez debug true --es epgUrl "https://…/epg.xml.gz"
```
`transport`: `tcp` | `udp` | `direct` · `cacheMs`: 200–15000 · `audioDelayMs`: −2000…2000 · `channel`: Sendername oder Nummer (1–97) · `debug`: Debug-Modus ein/aus · `epgUrl`: XMLTV-URL (löst sofort eine Aktualisierung aus) ·
`epgMap`: manuelle Zuordnung „Sendername=XMLTV-ID" (mehrere Zeilen mit `\n`). Die Werte werden gespeichert.

## 6. Log und Messwerte vom Fire TV holen

```
scripts\pull-log.bat                    (IP aus firetv-ip.txt)
scripts\udp-monitor.ps1                 (UDP-Verluste live, Strg+C beendet)
```
- `adb pull` des App-Logs ist unter Android 11 gesperrt (*Permission denied*). `pull-log.bat` löst deshalb einen Broadcast an `LogDumpReceiver` aus (nur ab Version 1.2.0), der die letzten ≈ 150 KB des App-Logs ins logcat (Tag `HomeTVLog`) schreibt und sie nach `tests\data\firetv-hometv.log` sichert; dazu logcat und UDP-Zähler.
- Das App-Log enthält: Start/Einstellungen, **`Key`-Zeilen**, **`TIMELINE`** je Sender-Start, **`STATS`** alle 10 s, Fehler, EPG-Ereignisse.
- `udp-monitor.ps1` zeigt `RcvbufErrors` (lokal verworfene UDP-Pakete) je 5 s – während des Fernsehens ausführen.

**So liest man eine `STATS`-Zeile:**
`rx=… lost=0 reord=0 late=0 tsCC=0/… jitter=…ms maxGap10s=…ms feedQ=…KiB feedDrop=0B dataT=…ms vlcT=…ms | vlc corrupt=0 discont=0 shown=… lostPic=0 lostAudio=0`

| Muster | Bedeutung |
|---|---|
| `lost`/`tsCC` > 0 | Verlust im Netz bzw. in der Quelle |
| `lost`=0, `tsCC`=0, aber `vlc corrupt/discont` > 0 | Verlust **hinter** unserer Zählstelle (lokale Übergabe/Demux) |
| `feedDrop` > 0 | App-Puffer übergelaufen (LibVLC liest zu langsam) |
| alles 0, aber sichtbare Fehler, `lostPic` > 0 | Decoder/Renderer (Frames zu spät) |
| `maxGap10s` groß | Empfangsthread/Netz stockt |

**So liest man eine `TIMELINE`-Zeile:** `tune=0 session=+2 PLAY_ok=+95 erstes_RTP=+118 Gate_offen=+1650 VLC_Playing=+300 Bild=+4900 (ms)` – Zeiten in ms seit dem Sender-Start; die großen Sprünge zeigen, wo die Zeit bis zum Bild bleibt.
## 7. App-Icon und Banner (Phase 3)

**Problem in 1.0.0:** Das Banner (640×360) lag als `drawable/banner.png`, also ohne Dichte-Zuordnung (wird als 640×360 *dp* gerechnet und skaliert),
und das Launcher-Icon war ein einzelnes 256-px-PNG ohne Dichten und ohne Adaptive-Icon-Variante. Zusätzlich saß der blaue Balken am Rand.

**Quellen (offiziell):**
[TV-App-Icon-Richtlinien (Android)](https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines) ·
[Adaptive Icons (Android)](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) ·
[Amazon Appstore – Store-Listing](https://developer.amazon.com/docs/app-submission/add-store-listing-information.html).
Amazon beschreibt für die Fire-TV-Oberfläche keine eigene `android:banner`-Spezifikation; Fire OS 8 basiert auf Android 11 und nutzt die Android-TV-Vorgaben.

| Anforderung | Umsetzung |
|---|---|
| `android:banner` 16:9 in `mipmap-*`: mdpi 160×90, hdpi 240×135, **xhdpi 320×180**, xxhdpi 480×270, xxxhdpi 640×360 | `res/mipmap-*/banner.png` in allen fünf Größen |
| Text muss im Bild stehen, kein Rahmen, Logo nicht abschneiden, in der Safe-Zone bleiben | Schriftzug „Home TV" mit blauem Akzentstrich, mittig, großzügiger Rand, kein Rahmen |
| `android:icon` 1:1: mdpi 80 … xxxhdpi 320 | `res/mipmap-*/ic_launcher.png` (Legacy für API < 26) |
| Adaptive Icon ab API 26 (Vordergrund + Hintergrund, je 108 dp; Logo in der 66-dp-Safe-Zone, TV nennt 72 dp) | `mipmap-anydpi-v26/ic_launcher.xml`, Hintergrund `#0E1320`, Vordergrund `drawable-*/ic_launcher_foreground.png` (108–432 px), Logo innerhalb 66 dp geprüft |
| Monochrom/Themed Icon | nicht nötig („Android TV unterstützt keine Themed Icons") |
| Amazon-Appstore-Grafik (nur für eine spätere Store-Einreichung): 1280×720 PNG ohne Transparenz | `docs/branding/appstore-fire-tv-1280x720.png` (für Sideloading nicht benötigt) |

Branding unverändert: dunkles Navy (`#0E1320`), Akzentblau (`#2E7BFF`), Schriftzug „Home TV" / „TV". Erzeugt mit `tools/make_icons.ps1` (ffmpeg; reproduzierbar).
Vorschau: `docs/branding/icon-preview.png` (Banner · Legacy-Icon · Adaptive-Vordergrund mit gelber 66-dp-Safe-Zone).

**Nicht geprüft:** Die tatsächliche Darstellung in der Fire-TV-Oberfläche. **Update 1.2.0:** Das Icon blieb nach dem Update von 1.0 auf 1.1 laut deinem Test unverändert; Befund und Maßnahmen stehen in `docs/firetv-limits.md`, Abschnitt 2 (die APK war korrekt, der Appstore-/Launcher-Cache hat das Update-Ereignis nicht verarbeitet; Ressourcen wurden ab 1.2.0 umbenannt). Ein Screenshot des Fire TV zeigte nur den ruhenden Startbildschirm (Hintergrundbild, keine App-Kachel
sichtbar); ich navigiere nicht ferngesteuert durch dein Menü. Bitte nach dem Update in *Apps* und *Einstellungen → Anwendungen* ansehen und mir sagen, ob Banner/Icon passen.
Hinweis: Der Fire-TV-Launcher cached Icons mitunter; ggf. App einmal neu starten bzw. das Gerät neu starten.