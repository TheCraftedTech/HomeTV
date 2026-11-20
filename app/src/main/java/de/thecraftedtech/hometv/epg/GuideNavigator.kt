package de.thecraftedtech.hometv.epg

import de.thecraftedtech.hometv.core.ListNav

/**
 * Bedienlogik der Programmuebersicht (Raster: Zeilen = Sender, waagerecht = Zeit) – ohne Android, damit testbar.
 *
 * Die Auswahl ist ein Paar aus [row] (Senderzeile) und [anchor] (Zeitpunkt). Die ausgewaehlte Sendung ist die, die in der
 * Zeile zum Zeitpunkt [anchor] laeuft. ▲▼ wechselt die Zeile und behaelt den Zeitpunkt, ◄► springt zur vorigen/naechsten Sendung
 * der Zeile. [windowStart] ist der linke Rand des sichtbaren Zeitfensters ([spanMs] breit); es scrollt seitenweise mit.
 *
 * @param rowCount    Anzahl der Zeilen (angezeigte Sender)
 * @param programmes  Sendungen einer Zeile, nach Beginn sortiert (leer = keine Programmdaten)
 */
class GuideNavigator(
    private val rowCount: () -> Int,
    private val programmes: (row: Int) -> Array<Programme>,
    val spanMs: Long = DEFAULT_SPAN_MS,
) {
    var row = 0
        private set
    /** Zeitpunkt der Auswahl (epoch ms). */
    var anchor = 0L
        private set
    /** Linker Rand des sichtbaren Zeitfensters (epoch ms). */
    var windowStart = 0L
        private set

    private var minTime = Long.MIN_VALUE
    private var maxTime = Long.MAX_VALUE

    /** Beim Oeffnen: Auswahl auf "jetzt" in Zeile [startRow]; erlaubt sind [pastMs] zurueck und [futureMs] voraus. */
    fun reset(startRow: Int, now: Long, pastMs: Long = 6 * HOUR_MS, futureMs: Long = 7 * DAY_MS) {
        minTime = now - pastMs
        maxTime = now + futureMs
        row = startRow.coerceIn(0, lastRow())
        anchor = now
        windowStart = alignDown(now - 30 * MIN_MS)
        ensureVisible()
    }

    /** Die Zeilenliste hat sich geaendert (z. B. Favoriten-Filter): Zeile neu setzen und einpassen. */
    fun setRow(r: Int) { row = r.coerceIn(0, lastRow()) }

    /** Ausgewaehlte Sendung (null = in dieser Zeile laeuft zu [anchor] nichts bzw. es gibt keine Daten). */
    fun selected(): Programme? = at(row, anchor)

    /** @return true, wenn sich die Zeile geaendert hat. Mit [wrap] springt ein Schritt ueber den Rand auf die andere Seite, sonst bleibt die Auswahl stehen. */
    fun moveRow(delta: Int, wrap: Boolean = false): Boolean {
        val n = ListNav.move(row, delta, rowCount(), wrap)
        if (n == row) return false
        row = n
        return true
    }

    /**
     * Zur vorigen ([dir] < 0) bzw. naechsten ([dir] > 0) Sendung der Zeile springen. In Zeilen ohne Programmdaten
     * (oder in Luecken) wird in 30-Minuten-Schritten verschoben. @return true, wenn sich die Auswahl geaendert hat.
     */
    fun moveTime(dir: Int): Boolean {
        if (rowCount() <= 0) return false
        val a = programmes(row)
        val cur = at(row, anchor)
        val before = anchor
        if (dir > 0) {
            if (cur != null) {
                val next = a.getOrNull(indexOf(a, cur) + 1) ?: return false
                anchor = next.start
            } else {
                val next = a.firstOrNull { it.start > anchor }
                if (next != null) anchor = next.start
                else if (a.isEmpty()) anchor = (anchor + 30 * MIN_MS).coerceAtMost(maxTime)
                else return false
            }
        } else if (dir < 0) {
            if (cur != null) {
                val prev = a.getOrNull(indexOf(a, cur) - 1) ?: return false
                anchor = prev.start
            } else {
                val prev = a.lastOrNull { it.start <= anchor }
                if (prev != null) anchor = prev.start
                else if (a.isEmpty()) anchor = (anchor - 30 * MIN_MS).coerceAtLeast(minTime)
                else return false
            }
        } else return false
        if (anchor == before) return false
        ensureVisible()
        return true
    }

    /** Zeitpunkt um [deltaMs] verschieben (z. B. +/- 3 h mit Vor-/Zurueckspulen). */
    fun jump(deltaMs: Long): Boolean {
        val n = (anchor + deltaMs).coerceIn(minTime, maxTime)
        if (n == anchor) return false
        anchor = n
        ensureVisible()
        return true
    }

    /** Fenster seitenweise nachfuehren, sobald der Auswahlzeitpunkt aus dem Bild laeuft. */
    fun ensureVisible() {
        if (anchor < windowStart || anchor >= windowStart + spanMs - 15 * MIN_MS) {
            windowStart = alignDown(anchor - spanMs / 4)
        }
        if (minTime != Long.MIN_VALUE) windowStart = windowStart.coerceAtLeast(alignDown(minTime))
    }

    private fun lastRow() = (rowCount() - 1).coerceAtLeast(0)

    private fun at(r: Int, t: Long): Programme? {
        if (rowCount() <= 0) return null
        val a = programmes(r)
        var lo = 0
        var hi = a.size - 1
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid].start <= t) { res = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (res >= 0 && a[res].end > t) a[res] else null
    }

    private fun indexOf(a: Array<Programme>, p: Programme): Int {
        var i = 0
        while (i < a.size) { if (a[i] === p || (a[i].start == p.start && a[i].end == p.end)) return i; i++ }
        return -1
    }

    private fun alignDown(t: Long) = t - Math.floorMod(t, 5 * MIN_MS)

    companion object {
        const val MIN_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        /** Sichtbares Zeitfenster der Programmuebersicht. */
        const val DEFAULT_SPAN_MS = 3 * HOUR_MS
    }
}
