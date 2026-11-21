# Drittsoftware

## LibVLC (VideoLAN)

Home TV spielt den Fernsehstrom mit **LibVLC** ab und liefert die Bibliothek in der APK mit.

| | |
|---|---|
| Komponente | `org.videolan.android:libvlc-all` **3.7.7** (Android-Build von LibVLC 3.x) |
| Hersteller | VideoLAN |
| Lizenz | **GNU Lesser General Public License, Version 2.1 (LGPL-2.1)** |
| Projektseite | https://www.videolan.org/vlc/libvlc.html |
| Quellcode | https://code.videolan.org/videolan/libvlc-android und https://code.videolan.org/videolan/vlc |
| Lizenztext | https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html |

Home TV bindet LibVLC als **unveränderte, dynamisch geladene Bibliothek** ein (native `.so`-Dateien in der APK); die Bibliothek wurde nicht verändert.
Wer die Bibliothek austauschen möchte, kann Home TV aus dem Quellcode dieses Repositories mit einer anderen Version von `libvlc-all` neu bauen (siehe `app/build.gradle.kts`).
LibVLC enthält seinerseits Komponenten Dritter unter weiteren freien Lizenzen; die vollständigen Hinweise liefert VideoLAN mit der Bibliothek.

## Weitere Abhängigkeiten

- AndroidX-Bibliotheken (Apache-2.0), soweit sie von LibVLC bzw. dem Android-Gradle-Plugin eingebunden werden.
- JUnit 4.13.2 (Eclipse Public License 1.0) – nur für Tests, nicht in der APK.

## Marken

„FRITZ!Box" ist eine Marke der AVM GmbH. „Fire TV" und „Amazon" sind Marken von Amazon.com, Inc. oder verbundener Unternehmen. „VLC" ist eine Marke von VideoLAN.
Home TV ist ein unabhängiges Projekt und steht in keiner Verbindung zu diesen Unternehmen.
