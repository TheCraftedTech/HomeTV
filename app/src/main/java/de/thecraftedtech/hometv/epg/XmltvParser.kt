package de.thecraftedtech.hometv.epg

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Streaming-Parser fuer XMLTV (SAX): haelt nie die ganze Datei im Speicher, sondern nur die Sendungen der
 * zugeordneten Sender im gewuenschten Zeitfenster. Grosse Dateien (mehrere 10 MB, auch .gz) sind dadurch unkritisch.
 *
 * Ablauf: Alle `<channel>`-Elemente stehen laut XMLTV-DTD vor den `<programme>`-Elementen. Beim ersten `<programme>`
 * wird [resolve] aufgerufen (Zuordnung XMLTV-Kanal -> App-Sender); Sendungen nicht zugeordneter Kanaele werden verworfen.
 */
object XmltvParser {

    class Result(
        val byKey: Map<String, List<Programme>>,
        val xmltvChannels: List<XmltvChannel>,
        val mapping: Map<String, List<String>>,
        val programmesSeen: Int,
        val programmesKept: Int,
    )

    private const val MAX_TITLE = 160
    private const val MAX_DESC = 600

    /**
     * @param windowStart Sendungen, die vor diesem Zeitpunkt enden, werden verworfen
     * @param windowEnd   Sendungen, die nach diesem Zeitpunkt beginnen, werden verworfen
     * @param resolve     liefert XMLTV-Kanal-ID -> Namen der App-Sender
     */
    fun parse(
        input: InputStream,
        windowStart: Long,
        windowEnd: Long,
        resolve: (List<XmltvChannel>) -> Map<String, List<String>>,
        onProgress: (programmesSeen: Int) -> Unit = {},
    ): Result {
        val handler = Handler(windowStart, windowEnd, resolve, onProgress)
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val parser = factory.newSAXParser()
        parser.parse(InputSource(maybeGunzip(input)), handler)
        return handler.result()
    }

