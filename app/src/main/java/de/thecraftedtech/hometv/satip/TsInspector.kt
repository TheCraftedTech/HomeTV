package de.thecraftedtech.hometv.satip

/**
 * Prueft den an den Player gelieferten MPEG-TS-Strom (nach der Resequenzierung):
 *  - Sync-Byte 0x47
 *  - Continuity-Counter je PID  -> fehlende TS-Pakete (= sichtbare Bildfehler), unabhaengig vom Transport
 *  - PCR der ersten PCR-PID      -> Datenzeit (Vorsprung der Daten vor der Wiedergabe)
 * Nicht thread-sicher; Zaehler sind @Volatile lesbar.
 */
class TsInspector {
    private val lastCc = IntArray(8192) { -1 }

    @Volatile var tsPackets = 0L; private set
    @Volatile var badSync = 0L; private set
    @Volatile var ccErrors = 0L; private set
    @Volatile var pcrPid = -1; private set

    private var pcrFirst27 = -1L
    @Volatile private var pcrLast27 = -1L

    /** Datenzeit in ms seit dem ersten PCR (0, solange noch kein PCR gesehen wurde). */
    val dataTimeMs: Long
        get() {
            val first = pcrFirst27
            val last = pcrLast27
            if (first < 0 || last < 0) return 0
            return Math.floorMod(last - first, PCR_MOD) / 27_000L
        }

    fun inspect(buf: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len - 187
        while (i < end) {
            if (buf[i] != 0x47.toByte()) { badSync++; i++; continue }
            tsPackets++
            val pid = ((buf[i + 1].toInt() and 0x1F) shl 8) or (buf[i + 2].toInt() and 0xFF)
            val afc = (buf[i + 3].toInt() shr 4) and 3
            val cc = buf[i + 3].toInt() and 0x0F
            if (pid != 0x1FFF) {
                if (afc and 1 != 0) {
                    val prev = lastCc[pid]
                    if (prev >= 0 && cc != ((prev + 1) and 0x0F) && cc != prev) ccErrors++ // gleicher CC = erlaubtes Duplikat
                    lastCc[pid] = cc
                }
                if (afc and 2 != 0 && (buf[i + 4].toInt() and 0xFF) >= 7 && (buf[i + 5].toInt() and 0x10) != 0 &&
                    (pcrPid < 0 || pcrPid == pid)
                ) {
                    pcrPid = pid
                    val b = { k: Int -> buf[i + k].toLong() and 0xFF }
                    val base = (b(6) shl 25) or (b(7) shl 17) or (b(8) shl 9) or (b(9) shl 1) or (b(10) shr 7)
                    val ext = ((b(10) and 1L) shl 8) or b(11)
                    val pcr = base * 300 + ext
                    if (pcrFirst27 < 0) pcrFirst27 = pcr
                    pcrLast27 = pcr
                }
            }
            i += 188
        }
    }

    private companion object {
        const val PCR_MOD = (1L shl 33) * 300
    }
}
