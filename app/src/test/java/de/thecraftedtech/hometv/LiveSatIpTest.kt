package de.thecraftedtech.hometv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicReference

/**
 * Integrationstest gegen die ECHTE FRITZ!Box (laeuft nur mit HOMETV_LIVE_TEST=1).
 * Prueft denselben Code wie die App: Senderliste laden, SAT>IP-Handshake, NAT-Punch,
 * Empfang gueltiger MPEG-TS-Daten, sauberes TEARDOWN.
 */
class LiveSatIpTest {

    @Test
    fun playlistContainsDasErsteHd() {
        assumeTrue(System.getenv("HOMETV_LIVE_TEST") == "1")
        val ch = Playlist.findDasErste(host())
        println("Sender: ${ch.name}")
        println("URL   : ${ch.url}")
        assertEquals("Das Erste HD", ch.name)
        assertTrue(ch.url.startsWith("rtsp://"))
    }

    @Test
    fun receivesValidTransportStreamAndTearsDown() {
        assumeTrue(System.getenv("HOMETV_LIVE_TEST") == "1")
        val ch = Playlist.findDasErste(host())

        val rx = DatagramSocket(0).apply { soTimeout = 1000; receiveBufferSize = 4 * 1024 * 1024 }
        val error = AtomicReference<String?>(null)
        val session = SatIpSession(ch.url, rx.localPort, object : SatIpSession.Listener {
            override fun onState(text: String) = println("[state] $text")
            override fun onStreaming() = println("[streaming] erste RTP-Daten")
            override fun onError(text: String) { error.set(text); println("[error] $text") }
        })

        val t0 = System.currentTimeMillis()
        session.start()
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        var datagrams = 0
        var bytes = 0L
        var badSync = 0
        var firstAfterMs = -1L
        val seconds = (System.getenv("HOMETV_LIVE_SECONDS") ?: "20").toLong()
        val end = t0 + seconds * 1000
        while (System.currentTimeMillis() < end && error.get() == null) {
            try {
                pkt.length = buf.size
                rx.receive(pkt)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            }
            if (firstAfterMs < 0) firstAfterMs = System.currentTimeMillis() - t0
            datagrams++
            bytes += pkt.length
            if (pkt.length % 188 != 0) badSync++
            else for (i in 0 until pkt.length step 188) if (buf[i] != 0x47.toByte()) { badSync++; break }
        }
        val tStop = System.currentTimeMillis()
        session.stop()
        val stopMs = System.currentTimeMillis() - tStop
        rx.close()

        val mbit = bytes * 8.0 / (seconds * 1_000_000.0)
        println("Ergebnis: $datagrams Datagramme, ${bytes / 1024} KiB in ${seconds}s = ${"%.2f".format(mbit)} Mbit/s")
        println("erstes Paket nach ${firstAfterMs} ms, verlorene RTP-Pakete: ${session.packetsLost.get()}, ungueltige TS-Datagramme: $badSync")
        println("session.stop() (inkl. TEARDOWN) dauerte $stopMs ms")

        assertTrue("Fehler: ${error.get()}", error.get() == null)
        assertTrue("keine Daten empfangen", datagrams > 0)
        assertEquals("TS-Synchronbyte 0x47 fehlt", 0, badSync)
        assertTrue("zu wenig Daten (${bytes} Byte)", mbit > 2.0)
    }

    private fun host() = System.getenv("HOMETV_HOST") ?: BuildConfig.FRITZBOX_HOST
}
