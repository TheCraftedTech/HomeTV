package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.core.ListNav
import de.thecraftedtech.hometv.epg.Programme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Senderliste als schwebende Karte am rechten Bildschirmrand. Die Auswahl wird NICHT ueber den Android-Fokus gefuehrt,
 * sondern von der Activity per [moveSelection] gesteuert (vorhersehbar mit der Fernbedienung). Die Wiedergabe laeuft dabei weiter.
 *
 * Je Sender: Nummer, Name, – wenn ein Programmfuehrer aktiv ist – laufende Sendung, Fortschrittsbalken und Restzeit.
 * Oben rechts die Uhrzeit; ein Rollbalken zeigt die Position in der Liste.
 */
class ChannelListView(context: Context) : FrameLayout(context) {

    private val list = ListView(context)
    private val scroll = ScrollIndicator(context)
    private val title = context.label(18f, Ui.TEXT, bold = true)
    private val clock = context.label(15f, Ui.ACCENT_LIGHT, bold = true)
    private val hint = context.label(12f, Ui.TEXT_DIM, lines = 2)
    private val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.GERMANY)

    private var channels: List<Channel> = emptyList()
    /** Angezeigte Sender (Indizes in [channels]) – bei aktivem Favoriten-Filter nur die Favoriten. */
    private var shown: List<Int> = emptyList()
    private var playingIndex = -1
    /** Position der Auswahl in der angezeigten Liste. */
    var selected = 0
        private set
    /** Index des ausgewaehlten Senders in der vollstaendigen Senderliste (-1 = keiner). */
    val selectedChannel: Int get() = shown.getOrElse(selected) { -1 }
    var isFavorite: (Channel) -> Boolean = { false }
    /** Laufende Sendung eines Senders (null = keine Information, z. B. Programmfuehrer aus). */
    var nowProgramme: (Channel) -> Programme? = { null }

    private val rowApprox = context.dp(46)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = channels[shown[position]]
        override fun getItemId(position: Int) = shown[position].toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row(context)
            val ci = shown[position]
            val ch = channels[ci]
            row.bind(ci, ch, position == selected, ci == playingIndex, nowProgramme(ch), System.currentTimeMillis(), isFavorite(ch))
            return row
        }
    }

    /** Uhr und Fortschrittsbalken regelmaessig aktualisieren, solange die Liste offen ist. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!opened) return
            updateClock(); adapter.notifyDataSetChanged()
            postDelayed(this, 20_000)
        }
    }

    init {
        visibility = GONE
        background = context.cardBackground()
        setPadding(context.dp(8), context.dp(14), context.dp(8), context.dp(10))

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(10), 0, context.dp(10), context.dp(8)) }
        val headRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.text = "Sender"
        headRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headRow.addView(clock)
        head.addView(headRow)
        hint.text = "▲▼ wählen   OK starten   OK lang ★ Favorit\n► Programm   Menü Filter   ◄ schließen"
        head.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(3) })
        col.addView(head)

        list.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            divider = ColorDrawable(0)
            dividerHeight = context.dp(1)
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            this.adapter = this@ChannelListView.adapter
            overScrollMode = OVER_SCROLL_NEVER
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) = scroll.update(first, visible, total)
            })
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(list, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        body.addView(scroll, LinearLayout.LayoutParams(context.dp(3), LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = context.dp(4); rightMargin = context.dp(2) })
        col.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setChannels(list: List<Channel>) { channels = list; shown = list.indices.toList(); adapter.notifyDataSetChanged() }

    /** Welche Sender gezeigt werden (Favoriten-Filter). Die Auswahl bleibt auf demselben Sender, wenn er noch enthalten ist. */
    fun setShown(indices: List<Int>, filtered: Boolean) {
        val keep = selectedChannel
        shown = indices
        selected = shown.indexOf(keep).takeIf { it >= 0 } ?: selected.coerceIn(0, (shown.size - 1).coerceAtLeast(0))
        title.text = if (filtered) "Favoriten ★" else "Sender"
        adapter.notifyDataSetChanged()
        if (opened) centerSelection()
    }

    private var opened = false

    fun open(currentIndex: Int) {
        opened = true
        playingIndex = currentIndex
        selected = shown.indexOf(currentIndex).takeIf { it >= 0 } ?: 0
        updateClock()
        adapter.notifyDataSetChanged()
        showAnimated(fromX = context.dpf(60f))
        post { centerSelection() }
        removeCallbacks(ticker); postDelayed(ticker, 20_000)
        // nach der Animation nochmals komplett neu zeichnen (verhindert halb aktualisierte Raender)
        postDelayed({ if (opened) redrawFully() }, Ui.ANIM_MS + 40)
    }

    fun close() { opened = false; removeCallbacks(ticker); hideAnimated(toX = context.dpf(60f)) }

    /** Sofort und ohne Animation schliessen (z. B. in onStop). */
    fun forceClose() { opened = false; removeCallbacks(ticker); animate().cancel(); visibility = GONE; alpha = 1f; translationX = 0f }

    val isOpen: Boolean get() = opened

    /** Auswahl verschieben (mit Begrenzung, kein Umlauf – in langen Listen orientiert man sich am Rand). */
    fun moveSelection(delta: Int, wrap: Boolean = false) {
        if (shown.isEmpty()) return
        val n = ListNav.move(selected, delta, shown.size, wrap)
        if (n == selected) return
        selected = n
        adapter.notifyDataSetChanged()
        centerSelection()
    }

    fun setPlaying(index: Int) { playingIndex = index; adapter.notifyDataSetChanged() }
    fun refresh() = adapter.notifyDataSetChanged()

    private fun updateClock() { clock.text = timeFmt.format(Date()) }

    private fun centerSelection() {
        val offset = (list.height / 2 - rowApprox / 2).coerceAtLeast(0)
        list.setSelectionFromTop(selected, offset)
        list.post { scroll.update(list.firstVisiblePosition, list.childCount, adapter.count) }
    }

    private inner class Row(context: Context) : LinearLayout(context) {
        private val num = context.label(14f, Ui.TEXT_DIM, bold = true)
        private val nm = context.label(15f, Ui.TEXT, bold = true)
        private val star = context.label(14f, Ui.EDIT, bold = true)
        private val remain = context.label(12f, Ui.TEXT_DIM)
        private val sub = context.label(12f, Ui.TEXT_DIM)
        private val bar = ProgressStrip(context)
        private val marker = View(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            setPadding(context.dp(8), context.dp(5), context.dp(10), context.dp(5))
            addView(marker, LayoutParams(context.dp(4), context.dp(24)).apply { rightMargin = context.dp(8) })
            num.gravity = Gravity.END
            addView(num, LayoutParams(context.dp(30), LayoutParams.WRAP_CONTENT).apply { rightMargin = context.dp(10) })

            val col = LinearLayout(context).apply { orientation = VERTICAL }
            val line1 = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            star.text = "★"
            line1.addView(star, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = context.dp(5) })
            line1.addView(nm, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            line1.addView(remain, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(8) })
            col.addView(line1)
            col.addView(sub, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(1) })
            col.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(3)).apply { topMargin = context.dp(3) })
            addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        fun bind(channelIndex: Int, ch: Channel, isSelected: Boolean, isPlaying: Boolean, p: Programme?, nowMs: Long, favourite: Boolean) {
            num.text = (channelIndex + 1).toString()
            star.visibility = if (favourite) VISIBLE else GONE
            nm.text = ch.name
            background = if (isSelected) roundRect(Ui.BG_ROW_SELECTED, context.dpf(8f)) else null
            nm.setTextColor(Ui.TEXT)
            num.setTextColor(if (isSelected) Ui.TEXT else Ui.TEXT_DIM)
            val dim = if (isSelected) 0xFFDCE8FF.toInt() else Ui.TEXT_DIM
            sub.setTextColor(dim); remain.setTextColor(dim)
            marker.background = if (isPlaying) roundRect(if (isSelected) Ui.TEXT else Ui.ACCENT, context.dpf(2f)) else null

            if (p == null) {
                sub.visibility = GONE; bar.visibility = GONE; remain.visibility = GONE
            } else {
                sub.visibility = VISIBLE; bar.visibility = VISIBLE; remain.visibility = VISIBLE
                sub.text = p.title
                bar.fraction = (nowMs - p.start).toFloat() / p.durationMs.coerceAtLeast(1)
                remain.text = remainingText(p.end - nowMs)
            }
        }
    }
}
