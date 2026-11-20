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

## 4. Fernbedienung und Tasten (Version 1.1.0)

| Taste | Keycode | Funktion |
|---|---:|---|
| CH+ (Channel Up) | 166 | nächster Sender (mit Umlauf) |
| CH− (Channel Down) | 167 | vorheriger Sender |
| ▲ / ▼ (ohne Overlay) | 19 / 20 | wie CH+ / CH− (Alias, falls die Fernbedienung keine CH-Tasten hat) |
| OK | 23 | Info-Overlay ein/aus; bei Fehleranzeige: erneut versuchen |
| ▲ / ▼ (mit Overlay) | 19 / 20 | Puffer 1000 → 2000 → 3000 → 5000 ms (Umlauf), startet den Sender neu |
| ◀ / ▶ (mit Overlay) | 21 / 22 | Audio-Versatz −50 / +50 ms (gespeichert) |
| Menü (≡) | 82 | Übergabemodus TCP → UDP → direkt (gespeichert) |
| Play/Pause | 85 | Standbild; erneut drücken = live neu starten |
| Zurück | 4 | Overlay schließen, sonst App beenden |

Die Standard-Fernbedienung des Fire TV Stick hat **keine** Kanaltasten (Keycodes 166/167 kommen nur von Fernbedienungen/Universalfernbedienungen mit CH-Tasten, z. B. Cube-/Pro-
Fernbedienung oder HDMI-CEC-Fernbedienungen des TVs). Jede Taste wird mit Keycode im Overlay („Taste") und im Log (`KeyDown …`) angezeigt – so lässt sich prüfen,
welche Codes dein Gerät tatsächlich sendet. Tasten testen ohne Fernbedienung (per ADB):
```
tools\platform-tools\adb.exe -s 192.168.1.134:5555 shell input keyevent 166     (CH+)
tools\platform-tools\adb.exe -s 192.168.1.134:5555 shell input keyevent 167     (CH-)
```
(`input keyevent` wird vom System wie eine Tastatur-/Fernbedienungstaste zugestellt; ob die App sie annimmt, steht im Log.)

## 5. Tests per ADB (Start-Extras, ohne neue APK)

Die App übernimmt beim Start Extras (werden gespeichert):
```
adb -s 192.168.1.134:5555 shell am start -n de.thecraftedtech.hometv/.MainActivity --es transport udp --ei cacheMs 3000 --ei audioDelayMs 0 --es channel "ZDF HD"
```
`transport`: `tcp` | `udp` | `direct` · `cacheMs`: 200–15000 · `audioDelayMs`: −2000…2000 · `channel`: Sendername oder Nummer (1–97).
Mit `onNewIntent` wirken die Werte auch bei bereits laufender App.

## 6. Log und Messwerte vom Fire TV holen

```
scripts\pull-log.bat                    (IP aus firetv-ip.txt)
```
holt (rein lesend) `hometv.log` (App-Log mit `STATS`-Zeile alle 10 s, Tasten, Decoder-Zeilen), den logcat und die UDP-Zähler des Geräts
(`/proc/net/snmp`, Spalte `RcvbufErrors` = Pakete, die wegen vollem Socket-Puffer verworfen wurden) nach `tests\data\`.
Der logcat-Ringpuffer des Fire TV hat nur 256 KiB und überschreibt Sitzungen binnen Minuten – deshalb schreibt die App ihr eigenes Log.

**So liest man eine `STATS`-Zeile** (Beispiel):
`rx=… lost=0 reord=0 late=0 tsCC=0/… jitter=…ms maxGap10s=…ms feedQ=…KiB feedDrop=0B dataT=…ms vlcT=…ms | vlc corrupt=0 discont=0 shown=… lostPic=0 lostAudio=0`

| Muster | Bedeutung |
|---|---|
| `lost`/`tsCC` > 0 | Verlust im Netz bzw. in der Quelle |
| `lost`=0, `tsCC`=0, aber `vlc corrupt/discont` > 0 | Verlust **hinter** unserer Zählstelle (lokale Übergabe/Demux) |
| `feedDrop` > 0 | App-Puffer übergelaufen (LibVLC liest zu langsam) |
| alles 0, aber sichtbare Fehler, `lostPic` > 0 | Decoder/Renderer (Frames zu spät) |
| `maxGap10s` groß | Empfangsthread/Netz stockt |
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

**Nicht geprüft:** Die tatsächliche Darstellung in der Fire-TV-Oberfläche. Ein Screenshot des Fire TV zeigte nur den ruhenden Startbildschirm (Hintergrundbild, keine App-Kachel
sichtbar); ich navigiere nicht ferngesteuert durch dein Menü. Bitte nach dem Update in *Apps* und *Einstellungen → Anwendungen* ansehen und mir sagen, ob Banner/Icon passen.
Hinweis: Der Fire-TV-Launcher cached Icons mitunter; ggf. App einmal neu starten bzw. das Gerät neu starten.