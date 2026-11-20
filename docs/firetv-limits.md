# Fire-OS-Einschränkungen und Untersuchungsergebnisse (Stand 2026-11-20)

Gerät: Fire TV Stick 4K Max (AFTKRT), Fire OS 8.1.6.0 (Android 11), Fernbedienung „Amazon Fire TV Remote" (Bluetooth, Vendor 0x0171 / Produkt 0x0427).
Alle Messungen rein lesend per ADB; die Tasten selbst konnte ich nicht drücken (siehe „Noch zu prüfen").

## 1. Fernbedienungstasten – was kommt bei der App an?

### Was die Fernbedienung dem Fire TV meldet (gemessen)
`getevent -lp` (Kernel-Ebene) und das Tastenlayout `/system/usr/keylayout/Vendor_0171_Product_0427.kl`:

| Scancode | Android-Keycode laut Layout | Taste |
|---:|---|---|
| 116 | `POWER` | Ein/Aus |
| 172 | `HOME` | Home |
| 158 | `BACK` | Zurück |
| 139 | `MENU` | Menü (≡) |
| 164 | `MEDIA_PLAY_PAUSE` | Play/Pause |
| 168 / 208 | `MEDIA_REWIND` / `MEDIA_FAST_FORWARD` | Zurück-/Vorspulen |
| 113 | `VOLUME_MUTE` | Stumm |
| 114 / 115 | `VOLUME_DOWN` / `VOLUME_UP` | Leiser / Lauter |
| 402 / 403 | `CHANNEL_UP` / `CHANNEL_DOWN` | Kanal +/− |
| 362 | `LIVE_TV` | Live-TV-Taste |

Die Fernbedienung **besitzt** also Power-, Lautstärke-, Stumm- und Kanaltasten, und das System kennt sie als Keycodes.

### Was Fire OS an Apps weitergibt (Amazon-Dokumentation)
Quelle: [Remote Control Input (Fire TV)](https://developer.amazon.com/docs/fire-tv/remote-input.html):

| Taste | Dokumentiert |
|---|---|
| Back, Menü (`KEYCODE_MENU`), Select, D-Pad, Play/Pause, Rewind, Fast Forward | werden als KeyEvent an die App geliefert |
| Kanal +/− (`KEYCODE_CHANNEL_UP/DOWN`) | als KeyEvent dokumentiert („nicht jede Fernbedienung hat sie") |
| **Home** | „This is a system event and cannot be intercepted." |
| **Mikrofon/Sprachsuche** | ebenfalls systemreserviert |
| **Lautstärke, Ein/Aus (Power)** | „Some remote controllers have additional buttons — such as volume up/down, power, … However, these buttons can't be mapped to events in third-party apps." |
| Stumm | in der Doku nicht erwähnt |

### Konsequenzen für Home TV
- **Power-Taste:** Android behandelt `KEYCODE_POWER` systemintern (Schlafmodus/Ausschaltmenü); eine normale App erhält sie nicht und kann sie nicht
  abfangen (laut Amazon-Doku und gängigem Android-Verhalten; Abfangen wäre nur über signatur-/privilegierte System-Berechtigungen möglich, die eine
  App nicht bekommt – ich baue deshalb **keinen Workaround**). Die App reagiert stattdessen auf das **Ergebnis**: geht der Bildschirm aus
  (`ACTION_SCREEN_OFF`) oder wird die App gestoppt (`onStop`), beendet sie sofort die SAT>IP-Session mit TEARDOWN und gibt den Tuner der FRITZ!Box frei.
- **Lautstärke/Stumm:** laut Amazon nicht an Drittanbieter-Apps. Home TV enthält trotzdem Code dafür (Lautstärke-Anzeige, App-Stumm via LibVLC), der
  **nur wirkt, falls die Tasten ankommen** (z. B. andere Fernbedienung/Tastatur). Auf diesem Fire TV ist die Lautstärke vermutlich Sache des Fernsehers (IR/CEC).
- **Kanal +/−:** wird zugestellt (Keycode 166/167) und von Home TV genutzt. Die App wertet zusätzlich ▲/▼ als Kanalwechsel, falls die Fernbedienung keine Kanaltasten hat.
- **Menü:** kurz = Hauptmenü; lang (nur bei aktivem Debug-Modus) = Debug-Overlay. **Hinweis:** Die Menü-Taste löst unter Fire OS sonst das Android-Optionsmenü aus;
  Home TV nutzt sie dafür nicht.
- **Home:** immer System (Startbildschirm). Die App wird dabei gestoppt (`onStop`) und gibt alles frei.

### Warum der Fernseher trotzdem „weiterläuft" (CEC)
Gemessen: `hdmi_control_enabled = 1`, **`hdmi_control_auto_device_off_enabled = 0`**. Schaltest du den **Fernseher** per Fernbedienungs-Ausschalter aus (diese
Taste sendet die Fernbedienung per Infrarot an den Fernseher), erfährt der Fire TV davon nur über HDMI-CEC – und mit ausgeschalteter „Automatische Geräteabschaltung"
schläft er **nicht** ein. Home TV hält außerdem den Bildschirm wach (`FLAG_KEEP_SCREEN_ON`, nötig gegen den Bildschirmschoner beim Fernsehen). Ergebnis: Die App streamt weiter,
obwohl der Fernseher aus ist, und belegt dabei einen Tuner der FRITZ!Box. Ob sich dein Fernseher/Fire TV so verhält, habe ich **nicht beobachtet** (ich habe die Tasten nicht
gedrückt) – es folgt aber aus den gemessenen Einstellungen.
Unterstützte Abhilfen:
1. Fire TV: *Einstellungen → Display & Audio → HDMI-CEC-Gerätesteuerung* (Bezeichnung je nach Fire-OS-Version): die automatische Geräteabschaltung einschalten – dann geht der Fire TV mit dem
   Fernseher in den Ruhezustand, Home TV stoppt und gibt den Tuner frei.
2. In Home TV: *Einstellungen → Allgemein → Automatisch beenden nach …* (Standard **Aus**, damit lange Fernsehabende nie unterbrochen werden). Das ist ein
   normaler Inaktivitäts-Timer wie bei Fernsehern: nach der gewählten Zeit **ohne jede Tastenbetätigung** beendet die App die Wiedergabe.

### Noch zu prüfen (braucht Tastendrücke am Gerät)
`scripts\key-monitor.bat` zeigt, welche Tasten der Fire TV auf Kernel-Ebene empfängt; `adb logcat -s Key:I` bzw. das App-Log (`Key`-Zeilen mit Gerätename und Scancode)
zeigt, was bei der App ankommt. Damit lässt sich klären: Kommt `KEY_POWER` überhaupt am Fire TV an, oder geht es per IR direkt an den Fernseher? Kommen Lauter/Leiser/Stumm in der App an?

## 2. App-Icon aktualisiert sich nicht (Launcher/Appstore-Cache)

Befund:
- Die **installierte APK ist byte-identisch** mit dem lokalen Build (SHA-256 `C64E607D…34E13`, Version 1.1.0 mit neuem Banner/Adaptive Icon). Manifest, Ressourcen und Dichten sind korrekt.
- Beim Update (20:00:19) wurden `PACKAGE_REMOVED`, `PACKAGE_ADDED` und `PACKAGE_REPLACED` an die Amazon-Appstore-Komponenten **blockiert**
  („Background execution not allowed"): `com.amazon.venezia` (`AppEventReceiver`, `AppManagerBroadcastReceiver`, `LockerSystemEventsBroadcastReceiver`,
  `PackageStateReceiver`, `AndroidPackageBroadcastReceiver`) sowie `com.amazon.tv.settings.v2`, `com.amazon.adep`, `com.amazon.vizzini` u. a.
- Die Startbildschirm-Kachel startet die App über `amzns://apps/android?p=de.thecraftedtech.hometv` im Appstore-Client (`com.amazon.venezia/.pdi.AppLaunchActivity`) – die
  App-Bibliothek (inkl. Icon/Banner) wird also vom Appstore-Client gepflegt, der das Update-Ereignis nicht verarbeitet hat.
- **Schluss:** Das Problem liegt im Fire-OS-Launcher/Appstore-Cache, nicht in der APK. Das passt zu deiner Beobachtung (Erstinstallation zeigte ein Icon, das Update änderte es nicht); bewiesen ist die Ursache damit nicht, die Logs zeigen nur die blockierten Broadcasts.

Maßnahmen:
- Ab Version 1.2.0: Banner und Icon haben **neue Ressourcennamen** (`hometv_banner`, `ic_hometv`, `ic_hometv_foreground` statt `banner`/`ic_launcher`) und der Versionscode steigt –
  Caches, die nach Ressourcen-ID/Name schlüsseln, laden das Bild neu. Das ist ein **Versuch**; ob der Appstore-Cache davon betroffen ist, wissen wir erst nach dem Test.
- Falls das Icon alt bleibt (Reihenfolge von harmlos zu aufwendig): (1) Fire TV neu starten (*Einstellungen → Mein Fire TV → Neu starten*), (2) *Einstellungen → Anwendungen →
  Installierte Anwendungen verwalten → Appstore* → *Cache leeren*, (3) Home TV deinstallieren und neu installieren (dabei gehen die App-Einstellungen verloren).
- Ich habe auf dem Fire TV **nichts** zurückgesetzt (kein `pm clear`, kein Neustart von Systemdiensten).

## 3. Weitere gemessene Eigenheiten

| Befund | Wert | Bedeutung |
|---|---|---|
| App-Log per ADB | `adb pull /sdcard/Android/data/…/hometv.log` → *Permission denied* (Android 11) | Log wird stattdessen per Broadcast (`LogDumpReceiver`) ins logcat geschrieben: `scripts\pull-log.bat` |
| logcat-Ringpuffer | 256 KiB je Puffer | Sitzungen werden in Minuten überschrieben (v. a. bei ausführlichen VLC-Logs); deshalb eigenes App-Log. VLC-Log (`-vv`) läuft ab 1.2.0 nur im Debug-Modus |
| UDP-Verluste seit Boot | `RcvbufErrors = 1520` bei 517 425 empfangenen UDP-Datagrammen (0,29 %), im Leerlauf konstant | Pakete wurden lokal verworfen (Socket-Puffer voll), nur während Wiedergabe entstanden; Ursache (UDP-Loopback v1.0 vs. RTP-Socket) nicht zuordenbar. Messung live: `scripts\udp-monitor.ps1` |
| Socket-Limits | `rmem_max = 8 388 608`, `rmem_default = 212 992` | Die angeforderten 4 MiB für den RTP-Socket sind erlaubt |
| Systemlast im Leerlauf | Load-Average ≈ 6,6 (4 Kerne) | Amazon-Hintergrunddienste belasten die CPU; Decoder/Empfang konkurrieren – deshalb hohe Empfangs-Thread-Priorität |
| Eingabegeräte | „Amazon Fire TV Remote", `hdmipower` (KEY_POWER), `WOW_INPUT_DEVICE` (KEY_POWER), `WOBLE_INPUT_DEVICE` (KEY_WAKEUP) | Interne Power-/Wake-Eingabegeräte (Namen deuten auf HDMI-/Funk-Aufwecken hin – Deutung nicht verifiziert), die das System selbst verarbeitet |
