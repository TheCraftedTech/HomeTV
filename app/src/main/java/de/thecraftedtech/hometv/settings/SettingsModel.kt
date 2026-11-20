package de.thecraftedtech.hometv.settings

/**
 * Datenmodell des Einstellungsmenues (ohne Android-Abhaengigkeit, damit testbar). Die Oberflaeche
 * ([de.thecraftedtech.hometv.ui.MenuView]) zeigt nur an und ruft [SettingItem.activate]/[SettingItem.adjust] auf.
 */
sealed class SettingItem(val id: String, val label: String, val help: String = "") {
    /** Wert aenderbar (Schalter, Auswahl, Zahl)? Schalter wechseln mit OK sofort, Auswahl/Zahl werden mit OK zum Bearbeiten "angeklickt". */
    open val adjustable: Boolean get() = false
    /** Wert fuer die Anzeige rechts. */
    abstract fun display(): String
    /** OK-Taste. Gibt true zurueck, wenn sich etwas geaendert hat. */
    open fun activate(): Boolean = false
    /** Links (-1) / Rechts (+1). Gibt true zurueck, wenn sich etwas geaendert hat. */
    open fun adjust(delta: Int): Boolean = false
    open val enabled: Boolean get() = true

    class Info(id: String, label: String, private val value: () -> String, help: String = "") : SettingItem(id, label, help) {
        override fun display() = value()
    }

    class Toggle(id: String, label: String, private val get: () -> Boolean, private val set: (Boolean) -> Unit, help: String = "") :
        SettingItem(id, label, help) {
        override val adjustable get() = true
        override fun display() = if (get()) "Ein" else "Aus"
        override fun activate(): Boolean { set(!get()); return true }
        override fun adjust(delta: Int): Boolean { set(delta > 0); return true }
    }

    class Choice<T>(
        id: String, label: String,
        private val options: List<Pair<String, T>>,
        private val get: () -> T,
        private val set: (T) -> Unit,
        help: String = "",
    ) : SettingItem(id, label, help) {
        override val adjustable get() = true
        private fun index() = options.indexOfFirst { it.second == get() }
        override fun display() = options.getOrNull(index())?.first ?: get().toString()
        override fun activate() = adjust(+1)
        override fun adjust(delta: Int): Boolean {
            val cur = index()
            // unbekannter aktueller Wert (z. B. per ADB gesetzt): beim ersten Schritt auf den naechsten/vorigen Preset springen
            val next = if (cur < 0) { if (delta > 0) 0 else options.size - 1 } else Math.floorMod(cur + delta, options.size)
            set(options[next].second)
            return true
        }
    }

    class Stepper(
        id: String, label: String,
        private val get: () -> Int, private val set: (Int) -> Unit,
        private val step: Int, private val min: Int, private val max: Int,
        private val format: (Int) -> String, help: String = "",
    ) : SettingItem(id, label, help) {
        override val adjustable get() = true
        override fun display() = format(get())
        override fun adjust(delta: Int): Boolean {
            val n = (get() + delta * step).coerceIn(min, max)
            if (n == get()) return false
            set(n); return true
        }
    }

    class Action(id: String, label: String, private val run: () -> Unit, private val isEnabled: () -> Boolean = { true }, help: String = "") :
        SettingItem(id, label, help) {
        override val enabled get() = isEnabled()
        override fun display() = ""
        override fun activate(): Boolean { if (enabled) run(); return false }
    }

    /** Textfeld (z. B. URL) – die Oberflaeche oeffnet dafuer einen Eingabedialog und ruft danach [commit] auf. */
    class Text(
        id: String, label: String, private val get: () -> String, private val set: (String) -> Unit, val hint: String, help: String = "",
        private val emptyText: String = "nicht gesetzt",
    ) : SettingItem(id, label, help) {
        override fun display() = get().ifBlank { emptyText }
        val current: String get() = get()
        fun commit(value: String) = set(value)
    }
}

class SettingsCategory(val id: String, val title: String, val items: () -> List<SettingItem>)

/** Verbindung des Menues zur restlichen App (alles, was keine reine Einstellung ist). */
class SettingsEnv(
    val versionText: String,
    val channelInfo: () -> String,
    val reloadChannels: () -> Unit,
    val epgStatus: () -> String,
    val epgLastUpdate: () -> String,
    val epgMatchInfo: () -> String,
    val epgCacheInfo: () -> String,
    val refreshEpg: () -> Unit,
    val clearEpgCache: () -> Unit,
    val showDebugOverlay: () -> Unit,
    val logPath: String,
    /** Programmuebersicht (Vollbild-Raster) oeffnen. */
    val openGuide: () -> Unit = {},
)

