package de.thecraftedtech.hometv

import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.channels.Playlist
import de.thecraftedtech.hometv.satip.RtpResequencer
import de.thecraftedtech.hometv.satip.TsInspector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResequencerTest {

    private fun seqPayload(seq: Int) = byteArrayOf((seq shr 8).toByte(), seq.toByte())
    private fun decode(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun run(order: List<Int>, start: Int = 0, maxHold: Int = 48, waitMs: Long = 60, stepMs: Long = 1): Pair<List<Int>, RtpResequencer> {
        val out = ArrayList<Int>()
        val r = RtpResequencer(maxHold, waitMs) { b, o, _ -> out += decode(b, o) }
        var now = 0L
        for (s in order) { val p = seqPayload(s and 0xFFFF); r.push(s and 0xFFFF, p, 0, p.size, now); now += stepMs }
        r.tick(now + 1000) // Wartezeit abgelaufen
        return out to r
    }

    @Test fun inOrderIsDeliveredUnchanged() {
        val (out, r) = run((0 until 100).toList())
        assertEquals((0 until 100).toList(), out)
        assertEquals(0, r.lost); assertEquals(0, r.reordered); assertEquals(0, r.lateOrDuplicate)
    }

    @Test fun smallReorderingIsRepaired() {
        val (out, r) = run(listOf(0, 1, 3, 2, 4, 5))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), out)
        assertEquals(0, r.lost); assertEquals(1, r.reordered)
    }

    @Test fun lossIsCountedAndStreamContinues() {
        val (out, r) = run(listOf(0, 1, 2, 4, 5, 6))      // 3 fehlt
        assertEquals(listOf(0, 1, 2, 4, 5, 6), out)
        assertEquals(1, r.lost)
    }

    @Test fun burstLossIsCounted() {
        val (out, r) = run(listOf(0, 1, 5, 6))             // 2,3,4 fehlen
        assertEquals(listOf(0, 1, 5, 6), out)
        assertEquals(3, r.lost)
    }

    @Test fun lateAndDuplicatePacketsAreDroppedNotReplayed() {
        val (out, r) = run(listOf(0, 1, 3, 4, 5, 2, 5))   // 2 kommt nach dem Ueberspringen (tick) bzw. 5 doppelt
        // 2 wird erst nach Ablauf des Wartefensters verworfen oder noch rechtzeitig eingefuegt - nie doppelt ausgeliefert
        assertEquals(out.distinct(), out)
        assertEquals(out.sorted(), out)
        assertTrue(r.lateOrDuplicate >= 1)
    }

    @Test fun sequenceNumberWrapAround() {
        val seqs = (65530..65545).toList()                  // laeuft ueber 65535 -> 0
        val (out, r) = run(seqs)
        assertEquals(seqs.map { it and 0xFFFF }, out)
        assertEquals(0, r.lost)
    }

    @Test fun holdingIsBoundedByWindow() {
        // Paket 1 fehlt, danach kommen viele Pakete: nach maxHold (nicht erst nach der Wartezeit) wird die Luecke uebersprungen
        val order = listOf(0) + (2..101)
        val (out, r) = run(order, maxHold = 10, waitMs = 10_000)
        assertEquals(order, out)
        assertEquals(1, r.lost)
    }

    @Test fun timeoutReleasesHeldPacketsWithoutNewTraffic() {
        val out = ArrayList<Int>()
        val r = RtpResequencer(48, 60) { b, o, _ -> out += decode(b, o) }
        for ((i, s) in listOf(0, 2, 3).withIndex()) { val p = seqPayload(s); r.push(s, p, 0, 2, i.toLong()) }
        assertEquals(listOf(0), out)                        // 2,3 warten auf 1
        r.tick(100)                                         // > 60 ms ohne neues Paket
        assertEquals(listOf(0, 2, 3), out)
        assertEquals(1, r.lost)
    }
}

class TsInspectorTest {

