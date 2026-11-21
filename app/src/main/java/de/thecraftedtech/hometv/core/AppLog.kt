package de.thecraftedtech.hometv.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Log in logcat UND in eine Datei (der logcat-Ringpuffer des Fire TV ist nur 256 KiB gross und ueberschreibt
 * Sitzungen sehr schnell). Datei: /sdcard/Android/data/de.thecraftedtech.hometv/files/hometv.log
 * Abholen: scripts\pull-log.bat
 *
 * Das Schreiben der Datei belastet den Flash-Speicher des Fire TV und ist deshalb standardmaessig AUS
 * (Einstellungen -> Debugging -> Log-Datei schreiben, [fileEnabled]). Ausgeschaltet geht das Log nur noch ins logcat (RAM).
 */
object AppLog {
    private const val MAX_BYTES = 1_500_000L
    @Volatile private var file: File? = null
    /** true = Meldungen zusaetzlich in die Datei schreiben. Standard aus; wird von der App aus der Einstellung gesetzt. */
    @Volatile var fileEnabled: Boolean = false
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "applog").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, fileLogging: Boolean) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, "hometv.log")
        file = f
        fileEnabled = fileLogging
        // nur anfassen (rotieren), wenn wirklich geschrieben wird
        if (fileLogging) {
            runCatching {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val old = File(dir, "hometv.log.1")
                    old.delete(); f.renameTo(old)
                }
            }
        }
        i("AppLog", "==== Home TV gestartet, Log-Datei: ${if (fileLogging) f.absolutePath else "aus"} ====")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg) { Log.i(tag, msg) }
    fun w(tag: String, msg: String) = write("W", tag, msg) { Log.w(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) =
        write("E", tag, msg + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")) { Log.e(tag, msg, t) }

    private inline fun write(level: String, tag: String, msg: String, logcat: () -> Unit) {
        logcat()
        if (!fileEnabled) return
        val f = file ?: return
        val line = synchronized(fmt) { "${fmt.format(Date())} $level/$tag: $msg\n" }
        exec.execute { if (fileEnabled) runCatching { f.appendText(line) } }
    }
}
