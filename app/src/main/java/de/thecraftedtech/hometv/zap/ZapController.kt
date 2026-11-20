package de.thecraftedtech.hometv.zap

/** Abstraktion des Handlers, damit die Logik ohne Android testbar ist. */
interface Scheduler {
    fun postDelayed(token: Any, delayMs: Long, action: Runnable)
    fun cancel(token: Any)
}

/**
 * Senderwahl mit Vorschau und Entprellung ("schnelles Umschalten").
 *
 *  - Jeder Tastendruck ([step]) aendert sofort die *vorgemerkte* Auswahl und meldet [Listener.onPreview]
 *    (Banner/Programm sofort anzeigen) – es wird noch nichts geladen.
 *  - Erst wenn [debounceMs] lang keine weitere Taste kam, meldet [Listener.onCommit] den Sender, der tatsaechlich
 *    gestartet werden soll. Fuenfmal CH+ innerhalb kurzer Zeit startet also genau einen Sender (den fuenften).
 *  - Kehrt die Auswahl zum laufenden Sender zurueck, wird nichts neu geladen.
 *  - [select] (z. B. OK in der Senderliste) bestaetigt sofort.
 */
class ZapController(
    private val channelCount: () -> Int,
    private val scheduler: Scheduler,
    private val debounceMs: () -> Long,
    private val listener: Listener,
) {
    interface Listener {
        /** Vorgemerkte Auswahl geaendert (noch nicht geladen). */
        fun onPreview(index: Int)
        /** Auswahl steht fest -> jetzt laden. */
        fun onCommit(index: Int)
    }

    /** Index des laufenden (bzw. zuletzt zum Laden freigegebenen) Senders; -1 = keiner. */
    var current: Int = -1
        private set
    /** Vorgemerkte, noch nicht bestaetigte Auswahl. */
    var pending: Int? = null
        private set

    private val token = Any()

    fun setCurrent(index: Int) {
        current = index
        pending = null
        scheduler.cancel(token)
    }

    /** Relativer Wechsel (+1 = CH+, -1 = CH-) mit Umlauf. */
    fun step(delta: Int) {
        val n = channelCount()
        if (n <= 0) return
        val base = pending ?: current.takeIf { it >= 0 } ?: 0
        pending = Math.floorMod(base + delta, n)
        listener.onPreview(pending!!)
        scheduler.cancel(token)
        scheduler.postDelayed(token, debounceMs(), Runnable { commit() })
    }

    /** Direkte Auswahl, sofort bestaetigt (kein Warten). */
    fun select(index: Int) {
        val n = channelCount()
        if (n <= 0) return
        pending = Math.floorMod(index, n)
        listener.onPreview(pending!!)
        scheduler.cancel(token)
        commit()
    }

    fun cancel() {
        pending = null
        scheduler.cancel(token)
    }

    private fun commit() {
        val p = pending ?: return
        pending = null
        if (p == current) return // zurueck beim laufenden Sender: nichts neu laden
        current = p
        listener.onCommit(p)
    }
}

/**
 * Wiederholrate beim Gedrueckthalten einer Kanal-/Pfeiltaste: Der erste Druck zaehlt sofort; bei gehaltener
 * Taste (Key-Repeat) wird nur dann weitergeschaltet, wenn das zur Haltedauer passende Intervall abgelaufen ist.
 * Das Intervall verkuerzt sich, je laenger gehalten wird (schnelleres Durchschalten).
 */
class HoldTracker {
    private var holdStart = 0L
    private var lastStep = 0L

    fun intervalMs(heldMs: Long): Long = when {
        heldMs < 800 -> 250
        heldMs < 1800 -> 160
        heldMs < 3000 -> 110
        else -> 70
    }

    /** true, wenn jetzt ein Schritt ausgefuehrt werden soll. [repeatCount] = KeyEvent.getRepeatCount(). */
    fun shouldStep(repeatCount: Int, nowMs: Long): Boolean {
        if (repeatCount == 0) { holdStart = nowMs; lastStep = nowMs; return true }
        if (nowMs - lastStep >= intervalMs(nowMs - holdStart)) { lastStep = nowMs; return true }
        return false
    }
}
