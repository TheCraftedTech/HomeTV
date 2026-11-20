package de.thecraftedtech.hometv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Schreibt das App-Log (hometv.log) in Teilen ins logcat (Tag "HomeTVLog"), damit es per ADB abgeholt werden kann:
 * Auf Android 11 ist /sdcard/Android/data/<app>/ fuer "adb pull" gesperrt. Ausloesen mit
 *   adb shell am broadcast -n de.thecraftedtech.hometv/.LogDumpReceiver -a de.thecraftedtech.hometv.DUMP_LOG [--ei bytes 150000]
 * (scripts\pull-log.bat macht das). Es wird nur die eigene Logdatei ausgegeben; logcat ist fuer andere Apps nicht lesbar.
 */
class LogDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = "HomeTVLog"
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "hometv.log")
        if (!file.exists()) { Log.i(tag, "keine Logdatei: ${file.absolutePath}"); return }
        val maxBytes = intent.getIntExtra("bytes", 150_000).coerceIn(1_000, 400_000)
        val total = file.length()
        val text = RandomAccessFile(file, "r").use { r ->
            r.seek(maxOf(0L, total - maxBytes))
            ByteArray(minOf(total, maxBytes.toLong()).toInt()).also { r.readFully(it) }.toString(Charsets.UTF_8)
        }
        Log.i(tag, "--- Anfang (letzte ${text.length} von $total Bytes, ${file.absolutePath}) ---")
        text.lineSequence().filter { it.isNotEmpty() }.forEach { Log.i(tag, it.take(3500)) }
        Log.i(tag, "--- Ende ---")
    }
}
