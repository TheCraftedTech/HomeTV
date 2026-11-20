package de.thecraftedtech.hometv.epg

import android.os.Process
import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.core.AppLog
import de.thecraftedtech.hometv.settings.AppSettings
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Programmfuehrer: laedt die XMLTV-Datei (unabhaengig von der SAT>IP-Wiedergabe), speichert nur die benoetigten
 * Daten lokal und beantwortet Abfragen ("laeuft gerade / danach") aus dem Speicher.
 *
 *  - Kein Download bei Senderwechsel: Abfragen lesen nur den Speicher.
 *  - Bedingte Abfrage (ETag / Last-Modified): ein unveraenderter Feed kostet fast keinen Traffic.
 *  - Fehler (URL nicht erreichbar, defekte Datei) lassen die zuletzt gespeicherten Daten unberuehrt und aktiv.
 *  - Der Download/Parser laeuft in einem Hintergrund-Thread mit niedriger Prioritaet, damit die Wiedergabe nicht stoert.
 */
class EpgRepository(
    private val cacheFile: File,
    private val settings: AppSettings,
    private val worker: Executor,
    private val postMain: (Runnable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Phase { IDLE, DOWNLOADING, DONE_OK, DONE_UNCHANGED, ERROR }

    interface Listener { fun onEpgStatusChanged() }

    @Volatile var data: EpgData? = null
        private set
    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile private var detail: String = ""
    @Volatile var listener: Listener? = null

    @Volatile var channels: List<Channel> = emptyList()

    private val running = AtomicBoolean(false)
    private val tag = "EPG"

    // ------------------------------------------------------------------ Abfragen (UI-Thread-tauglich)

    fun now(channel: Channel, t: Long = clock()): Programme? = data?.now(channel.name, t)
    fun next(channel: Channel, t: Long = clock()): Programme? = data?.next(channel.name, t)

    // ------------------------------------------------------------------ Start / Cache

    /** Laedt den lokalen Cache (schnell, ohne Netzwerk). Im Hintergrund aufrufen. */
    fun loadCache() {
        val d = EpgCache.read(cacheFile)
        if (d != null) {
            data = d
            AppLog.i(tag, "Cache geladen: ${d.meta.programmeCount} Sendungen, ${d.meta.matchedChannels}/${d.meta.totalChannels} Sender, Stand ${fmt(d.meta.createdAt)}")
        }
        notifyChanged()
    }

    fun clearCache() {
        data = null
        cacheFile.delete()
        settings.epgLastSuccessMs = 0
        settings.epgLastStatus = ""
        phase = Phase.IDLE; detail = ""
        notifyChanged()
    }

    /** Sollte beim Start (bzw. nach einiger Zeit) aufgerufen werden: aktualisiert nur, wenn die Daten zu alt sind. */
    fun refreshIfStale() {
        if (!settings.epgActive || !settings.epgAutoUpdate) return
        val age = clock() - settings.epgLastSuccessMs
        val stale = settings.epgLastSuccessMs == 0L || age > settings.epgUpdateIntervalHours * 3_600_000L
        val urlChanged = data?.meta?.sourceUrl?.let { it != settings.epgUrl } ?: false
        if (stale || urlChanged) refresh(force = false)
    }

    /** Aktualisiert die Programmdaten im Hintergrund. [force] ignoriert ETag/Last-Modified. */
    fun refresh(force: Boolean) {
        if (!settings.epgActive) { setPhase(Phase.IDLE, ""); return }
        if (!running.compareAndSet(false, true)) return
        setPhase(Phase.DOWNLOADING, "Verbinde …")
        worker.execute {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) { }
            try {
                doRefresh(force)
            } catch (e: Throwable) {
                AppLog.e(tag, "Aktualisierung fehlgeschlagen", e)
                val msg = describe(e)
                settings.epgLastStatus = "Fehler: $msg"
                setPhase(Phase.ERROR, msg)
            } finally {
                running.set(false)
                notifyChanged()
            }
        }
    }

    // ------------------------------------------------------------------ Status (fuer das Menue)

    fun statusText(): String {
        if (settings.epgUrl.isBlank()) return "Keine URL eingetragen"
        if (!settings.epgEnabled) return "Deaktiviert"
        val have = data
        val old = if (have != null) " – gespeicherte Daten vom ${fmt(have.meta.createdAt)} werden weiter verwendet" else ""
        return when (phase) {
            Phase.DOWNLOADING -> detail.ifEmpty { "Aktualisiere …" }
            Phase.ERROR -> "Fehler: $detail$old"
            Phase.DONE_UNCHANGED -> "Aktuell (Quelle unverändert)"
            Phase.DONE_OK -> "OK – ${have?.meta?.programmeCount ?: 0} Sendungen"
            Phase.IDLE -> when {
                have != null -> "Bereit – ${have.meta.programmeCount} Sendungen gespeichert"
                settings.epgLastStatus.isNotEmpty() -> settings.epgLastStatus
                else -> "Noch nicht aktualisiert"
            }
        }
    }

    fun lastUpdateText(): String =
        if (settings.epgLastSuccessMs == 0L) "nie" else fmt(settings.epgLastSuccessMs)

    fun matchText(): String {
        val m = data?.meta ?: return "–"
        return "${m.matchedChannels} von ${channels.size.takeIf { it > 0 } ?: m.totalChannels} Sendern (XMLTV enthält ${m.xmltvChannels} Kanäle)"
    }

    fun cacheText(): String {
        val d = data ?: return "leer"
        val kb = if (cacheFile.exists()) cacheFile.length() / 1024 else 0
        return "${d.meta.programmeCount} Sendungen, $kb KiB"
    }

    // ------------------------------------------------------------------ Implementierung

    private fun doRefresh(force: Boolean) {
        val url = settings.epgUrl
        val meta = data?.meta?.takeIf { it.sourceUrl == url }
        val t0 = clock()
        var conn = open(url, if (force) null else meta)
        var redirects = 0
        while (conn.responseCode in intArrayOf(301, 302, 303, 307, 308) && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(URL(URL(url), loc).toString(), if (force) null else meta)
            redirects++
        }
        try {
            val code = conn.responseCode
            if (code == 304 && meta != null) {
                settings.epgLastSuccessMs = clock()
                settings.epgLastStatus = "Aktuell (Quelle unverändert)"
                AppLog.i(tag, "304 – Quelle unveraendert")
                setPhase(Phase.DONE_UNCHANGED, "")
                return
            }
            if (code != 200) throw IOException("HTTP $code")
            val etag = conn.getHeaderField("ETag")
            val lastMod = conn.getHeaderField("Last-Modified")
            val total = conn.contentLength.toLong() // -1 = unbekannt (getContentLengthLong erst ab API 24)
            val now = clock()
            val windowStart = now - 6 * 3_600_000L
            val windowEnd = now + settings.epgDaysAhead * 86_400_000L
            val overrides = EpgMatcher.parseOverrides(settings.epgOverrides)
            val app = channels
            if (app.isEmpty()) throw IOException("Senderliste noch nicht geladen")

            var bytes = 0L
            var lastUi = 0L
            val counting = object : FilterInputStream(conn.inputStream) {
                override fun read(): Int = super.read().also { if (it >= 0) bytes++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { bytes += it; tick() } }
                fun tick() {
                    val c = clock()
                    if (c - lastUi > 400) {
                        lastUi = c
                        val mb = bytes / 1_048_576.0
                        detail = if (total > 0) "Lade und verarbeite … %.1f von %.1f MB".format(Locale.US, mb, total / 1_048_576.0)
                                 else "Lade und verarbeite … %.1f MB".format(Locale.US, mb)
                        notifyChanged()
                    }
                }
            }
            val result = XmltvParser.parse(counting, windowStart, windowEnd, { xml -> EpgMatcher.match(app, xml, overrides).mapping })
            if (result.programmesKept == 0) {
                throw IOException("Keine Sendungen zugeordneter Sender gefunden (${result.xmltvChannels.size} XMLTV-Kanäle, ${result.programmesSeen} Sendungen gesehen)")
            }
            val matchedNames = result.byKey.keys
            val newData = EpgData(
                EpgMeta(now, url, etag, lastMod, result.programmesKept, matchedNames.size, app.size, result.xmltvChannels.size),
                result.byKey.mapValues { it.value.toTypedArray() },
            )
            EpgCache.write(cacheFile, newData)
            data = newData
            settings.epgLastSuccessMs = clock()
            settings.epgLastStatus = "OK"
            AppLog.i(tag, "Aktualisiert in ${clock() - t0} ms: ${result.programmesSeen} Sendungen gelesen, ${result.programmesKept} behalten, ${matchedNames.size}/${app.size} Sender zugeordnet, ${bytes / 1024} KiB geladen")
            setPhase(Phase.DONE_OK, "")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, cond: EpgMeta?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "HomeTV/1.2")
        c.setRequestProperty("Accept-Encoding", "gzip")
        cond?.etag?.let { c.setRequestProperty("If-None-Match", it) }
        cond?.lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
        return c
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Server nicht gefunden (${e.message})"
        is java.net.SocketTimeoutException -> "Zeitüberschreitung"
        is javax.net.ssl.SSLException -> "TLS-/Zertifikatsfehler (${e.message})"
        is org.xml.sax.SAXException -> "Datei ist kein gültiges XMLTV (${e.message?.take(80)})"
        is java.util.zip.ZipException -> "Defekte gzip-Datei"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun setPhase(p: Phase, d: String) { phase = p; detail = d; notifyChanged() }

    private fun notifyChanged() { postMain(Runnable { listener?.onEpgStatusChanged() }) }

    private fun fmt(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.GERMANY).format(Date(ms))
}
