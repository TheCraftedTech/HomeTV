package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import de.thecraftedtech.hometv.settings.SettingItem
import de.thecraftedtech.hometv.settings.SettingsCategory

/**
 * Einstellungsmenue (links eingeblendet): linke Spalte Kategorien, rechte Spalte Eintraege.
 * Steuerung ausschliesslich ueber [handleKey] (kein Android-Fokus):
 *   ▲▼ Auswahl · ► / OK in die Eintraege · ◄ ► Wert aendern · ◄ zurueck zu den Kategorien · Zurueck schliesst.
 */
class MenuView(context: Context, private val categories: List<SettingsCategory>) : FrameLayout(context) {

    interface Callbacks {
        fun onClose()
        /** Eingabedialog fuer ein Textfeld oeffnen. */
        fun onEditText(item: SettingItem.Text)
        /** Ein Wert wurde geaendert (oder eine Aktion ausgefuehrt). */
        fun onItemChanged(item: SettingItem)
    }

    var callbacks: Callbacks? = null

    private var catIndex = 0
    private var itemIndex = 0
    private var inItems = false
    private var items: List<SettingItem> = emptyList()

    private val catList = ListView(context)
    private val itemList = ListView(context)
    private val title = context.label(26f, Ui.TEXT, bold = true)
    private val help = context.label(17f, Ui.TEXT_DIM, lines = 2)
    private val rowH = context.dp(54)

    private val catAdapter = object : BaseAdapter() {
        override fun getCount() = categories.size
        override fun getItem(p: Int) = categories[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val t = (v as? TextView) ?: context.label(22f, Ui.TEXT, bold = true).also {
                it.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, rowH)
                it.gravity = Gravity.CENTER_VERTICAL
                it.setPadding(context.dp(20), 0, context.dp(12), 0)
            }
            t.text = categories[p].title
            val sel = p == catIndex
            t.setTextColor(if (sel) Ui.TEXT else Ui.TEXT_DIM)
            t.background = if (sel) roundRect(if (inItems) Ui.BG_ROW_SELECTED_DIM else Ui.BG_ROW_SELECTED, context.dpf(10f)) else null
            return t
        }
    }

    private val itemAdapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val row = (v as? ItemRow) ?: ItemRow(context)
            row.bind(items[p], inItems && p == itemIndex)
            return row
        }
    }

    init {
        visibility = GONE
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG_PANEL)
            setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(20))
        }
        title.text = "Einstellungen"
        panel.addView(title)
        val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        styleList(catList, catAdapter)
        styleList(itemList, itemAdapter)
        cols.addView(catList, LinearLayout.LayoutParams(context.dp(230), LinearLayout.LayoutParams.MATCH_PARENT))
        cols.addView(View(context).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(context.dp(12), 0, context.dp(16), 0) })
        cols.addView(itemList, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        panel.addView(cols, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(18) })
        panel.addView(help, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(52)).apply { topMargin = context.dp(10) })
        addView(panel, LayoutParams(context.dp(760), LayoutParams.MATCH_PARENT, Gravity.START))
    }

    private fun styleList(l: ListView, a: BaseAdapter) {
        l.isFocusable = false
        l.isFocusableInTouchMode = false
        l.divider = ColorDrawable(0)
        l.dividerHeight = context.dp(4)
        l.selector = ColorDrawable(0)
        l.isVerticalScrollBarEnabled = false
        l.overScrollMode = OVER_SCROLL_NEVER
        l.adapter = a
    }

    // ------------------------------------------------------------------ Oeffnen/Schliessen

    private var opened = false
    val isOpen: Boolean get() = opened

    fun open() {
        opened = true
        inItems = false
        loadItems()
        showAnimated(fromX = -context.dpf(80f))
        refresh()
    }

    fun close() { opened = false; hideAnimated(toX = -context.dpf(80f)) }

    /** Sofort und ohne Animation schliessen (z. B. in onStop). */
    fun forceClose() { opened = false; animate().cancel(); visibility = GONE; alpha = 1f; translationX = 0f }

    private fun loadItems() {
        items = categories[catIndex].items()
        itemIndex = itemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        itemAdapter.notifyDataSetChanged()
    }

    /** Neu zeichnen (z. B. wenn sich der EPG-Status geaendert hat). */
    fun refresh() {
        catAdapter.notifyDataSetChanged()
        itemAdapter.notifyDataSetChanged()
        help.text = if (inItems) items.getOrNull(itemIndex)?.help.orEmpty() else "▲▼ Bereich wählen    ► / OK öffnen    ◄ / Zurück schließen"
        if (inItems) itemList.setSelectionFromTop(itemIndex, (itemList.height / 2 - rowH / 2).coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ Tasten

    /** @return true, wenn die Taste verarbeitet wurde. */
    fun handleKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> move(+1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!inItems) enterItems()
                else items.getOrNull(itemIndex)?.takeIf { it.adjustable }?.let { changed(it, it.adjust(+1)) }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (inItems) {
                    val it = items.getOrNull(itemIndex)
                    if (it != null && it.adjustable) changed(it, it.adjust(-1)) else leaveItems()
                } else callbacks?.onClose()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!inItems) enterItems() else activate()
            }
            KeyEvent.KEYCODE_BACK -> if (inItems) leaveItems() else callbacks?.onClose()
            KeyEvent.KEYCODE_MENU -> callbacks?.onClose()
            else -> return false
        }
        return true
    }

    private fun move(d: Int) {
        if (!inItems) {
            val n = (catIndex + d).coerceIn(0, categories.size - 1)
            if (n != catIndex) { catIndex = n; itemIndex = 0; loadItems() }
        } else {
            itemIndex = (itemIndex + d).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        refresh()
    }

    private fun enterItems() { if (items.isNotEmpty()) { inItems = true; refresh() } }
    private fun leaveItems() { inItems = false; refresh() }

    private fun activate() {
        val it = items.getOrNull(itemIndex) ?: return
        if (!it.enabled) return
        if (it is SettingItem.Text) { callbacks?.onEditText(it); return }
        changed(it, it.activate())
    }

    private fun changed(item: SettingItem, didChange: Boolean) {
        // Eintragsliste neu aufbauen: Aktionen/Schalter koennen andere Eintraege beeinflussen (z. B. Debug-Modus)
        val keep = itemIndex
        loadItems(); itemIndex = keep.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        refresh()
        if (didChange || item is SettingItem.Action) callbacks?.onItemChanged(item)
    }

    // ------------------------------------------------------------------ Zeile

    private inner class ItemRow(context: Context) : LinearLayout(context) {
        private val lab = context.label(20f, Ui.TEXT)
        private val value = context.label(20f, Ui.ACCENT_LIGHT)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, rowH)
            setPadding(context.dp(16), 0, context.dp(16), 0)
            addView(lab, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(16) })
            value.maxWidth = context.dp(300)
        }

        fun bind(item: SettingItem, selected: Boolean) {
            lab.text = item.label
            val v = item.display()
            value.text = if (selected && item.adjustable) "◄  $v  ►" else v
            val enabled = item.enabled
            lab.setTextColor(if (!enabled) Ui.TEXT_DISABLED else if (item is SettingItem.Action) Ui.ACCENT_LIGHT else Ui.TEXT)
            value.setTextColor(if (!enabled) Ui.TEXT_DISABLED else if (selected) Ui.TEXT else Ui.ACCENT_LIGHT)
            background = if (selected) roundRect(Ui.BG_ROW_SELECTED, context.dpf(10f)) else null
        }
    }
}
