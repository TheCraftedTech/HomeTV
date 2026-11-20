package de.thecraftedtech.hometv.epg

import de.thecraftedtech.hometv.channels.Channel
import java.text.Normalizer

/**
 * Ordnet die Sender der XMLTV-Datei den SAT>IP-Sendern der App zu.
 *
 * Die M3U der FRITZ!Box enthaelt keine `tvg-id`/`tvg-name` (nur den Sendernamen). Deshalb gibt es mehrere Strategien,
 * in dieser Reihenfolge (erste Uebereinstimmung gewinnt):
 *  0. manuelle Zuordnung (Einstellung "Sendername=XMLTV-ID")
 *  1. `tvg-id` der M3U == XMLTV-Kanal-ID
 *  2. `tvg-name` der M3U == Anzeigename in der XMLTV-Datei (normalisiert)
 *  3. Sendername == Anzeigename (normalisiert: Kleinschreibung, Umlaute, Satzzeichen, Qualitaetszusatz HD/SD/UHD entfernt)
 *  4. Sendername == normalisierte XMLTV-ID (z. B. "DasErste.de" -> "daserste")
 *  5. eindeutige Teilmenge der Namens-Woerter (nur wenn genau ein Kandidat passt)
 */
object EpgMatcher {

    class Result(
        /** XMLTV-ID -> Namen der App-Sender, die diese Daten nutzen. */
        val mapping: Map<String, List<String>>,
        val matched: Set<String>,
        val unmatched: List<String>,
    )

    private val QUALITY = setOf("hd", "uhd", "sd", "fhd", "4k", "hevc", "h265", "h264", "1080p", "720p")
    private val DOMAIN = setOf("de", "at", "ch", "com", "tv", "net", "org", "eu", "uk", "fr", "it", "es", "nl")

    /** Normalisierter Vergleichsschluessel eines Anzeigenamens. */
    fun normalize(name: String): String = tokens(name).joinToString("")

    internal fun tokens(name: String, dropDomain: Boolean = false): List<String> {
        val s = Normalizer.normalize(name.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return s.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in QUALITY && !(dropDomain && it in DOMAIN) }
    }

    private fun normalizeId(id: String) = tokens(id, dropDomain = true).joinToString("")

    fun parseOverrides(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

    fun match(app: List<Channel>, xml: List<XmltvChannel>, overrides: Map<String, String> = emptyMap()): Result {
        val byId = xml.associateBy { it.id.lowercase() }
        val byName = HashMap<String, MutableList<String>>()      // normalisierter Anzeigename -> IDs
        val byNormId = HashMap<String, MutableList<String>>()    // normalisierte ID -> IDs
        for (c in xml) {
            for (n in c.names) byName.getOrPut(normalize(n)) { ArrayList() }.let { if (c.id !in it) it += c.id }
            byNormId.getOrPut(normalizeId(c.id)) { ArrayList() }.let { if (c.id !in it) it += c.id }
        }
        val tokenSets = xml.map { c -> c.id to c.names.map { tokens(it).toSet() }.filter { it.isNotEmpty() } }

        val mapping = LinkedHashMap<String, MutableList<String>>()
        val matched = LinkedHashSet<String>()
        val unmatched = ArrayList<String>()

        for (ch in app) {
            val id = findId(ch, byId, byName, byNormId, tokenSets, overrides)
            if (id == null) unmatched += ch.name
            else { mapping.getOrPut(id) { ArrayList() } += ch.name; matched += ch.name }
        }
        return Result(mapping, matched, unmatched)
    }

    private fun findId(
        ch: Channel, byId: Map<String, XmltvChannel>, byName: Map<String, List<String>>, byNormId: Map<String, List<String>>,
        tokenSets: List<Pair<String, List<Set<String>>>>, overrides: Map<String, String>,
    ): String? {
        overrides[ch.name]?.let { o -> return byId[o.lowercase()]?.id ?: o }
        ch.tvgId?.let { byId[it.lowercase()]?.let { c -> return c.id } }
        ch.tvgName?.let { n -> byName[normalize(n)]?.firstOrNull()?.let { return it } }
        val nn = normalize(ch.name)
        if (nn.isEmpty()) return null
        byName[nn]?.firstOrNull()?.let { return it }
        byNormId[nn]?.firstOrNull()?.let { return it }
        // 5. eindeutige Teilmenge der Woerter
        val mine = tokens(ch.name).toSet()
        if (mine.isEmpty()) return null
        val cands = tokenSets.filter { (_, sets) ->
            sets.any { other -> (mine.containsAll(other) || other.containsAll(mine)) && minOf(mine.size, other.size) >= 1 && minOf(mine.joinToString("").length, other.joinToString("").length) >= 4 }
        }.map { it.first }
        return cands.singleOrNull()
    }
}
