package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import de.thecraftedtech.hometv.core.ListNav

/**
 * Anleitung im Vollbild: pro Seite eine Gruppe (Fernsehbild, Senderliste, ...) mit **selbst gezeichneten Tastensymbolen**
 * (Pfeile, OK, Zurueck, Menue, Play/Pause, Vor-/Ruecklauf, CH+/CH-) und was sie bewirken. Gezeichnet statt Schrift-Symbole,
 * damit es auf jedem Fire TV gleich aussieht. ◄► blaettert, Zurueck schliesst.
 */
class HelpView(context: Context) : View(context) {

    private var pages: List<HelpPage> = emptyList()
    private var page = 0
    private var opened = false
    val isOpen: Boolean get() = opened

    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    init { visibility = GONE; setBackgroundColor(0xF50B101C.toInt()) }

    fun open(content: List<HelpPage>) {
        pages = content; page = 0; opened = true
        showAnimated(); invalidate()
    }

    fun close() { opened = false; hideAnimated() }
    fun forceClose() { opened = false; animate().cancel(); visibility = GONE; alpha = 1f }

    fun movePage(d: Int) {
        if (pages.isEmpty()) return
        page = ListNav.move(page, d, pages.size, wrap = true)
        invalidate()
    }

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        val pg = pages.getOrNull(page) ?: return
        val d = resources.displayMetrics.density
        val padX = 48 * d
        val top = 24 * d

        // Kopf
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(20f); p.color = Ui.TEXT
        c.drawText("Anleitung – Tastenbelegung", padX, top + 22 * d, p)
        p.textAlign = Paint.Align.RIGHT; p.textSize = sp(14f); p.color = Ui.TEXT_DIM; p.typeface = Typeface.DEFAULT
        c.drawText("${page + 1} / ${pages.size}", w - padX, top + 20 * d, p)

        // Seiten-Reiter
        var tx = padX
        val tabY = top + 56 * d
        p.textAlign = Paint.Align.LEFT; p.textSize = sp(14f)
        for ((i, pageItem) in pages.withIndex()) {
            val sel = i == page
            p.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val tw = p.measureText(pageItem.title)
            if (sel) { p.color = Ui.BG_ROW_SELECTED; rect.set(tx - 10 * d, tabY - 18 * d, tx + tw + 10 * d, tabY + 8 * d); c.drawRoundRect(rect, 13 * d, 13 * d, p) }
            p.color = if (sel) Ui.TEXT else Ui.TEXT_DIM
            c.drawText(pageItem.title, tx, tabY, p)
            tx += tw + 34 * d
        }

        // Zeilen
        val rowsTop = tabY + 26 * d
        val rowH = 36 * d
        val iconW = 150 * d
        val textX = padX + iconW + 16 * d
        for ((i, row) in pg.rows.withIndex()) {
            val y = rowsTop + i * rowH
            if (i % 2 == 0) { p.color = 0x10FFFFFF; rect.set(padX - 12 * d, y, w - padX + 12 * d, y + rowH - 2 * d); c.drawRoundRect(rect, 8 * d, 8 * d, p) }
            var x = padX
            val cy = y + (rowH - 2 * d) / 2
            for (chip in row.keys) { x += drawChip(c, chip, x, cy, d) + 8 * d }
            p.typeface = Typeface.DEFAULT; p.textSize = sp(15f); p.color = Ui.TEXT; p.textAlign = Paint.Align.LEFT
            c.drawText(TextUtils.ellipsize(row.text, p, w - textX - padX, TextUtils.TruncateAt.END).toString(), textX, cy + 5 * d, p)
        }

