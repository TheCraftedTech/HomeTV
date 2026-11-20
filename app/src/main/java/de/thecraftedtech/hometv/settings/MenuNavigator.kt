package de.thecraftedtech.hometv.settings

/**
 * Bedienlogik des Einstellungsmenues (ohne Android-Abhaengigkeit, damit testbar).
 *
 * Drei Ebenen:
 *  - [Mode.CATEGORIES]: ▲▼ Bereich waehlen, ► / OK in die Eintraege, ◄ / Zurueck schliesst das Menue.
 *  - [Mode.ITEMS]: ▲▼ Eintrag markieren ("hover" – aendert nichts!), OK "klickt" den Eintrag an:
 *      Schalter wechseln sofort, Textfelder oeffnen den Eingabedialog, Aktionen laufen,
 *      Auswahl/Zahl wechseln in den Bearbeiten-Modus. ◄ / Zurueck zurueck zu den Bereichen.
 *  - [Mode.EDITING]: ◄► (oder ▼▲) aendern den Wert, OK / Zurueck beenden das Bearbeiten.
 */
class MenuNavigator(private val categories: List<SettingsCategory>) {

    enum class Mode { CATEGORIES, ITEMS, EDITING }
    enum class Nav { UP, DOWN, LEFT, RIGHT, OK, BACK, MENU }

    /** Was die Oberflaeche nach einer Taste tun soll. */
    sealed class Effect {
        /** Taste verbraucht, nichts zu tun. */
        object None : Effect()
        /** Neu zeichnen. */
        object Redraw : Effect()
        /** Menue schliessen. */
        object Close : Effect()
        class EditText(val item: SettingItem.Text) : Effect()
        /** Ein Eintrag wurde bedient (Wert geaendert oder Aktion ausgefuehrt); [changed] = Wert hat sich geaendert. */
        class Changed(val item: SettingItem, val changed: Boolean) : Effect()
    }

    var catIndex = 0
        private set
    var itemIndex = 0
        private set
    var mode = Mode.CATEGORIES
        private set
    var items: List<SettingItem> = emptyList()
        private set

    val currentItem: SettingItem? get() = items.getOrNull(itemIndex)

    init { reload() }

    /** Beim Oeffnen: immer bei den Bereichen beginnen. */
    fun reset() { mode = Mode.CATEGORIES; reload() }

    /** Eintraege des aktuellen Bereichs neu aufbauen (Werte koennen andere Eintraege beeinflussen). */
    fun reload() {
        items = categories[catIndex].items()
        itemIndex = itemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun onNav(n: Nav): Effect = when (mode) {
        Mode.CATEGORIES -> when (n) {
            Nav.UP -> moveCategory(-1)
            Nav.DOWN -> moveCategory(+1)
            Nav.RIGHT, Nav.OK -> enterItems()
            Nav.LEFT, Nav.BACK, Nav.MENU -> Effect.Close
        }
        Mode.ITEMS -> when (n) {
            Nav.UP -> moveItem(-1)
            Nav.DOWN -> moveItem(+1)
            Nav.LEFT, Nav.BACK -> { mode = Mode.CATEGORIES; Effect.Redraw }
            Nav.RIGHT -> Effect.None
            Nav.OK -> activate()
            Nav.MENU -> Effect.Close
        }
        Mode.EDITING -> when (n) {
            Nav.LEFT, Nav.DOWN -> adjust(-1)
            Nav.RIGHT, Nav.UP -> adjust(+1)
            Nav.OK, Nav.BACK -> { mode = Mode.ITEMS; Effect.Redraw }
            Nav.MENU -> { mode = Mode.ITEMS; Effect.Close }
        }
    }

    private fun moveCategory(d: Int): Effect {
        val n = (catIndex + d).coerceIn(0, categories.size - 1)
        if (n == catIndex) return Effect.None
        catIndex = n; itemIndex = 0; reload()
        return Effect.Redraw
    }

    private fun moveItem(d: Int): Effect {
        val n = (itemIndex + d).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (n == itemIndex) return Effect.None
        itemIndex = n
        return Effect.Redraw
    }

    private fun enterItems(): Effect {
        if (items.isEmpty()) return Effect.None
        mode = Mode.ITEMS
        return Effect.Redraw
    }

    private fun activate(): Effect {
        val item = currentItem ?: return Effect.None
        if (!item.enabled) return Effect.None
        return when {
            item is SettingItem.Text -> Effect.EditText(item)
            item is SettingItem.Toggle || item is SettingItem.Action -> applied(item, item.activate())
            item.adjustable -> { mode = Mode.EDITING; Effect.Redraw }
            else -> Effect.None   // reine Anzeige
        }
    }

    private fun adjust(delta: Int): Effect {
        val item = currentItem?.takeIf { it.adjustable } ?: return Effect.None
        return applied(item, item.adjust(delta))
    }

    /** Nach jeder Aenderung die Eintraege neu aufbauen (z. B. schaltet der Debug-Modus weitere Eintraege frei). */
    fun applied(item: SettingItem, changed: Boolean): Effect {
        reload()
        return Effect.Changed(item, changed)
    }
}
