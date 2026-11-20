package de.thecraftedtech.hometv

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

/**
 * Einstellungen, persistent in SharedPreferences. Zum Testen per ADB ueberschreibbar:
 *   adb shell am start -n de.thecraftedtech.hometv/.MainActivity --es transport udp --ei cacheMs 3000 --ei audioDelayMs 0 --es channel "ZDF HD"
 */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("hometv", Context.MODE_PRIVATE)

    var cacheMs: Int
        get() = sp.getInt("cacheMs", DEFAULT_CACHE_MS)
        set(v) { sp.edit().putInt("cacheMs", v).apply() }

    var audioDelayMs: Int
        get() = sp.getInt("audioDelayMs", 0)
        set(v) { sp.edit().putInt("audioDelayMs", v.coerceIn(-2000, 2000)).apply() }

    var transport: Transport
        get() = Transport.fromKey(sp.getString("transport", null)) ?: Transport.TCP
        set(v) { sp.edit().putString("transport", v.key).apply() }

    /** Uebernimmt Test-Extras eines Start-Intents (nur wenn vorhanden). */
    fun applyIntent(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra("cacheMs")) cacheMs = intent.getIntExtra("cacheMs", DEFAULT_CACHE_MS).coerceIn(200, 15000)
        if (intent.hasExtra("audioDelayMs")) audioDelayMs = intent.getIntExtra("audioDelayMs", 0)
        Transport.fromKey(intent.getStringExtra("transport"))?.let { transport = it }
    }

    companion object {
        val CACHE_PRESETS = listOf(1000, 2000, 3000, 5000)
        const val DEFAULT_CACHE_MS = 2000
    }
}