    private fun tsPacket(pid: Int, cc: Int, pcr27: Long? = null): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = pid.toByte()
        if (pcr27 == null) {
            p[3] = (0x10 or (cc and 0x0F)).toByte()          // nur Payload
        } else {
            p[3] = (0x30 or (cc and 0x0F)).toByte()          // Adaptation + Payload
            p[4] = 7; p[5] = 0x10
            val base = pcr27 / 300; val ext = (pcr27 % 300).toInt()
            p[6] = (base shr 25).toByte(); p[7] = (base shr 17).toByte(); p[8] = (base shr 9).toByte(); p[9] = (base shr 1).toByte()
            p[10] = (((base and 1L) shl 7).toInt() or 0x7E or (ext shr 8)).toByte(); p[11] = ext.toByte()
        }
        return p
    }

    private fun feed(i: TsInspector, vararg pk: ByteArray) {
        val all = ByteArray(pk.size * 188); pk.forEachIndexed { n, p -> System.arraycopy(p, 0, all, n * 188, 188) }
        i.inspect(all, 0, all.size)
    }

    @Test fun contiguousCountersAreClean() {
        val i = TsInspector()
        feed(i, *Array(40) { tsPacket(0x100, it % 16) })
        assertEquals(40, i.tsPackets); assertEquals(0, i.ccErrors)
    }

    @Test fun missingPacketsAreDetectedPerPid() {
        val i = TsInspector()
        feed(i, tsPacket(0x100, 0), tsPacket(0x100, 1), tsPacket(0x100, 3), tsPacket(0x200, 5), tsPacket(0x200, 6))
        assertEquals(1, i.ccErrors)                          // nur PID 0x100 (1 -> 3)
    }

    @Test fun duplicateCounterIsAllowed() {
        val i = TsInspector()
        feed(i, tsPacket(0x100, 4), tsPacket(0x100, 4), tsPacket(0x100, 5))
        assertEquals(0, i.ccErrors)
    }

    @Test fun badSyncIsCounted() {
        val i = TsInspector()
        val bad = tsPacket(0x100, 0); bad[0] = 0x00
        i.inspect(bad, 0, 188)
        assertTrue(i.badSync > 0)
    }

    @Test fun pcrDataTime() {
        val i = TsInspector()
        feed(i, tsPacket(0x1ED, 0, pcr27 = 27_000_000L * 10), tsPacket(0x1ED, 1, pcr27 = 27_000_000L * 12 + 27_000L * 500))
        assertEquals(0x1ED, i.pcrPid)
        assertEquals(2_500, i.dataTimeMs)                    // 2,5 s zwischen den PCR
    }
}

class PlaylistTest {
    @Test fun parsesNamesAndRtspUrls() {
        val m3u = "#EXTM3U\n#EXTINF:0,Das Erste HD\n#EXTVLCOPT:network-caching=1000\nrtsp://192.168.200.1:554/?avm=1&freq=386\n" +
            "#EXTINF:0,ZDF HD\n#EXTVLCOPT:network-caching=1000\nrtsp://192.168.200.1:554/?avm=1&freq=450\n"
        val ch = Playlist.parse(m3u)
        assertEquals(listOf("Das Erste HD", "ZDF HD"), ch.map { it.name })
        assertTrue(ch.all { it.url.startsWith("rtsp://") })
        assertEquals(0, Playlist.defaultIndex(ch))
    }

    @Test fun roundTrip() {
        val ch = listOf(Channel("A", "rtsp://x/1"), Channel("B", "rtsp://x/2"))
        assertEquals(ch, Playlist.parse(Playlist.toM3u(ch)))
    }

    /** Prueft die echte, von der FRITZ!Box geladene Liste, falls vorhanden (tests/data/tv.m3u). */
    @Test fun realChannelListIfPresent() {
        val f = File("../tests/data/tv.m3u").takeIf { it.exists() } ?: File("tests/data/tv.m3u").takeIf { it.exists() } ?: return
        val ch = Playlist.parse(f.readText())
        println("tv.m3u: ${ch.size} Sender, erster: ${ch.first().name}, letzter: ${ch.last().name}")
        assertTrue(ch.size > 50)
        assertEquals("Das Erste HD", ch[Playlist.defaultIndex(ch)].name)
        assertTrue(ch.all { it.url.startsWith("rtsp://") })
    }
}