    /** Erkennt gzip am Dateianfang (Magic 1f 8b), unabhaengig von Dateiendung/Header. */
    fun maybeGunzip(input: InputStream): InputStream {
        val bin = if (input.markSupported()) input else java.io.BufferedInputStream(input, 64 * 1024)
        bin.mark(2)
        val b1 = bin.read()
        val b2 = bin.read()
        bin.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bin, 64 * 1024) else bin
    }

    private class Handler(
        val windowStart: Long, val windowEnd: Long,
        val resolve: (List<XmltvChannel>) -> Map<String, List<String>>,
        val onProgress: (Int) -> Unit,
    ) : DefaultHandler(), EntityResolver {

        val channels = ArrayList<XmltvChannel>()
        var mapping: Map<String, List<String>>? = null
        val out = HashMap<String, ArrayList<Programme>>()
        var seen = 0
        var kept = 0

        // laufendes <channel>
        var chId: String? = null
        var chNames = ArrayList<String>()
        // laufendes <programme>
        var progTargets: List<String>? = null
        var pStart = 0L
        var pEnd = 0L
        var pTitle: String? = null
        var pSub: String? = null
        var pDesc: String? = null
        // Text sammeln
        var textFor: String? = null
        val text = StringBuilder()

        /** Externe Entities/DTDs nie laden (kein Netzwerkzugriff beim Parsen, schuetzt vor XXE). */
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            when (qName) {
                "channel" -> { chId = a.getValue("id"); chNames = ArrayList() }
                "display-name" -> if (chId != null) begin("display-name")
                "programme" -> startProgramme(a)
                "title" -> if (progTargets != null && pTitle == null) begin("title")
                "sub-title" -> if (progTargets != null && pSub == null) begin("sub-title")
                "desc" -> if (progTargets != null && pDesc == null) begin("desc")
            }
        }

        private fun begin(name: String) { textFor = name; text.setLength(0) }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (textFor != null && text.length < 2000) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "display-name" -> if (textFor == "display-name") { chNames += text.toString().trim(); textFor = null }
                "channel" -> { chId?.let { channels += XmltvChannel(it, chNames.filter { n -> n.isNotEmpty() }) }; chId = null }
                "title" -> if (textFor == "title") { pTitle = text.toString().trim(); textFor = null }
                "sub-title" -> if (textFor == "sub-title") { pSub = text.toString().trim(); textFor = null }
                "desc" -> if (textFor == "desc") { pDesc = text.toString().trim(); textFor = null }
                "programme" -> endProgramme()
            }
        }

        private fun startProgramme(a: Attributes) {
            if (mapping == null) mapping = resolve(channels)
            seen++
            if (seen % 2000 == 0) onProgress(seen)
            pTitle = null; pSub = null; pDesc = null; progTargets = null
            val targets = mapping!![a.getValue("channel") ?: return] ?: return
            val s = parseTime(a.getValue("start")) ?: return
            val e = parseTime(a.getValue("stop")) ?: (s + 30 * 60_000L)
            if (e <= windowStart || s >= windowEnd || e <= s) return
            pStart = s; pEnd = e; progTargets = targets
        }

        private fun endProgramme() {
            val targets = progTargets ?: return
            progTargets = null
            val title = (pTitle?.takeIf { it.isNotEmpty() } ?: pSub ?: "").take(MAX_TITLE)
            if (title.isEmpty()) return
            val desc = (pDesc?.takeIf { it.isNotEmpty() } ?: pSub?.takeIf { it.isNotEmpty() && it != title })?.take(MAX_DESC)
            val p = Programme(pStart, pEnd, title, desc)
            for (k in targets) out.getOrPut(k) { ArrayList() } += p
            kept++
        }

        fun result(): Result {
            val sorted = out.mapValues { (_, v) -> v.sortedBy { it.start } }
            return Result(sorted, channels, mapping ?: resolve(channels), seen, kept)
        }
    }

    // ---------------------------------------------------------------- Zeit

    /**
     * XMLTV-Zeit "YYYYMMDDHHMMSS [+-HHMM]" -> epoch-ms. Ohne Zeitzonenangabe gilt UTC (XMLTV-Vorgabe).
     * Auch gekuerzte Formen (YYYYMMDDHHMM, YYYYMMDD) und "Z"/"UTC" werden akzeptiert.
     */
    fun parseTime(s: String?): Long? {
        if (s == null) return null
        val t = s.trim()
        var i = 0
        while (i < t.length && t[i].isDigit()) i++
        if (i < 8 || i > 14) return null
        val d = t.substring(0, i).padEnd(14, '0')
        val y = d.substring(0, 4).toInt(); val mo = d.substring(4, 6).toInt(); val da = d.substring(6, 8).toInt()
        val h = d.substring(8, 10).toInt(); val mi = d.substring(10, 12).toInt(); val se = d.substring(12, 14).toInt()
        if (mo !in 1..12 || da !in 1..31 || h > 23 || mi > 59 || se > 60) return null
        var offMin = 0
        val rest = t.substring(i).trim()
        if (rest.isNotEmpty() && rest != "Z" && !rest.equals("UTC", true) && !rest.equals("GMT", true)) {
            val sign = when (rest[0]) { '+' -> 1; '-' -> -1; else -> return null }
            val digits = rest.substring(1).replace(":", "")
            if (digits.length < 2 || !digits.all { it.isDigit() }) return null
            val oh = digits.substring(0, 2).toInt()
            val om = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
            offMin = sign * (oh * 60 + om)
        }
        val days = daysFromCivil(y, mo, da)
        val epochSec = days * 86_400L + h * 3_600L + mi * 60L + se - offMin * 60L
        return epochSec * 1000L
    }

    /** Tage seit 1970-01-01 fuer ein Datum im proleptischen gregorianischen Kalender (Howard Hinnant). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
