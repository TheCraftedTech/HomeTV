package de.thecraftedtech.hometv.channels

/** Welche Sender angezeigt bzw. beim Umschalten beruecksichtigt werden (Favoriten-Filter). Reine Logik, ohne Android. */
object ChannelFilter {

    /**
     * Indizes der anzuzeigenden Sender. Ist der Favoriten-Filter an, aber kein Sender der Liste ein Favorit,
     * werden – statt einer leeren Liste – alle Sender gezeigt.
     */
    fun shown(channels: List<Channel>, favoritesOnly: Boolean, isFavorite: (Channel) -> Boolean): List<Int> {
        val all = channels.indices.toList()
        if (!favoritesOnly) return all
        val fav = all.filter { isFavorite(channels[it]) }
        return fav.ifEmpty { all }
    }

    /** true, wenn der Filter tatsaechlich wirkt (an und mindestens ein Favorit vorhanden). */
    fun isActive(channels: List<Channel>, favoritesOnly: Boolean, isFavorite: (Channel) -> Boolean): Boolean =
        favoritesOnly && channels.any(isFavorite)
}
