package de.thecraftedtech.hometv

import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.epg.EpgRepository
import de.thecraftedtech.hometv.epg.XmltvParser
import de.thecraftedtech.hometv.settings.AppSettings
import de.thecraftedtech.hometv.settings.MemoryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * Ende-zu-Ende-Test des Programmfuehrers gegen einen lokalen HTTP-Server (JDK-HttpServer):
 * Download, gzip, ETag/304, Fehlerfaelle, Cache-Weiterverwendung, Aktivierung/Deaktivierung.
 */
class EpgRepositoryTest {
    private lateinit var server: ServerSocket
    @Volatile private var serving = true
    private lateinit var dir: File
    private val hits = AtomicInteger()
    private val conditionalHits = AtomicInteger()
    @Volatile private var body: ByteArray = ByteArray(0)
    @Volatile private var status = 200
    @Volatile private var etag = "\"v1\""
    private val direct = Executor { it.run() }

    private val channels = listOf(Channel("Das Erste HD", "rtsp://x/1"), Channel("ZDF HD", "rtsp://x/2"))

    private fun xml(now: Long, extra: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
<tv><channel id="DasErste.de"><display-name>Das Erste</display-name></channel><channel id="zdf.de"><display-name>ZDF</display-name></channel>
<programme start="${stamp(now - 3_600_000)} +0000" stop="${stamp(now + 3_600_000)} +0000" channel="DasErste.de"><title>Laeuft gerade</title><desc>Beschreibung</desc></programme>
<programme start="${stamp(now + 3_600_000)} +0000" stop="${stamp(now + 7_200_000)} +0000" channel="DasErste.de"><title>Danach</title></programme>
<programme start="${stamp(now - 1_800_000)} +0000" stop="${stamp(now + 1_800_000)} +0000" channel="zdf.de"><title>ZDF jetzt</title></programme>$extra</tv>"""

    private fun stamp(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))

    private fun gz(b: ByteArray): ByteArray { val o = ByteArrayOutputStream(); GZIPOutputStream(o).use { it.write(b) }; return o.toByteArray() }

    @Before fun setUp() {
        dir = File.createTempFile("epgtest", "").also { it.delete(); it.mkdirs() }
        server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        serving = true
        Thread {
            while (serving) {
                val s = try { server.accept() } catch (_: Exception) { break }
                try { handle(s) } catch (_: Exception) { } finally { runCatching { s.close() } }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Minimaler HTTP/1.1-Server: liest Anfrage + Header, beantwortet 304 (ETag), Fehlerstatus oder 200 mit Body. */
    private fun handle(s: Socket) {
        val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        inp.readLine() ?: return
        var inm: String? = null
        while (true) {
            val line = inp.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("If-None-Match:", ignoreCase = true)) inm = line.substringAfter(':').trim()
        }
        hits.incrementAndGet()
        val out = s.getOutputStream()
        if (inm != null && inm == etag) {
            conditionalHits.incrementAndGet()
            out.write("HTTP/1.1 304 Not Modified\r\nETag: $etag\r\nConnection: close\r\n\r\n".toByteArray())
        } else if (status != 200) {
            out.write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val b = body
            out.write("HTTP/1.1 200 OK\r\nETag: $etag\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b)
        }
        out.flush()
    }
    @After fun tearDown() { serving = false; runCatching { server.close() }; dir.deleteRecursively() }

    private val url get() = "http://127.0.0.1:${server.localPort}/epg.xml"

    private fun repo(settings: AppSettings) = EpgRepository(File(dir, "epg.bin"), settings, direct, { it.run() }).also { it.channels = channels }

    private fun settings(u: String = url) = AppSettings(MemoryStore()).also { it.epgUrl = u }

    @Test fun downloadsParsesAndAnswersNowNext() {
        val now = System.currentTimeMillis()
        body = xml(now).toByteArray()
        val s = settings(); val r = repo(s)
        r.refresh(force = false)
        assertEquals(EpgRepository.Phase.DONE_OK, r.phase)
        assertEquals("Laeuft gerade", r.now(channels[0], now)?.title)
        assertEquals("Beschreibung", r.now(channels[0], now)?.desc)
        assertEquals("Danach", r.next(channels[0], now)?.title)
        assertEquals("ZDF jetzt", r.now(channels[1], now)?.title)
        assertTrue(s.epgLastSuccessMs > 0)
        assertEquals("OK", s.epgLastStatus)
        assertEquals(2, r.data!!.meta.matchedChannels)
        assertTrue(File(dir, "epg.bin").exists())
    }

    @Test fun gzipSourceWorks() {
        val now = System.currentTimeMillis()
        body = gz(xml(now).toByteArray())
        val r = repo(settings()); r.refresh(force = false)
        assertEquals(EpgRepository.Phase.DONE_OK, r.phase)
        assertNotNull(r.now(channels[0], now))
    }

    @Test fun unchangedSourceUsesConditionalRequest() {
        val now = System.currentTimeMillis()
        body = xml(now).toByteArray()
        val s = settings(); val r = repo(s)
        r.refresh(force = false)
        r.refresh(force = false)                           // zweiter Lauf: If-None-Match -> 304
        assertEquals(EpgRepository.Phase.DONE_UNCHANGED, r.phase)
        assertEquals(1, conditionalHits.get())
        assertNotNull("Daten bleiben erhalten", r.now(channels[0], now))
        r.refresh(force = true)                            // erzwungen: voller Download
        assertEquals(EpgRepository.Phase.DONE_OK, r.phase)
        assertEquals(1, conditionalHits.get())
    }

    @Test fun cacheSurvivesRestartAndNetworkErrors() {
        val now = System.currentTimeMillis()
        body = xml(now).toByteArray()
        val s = settings(); repo(s).refresh(force = false)
        val r2 = repo(s)                                   // "App-Neustart": neues Repository, Daten nur aus dem Cache
        assertNull(r2.now(channels[0], now))
        r2.loadCache()
        assertEquals("Laeuft gerade", r2.now(channels[0], now)?.title)
        status = 500; etag = "\"v2\""                      // Quelle faellt aus
        r2.refresh(force = false)
        assertEquals(EpgRepository.Phase.ERROR, r2.phase)
        assertTrue(r2.statusText().startsWith("Fehler"))
        assertTrue("alte Daten werden weiter angezeigt", r2.statusText().contains("gespeicherte Daten"))
        assertEquals("Laeuft gerade", r2.now(channels[0], now)?.title)
    }

    @Test fun invalidXmlKeepsOldData() {
        val now = System.currentTimeMillis()
        body = xml(now).toByteArray()
        val r = repo(settings()); r.refresh(force = false)
        body = "<html>kein xmltv</html".toByteArray(); etag = "\"v3\""
        r.refresh(force = true)
        assertEquals(EpgRepository.Phase.ERROR, r.phase)
        assertEquals("Laeuft gerade", r.now(channels[0], now)?.title)
    }

    @Test fun sourceWithoutMatchingChannelsIsAnError() {
        body = """<tv><channel id="x"><display-name>Voellig anderer Sender</display-name></channel>
            <programme start="20300101000000 +0000" stop="20300101010000 +0000" channel="x"><title>t</title></programme></tv>""".toByteArray()
        val r = repo(settings()); r.refresh(force = true)
        assertEquals(EpgRepository.Phase.ERROR, r.phase)
        assertTrue(r.statusText().contains("Keine Sendungen zugeordneter Sender"))
        assertNull(r.data)
    }

    @Test fun inactiveWhenNoUrlOrDisabled() {
        val s = AppSettings(MemoryStore())
        val r = repo(s)
        r.refresh(force = true)
        assertEquals(0, hits.get())                         // ohne URL kein Netzwerkzugriff
        assertEquals("Keine URL eingetragen", r.statusText())
        s.epgUrl = url; s.epgEnabled = false
        r.refresh(force = true)
        assertEquals(0, hits.get())
        assertEquals("Deaktiviert", r.statusText())
    }

    @Test fun refreshIfStaleHonoursIntervalAndAutoSwitch() {
        val now = System.currentTimeMillis()
        body = xml(now).toByteArray()
        val s = settings(); val r = repo(s)
        s.epgAutoUpdate = false
        r.refreshIfStale(); assertEquals(0, hits.get())
        s.epgAutoUpdate = true
        r.refreshIfStale(); assertEquals(1, hits.get())    // noch nie aktualisiert -> laden
        r.refreshIfStale(); assertEquals(1, hits.get())    // frisch -> nicht erneut
        s.epgLastSuccessMs = System.currentTimeMillis() - 13 * 3_600_000L
        r.refreshIfStale(); assertEquals(2, hits.get())    // aelter als 12 h -> erneut (per 304 geprueft)
    }

    @Test fun clearCacheRemovesEverything() {
        val now = System.currentTimeMillis()
        body = xml(now).toByteArray()
        val s = settings(); val r = repo(s); r.refresh(force = false)
        r.clearCache()
        assertNull(r.data)
        assertTrue(!File(dir, "epg.bin").exists())
        assertEquals(0L, s.epgLastSuccessMs)
    }

    @Test fun largeFileIsStreamedNotLoaded() {
        // ~20 MB XMLTV, 120 Kanaele x 4 Tage: es duerfen nur die zugeordneten Sender im Zeitfenster uebrig bleiben
        val now = System.currentTimeMillis()
        val f = File(dir, "big.xml")
        f.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\"?><tv>")
            for (c in 0 until 120) w.write("<channel id=\"ch$c.de\"><display-name>Kanal $c</display-name></channel>")
            w.write("<channel id=\"DasErste.de\"><display-name>Das Erste</display-name></channel>")
            val day = 86_400_000L
            for (c in 0 until 121) {
                val id = if (c == 120) "DasErste.de" else "ch$c.de"
                for (i in 0 until 4 * 48) {
                    val st = now - 2 * day + i * 1_800_000L
                    w.write("<programme start=\"${stamp(st)} +0000\" stop=\"${stamp(st + 1_800_000)} +0000\" channel=\"$id\"><title>Sendung $i</title><desc>${"Beschreibung ".repeat(20)}</desc></programme>")
                }
            }
            w.write("</tv>")
        }
        val sizeMb = f.length() / 1_048_576.0
        val rt = Runtime.getRuntime(); System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val res = f.inputStream().use { XmltvParser.parse(it, now - 6 * 3_600_000L, now + 3 * 86_400_000L, { xml -> mapOf("DasErste.de" to listOf("Das Erste HD")) }) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val used = (rt.totalMemory() - rt.freeMemory() - before) / 1_048_576
        println("Grosse Datei: %.1f MB, %d Sendungen gelesen, %d behalten, %d ms, ca. %d MB Heap-Zuwachs".format(sizeMb, res.programmesSeen, res.programmesKept, ms, used))
        assertTrue(res.programmesSeen > 20_000)
        assertEquals(setOf("Das Erste HD"), res.byKey.keys)
        assertTrue("nur das Zeitfenster bleibt uebrig", res.programmesKept < 4 * 48)
    }
}
