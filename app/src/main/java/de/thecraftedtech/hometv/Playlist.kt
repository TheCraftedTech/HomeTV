package de.thecraftedtech.hometv

import java.net.HttpURLConnection
import java.net.URL

data class Channel(val name: String, val url: String)

/**
 * Laedt die Senderliste direkt von der FRITZ!Box (AVM-Weg: /dvb/m3u/tvhd.m3u bzw. tvsd.m3u).
 * Die Stream-URL wird ausschliesslich aus dieser Liste gelesen, nie zusammengesetzt.
 */
object Playlist {

    private val PREFERRED = listOf("Das Erste HD", "Das Erste", "ARD HD")

    class PlaylistException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Sucht "Das Erste HD" in der HD-Liste, sonst "Das Erste" in der SD-Liste. */
    fun findDasErste(host: String): Channel {
        val hd = fetchAndParse(host, "tvhd")
        pick(hd)?.let { return it }
        val sd = fetchAndParse(host, "tvsd")
        pick(sd)?.let { return it }
        throw PlaylistException("„Das Erste“ ist in der Senderliste der FRITZ!Box nicht enthalten.")
    }

    private fun pick(channels: List<Channel>): Channel? {
        for (wanted in PREFERRED) {
            channels.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun fetchAndParse(host: String, list: String): List<Channel> {
        val url = URL("http://$host/dvb/m3u/$list.m3u")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) throw PlaylistException("Senderliste ($list) lieferte HTTP $code.")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(text)
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
}
