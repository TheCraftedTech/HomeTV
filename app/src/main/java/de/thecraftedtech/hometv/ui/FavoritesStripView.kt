package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.core.ListNav
import de.thecraftedtech.hometv.epg.Programme

/**
 * Favoriten-Leiste am unteren Bildrand: Karten mit Sendernummer, Name, laufender Sendung und Fortschrittsbalken.
 * Das Fernsehbild bleibt darueber sichtbar (nur ein dunkler Verlauf unten). Die gewaehlte Karte ist groesser und blau,
 * der gerade laufende Sender hat einen hellen Rand. Steuerung: ◄► waehlen, OK ansehen, OK lange = aus Favoriten entfernen.
 */
class FavoritesStripView(context: Context) : View(context) {

    var channels: List<Channel> = emptyList()
    /** Laufende Sendung eines Senders (null = keine Information). */
    var nowProgramme: (Channel) -> Programme? = { null }
    /** Index (in [channels]) des laufenden Senders; -1 = keiner. */
    var playingChannel = -1

    private var items: List<Int> = emptyList()
    private var sel = 0
    private var opened = false

    val isOpen: Boolean get() = opened
    /** Index (in [channels]) der gewaehlten Karte; -1 = keine. */
    val selectedChannelIndex: Int get() = items.getOrElse(sel) { -1 }
    val count: Int get() = items.size

    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bar = ProgressStripPainter()

    private val ticker = object : Runnable {
        override fun run() { if (!opened) return; invalidate(); postDelayed(this, 20_000) }
    }

    init { visibility = GONE }

    /** Favoriten (Indizes in [channels]) setzen; die Auswahl bleibt auf demselben Sender, falls noch enthalten. */
    fun setItems(list: List<Int>) {
        val keep = selectedChannelIndex
        items = list
        sel = list.indexOf(keep).takeIf { it >= 0 } ?: sel.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (opened) invalidate()
    }

    fun open(currentChannel: Int) {
        opened = true
        sel = items.indexOf(currentChannel).takeIf { it >= 0 } ?: 0
        showAnimated(fromY = resources.displayMetrics.density * 50f)
        invalidate()
        removeCallbacks(ticker); postDelayed(ticker, 20_000)
    }

    fun close() { opened = false; removeCallbacks(ticker); hideAnimated(toY = resources.displayMetrics.density * 50f) }
    fun forceClose() { opened = false; removeCallbacks(ticker); animate().cancel(); visibility = GONE; alpha = 1f; translationY = 0f }

    fun move(delta: Int, wrap: Boolean) {
        if (items.isEmpty()) return
        val n = ListNav.move(sel, delta, items.size, wrap)
        if (n != sel) { sel = n; invalidate() }
    }