class SettingsModel(private val s: AppSettings, private val env: SettingsEnv) {

    companion object {
        const val DEVELOPER = "Samuel Zenker"
    }

    val categories: List<SettingsCategory> = listOf(
        SettingsCategory("general", "Allgemein") {
            listOf(
                SettingItem.Info("version", "Version", { env.versionText }),
                SettingItem.Info("developer", "Entwickler", { DEVELOPER }),
                SettingItem.Info("channels", "Sender", env.channelInfo),
                SettingItem.Text("host", "FRITZ!Box-Adresse", { s.fritzboxHost }, { s.fritzboxHost = it }, "z. B. ${s.defaultHost}",
                    "Adresse der FRITZ!Box mit DVB-C-Tuner (Standard ${s.defaultHost}). Gilt nur, wenn keine eigene Senderlisten-URL gesetzt ist."),
                SettingItem.Text("playlistUrl", "Eigene Senderlisten-URL", { s.playlistUrl }, { s.playlistUrl = it }, "http://…/liste.m3u",
                    "Optional: eigene M3U-Liste (http/https) mit rtsp://-SAT>IP-Adressen. Leer lassen = Senderliste der FRITZ!Box.",
                    emptyText = "FRITZ!Box verwenden"),
                SettingItem.Toggle("startLast", "Beim Start letzter Sender", { s.startWithLastChannel }, { s.startWithLastChannel = it },
                    "Ein: Home TV startet mit dem zuletzt gesehenen Sender, sonst mit Das Erste HD."),
                SettingItem.Choice("idle", "Automatisch beenden nach", listOf(0, 1, 2, 4, 6, 8, 12).map { (if (it == 0) "Aus" else "$it h") to it }, { s.idleStopHours }, { s.idleStopHours = it },
                    "Beendet die Wiedergabe nach dieser Zeit ohne jede Tastenbetätigung (gibt den Tuner der FRITZ!Box frei, z. B. wenn der Fernseher ausgeschaltet wurde). Aus = nie."),
                SettingItem.Action("reload", "Senderliste neu laden", env.reloadChannels, help = "Lädt die Senderliste erneut (von der FRITZ!Box bzw. der eigenen URL)."),
            )
        },
        SettingsCategory("playback", "Wiedergabe") {
            listOf(
                SettingItem.Choice("cache", "Puffer", AppSettings.CACHE_PRESETS.map { "$it ms" to it }, { s.cacheMs }, { s.cacheMs = it },
                    "Größerer Puffer = ruhiger bei Netz-/CPU-Schwankungen, aber längere Verzögerung und späteres Bild."),
                SettingItem.Stepper("audio", "Audio-Versatz", { s.audioDelayMs }, { s.audioDelayMs = it }, 50, -2000, 2000,
                    { v -> (if (v > 0) "+" else "") + "$v ms" }, "Links/Rechts: Ton in 50-ms-Schritten gegenüber dem Bild verschieben."),
                SettingItem.Choice("transport", "Übergabemodus", Transport.values().map { it.label to it }, { s.transport }, { s.transport = it },
                    "Wie der Datenstrom an den Player geht. TCP ist verlustfrei; die anderen Modi dienen als Rückfall."),
                SettingItem.Toggle("gate", "Start-Gate", { s.startGate }, { s.startGate = it },
                    "Ein: überspringt die beschädigte erste Sekunde jedes Sender-Starts (ca. 1,5 s länger, aber sauberes Bild)."),
                SettingItem.Choice("debounce", "Umschalt-Wartezeit", listOf(150, 300, 500, 800).map { "$it ms" to it }, { s.zapDebounceMs }, { s.zapDebounceMs = it },
                    "Wartezeit nach der letzten Kanaltaste, bevor der Sender geladen wird (verhindert unnötige Ladeversuche)."),
            )
        },
        SettingsCategory("epg", "Programmführer") {
            listOf(
                SettingItem.Action("openGuide", "Programmübersicht öffnen", env.openGuide, help = "Zeigt alle Sender mit dem Programm – Gelaufenes, Laufendes und Kommendes – als Raster. Sonst: Senderliste ►, oder OK lange drücken."),
                SettingItem.Info("epgStatus", "Status", env.epgStatus),
                SettingItem.Toggle("epgOn", "Programmführer", { s.epgEnabled }, { s.epgEnabled = it }, "Aus: keine Programmdaten anzeigen und nichts herunterladen."),
                SettingItem.Text("epgUrl", "XMLTV-URL", { s.epgUrl }, { s.epgUrl = it }, "https://…/epg.xml oder .xml.gz",
                    "Adresse einer XMLTV-Datei (auch .gz). Es ist keine Adresse voreingestellt."),
                SettingItem.Action("epgRefresh", "Jetzt aktualisieren", env.refreshEpg, { s.epgActive }, "Lädt die Programmdaten sofort neu."),
                SettingItem.Info("epgLast", "Letzte Aktualisierung", env.epgLastUpdate),
                SettingItem.Info("epgMatch", "Zugeordnete Sender", env.epgMatchInfo),
                SettingItem.Info("epgCache", "Zwischenspeicher", env.epgCacheInfo),
                SettingItem.Toggle("epgAuto", "Automatisch aktualisieren", { s.epgAutoUpdate }, { s.epgAutoUpdate = it }, "Aktualisiert beim Start, wenn die Daten älter als das Intervall sind."),
                SettingItem.Choice("epgInterval", "Aktualisierungsintervall", listOf(6, 12, 24, 48).map { "$it h" to it }, { s.epgUpdateIntervalHours }, { s.epgUpdateIntervalHours = it }),
                SettingItem.Choice("epgDays", "Tage im Voraus", listOf(1, 2, 3, 5, 7).map { "$it" to it }, { s.epgDaysAhead }, { s.epgDaysAhead = it },
                    "Wie viele Tage Programm gespeichert werden (weniger = weniger Speicher)."),
                SettingItem.Action("epgClear", "Zwischenspeicher löschen", env.clearEpgCache),
            )
        },
        SettingsCategory("ui", "Oberfläche") {
            listOf(
                SettingItem.Choice("infoSecs", "Info-Leiste anzeigen für", listOf(3, 5, 8, 12).map { "$it s" to it }, { s.infoBarSeconds }, { s.infoBarSeconds = it }),
                SettingItem.Choice("listSecs", "Senderliste schließt nach", listOf(10, 15, 30, 60, 0).map { (if (it == 0) "nie" else "$it s") to it }, { s.channelListTimeoutSeconds }, { s.channelListTimeoutSeconds = it }),
                SettingItem.Toggle("epgOnZap", "Programm beim Umschalten", { s.showEpgOnZap }, { s.showEpgOnZap = it }, "Zeigt beim Senderwechsel sofort die aktuelle Sendung."),
                SettingItem.Toggle("epgInList", "Programm in der Senderliste", { s.showEpgInChannelList }, { s.showEpgInChannelList = it }),
                SettingItem.Toggle("favOnly", "Nur Favoriten", { s.favoritesOnly }, { s.favoritesOnly = it },
                    "Senderliste, Programmübersicht und CH+/CH− zeigen nur Favoriten (sobald es welche gibt). In Liste/Übersicht: Menü-Taste."),
                SettingItem.Info("favCount", "Favoriten", { if (s.favorites.isEmpty()) "keine" else "${s.favorites.size}" },
                    "Favoriten setzen: in Senderliste oder Programmübersicht OK lange drücken (★)."),
                SettingItem.Action("favClear", "Alle Favoriten entfernen", { s.clearFavorites() }, { s.favorites.isNotEmpty() }),
            )
        },
        SettingsCategory("debug", "Debugging") {
            listOf(
                SettingItem.Toggle("debug", "Debug-Modus", { s.debugMode }, { s.debugMode = it },
                    "Standardmäßig aus. Aktiviert technische Anzeigen; ohne diese Einstellung erscheint nie ein Debug-Overlay."),
                SettingItem.Action("debugShow", "Debug-Overlay anzeigen", env.showDebugOverlay, { s.debugMode }),
                SettingItem.Info("debugHow", "Bedienung", { "Menü-Taste lang drücken" }, "Nur bei aktivem Debug-Modus. Im Overlay: ▲▼ Puffer, ◀▶ Audio-Versatz."),
                SettingItem.Info("debugLog", "Log-Datei", { env.logPath }, "Wird immer geschrieben, auch ohne Debug-Modus."),
            )
        },
    )
}
