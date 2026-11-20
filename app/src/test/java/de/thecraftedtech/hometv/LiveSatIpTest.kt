package de.thecraftedtech.hometv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Integrationstest gegen die ECHTE FRITZ!Box (laeuft nur mit HOMETV_LIVE_TEST=1).
 * Prueft denselben Code wie die App: Senderliste laden, SAT>IP-Handshake, NAT-Punch, Resequenzierung,
 * Empfang gueltiger MPEG-TS-Daten, sauberes TEARDOWN. Optional: HOMETV_LIVE_SECONDS, HOMETV_HOST, HOMETV_CHANNEL.
 */
class LiveSatIpTest {

    /** Sammelt die an LibVLC gelieferten TS-Bytes (ersetzt Loopback-Socket) und prueft die Synchronbytes. */
    private class CollectingFeed : LocalFeed {
        val bytes = AtomicLong()
        val badSync = AtomicLong()
        override val url = "collect://"
        override val name = "Test"
        override val queuedBytes = 0L
        override val overflowBytes = 0L
        override val consumerConnected = true
        override fun start() {}
        override fun close() {}
        override fun write(buf: ByteArray, off: Int, len: Int) {
            bytes.addAndGet(len.toLong())
            if (len % 188 != 0) badSync.incrementAndGet()
            else for (i in off until off + len step 188) if (buf[i] != 0x47.toByte()) { badSync.incrementAndGet(); break }
        }
    }

    @Test
    fun playlistContainsAllChannelsWithDasErsteFirst() {
        assumeTrue(System.getenv("HOMETV_LIVE_TEST") == "1")
        val hdList = Playlist.fetch(host(), "tvhd")
        val sdList = Playlist.fetch(host(), "tvsd")
        println("Senderliste live: HD=${hdList.size} SD=${sdList.size} gesamt=${hdList.size + sdList.size}")
        println("Sender: ${hdList.first().name}  URL: ${hdList.first().url}")
        assertEquals("Das Erste HD", hdList[Playlist.defaultIndex(hdList)].name)
        assertTrue(hdList.size + sdList.size > 50)
    }

    @Test
    fun receivesValidTransportStreamAndTearsDown() {
        assumeTrue(System.getenv("HOMETV_LIVE_TEST") == "1")
        val channels = Playlist.fetch(host(), "tvhd")
        val wanted = System.getenv("HOMETV_CHANNEL") ?: "Das Erste HD"
        val ch = channels.first { it.name.equals(wanted, ignoreCase = true) }

        val feed = CollectingFeed()
        val error = AtomicReference<String?>(null)
        val session = SatIpSession(ch.url, feed, object : SatIpSession.Listener {
            override fun onState(text: String) = println("[state] $text")
            override fun onStreaming() = println("[streaming] erste RTP-Daten")
            override fun onError(text: String) { error.set(text); println("[error] $text") }
        })

        val seconds = (System.getenv("HOMETV_LIVE_SECONDS") ?: "20").toLong()
        val t0 = System.currentTimeMillis()
        session.start()
        while (System.currentTimeMillis() - t0 < seconds * 1000 && error.get() == null) Thread.sleep(200)
        val s = session.stats()
        val gap = session.takeMaxGapMs()
        val tStop = System.currentTimeMillis()
        session.stop()
        val stopMs = System.currentTimeMillis() - tStop

        val mbit = feed.bytes.get() * 8.0 / (seconds * 1_000_000.0)
        println("Sender: ${ch.name}")
        println("Ergebnis: ${s.packets} RTP-Pakete, ${feed.bytes.get() / 1024} KiB geliefert in ${seconds}s = ${"%.2f".format(mbit)} Mbit/s")
        println("RTP: verloren=${s.lost} umsortiert=${s.reordered} spaet/doppelt=${s.lateOrDuplicate}  jitter=${"%.2f".format(s.jitterMs)} ms  groesste Luecke=${gap} ms  UDP-Puffer=${s.rcvBufBytes} B")
        println("TS: Pakete=${s.tsPackets} CC-Fehler=${s.tsCcErrors} Sync-Fehler=${s.tsBadSync} Datenzeit=${s.dataTimeMs} ms")
        println("Feed: ungueltige Datagramme=${feed.badSync.get()}")
        println("session.stop() (inkl. TEARDOWN) dauerte $stopMs ms")

        assertTrue("Fehler: ${error.get()}", error.get() == null)
        assertTrue("keine Daten", s.packets > 0)
        assertEquals("TS-Synchronbyte 0x47 fehlt", 0, feed.badSync.get())
        assertEquals(0, s.tsBadSync)
        assertTrue("zu wenig Daten", mbit > 2.0)
    }

    private fun host() = System.getenv("HOMETV_HOST") ?: BuildConfig.FRITZBOX_HOST
}
