package de.thecraftedtech.hometv.channels

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ein Sender der FRITZ!Box-Liste. [tvgId]/[tvgName]/[logo]/[group] stammen aus optionalen M3U-Attributen
 * (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`); die M3U der FRITZ!Box enthaelt sie nicht (nur Name + URL).
 * [number] ist die 1-basierte Position in der Liste (wird von der App vergeben, nicht gespeichert).
 */
data class Channel(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    val group: String? = null,
)

/**
 * Senderliste. Standardquelle ist die M3U der FRITZ!Box (AVM-Weg /dvb/m3u/tvhd.m3u + tvsd.m3u), HD zuerst, dann SD –
 * genau wie tests/data/tv.m3u. Optional kann der Nutzer eine eigene M3U-Adresse (http/https) angeben; ihre Eintraege muessen
 * rtsp://-SAT>IP-Adressen sein. Stream-Adressen werden nie zusammengesetzt.
 *
 * Reihenfolge: 1. live von der Quelle, 2. Zwischenspeicher der letzten erfolgreichen Abfrage DIESER Quelle,
 * 3. (nur Standard-FRITZ!Box) beim Build mitgelieferte Kopie von tests/data/tv.m3u (falls beim Build vorhanden).
 */
object Playlist {

    class PlaylistException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Loaded(val channels: List<Channel>, val source: String)

    private const val ASSET_FILE = "tv.m3u"
    private val DEFAULT_NAMES = listOf("Das Erste HD", "Das Erste", "ARD HD")
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    /** Zwischenspeicher je Quelle (FRITZ!Box-Adresse bzw. eigene URL), damit eine andere Quelle nie die Liste der vorigen anzeigt. */
    internal fun cacheName(host: String, customUrl: String): String =
        if (customUrl.isNotBlank()) "tv-url-${customUrl.trim().hashCode().toUInt().toString(16)}.m3u"
        else "tv-box-${host.trim().hashCode().toUInt().toString(16)}.m3u"

    /**
     * Blockierend (Netzwerk) – nicht im UI-Thread aufrufen.
     * @param customUrl  eigene M3U-Adresse; leer = Senderliste der FRITZ!Box unter [host]
     * @param assetFallback  die beim Build mitgelieferte Kopie der FRITZ!Box-Liste nur fuer die Standard-Box zulassen
     */
    fun load(context: Context, host: String, customUrl: String = "", assetFallback: Boolean = true): Loaded {
        val custom = customUrl.isNotBlank()
        val cache = File(context.filesDir, cacheName(host, customUrl))
        var liveError: Exception? = null
        try {
            val all = if (custom) {
                fetchUrl(customUrl, "Senderliste").also {
                    if (it.isEmpty()) throw PlaylistException("Unter dieser Adresse wurden keine Sender mit rtsp://-Adresse (SAT>IP) gefunden.")
                }
            } else {
                val hd = fetch(host, "tvhd")
                val sd = runCatching { fetch(host, "tvsd") }.getOrDefault(emptyList())
                (hd + sd).also { if (it.isEmpty()) throw PlaylistException("Die Senderliste der FRITZ!Box ist leer.") }
            }
            runCatching { cache.writeText(toM3u(all)) }
            return Loaded(all, if (custom) "eigene URL" else "FRITZ!Box")
        } catch (e: Exception) {
            liveError = e
        }
        runCatching {
            if (cache.exists()) parse(cache.readText()).takeIf { it.isNotEmpty() }?.let { return Loaded(it, "Zwischenspeicher") }
        }
        if (assetFallback && !custom) runCatching {
            val text = context.assets.open(ASSET_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(text).takeIf { it.isNotEmpty() }?.let { return Loaded(it, "mitgelieferte Kopie") }
        }
        throw liveError as? PlaylistException ?: PlaylistException(liveError?.message ?: "Senderliste nicht ladbar", liveError)
    }

    fun defaultIndex(channels: List<Channel>): Int {
        for (wanted in DEFAULT_NAMES) {
            val i = channels.indexOfFirst { it.name.equals(wanted, ignoreCase = true) }
            if (i >= 0) return i
        }
        return 0
    }

    fun indexOfName(channels: List<Channel>, name: String): Int =
        channels.indexOfFirst { it.name.equals(name, ignoreCase = true) }

    internal fun fetch(host: String, list: String): List<Channel> =
        fetchUrl("http://$host/dvb/m3u/$list.m3u", "Senderliste ($list)", "FRITZ!Box $host")

    /** Laedt und parst eine M3U per HTTP(S). Eine Adresse ohne Schema wird als http:// behandelt. */
    internal fun fetchUrl(rawUrl: String, what: String, where: String? = null): List<Channel> {
        val text = rawUrl.trim()
        val full = if ("://" in text) text else "http://$text"
        val url = try { URL(full) } catch (e: java.net.MalformedURLException) { throw PlaylistException("Ungültige Adresse: $text", e) }
        if (url.protocol != "http" && url.protocol != "https") throw PlaylistException("Nur http:// und https:// Adressen werden unterstützt: $text")
        val label = where ?: url.host
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) throw PlaylistException("$what lieferte HTTP $code.")
            return parse(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: PlaylistException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw PlaylistException("Zeitüberschreitung beim Laden von $label.", e)
        } catch (e: java.io.IOException) {
            throw PlaylistException("$label nicht erreichbar (${e.javaClass.simpleName}: ${e.message}).", e)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var pending: Channel? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('﻿')
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF") -> pending = parseExtInf(line)
                line.startsWith("#") -> Unit
                else -> {
                    val p = pending
                    if (p != null && line.startsWith("rtsp://")) result += p.copy(url = line)
                    pending = null
                }
            }
        }
        return result
    }

    /** `#EXTINF:0 tvg-id="x" tvg-name="y" tvg-logo="z" group-title="g",Anzeigename` */
    private fun parseExtInf(line: String): Channel {
        val comma = line.lastIndexOf(',')
        val name = if (comma >= 0) line.substring(comma + 1).trim() else ""
        val head = if (comma >= 0) line.substring(0, comma) else line
        val attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return Channel(
            name = name.ifEmpty { attrs["tvg-name"].orEmpty() },
            url = "",
            tvgId = attrs["tvg-id"]?.ifBlank { null },
            tvgName = attrs["tvg-name"]?.ifBlank { null },
            logo = attrs["tvg-logo"]?.ifBlank { null },
            group = attrs["group-title"]?.ifBlank { null },
        )
    }

    internal fun toM3u(channels: List<Channel>): String = buildString {
        append("#EXTM3U\n")
        for (c in channels) {
            append("#EXTINF:0")
            c.tvgId?.let { append(" tvg-id=\"").append(it).append('"') }
            c.tvgName?.let { append(" tvg-name=\"").append(it).append('"') }
            c.logo?.let { append(" tvg-logo=\"").append(it).append('"') }
            c.group?.let { append(" group-title=\"").append(it).append('"') }
            append(',').append(c.name).append('\n').append(c.url).append('\n')
        }
    }
}
