package de.thecraftedtech.hometv.core

/** Bewegung in Listen mit optionalem Umspringen am Rand (reine Logik, ohne Android). */
object ListNav {
    /**
     * Neue Position nach einer Bewegung um [delta]. Mit [wrap] springt ein Schritt ueber den Rand auf die andere Seite
     * (nur bei Schritten von +-1; grosse Spruenge wie Bild auf/ab bleiben am Rand stehen). [size] <= 0 ergibt 0.
     */
    fun move(cur: Int, delta: Int, size: Int, wrap: Boolean): Int {
        if (size <= 0) return 0
        val t = cur + delta
        return when {
            t in 0 until size -> t
            wrap && (delta == 1 || delta == -1) -> Math.floorMod(t, size)
            else -> t.coerceIn(0, size - 1)
        }
    }
}
