package de.thecraftedtech.hometv

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val name: String, val url: String)

/**
 * Senderliste. Quelle ist ausschliesslich die M3U der FRITZ!Box (AVM-Weg /dvb/m3u/tvhd.m3u + tvsd.m3u),
 * HD zuerst, dann SD – genau wie tests/data/tv.m3u. Stream-Adressen werden nie zusammengesetzt.
 *
 * Reihenfolge der Quellen: 1. live von der Box, 2. Zwischenspeicher der letzten erfolgreichen Abfrage,
 * 3. beim Build mitgelieferte Kopie von tests/data/tv.m3u (falls beim Build vorhanden).
 */
object Playlist {

    class PlaylistException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Loaded(val channels: List<Channel>, val source: String)

    private const val CACHE_FILE = "tv.m3u"
    private const val ASSET_FILE = "tv.m3u"
    private val DEFAULT_NAMES = listOf("Das Erste HD", "Das Erste", "ARD HD")

    fun load(context: Context, host: String): Loaded {
        var liveError: Exception? = null
        try {
            val hd = fetch(host, "tvhd")
            val sd = runCatching { fetch(host, "tvsd") }.getOrDefault(emptyList())
            val all = hd + sd
            if (all.isEmpty()) throw PlaylistException("Die Senderliste der FRITZ!Box ist leer.")
            runCatching { File(context.filesDir, CACHE_FILE).writeText(toM3u(all)) }
            return Loaded(all, "FRITZ!Box")
        } catch (e: Exception) {
            liveError = e
        }
        runCatching {
            val f = File(context.filesDir, CACHE_FILE)
            if (f.exists()) parse(f.readText()).takeIf { it.isNotEmpty() }?.let { return Loaded(it, "Zwischenspeicher") }
        }
        runCatching {
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

    internal fun fetch(host: String, list: String): List<Channel> {
        val conn = URL("http://$host/dvb/m3u/$list.m3u").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) throw PlaylistException("Senderliste ($list) lieferte HTTP $code.")
            return parse(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: PlaylistException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw PlaylistException("Zeitüberschreitung beim Laden der Senderliste von $host.", e)
        } catch (e: java.io.IOException) {
            throw PlaylistException("FRITZ!Box $host nicht erreichbar (${e.javaClass.simpleName}: ${e.message}).", e)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var pendingName: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('﻿')
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF") -> pendingName = line.substringAfter(',', "").trim()
                line.startsWith("#") -> Unit
                else -> {
                    val name = pendingName
                    if (name != null && line.startsWith("rtsp://")) result += Channel(name, line)
                    pendingName = null
                }
            }
        }
        return result
    }

    internal fun toM3u(channels: List<Channel>): String = buildString {
        append("#EXTM3U\n")
        for (c in channels) append("#EXTINF:0,").append(c.name).append('\n').append(c.url).append('\n')
    }
}