        // Fuss
        p.typeface = Typeface.DEFAULT; p.textSize = sp(12f); p.color = Ui.TEXT_DIM; p.textAlign = Paint.Align.CENTER
        c.drawText("◄ ►  Seite wechseln        Zurück  schließen", w / 2, h - 20 * d, p)
        p.textAlign = Paint.Align.LEFT
    }

    /** Zeichnet ein Tastensymbol mit linkem Rand [x] und Mitte [cy]; gibt die belegte Breite zurueck. */
    private fun drawChip(c: Canvas, chip: KeyChip, x: Float, cy: Float, d: Float): Float {
        val r = 13 * d
        val wide = chip.key == RemoteKey.CH_UP || chip.key == RemoteKey.CH_DOWN
        val wChip = if (wide) 40 * d else 2 * r
        val cx = x + wChip / 2

        // Grundform
        p.style = Paint.Style.FILL
        p.color = if (chip.key == RemoteKey.OK) Ui.ACCENT else 0xFF2A3452.toInt()
        if (wide) { rect.set(x, cy - r, x + wChip, cy + r); c.drawRoundRect(rect, r, r, p) } else c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d; p.color = 0x55FFFFFF
        if (wide) c.drawRoundRect(rect, r, r, p) else c.drawCircle(cx, cy, r, p)

        // Symbol
        p.style = Paint.Style.FILL; p.color = Ui.TEXT
        val t = 0.42f * r
        when (chip.key) {
            RemoteKey.UP -> triangle(c, cx, cy, t, 0f)
            RemoteKey.RIGHT -> triangle(c, cx, cy, t, 90f)
            RemoteKey.DOWN -> triangle(c, cx, cy, t, 180f)
            RemoteKey.LEFT -> triangle(c, cx, cy, t, 270f)
            RemoteKey.OK -> {
                p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(11f); p.textAlign = Paint.Align.CENTER
                c.drawText("OK", cx, cy + 4 * d, p)
            }
            RemoteKey.BACK -> {
                // Pfeil "zurueck": Schaft rechts hoch, dann nach links, Spitze links
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                path.reset()
                path.moveTo(cx + 0.45f * r, cy + 0.5f * r); path.lineTo(cx + 0.45f * r, cy - 0.1f * r); path.lineTo(cx - 0.3f * r, cy - 0.1f * r)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(cx - 0.05f * r, cy - 0.45f * r); path.lineTo(cx - 0.5f * r, cy - 0.1f * r); path.lineTo(cx - 0.05f * r, cy + 0.25f * r)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            }
            RemoteKey.MENU -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.strokeCap = Paint.Cap.ROUND
                for (k in -1..1) c.drawLine(cx - 0.5f * r, cy + k * 0.38f * r, cx + 0.5f * r, cy + k * 0.38f * r, p)
                p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            }
            RemoteKey.PLAY_PAUSE -> {
                sideTriangle(c, cx - 0.32f * r, cy, 0.4f * r, +1)
                c.drawRect(cx + 0.18f * r, cy - 0.38f * r, cx + 0.34f * r, cy + 0.38f * r, p)
                c.drawRect(cx + 0.5f * r, cy - 0.38f * r, cx + 0.66f * r, cy + 0.38f * r, p)
            }
            RemoteKey.FF -> { sideTriangle(c, cx - 0.25f * r, cy, 0.4f * r, +1); sideTriangle(c, cx + 0.2f * r, cy, 0.4f * r, +1) }
            RemoteKey.REW -> { sideTriangle(c, cx + 0.25f * r, cy, 0.4f * r, -1); sideTriangle(c, cx - 0.2f * r, cy, 0.4f * r, -1) }
            RemoteKey.CH_UP, RemoteKey.CH_DOWN -> {
                p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(10f); p.textAlign = Paint.Align.CENTER
                c.drawText("CH", cx - 7 * d, cy + 4 * d, p)
                triangle(c, cx + 11 * d, cy, 0.32f * r, if (chip.key == RemoteKey.CH_UP) 0f else 180f)
            }
        }

        var used = wChip
        if (chip.long) {
            // Halten: amberfarbener Ring + Beschriftung "lang"
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = Ui.EDIT
            if (wide) { rect.set(x - 2 * d, cy - r - 2 * d, x + wChip + 2 * d, cy + r + 2 * d); c.drawRoundRect(rect, r + 2 * d, r + 2 * d, p) }
            else c.drawCircle(cx, cy, r + 2.5f * d, p)
            p.style = Paint.Style.FILL; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(11f); p.textAlign = Paint.Align.LEFT
            c.drawText("lang", x + wChip + 6 * d, cy + 4 * d, p)
            used += 6 * d + p.measureText("lang")
        }
        p.textAlign = Paint.Align.LEFT
        return used
    }

    /** Gleichschenkliges Dreieck mit Spitze nach oben, um [deg] Grad um ([cx],[cy]) gedreht. */
    private fun triangle(c: Canvas, cx: Float, cy: Float, t: Float, deg: Float) {
        c.save(); c.rotate(deg, cx, cy)
        path.reset(); path.moveTo(cx, cy - t); path.lineTo(cx - t, cy + 0.7f * t); path.lineTo(cx + t, cy + 0.7f * t); path.close()
        p.style = Paint.Style.FILL; c.drawPath(path, p)
        c.restore()
    }

    /** Dreieck, das nach rechts ([dir] = 1) oder links (-1) zeigt; [cx] ist die Mitte. */
    private fun sideTriangle(c: Canvas, cx: Float, cy: Float, t: Float, dir: Int) {
        path.reset()
        path.moveTo(cx + dir * t, cy); path.lineTo(cx - dir * t * 0.8f, cy - t); path.lineTo(cx - dir * t * 0.8f, cy + t); path.close()
        p.style = Paint.Style.FILL; c.drawPath(path, p)
    }
}
