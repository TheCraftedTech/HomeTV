# Fire TV einrichten (IP-Adresse, ADB-Debugging, VLC-Vergleich)

Stand: 2026-11-20. Dein Gerät laut ADB: **Amazon Fire TV Stick 4K Max (AFTKRT, „karat")**,
Fire OS 8.1.6.0 (Android 11, API 30), 32-Bit-ARM (`armeabi-v7a`), Ethernet-Verbindung, Display 1920×1080
(Panel 3840×2160). IP-Adresse bei der Untersuchung: **192.168.1.71** (steht in `firetv-ip.txt`).

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

Eine Datei `firetv-ip.txt` im Projektstamm hält die Adresse für die Skripte. Ändert sich die IP (DHCP),
dort einfach anpassen – oder dem Router eine feste Adresse für den Fire TV geben (empfohlen).

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
