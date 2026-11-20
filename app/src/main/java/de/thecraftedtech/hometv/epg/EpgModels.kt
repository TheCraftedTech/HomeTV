package de.thecraftedtech.hometv.epg

/** Eine Sendung. Zeiten in epoch-ms (UTC); die Anzeige rechnet in die lokale Zeitzone um. */
data class Programme(val start: Long, val end: Long, val title: String, val desc: String?) {
    val durationMs: Long get() = end - start
}

/** Ein `<channel>`-Eintrag der XMLTV-Datei. */
data class XmltvChannel(val id: String, val names: List<String>)

/** Angaben zur zwischengespeicherten Datei. */
data class EpgMeta(
    val createdAt: Long,
    val sourceUrl: String,
    val etag: String?,
    val lastModified: String?,
    val programmeCount: Int,
    val matchedChannels: Int,
    val totalChannels: Int,
    val xmltvChannels: Int,
)

/**
 * Programmdaten im Speicher, je App-Sender (Schluessel = Sendername) nach Beginn sortiert.
 * Abfragen sind Binaersuchen und damit auch im UI-Thread schnell.
 */
class EpgData(val meta: EpgMeta, private val byKey: Map<String, Array<Programme>>) {

    val channelKeys: Set<String> get() = byKey.keys

    fun programmes(key: String): Array<Programme> = byKey[key] ?: emptyArray()

    /** Laufende Sendung zum Zeitpunkt [t] oder null. */
    fun now(key: String, t: Long): Programme? {
        val a = byKey[key] ?: return null
        val i = lastStartingAtOrBefore(a, t)
        return if (i >= 0 && a[i].end > t) a[i] else null
    }

    /** Naechste Sendung nach der laufenden (bzw. die erste kommende, wenn gerade keine laeuft). */
    fun next(key: String, t: Long): Programme? {
        val a = byKey[key] ?: return null
        val i = lastStartingAtOrBefore(a, t)
        val from = if (i >= 0 && a[i].end > t) i + 1 else i + 1
        return a.getOrNull(from)
    }

    private fun lastStartingAtOrBefore(a: Array<Programme>, t: Long): Int {
        var lo = 0
        var hi = a.size - 1
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid].start <= t) { res = mid; lo = mid + 1 } else hi = mid - 1
        }
        return res
    }

    companion object {
        val EMPTY_META = EpgMeta(0, "", null, null, 0, 0, 0, 0)
    }
}
