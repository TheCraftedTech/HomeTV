package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import de.thecraftedtech.hometv.channels.Channel

/**
 * Senderliste am rechten Bildschirmrand. Die Auswahl wird NICHT ueber den Android-Fokus gefuehrt, sondern von
 * der Activity per [moveSelection] gesteuert (vorhersehbar mit der Fernbedienung). Die Wiedergabe laeuft dabei weiter.
 */
class ChannelListView(context: Context) : FrameLayout(context) {

    private val list = ListView(context)
    private val title = context.label(22f, Ui.TEXT, bold = true)
    private val hint = context.label(15f, Ui.TEXT_DIM)

    private var channels: List<Channel> = emptyList()
    private var playingIndex = -1
    var selected = 0
        private set
    /** Zweite Zeile je Sender (aktuelle Sendung) – null/leer = keine. */
    var subtitle: (Channel) -> String? = { null }

    private val rowH = context.dp(66)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row(context)
            row.bind(position, channels[position], position == selected, position == playingIndex, subtitle(channels[position]))
            return row
        }
    }

    init {
        visibility = GONE
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG_PANEL)
            setPadding(context.dp(8), context.dp(24), context.dp(8), context.dp(16))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(20), 0, context.dp(20), context.dp(12)) }
        title.text = "Sender"
        hint.text = "▲▼ wählen    OK starten    Zurück schließen"
        head.addView(title); head.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) })
        panel.addView(head)
        list.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            divider = ColorDrawable(0)
            dividerHeight = 0
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            this.adapter = this@ChannelListView.adapter
            overScrollMode = OVER_SCROLL_NEVER
        }
        panel.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(panel, LayoutParams(context.dp(400), LayoutParams.MATCH_PARENT, Gravity.END))
    }

    fun setChannels(list: List<Channel>) { channels = list; adapter.notifyDataSetChanged() }

    private var opened = false

    fun open(currentIndex: Int) {
        opened = true
        playingIndex = currentIndex
        selected = currentIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        adapter.notifyDataSetChanged()
        showAnimated(fromX = context.dpf(80f))
        post { centerSelection() }
    }

    fun close() { opened = false; hideAnimated(toX = context.dpf(80f)) }

    /** Sofort und ohne Animation schliessen (z. B. in onStop). */
    fun forceClose() { opened = false; animate().cancel(); visibility = GONE; alpha = 1f; translationX = 0f }

    val isOpen: Boolean get() = opened

    /** Auswahl verschieben (mit Begrenzung, kein Umlauf – in langen Listen orientiert man sich am Rand). */
    fun moveSelection(delta: Int) {
        if (channels.isEmpty()) return
        val n = (selected + delta).coerceIn(0, channels.size - 1)
        if (n == selected) return
        selected = n
        adapter.notifyDataSetChanged()
        centerSelection()
    }

    fun setPlaying(index: Int) { playingIndex = index; adapter.notifyDataSetChanged() }
    fun refresh() = adapter.notifyDataSetChanged()

    private fun centerSelection() {
        val offset = (list.height / 2 - rowH / 2).coerceAtLeast(0)
        list.setSelectionFromTop(selected, offset)
    }

    private inner class Row(context: Context) : LinearLayout(context) {
        private val num = context.label(22f, Ui.TEXT_DIM, bold = true)
        private val nm = context.label(22f, Ui.TEXT, bold = true)
        private val sub = context.label(16f, Ui.TEXT_DIM)
        private val marker = View(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListViewLayoutParams(rowH)
            setPadding(context.dp(12), 0, context.dp(16), 0)
            addView(marker, LayoutParams(context.dp(5), context.dp(36)).apply { rightMargin = context.dp(10) })
            num.gravity = Gravity.END
            addView(num, LayoutParams(context.dp(52), LayoutParams.WRAP_CONTENT).apply { rightMargin = context.dp(14) })
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(nm); col.addView(sub)
            addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        fun bind(pos: Int, ch: Channel, isSelected: Boolean, isPlaying: Boolean, subText: String?) {
            num.text = (pos + 1).toString()
            nm.text = ch.name
            sub.text = subText.orEmpty()
            sub.visibility = if (subText.isNullOrEmpty()) GONE else VISIBLE
            background = if (isSelected) roundRect(Ui.BG_ROW_SELECTED, context.dpf(10f)) else null
            nm.setTextColor(Ui.TEXT)
            num.setTextColor(if (isSelected) Ui.TEXT else Ui.TEXT_DIM)
            sub.setTextColor(if (isSelected) 0xFFDCE8FF.toInt() else Ui.TEXT_DIM)
            marker.background = if (isPlaying) roundRect(if (isSelected) Ui.TEXT else Ui.ACCENT, context.dpf(3f)) else null
        }
    }

    private fun AbsListViewLayoutParams(h: Int) = android.widget.AbsListView.LayoutParams(android.widget.AbsListView.LayoutParams.MATCH_PARENT, h)
}
