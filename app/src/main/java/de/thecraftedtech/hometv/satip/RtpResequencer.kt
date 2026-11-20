package de.thecraftedtech.hometv.satip

/**
 * Bringt RTP-Pakete in Sequenzreihenfolge, bevor ihr MPEG-TS-Inhalt an den Player geht.
 *
 * - Pakete in Reihenfolge werden sofort ausgeliefert (kein zusaetzlicher Puffer im Normalfall).
 * - Kommt ein Paket zu frueh (Luecke davor), wird es hoechstens [maxWaitMs] ms bzw. [maxHold] Pakete
 *   zurueckgehalten, damit das fehlende Paket noch nachkommen kann (Reordering).
 * - Kommt es nicht, gilt die Luecke als Verlust und es geht weiter; spaeter eintreffende Pakete
 *   (zu spaet oder doppelt) werden verworfen, nie ausser der Reihe ausgeliefert.
 *
 * Alle Zaehler sind echte Messwerte. Nicht thread-sicher fuer push(); Zaehler sind @Volatile lesbar.
 */
class RtpResequencer(
    private val maxHold: Int = 48,
    private val maxWaitMs: Long = 60,
    private val sink: (buf: ByteArray, off: Int, len: Int) -> Unit,
) {
    private val slots = arrayOfNulls<ByteArray>(SLOTS)
    private var held = 0
    private var oldestHeldAtMs = 0L
    private var expected = -1

    @Volatile var delivered = 0L; private set
    /** Pakete, die endgueltig fehlen (Luecke wurde uebersprungen). */
    @Volatile var lost = 0L; private set
    /** Pakete, die nach dem Ueberspringen oder doppelt eintrafen und verworfen wurden. */
    @Volatile var lateOrDuplicate = 0L; private set
    /** Pakete, die ausser der Reihe ankamen, aber noch rechtzeitig einsortiert werden konnten. */
    @Volatile var reordered = 0L; private set

    fun push(seq: Int, buf: ByteArray, off: Int, len: Int, nowMs: Long) {
        if (expected < 0) expected = seq
        val diff = (seq - expected) and 0xFFFF
        when {
            diff == 0 -> {
                if (held > 0) reordered++ // das fehlende Paket kam noch rechtzeitig
                deliver(buf, off, len)
                expected = (expected + 1) and 0xFFFF
                drain()
            }
            diff < 0x8000 -> {
                if (diff >= SLOTS - 1) {
                    // zu weit voraus: alles Wartende ist verloren, neu aufsetzen
                    giveUpAll()
                    lost += diff.toLong()
                    expected = seq
                    deliver(buf, off, len)
                    expected = (expected + 1) and 0xFFFF
                    return
                }
                val idx = seq and (SLOTS - 1)
                if (slots[idx] != null) { lateOrDuplicate++; return }
                slots[idx] = buf.copyOfRange(off, off + len)
                if (held == 0) oldestHeldAtMs = nowMs
                held++
                if (held > maxHold || nowMs - oldestHeldAtMs > maxWaitMs) skipGap()
            }
            else -> lateOrDuplicate++ // zu spaet oder Duplikat
        }
    }

    /** Regelmaessig aufrufen (auch ohne neue Pakete), damit wartende Pakete nach [maxWaitMs] freigegeben werden. */
    fun tick(nowMs: Long) {
        if (held > 0 && nowMs - oldestHeldAtMs > maxWaitMs) skipGap()
    }

    private fun skipGap() {
        // naechstes vorhandenes Paket suchen, Luecke davor als Verlust verbuchen
        var step = 1
        while (step < SLOTS) {
            if (slots[(expected + step) and (SLOTS - 1)] != null) break
            step++
        }
        if (step >= SLOTS) { held = 0; return }
        lost += (step).toLong()
        expected = (expected + step) and 0xFFFF
        drain()
    }

    private fun drain() {
        while (true) {
            val idx = expected and (SLOTS - 1)
            val p = slots[idx] ?: break
            slots[idx] = null
            held--
            deliver(p, 0, p.size)
            expected = (expected + 1) and 0xFFFF
        }
        if (held == 0) oldestHeldAtMs = 0L
    }

    private fun giveUpAll() {
        for (i in slots.indices) slots[i] = null
        held = 0
        oldestHeldAtMs = 0L
    }

    private fun deliver(buf: ByteArray, off: Int, len: Int) {
        delivered++
        sink(buf, off, len)
    }

    companion object {
        private const val SLOTS = 256
    }
}