    fun refresh() { if (opened) invalidate() }

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0 || items.isEmpty()) return
        val d = resources.displayMetrics.density
        val now = System.currentTimeMillis()

        // dunkler Verlauf am unteren Rand, damit die Karten auf jedem Bild lesbar sind
        val gh = 230 * d
        p.shader = LinearGradient(0f, h - gh, 0f, h, 0x00000000, 0xE6070B14.toInt(), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawRect(0f, h - gh, w, h, p)
        p.shader = null

        val cardW = 168 * d; val cardH = 88 * d
        val selW = 196 * d; val selH = 104 * d
        val gap = 12 * d
        val baseY = h - 52 * d                       // Unterkante der Karten
        // Positionen: Karten nebeneinander, gewaehlte Karte breiter; bei vielen Karten bleibt die Auswahl in der Mitte
        val totalW = items.size * cardW + (items.size - 1) * gap + (selW - cardW)
        var x0 = if (totalW <= w - 96 * d) (w - totalW) / 2 else w / 2 - (sel * (cardW + gap) + selW / 2)
        if (totalW > w - 96 * d) x0 = x0.coerceIn(w - 48 * d - totalW, 48 * d)

        // Titel
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(15f); p.color = Ui.EDIT; p.textAlign = Paint.Align.LEFT
        val titleX = if (totalW <= w - 96 * d) x0 else 48 * d
        c.drawText("★ Favoriten", titleX, baseY - selH - 14 * d, p)
        p.typeface = Typeface.DEFAULT; p.textSize = sp(12f); p.color = Ui.TEXT_DIM
        c.drawText("${sel + 1} / ${items.size}", titleX + 110 * d, baseY - selH - 14 * d, p)

        var x = x0
        var selX = 0f
        for ((i, ci) in items.withIndex()) {
            val isSel = i == sel
            val cw = if (isSel) selW else cardW
            if (isSel) selX = x else drawCard(c, ci, x, baseY - cardH, cw, cardH, false, now, d)
            x += cw + gap
        }
        drawCard(c, items[sel], selX, baseY - selH, selW, selH, true, now, d)

        // Tastenhinweis
        p.typeface = Typeface.DEFAULT; p.textSize = sp(11f); p.color = Ui.TEXT_DIM; p.textAlign = Paint.Align.CENTER
        c.drawText("◄ ►  wählen        OK  ansehen        OK lang  aus Favoriten entfernen        Zurück  schließen", w / 2, h - 18 * d, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawCard(c: Canvas, ci: Int, x: Float, y: Float, w: Float, h: Float, selected: Boolean, now: Long, d: Float) {
        val ch = channels.getOrNull(ci) ?: return
        rect.set(x, y, x + w, y + h)
        p.style = Paint.Style.FILL
        p.color = if (selected) Ui.ACCENT else 0xF2161E33.toInt()
        c.drawRoundRect(rect, 12 * d, 12 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (selected) 2 * d else 1 * d
        p.color = when { selected -> 0xE6FFFFFF.toInt(); ci == playingChannel -> Ui.ACCENT_LIGHT; else -> Ui.BORDER }
        c.drawRoundRect(rect, 12 * d, 12 * d, p)
        p.style = Paint.Style.FILL

        val padX = 12 * d
        val inner = w - 2 * padX
        // Nummer + Stern
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(if (selected) 13f else 12f); p.textAlign = Paint.Align.LEFT
        p.color = if (selected) 0xFFDCE8FF.toInt() else Ui.TEXT_DIM
        c.drawText((ci + 1).toString(), x + padX, y + 22 * d, p)
        p.color = Ui.EDIT; p.textAlign = Paint.Align.RIGHT
        c.drawText("★", x + w - padX, y + 22 * d, p)
        p.textAlign = Paint.Align.LEFT
        if (ci == playingChannel) {
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(10f); p.color = if (selected) Ui.TEXT else Ui.ACCENT_LIGHT
            c.drawText("● LÄUFT", x + padX + 30 * d, y + 21 * d, p)
        }
        // Name
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(if (selected) 16f else 14f); p.color = Ui.TEXT
        c.drawText(TextUtils.ellipsize(ch.name, p, inner, TextUtils.TruncateAt.END).toString(), x + padX, y + 44 * d, p)
        // laufende Sendung + Fortschritt
        val pr = nowProgramme(ch)
        if (pr != null) {
            p.typeface = Typeface.DEFAULT; p.textSize = sp(11f); p.color = if (selected) 0xFFDCE8FF.toInt() else Ui.TEXT_DIM
            c.drawText(TextUtils.ellipsize(pr.title, p, inner, TextUtils.TruncateAt.END).toString(), x + padX, y + 62 * d, p)
            val frac = ((now - pr.start).toFloat() / pr.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            bar.draw(c, p, rect, x + padX, y + h - 16 * d, inner, 3 * d, frac, if (selected) 0xFFFFFFFF.toInt() else Ui.ACCENT)
        } else {
            p.typeface = Typeface.DEFAULT; p.textSize = sp(11f); p.color = Ui.TEXT_DISABLED
            c.drawText("Keine Programminformation", x + padX, y + 62 * d, p)
        }
    }

    /** Kleiner Helfer zum Zeichnen eines Fortschrittsbalkens ohne neue Objekte pro Zeichenvorgang. */
    private class ProgressStripPainter {
        private val r = RectF()
        fun draw(c: Canvas, p: Paint, tmp: RectF, x: Float, y: Float, w: Float, h: Float, fraction: Float, fillColor: Int) {
            p.style = Paint.Style.FILL
            p.color = 0x44FFFFFF; r.set(x, y, x + w, y + h); c.drawRoundRect(r, h / 2, h / 2, p)
            if (fraction > 0f) { p.color = fillColor; r.set(x, y, x + w * fraction, y + h); c.drawRoundRect(r, h / 2, h / 2, p) }
        }
    }
}
