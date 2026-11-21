package de.thecraftedtech.hometv.settings

import android.content.Context
import android.content.Intent

/** Transport vom SAT>IP-Client zu LibVLC bzw. direkter LibVLC-RTSP-Zugriff. */
enum class Transport(val key: String, val label: String) {
    TCP("tcp", "SAT>IP-Client + TCP-Loopback"),
    UDP("udp", "SAT>IP-Client + UDP-Loopback"),
    DIRECT("direct", "LibVLC direkt (RTSP)");

    fun next() = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key.equals(k, ignoreCase = true) }
    }
}

/** Minimaler Schluessel-Wert-Speicher (SharedPreferences in der App, Map im Test). */
interface KeyValueStore {
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putString(key: String, value: String?)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    /** Alle gespeicherten Werte loeschen. */
    fun clear()
}

class SharedPrefsStore(context: Context, name: String = "hometv") : KeyValueStore {
    private val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String, def: String?) = sp.getString(key, def)
    override fun getInt(key: String, def: Int) = sp.getInt(key, def)
    override fun getLong(key: String, def: Long) = sp.getLong(key, def)
    override fun getBoolean(key: String, def: Boolean) = sp.getBoolean(key, def)
    override fun putString(key: String, value: String?) { sp.edit().putString(key, value).apply() }
    override fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    override fun putLong(key: String, value: Long) { sp.edit().putLong(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    override fun remove(key: String) { sp.edit().remove(key).apply() }
    override fun clear() { sp.edit().clear().apply() }
}

class MemoryStore(val map: MutableMap<String, Any?> = HashMap()) : KeyValueStore {
    override fun getString(key: String, def: String?) = map[key] as? String ?: def
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
    override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() { map.clear() }
}

/**
 * Alle persistenten Einstellungen mit Standardwerten und Wertebereichen. Die Schluessel `cacheMs`, `audioDelayMs`
 * und `transport` sind identisch mit Version 1.0/1.1 – bestehende Einstellungen bleiben beim Update erhalten.
 *
 * Zum Testen per ADB ueberschreibbar (siehe [applyIntent]).
 */
class AppSettings(private val store: KeyValueStore, val defaultHost: String = DEFAULT_HOST) {

    // ---- Senderquelle ----
    /** Adresse der FRITZ!Box (nur Host/IP). Leer gespeichert = Standard ([defaultHost]). */
    var fritzboxHost: String
        get() = store.getString("fritzboxHost", "").orEmpty().ifBlank { defaultHost }
        set(v) = store.putString("fritzboxHost", normalizeHost(v).takeIf { it != defaultHost }.orEmpty())

    /** Eigene M3U-Adresse (http/https). Leer = Senderliste der FRITZ!Box verwenden. Die Eintraege muessen rtsp://-SAT>IP-Adressen sein. */
    var playlistUrl: String
        get() = store.getString("playlistUrl", "").orEmpty()
        set(v) = store.putString("playlistUrl", v.trim())

    val customPlaylist: Boolean get() = playlistUrl.isNotBlank()

    // ---- Wiedergabe (unveraendert seit 1.1) ----
    var cacheMs: Int
        get() = store.getInt(K_CACHE, DEFAULT_CACHE_MS).coerceIn(200, 15000)
        set(v) = store.putInt(K_CACHE, v.coerceIn(200, 15000))

    var audioDelayMs: Int
        get() = store.getInt(K_AUDIO_DELAY, 0).coerceIn(-2000, 2000)
        set(v) = store.putInt(K_AUDIO_DELAY, v.coerceIn(-2000, 2000))

    var transport: Transport
        get() = Transport.fromKey(store.getString(K_TRANSPORT, null)) ?: Transport.TCP
        set(v) = store.putString(K_TRANSPORT, v.key)

    /** Start-Gate (Daten erst nach der beschaedigten Startphase der Box an LibVLC). Standard: an (wie 1.1). */
    var startGate: Boolean
        get() = store.getBoolean("startGate", true)
        set(v) = store.putBoolean("startGate", v)

    // ---- Senderwechsel ----
    /** Wartezeit nach der letzten CH+/CH--Taste, bevor der Sender tatsaechlich geladen wird (schnelles Umschalten). */
    var zapDebounceMs: Int
        get() = store.getInt("zapDebounceMs", 300).coerceIn(100, 1500)
        set(v) = store.putInt("zapDebounceMs", v.coerceIn(100, 1500))

    /** Inaktivitaets-Timer: 0 = aus. Nach so vielen Stunden OHNE jede Tastenbetaetigung beendet die App die Wiedergabe (gibt den Tuner frei). */
    var idleStopHours: Int
        get() = store.getInt("idleStopHours", 0).coerceIn(0, 24)
        set(v) = store.putInt("idleStopHours", v.coerceIn(0, 24))

    var startWithLastChannel: Boolean
        get() = store.getBoolean("startWithLastChannel", true)
        set(v) = store.putBoolean("startWithLastChannel", v)

    var lastChannelName: String?
        get() = store.getString("lastChannelName", null)
        set(v) = store.putString("lastChannelName", v)

    // ---- Oberflaeche ----
    var infoBarSeconds: Int
        get() = store.getInt("infoBarSeconds", 5).coerceIn(2, 30)
        set(v) = store.putInt("infoBarSeconds", v.coerceIn(2, 30))

    /** 0 = Senderliste schliesst sich nie automatisch. */
    var channelListTimeoutSeconds: Int
        get() = store.getInt("channelListTimeoutSeconds", 15).coerceIn(0, 300)
        set(v) = store.putInt("channelListTimeoutSeconds", v.coerceIn(0, 300))

    var showEpgOnZap: Boolean
        get() = store.getBoolean("showEpgOnZap", true)
        set(v) = store.putBoolean("showEpgOnZap", v)

    var showEpgInChannelList: Boolean
        get() = store.getBoolean("showEpgInChannelList", true)
        set(v) = store.putBoolean("showEpgInChannelList", v)

    // ---- Bedienung ----
    /** Kanaltasten (▲/▼ und CH+/CH-) im Fernsehbild vertauschen: ▲ schaltet dann zum vorigen statt zum naechsten Sender. */
    var invertChannelKeys: Boolean
        get() = store.getBoolean("invertChannelKeys", false)
        set(v) = store.putBoolean("invertChannelKeys", v)

    /** Am Anfang/Ende von Listen mit einem weiteren Druck auf die andere Seite springen (nur bei neuem Tastendruck, nicht beim Halten). */
    var wrapLists: Boolean
        get() = store.getBoolean("wrapLists", true)
        set(v) = store.putBoolean("wrapLists", v)

    /** Alle Einstellungen auf Standard; Favoriten und zuletzt gesehener Sender bleiben erhalten. */
    fun resetSettings() {
        val fav = store.getString("favorites", "").orEmpty()
        val last = store.getString("lastChannelName", null)
        store.clear()
        if (fav.isNotEmpty()) store.putString("favorites", fav)
        if (last != null) store.putString("lastChannelName", last)
    }

    /** Werkseinstellungen: wirklich alles (Einstellungen, Favoriten, letzter Sender, EPG-Status). */
    fun resetAll() = store.clear()

    // ---- Favoriten ----
    /** Favoriten als Sendernamen (eine Zeile je Sender gespeichert). */
    val favorites: Set<String>
        get() = store.getString("favorites", "").orEmpty().split('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet())

    fun isFavorite(name: String): Boolean = favorites.contains(name)

    /** Schaltet einen Favoriten um und gibt den neuen Zustand zurueck (true = jetzt Favorit). */
    fun toggleFavorite(name: String): Boolean {
        val set = LinkedHashSet(favorites)
        val now = if (set.contains(name)) { set.remove(name); false } else { set.add(name); true }
        store.putString("favorites", set.joinToString("\n"))
        return now
    }

    fun clearFavorites() = store.putString("favorites", "")

    /** Senderliste, Programmuebersicht und CH+/CH- zeigen nur Favoriten (sofern es welche gibt). */
    var favoritesOnly: Boolean
        get() = store.getBoolean("favoritesOnly", false)
        set(v) = store.putBoolean("favoritesOnly", v)

    // ---- Programmfuehrer (EPG) ----
    /** XMLTV-URL. Bewusst KEIN Standardwert: ohne echte Quelle bleibt das Feld leer. */
    var epgUrl: String
        get() = store.getString("epgUrl", "").orEmpty()
        set(v) = store.putString("epgUrl", v.trim())

    var epgEnabled: Boolean
        get() = store.getBoolean("epgEnabled", true)
        set(v) = store.putBoolean("epgEnabled", v)

    val epgActive: Boolean get() = epgEnabled && epgUrl.isNotBlank()

    var epgAutoUpdate: Boolean
        get() = store.getBoolean("epgAutoUpdate", true)
        set(v) = store.putBoolean("epgAutoUpdate", v)

    var epgUpdateIntervalHours: Int
        get() = store.getInt("epgUpdateIntervalHours", 12).coerceIn(1, 168)
        set(v) = store.putInt("epgUpdateIntervalHours", v.coerceIn(1, 168))

    var epgDaysAhead: Int
        get() = store.getInt("epgDaysAhead", 3).coerceIn(1, 14)
        set(v) = store.putInt("epgDaysAhead", v.coerceIn(1, 14))

    /** Zeitpunkt (epoch ms) der letzten ERFOLGREICHEN Aktualisierung; 0 = noch nie. */
    var epgLastSuccessMs: Long
        get() = store.getLong("epgLastSuccessMs", 0L)
        set(v) = store.putLong("epgLastSuccessMs", v)

    var epgLastStatus: String
        get() = store.getString("epgLastStatus", "").orEmpty()
        set(v) = store.putString("epgLastStatus", v)

    /** Manuelle Zuordnung "Sendername=XMLTV-ID" (eine pro Zeile); hat Vorrang vor der automatischen Zuordnung. */
    var epgOverrides: String
        get() = store.getString("epgOverrides", "").orEmpty()
        set(v) = store.putString("epgOverrides", v)

    // ---- Debugging ----
    /** Standardmaessig aus. Ohne diese Einstellung erscheint nie ein Debug-Overlay. */
    var debugMode: Boolean
        get() = store.getBoolean("debugMode", false)
        set(v) = store.putBoolean("debugMode", v)

    /** Log-Datei (hometv.log) schreiben? Standardmaessig aus, um den Flash-Speicher des Fire TV zu schonen. */
    var fileLogging: Boolean
        get() = store.getBoolean("fileLogging", false)
        set(v) = store.putBoolean("fileLogging", v)

    /** Uebernimmt Test-Extras eines Start-Intents (nur wenn vorhanden). */
    fun applyIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra("cacheMs")) cacheMs = intent.getIntExtra("cacheMs", DEFAULT_CACHE_MS)
        if (intent.hasExtra("audioDelayMs")) audioDelayMs = intent.getIntExtra("audioDelayMs", 0)
        Transport.fromKey(intent.getStringExtra("transport"))?.let { transport = it }
        if (intent.hasExtra("debug")) debugMode = intent.getBooleanExtra("debug", false)
        if (intent.hasExtra("fileLog")) fileLogging = intent.getBooleanExtra("fileLog", false)
        intent.getStringExtra("fritzboxHost")?.let { fritzboxHost = it }
        intent.getStringExtra("playlistUrl")?.let { playlistUrl = it }
        intent.getStringExtra("epgUrl")?.let { epgUrl = it }
        intent.getStringExtra("epgMap")?.let { epgOverrides = it.replace("\\n", "\n") }   // "Sendername=XMLTV-ID" je Zeile (\n als Trenner)
    }

    companion object {
        const val K_CACHE = "cacheMs"
        const val K_AUDIO_DELAY = "audioDelayMs"
        const val K_TRANSPORT = "transport"
        val CACHE_PRESETS = listOf(1000, 2000, 3000, 5000)
        const val DEFAULT_CACHE_MS = 2000
        const val DEFAULT_HOST = "192.168.200.1"

        /** "http://192.168.178.1/x" -> "192.168.178.1" (nur Host bzw. Host:Port). */
        fun normalizeHost(s: String): String =
            s.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').trim()
    }
}
