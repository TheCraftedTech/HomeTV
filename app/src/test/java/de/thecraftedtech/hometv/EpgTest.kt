package de.thecraftedtech.hometv

import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.channels.Playlist
import de.thecraftedtech.hometv.epg.EpgCache
import de.thecraftedtech.hometv.epg.EpgData
import de.thecraftedtech.hometv.epg.EpgMatcher
import de.thecraftedtech.hometv.epg.EpgMeta
import de.thecraftedtech.hometv.epg.Programme
import de.thecraftedtech.hometv.epg.XmltvChannel
import de.thecraftedtech.hometv.epg.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.GZIPOutputStream

private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

class XmltvTimeTest {
    @Test fun parsesOffsets() {
        assertEquals(utc("2026-11-20T19:00:00Z"), XmltvParser.parseTime("20261120200000 +0100"))
        assertEquals(utc("2026-07-01T18:00:00Z"), XmltvParser.parseTime("20260701200000 +0200"))   // Sommerzeit
        assertEquals(utc("2026-11-20T20:00:00Z"), XmltvParser.parseTime("20261120200000 +0000"))
        assertEquals(utc("2026-11-21T01:30:00Z"), XmltvParser.parseTime("20261120200000 -0530"))
    }

    @Test fun withoutOffsetIsUtc() {
        assertEquals(utc("2026-11-20T20:00:00Z"), XmltvParser.parseTime("20261120200000"))
        assertEquals(utc("2026-11-20T20:00:00Z"), XmltvParser.parseTime("20261120200000 Z"))
        assertEquals(utc("2026-11-20T20:00:00Z"), XmltvParser.parseTime("20261120200000 UTC"))
    }

    @Test fun shortenedForms() {
        assertEquals(utc("2026-11-20T20:15:00Z"), XmltvParser.parseTime("202611202015"))
        assertEquals(utc("2026-11-20T00:00:00Z"), XmltvParser.parseTime("20261120"))
    }

    @Test fun leapDayAndYearBoundary() {
        assertEquals(utc("2028-02-29T12:00:00Z"), XmltvParser.parseTime("20280229120000 +0000"))
        assertEquals(utc("2027-01-01T00:00:00Z"), XmltvParser.parseTime("20270101010000 +0100"))
    }

    @Test fun invalidValues() {
        assertNull(XmltvParser.parseTime(null))
        assertNull(XmltvParser.parseTime(""))
        assertNull(XmltvParser.parseTime("abc"))
        assertNull(XmltvParser.parseTime("20261320200000"))   // Monat 13
        assertNull(XmltvParser.parseTime("20261120200000 +XX"))
    }
}

class XmltvParserTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="test">
  <channel id="DasErste.de"><display-name lang="de">Das Erste</display-name><display-name>ARD</display-name></channel>
  <channel id="zdf.de"><display-name>ZDF</display-name></channel>
  <channel id="unbekannt.de"><display-name>Gibt es nicht</display-name></channel>
  <programme start="20261120190000 +0100" stop="20261120200000 +0100" channel="DasErste.de">
    <title lang="de">Davor</title></programme>
  <programme start="20261120200000 +0100" stop="20261120201500 +0100" channel="DasErste.de">
    <title lang="de">Tagesschau</title><desc lang="de">Nachrichten &amp; Wetter</desc></programme>
  <programme start="20261120201500 +0100" stop="20261120211500 +0100" channel="DasErste.de">
    <title>Tatort</title><sub-title>Folge 1</sub-title></programme>
  <programme start="20261120200000 +0100" stop="20261120210000 +0100" channel="zdf.de"><title>heute-journal</title></programme>
  <programme start="20261120200000 +0100" stop="20261120210000 +0100" channel="unbekannt.de"><title>Wird verworfen</title></programme>
  <programme start="20261201200000 +0100" stop="20261201210000 +0100" channel="zdf.de"><title>Zu weit in der Zukunft</title></programme>
  <programme start="20261120210000 +0100" stop="20261120220000 +0100" channel="zdf.de"><title></title></programme>
