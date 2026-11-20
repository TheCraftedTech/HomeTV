package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import de.thecraftedtech.hometv.settings.MenuNavigator
import de.thecraftedtech.hometv.settings.MenuNavigator.Effect
import de.thecraftedtech.hometv.settings.MenuNavigator.Mode
import de.thecraftedtech.hometv.settings.MenuNavigator.Nav
import de.thecraftedtech.hometv.settings.SettingItem
import de.thecraftedtech.hometv.settings.SettingsCategory

/**
 * Einstellungsmenue als schwebende Karte am linken Rand (links Bereiche, rechts Eintraege).
 * Die Bedienlogik steckt in [MenuNavigator]; diese Klasse zeichnet nur und uebersetzt Tasten.
 *
 *  - blau  = markiert (nur Auswahl, aendert nichts)
 *  - amber = "angeklickt"/Bearbeiten-Modus (◄► aendert den Wert, OK/Zurueck beendet)
 *  - gruen/grau = Schalter Ein/Aus
 */
class MenuView(context: Context, private val categories: List<SettingsCategory>, wrap: () -> Boolean = { true }) : FrameLayout(context) {

    interface Callbacks {
        fun onClose()
        /** Eingabedialog fuer ein Textfeld oeffnen. */
        fun onEditText(item: SettingItem.Text)
        /** Ein Wert wurde geaendert (oder eine Aktion ausgefuehrt). */
        fun onItemChanged(item: SettingItem)
        /** Sicherheitsabfrage zeigen; bei "Ja" [proceed] aufrufen. */
        fun onConfirm(item: SettingItem.Action, proceed: () -> Unit)
    }

    var callbacks: Callbacks? = null

    private val nav = MenuNavigator(categories, wrap)

    private val catList = ListView(context)
    private val itemList = ListView(context)
    private val itemScroll = ScrollIndicator(context)
    private val title = context.label(18f, Ui.TEXT, bold = true)
    private val help = context.label(12f, Ui.TEXT_DIM, lines = 2)
    private val controls = context.label(12f, Ui.ACCENT_LIGHT)
    private val catRowH = context.dp(34)
    private val rowH = context.dp(34)

