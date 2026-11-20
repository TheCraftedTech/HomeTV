package de.thecraftedtech.hometv.playback

import java.util.concurrent.Executor

/** Etwas, das beim Senderwechsel sauber beendet werden muss (SAT>IP-Session mit TEARDOWN, lokaler Feed, ...). */
interface TuneHandle {
    /** Blockierend (bis ca. 2 s) – laeuft deshalb nie im UI-Thread. */
    fun stopBlocking()
}

/**
 * Serialisiert Senderwechsel: **nur der neueste Wechsel startet**, die alte Session wird vorher beendet,
 * es laufen nie zwei Sessions gleichzeitig.
 *
 *  - [tune] erhoeht die Generation, loest die alte Session und beendet sie auf dem [worker]-Thread (TEARDOWN).
 *  - Erst danach wird auf dem Haupt-Thread (via [mainPost]) die neue Session erzeugt – aber nur, wenn
 *    inzwischen kein neuerer Wechsel angefordert wurde. Veraltete Wechsel werden verworfen, bevor sie etwas oeffnen.
 *  - [worker] muss ein seriell arbeitender Executor sein (ein Thread), damit sich Beenden und Starten nie ueberholen.
 */
class TuneCoordinator<H : TuneHandle>(
    private val worker: Executor,
    private val mainPost: (Runnable) -> Unit,
    private val factory: (index: Int, generation: Int) -> H,
) {
    @Volatile var generation = 0
        private set
    private var current: H? = null

    val hasActive: Boolean get() = current != null

    /**
     * Fordert Sender [index] an. Gibt die zugehoerige Generation zurueck. Nur im Haupt-Thread aufrufen.
     * @param preStop  Blockierende Vorarbeit (z. B. LibVLC-Player anhalten), die auf dem [worker] VOR dem Beenden der alten
     *                 Session laeuft – so blockiert sie nie den Haupt-Thread.
     */
    fun tune(index: Int, preStop: (() -> Unit)? = null): Int {
        val gen = ++generation
        val old = current
        current = null
        worker.execute {
            preStop?.invoke()
            old?.stopBlocking()
            mainPost(Runnable { if (gen == generation) current = factory(index, gen) })
        }
        return gen
    }

    /** Beendet die laufende Session im Hintergrund (ohne neuen Sender). */
    fun stopAsync() {
        generation++
        val old = current ?: return
        current = null
        worker.execute { old.stopBlocking() }
    }

    /** Beendet die laufende Session sofort und blockierend (z. B. in onStop, bevor der Prozess pausiert). */
    fun stopNow() {
        generation++
        val old = current ?: return
        current = null
        old.stopBlocking()
    }

    fun isCurrent(gen: Int) = gen == generation
}