</tv>"""

    private val app = listOf(Channel("Das Erste HD", "rtsp://x/1"), Channel("ZDF HD", "rtsp://x/2"), Channel("Das Erste", "rtsp://x/3"))

    private fun parse(input: ByteArray) = XmltvParser.parse(
        ByteArrayInputStream(input),
        windowStart = utc("2026-11-20T19:10:00Z"),     // 20:10 lokal: "Davor" ist vorbei, die Tagesschau laeuft noch
        windowEnd = utc("2026-11-23T00:00:00Z"),
        resolve = { ch -> EpgMatcher.match(app, ch).mapping },
    )

    @Test fun parsesChannelsAndProgrammes() {
        val r = parse(xml.toByteArray())
        assertEquals(3, r.xmltvChannels.size)
        assertEquals(listOf("Das Erste", "ARD"), r.xmltvChannels.first().names)
        // "Das Erste" (XMLTV) wird HD- und SD-Variante zugeordnet
        assertEquals(setOf("Das Erste HD", "Das Erste", "ZDF HD"), r.byKey.keys)
        val erste = r.byKey.getValue("Das Erste HD")
        assertEquals(listOf("Tagesschau", "Tatort"), erste.map { it.title })     // "Davor" liegt vor dem Fenster
        assertEquals(utc("2026-11-20T19:00:00Z"), erste[0].start)
        assertEquals("Nachrichten & Wetter", erste[0].desc)                       // Entity korrekt aufgeloest
        assertEquals("Folge 1", erste[1].desc)                                    // sub-title als Beschreibung, wenn keine desc
        assertEquals(erste, r.byKey.getValue("Das Erste"))
    }

    @Test fun dropsUnmappedAfterWindowAndEmptyTitles() {
        val r = parse(xml.toByteArray())
        assertEquals(listOf("heute-journal"), r.byKey.getValue("ZDF HD").map { it.title })
        assertTrue(r.programmesSeen >= 6)
        assertEquals(3, r.programmesKept)   // Tagesschau, Tatort, heute-journal
    }

    @Test fun readsGzipTransparently() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(xml.toByteArray()) }
        val r = parse(bos.toByteArray())
        assertEquals(setOf("Das Erste HD", "Das Erste", "ZDF HD"), r.byKey.keys)
    }

    @Test fun doesNotLoadExternalDtd() {
        // DOCTYPE verweist auf "xmltv.dtd" (existiert nicht) – darf nicht zu einem Fehler/Netzzugriff fuehren (siehe oben: Parser lief)
        val r = parse(xml.toByteArray())
        assertNotNull(r.byKey["ZDF HD"])
    }

    @Test fun malformedXmlThrows() {
        try { parse("<tv><channel id=\"a\"".toByteArray()); org.junit.Assert.fail("Exception erwartet") } catch (e: org.xml.sax.SAXException) { /* ok */ }
    }
}

class EpgMatcherTest {
    private fun ch(name: String, tvgId: String? = null, tvgName: String? = null) = Channel(name, "rtsp://x", tvgId, tvgName)

    @Test fun normalizeDropsQualityCaseAndUmlauts() {
        assertEquals("daserste", EpgMatcher.normalize("Das Erste HD"))
        assertEquals("daserste", EpgMatcher.normalize("DAS ERSTE"))
        assertEquals("wdrkoln", EpgMatcher.normalize("WDR HD Köln"))
        assertEquals("3sat", EpgMatcher.normalize("3sat HD"))
        assertEquals("strasse", EpgMatcher.normalize("Straße"))
    }

    @Test fun matchesByNameWithoutQualitySuffix() {
        val xml = listOf(XmltvChannel("DasErste.de", listOf("Das Erste")), XmltvChannel("zdf.de", listOf("ZDF")))
        val r = EpgMatcher.match(listOf(ch("Das Erste HD"), ch("ZDF HD"), ch("Gibt es nicht HD")), xml)
        assertEquals(listOf("Das Erste HD"), r.mapping["DasErste.de"])
        assertEquals(listOf("ZDF HD"), r.mapping["zdf.de"])
        assertEquals(listOf("Gibt es nicht HD"), r.unmatched)
    }

    @Test fun tvgIdHasPriority() {
        val xml = listOf(XmltvChannel("abc", listOf("Voellig anders")), XmltvChannel("x", listOf("Das Erste")))
        val r = EpgMatcher.match(listOf(ch("Das Erste HD", tvgId = "ABC")), xml)
        assertEquals(listOf("Das Erste HD"), r.mapping["abc"])
        assertNull(r.mapping["x"])
    }

    @Test fun tvgNameMatchesDisplayName() {
        val xml = listOf(XmltvChannel("id1", listOf("Mein Sender")))
        val r = EpgMatcher.match(listOf(ch("FS 1", tvgName = "Mein Sender")), xml)
        assertEquals(listOf("FS 1"), r.mapping["id1"])
    }

    @Test fun matchesByChannelIdWhenNamesDiffer() {
        val xml = listOf(XmltvChannel("ZDFneo.de", listOf("Fernsehen Neo")))
        val r = EpgMatcher.match(listOf(ch("ZDFneo HD")), xml)
        assertEquals(listOf("ZDFneo HD"), r.mapping["ZDFneo.de"])
    }

    @Test fun overridesWin() {
        val xml = listOf(XmltvChannel("a", listOf("Das Erste")), XmltvChannel("b", listOf("Anderer")))
        val r = EpgMatcher.match(listOf(ch("Das Erste HD")), xml, mapOf("Das Erste HD" to "b"))
        assertEquals(listOf("Das Erste HD"), r.mapping["b"])
        assertNull(r.mapping["a"])
    }

    @Test fun parseOverridesText() {
        val m = EpgMatcher.parseOverrides("Das Erste HD = ard.de\n\nzdf=zdf.de\nkaputt\n=x")
        assertEquals(mapOf("Das Erste HD" to "ard.de", "zdf" to "zdf.de"), m)
    }

    @Test fun doesNotGuessAmbiguousOrShortNames() {
        val xml = listOf(XmltvChannel("a", listOf("ZDFinfo")), XmltvChannel("b", listOf("ZDF Kultur")))
        val r = EpgMatcher.match(listOf(ch("ZDF HD")), xml)
        assertEquals(listOf("ZDF HD"), r.unmatched)          // "zdf" kommt als ganzes Wort nur in einem Namen vor, aber nicht eindeutig genug (<4 Zeichen)
    }

    @Test fun matchesSdAndHdToSameEpgChannel() {
        val xml = listOf(XmltvChannel("DasErste.de", listOf("Das Erste")))
        val r = EpgMatcher.match(listOf(ch("Das Erste HD"), ch("Das Erste")), xml)
        assertEquals(listOf("Das Erste HD", "Das Erste"), r.mapping["DasErste.de"])
        assertEquals(2, r.matched.size)
    }
}

class EpgDataTest {
    private fun p(startH: Int, endH: Int, title: String, desc: String? = null) =
        Programme(utc("2026-11-20T00:00:00Z") + startH * 3_600_000L, utc("2026-11-20T00:00:00Z") + endH * 3_600_000L, title, desc)

    private val data = EpgData(
        EpgMeta(1, "u", "etag", null, 3, 1, 2, 5),
        mapOf("A" to arrayOf(p(18, 19, "Eins"), p(19, 21, "Zwei", "lang"), p(21, 22, "Drei"))),
    )
    private fun t(h: Int, min: Int = 0) = utc("2026-11-20T00:00:00Z") + h * 3_600_000L + min * 60_000L

    @Test fun nowAndNext() {
        assertEquals("Zwei", data.now("A", t(19))?.title)             // Beginn zaehlt zur Sendung
        assertEquals("Zwei", data.now("A", t(20, 59))?.title)
        assertEquals("Drei", data.now("A", t(21))?.title)             // Ende ist exklusiv
        assertEquals("Drei", data.next("A", t(20))?.title)
        assertNull(data.next("A", t(21, 30)))                         // nach der letzten Sendung nichts mehr
    }

    @Test fun gapsAndUnknown() {
        assertNull(data.now("A", t(10)))                              // vor der ersten Sendung
        assertEquals("Eins", data.next("A", t(10))?.title)
        assertNull(data.now("A", t(23)))
        assertNull(data.now("unbekannt", t(19)))
        assertNull(data.next("unbekannt", t(19)))
    }

    @Test fun cacheRoundTrip() {
        val f = File.createTempFile("epg", ".bin")
        try {
            EpgCache.write(f, data)
            val back = EpgCache.read(f)!!
            assertEquals(data.meta, back.meta)
            assertEquals(data.programmes("A").toList(), back.programmes("A").toList())
            assertNull(back.programmes("A")[0].desc)
            assertEquals("lang", back.programmes("A")[1].desc)
        } finally { f.delete() }
    }

    @Test fun corruptOrMissingCacheYieldsNull() {
        val f = File.createTempFile("epg", ".bin")
        try {
            assertNull(EpgCache.read(File(f.parentFile, "gibt-es-nicht.bin")))
            f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            assertNull(EpgCache.read(f))
            EpgCache.write(f, data)
            f.writeBytes(f.readBytes().copyOf(30))                      // abgeschnitten
            assertNull(EpgCache.read(f))
        } finally { f.delete() }
    }

    @Test fun writeIsAtomicAndReplacesOldFile() {
        val f = File.createTempFile("epg", ".bin")
        try {
            EpgCache.write(f, data)
            val data2 = EpgData(data.meta.copy(programmeCount = 1), mapOf("B" to arrayOf(p(1, 2, "X"))))
            EpgCache.write(f, data2)
            assertEquals(setOf("B"), EpgCache.read(f)!!.channelKeys)
            assertTrue(!File(f.parentFile, f.name + ".tmp").exists())
        } finally { f.delete() }
    }
}

class PlaylistAttributeTest {
    @Test fun parsesOptionalM3uAttributes() {
        val m3u = "#EXTM3U\n#EXTINF:0 tvg-id=\"DasErste.de\" tvg-name=\"Das Erste\" tvg-logo=\"http://l/1.png\" group-title=\"Vollprogramm\",Das Erste HD\nrtsp://x/1\n#EXTINF:0,ZDF\nrtsp://x/2\n"
        val ch = Playlist.parse(m3u)
        assertEquals(2, ch.size)
        assertEquals("Das Erste HD", ch[0].name)
        assertEquals("DasErste.de", ch[0].tvgId)
        assertEquals("Das Erste", ch[0].tvgName)
        assertEquals("http://l/1.png", ch[0].logo)
        assertEquals("Vollprogramm", ch[0].group)
        assertNull(ch[1].tvgId)
        assertEquals(ch, Playlist.parse(Playlist.toM3u(ch)))
    }
}