    private val catAdapter = object : BaseAdapter() {
        override fun getCount() = categories.size
        override fun getItem(p: Int) = categories[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val t = (v as? TextView) ?: context.label(15f, Ui.TEXT, bold = true).also {
                it.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, catRowH)
                it.gravity = Gravity.CENTER_VERTICAL
                it.setPadding(context.dp(12), 0, context.dp(8), 0)
            }
            t.text = categories[p].title
            val sel = p == nav.catIndex
            t.setTextColor(if (sel) Ui.TEXT else Ui.TEXT_DIM)
            t.background = if (sel) roundRect(if (nav.mode == Mode.CATEGORIES) Ui.BG_ROW_SELECTED else Ui.BG_ROW_SELECTED_DIM, context.dpf(8f)) else null
            return t
        }
    }

    private val itemAdapter = object : BaseAdapter() {
        override fun getCount() = nav.items.size
        override fun getItem(p: Int) = nav.items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val row = (v as? ItemRow) ?: ItemRow(context)
            val state = when {
                nav.mode == Mode.CATEGORIES || p != nav.itemIndex -> RowState.NORMAL
                nav.mode == Mode.EDITING -> RowState.EDITING
                else -> RowState.HOVER
            }
            row.bind(nav.items[p], state)
            return row
        }
    }

    init {
        visibility = GONE
        background = context.cardBackground()
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(10))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title.text = "Einstellungen"
        col.addView(title)

        val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        styleList(catList, catAdapter)
        styleList(itemList, itemAdapter)
        itemList.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) = itemScroll.update(first, visible, total)
        })
        cols.addView(catList, LinearLayout.LayoutParams(context.dp(150), LinearLayout.LayoutParams.MATCH_PARENT))
        cols.addView(View(context).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(context.dp(8), 0, context.dp(10), 0) })
        cols.addView(itemList, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        cols.addView(itemScroll, LinearLayout.LayoutParams(context.dp(3), LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = context.dp(6) })
        col.addView(cols, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(10) })

        col.addView(help, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(34)).apply { topMargin = context.dp(8) })
        col.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun styleList(l: ListView, a: BaseAdapter) {
        l.isFocusable = false
        l.isFocusableInTouchMode = false
        l.divider = ColorDrawable(0)
        l.dividerHeight = context.dp(2)
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
        nav.reset()
        showAnimated(fromX = -context.dpf(60f))
        refresh()
        // nach der Animation nochmals komplett neu zeichnen (verhindert halb aktualisierte Raender)
        postDelayed({ if (opened) redrawFully() }, Ui.ANIM_MS + 40)
    }

    fun close() { opened = false; hideAnimated(toX = -context.dpf(60f)) }

    /** Sofort und ohne Animation schliessen (z. B. in onStop). */
    fun forceClose() { opened = false; animate().cancel(); visibility = GONE; alpha = 1f; translationX = 0f }

    /** Neu zeichnen (z. B. wenn sich der EPG-Status geaendert hat oder ein Textfeld gespeichert wurde). */
    fun refresh() {
        nav.reload()
        catAdapter.notifyDataSetChanged()
        itemAdapter.notifyDataSetChanged()
        // lange Bereichsliste: ausgewaehlten Bereich im Sichtbereich halten
        val catStep = catRowH + context.dp(2)
        if (catList.height > 0 && categories.size * catStep > catList.height) catList.setSelectionFromTop(nav.catIndex, (catList.height / 2 - catRowH / 2).coerceAtLeast(0))
        val item = nav.currentItem
        help.text = if (nav.mode == Mode.CATEGORIES) "" else item?.help.orEmpty()
        controls.text = controlsHint(item)
        if (nav.mode != Mode.CATEGORIES) itemList.setSelectionFromTop(nav.itemIndex, (itemList.height / 2 - rowH / 2).coerceAtLeast(0))
        else itemList.setSelection(0)
        itemList.post { itemScroll.update(itemList.firstVisiblePosition, itemList.childCount, itemAdapter.count) }
    }

    private fun controlsHint(item: SettingItem?): String = when (nav.mode) {
        Mode.CATEGORIES -> "▲▼ Bereich    OK / ► öffnen    ◄ / Zurück schließen"
        Mode.EDITING -> "◄ ► Wert ändern    OK / Zurück fertig"
        Mode.ITEMS -> when {
            item == null -> "◄ / Zurück zu den Bereichen"
            !item.enabled -> "▲▼ wählen    ◄ / Zurück zu den Bereichen"
            item is SettingItem.Toggle -> "▲▼ wählen    OK ein/aus    ◄ zurück"
            item is SettingItem.Text -> "▲▼ wählen    OK eingeben    ◄ zurück"
            item is SettingItem.Action -> "▲▼ wählen    OK ausführen    ◄ zurück"
            item.adjustable -> "▲▼ wählen    OK ändern    ◄ zurück"
            else -> "▲▼ wählen    ◄ zurück"
        }
    }

    // ------------------------------------------------------------------ Tasten

    /** @return true, wenn die Taste verarbeitet wurde. */
    fun handleKey(keyCode: Int, repeatCount: Int = 0): Boolean {
        val n = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> Nav.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> Nav.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Nav.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Nav.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Nav.OK
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> Nav.BACK
            KeyEvent.KEYCODE_MENU -> Nav.MENU
            else -> return false
        }
        // Gehaltenes OK/Zurueck darf nichts mehrfach ausloesen (Schalter, Dialoge, Ebenenwechsel)
        if (repeatCount > 0 && (n == Nav.OK || n == Nav.BACK || n == Nav.MENU)) return true
        handle(nav.onNav(n, fresh = repeatCount == 0))
        return true
    }

    private fun handle(e: Effect) {
        when (e) {
            Effect.None -> Unit
            Effect.Redraw -> refresh()
            Effect.Close -> callbacks?.onClose()
            is Effect.EditText -> callbacks?.onEditText(e.item)
            is Effect.Confirm -> callbacks?.onConfirm(e.item) { handle(nav.confirmed(e.item)) }
            is Effect.Changed -> {
                refresh()
                if (e.changed || e.item is SettingItem.Action) callbacks?.onItemChanged(e.item)
            }
        }
    }

    // ------------------------------------------------------------------ Zeile

    private enum class RowState { NORMAL, HOVER, EDITING }

    private inner class ItemRow(context: Context) : LinearLayout(context) {
        private val lab = context.label(15f, Ui.TEXT)
        private val value = context.label(15f, Ui.ACCENT_LIGHT)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, rowH)
            setPadding(context.dp(10), 0, context.dp(8), 0)
            addView(lab, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(10) })
            value.maxWidth = context.dp(190)
            value.setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
        }

        fun bind(item: SettingItem, state: RowState) {
            lab.text = item.label
            val enabled = item.enabled
            val v = item.display()
            val isToggle = item is SettingItem.Toggle
            val on = isToggle && v == "Ein"

            // Zeilenhintergrund je Zustand
            background = when (state) {
                RowState.HOVER -> roundRect(Ui.BG_ROW_SELECTED, context.dpf(8f))
                RowState.EDITING -> GradientDrawable().apply {
                    setColor(Ui.BG_ROW_EDIT); cornerRadius = context.dpf(8f); setStroke(context.dp(2), Ui.EDIT)
                }
                RowState.NORMAL -> null
            }
            lab.setTextColor(
                when {
                    !enabled -> Ui.TEXT_DISABLED
                    item is SettingItem.Action -> if (state == RowState.HOVER) Ui.TEXT else Ui.ACCENT_LIGHT
                    else -> Ui.TEXT
                }
            )

            // Wert: Schalter als farbige Kapsel, im Bearbeiten-Modus amber mit Pfeilen
            when {
                state == RowState.EDITING -> {
                    value.text = "◄  $v  ►"
                    value.setTextColor(Ui.EDIT_TEXT)
                    value.background = roundRect(Ui.EDIT, context.dpf(10f))
                }
                isToggle -> {
                    value.text = v
                    value.setTextColor(if (!enabled) Ui.TEXT_DISABLED else if (on) Ui.ON_TEXT else Ui.TEXT_DIM)
                    value.background = roundRect(if (on) Ui.ON_BG else Ui.OFF_BG, context.dpf(10f))
                }
                else -> {
                    value.text = v
                    value.setTextColor(if (!enabled) Ui.TEXT_DISABLED else if (state == RowState.HOVER) Ui.TEXT else Ui.ACCENT_LIGHT)
                    value.background = null
                }
            }
            value.visibility = if (v.isEmpty()) GONE else VISIBLE
        }
    }
}
