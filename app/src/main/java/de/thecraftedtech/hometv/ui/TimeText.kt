package de.thecraftedtech.hometv.ui

/** Restzeit einer Sendung fuer die Senderliste: "noch 23 min", "noch 1:05 h", unter einer Minute "gleich Ende", abgelaufen = leer. */
fun remainingText(remainingMs: Long): String {
    val min = (remainingMs / 60_000).toInt()
    return when {
        remainingMs <= 0 -> ""
        min < 1 -> "gleich Ende"
        min < 60 -> "noch $min min"
        else -> "noch ${min / 60}:${(min % 60).toString().padStart(2, '0')} h"
    }
}
