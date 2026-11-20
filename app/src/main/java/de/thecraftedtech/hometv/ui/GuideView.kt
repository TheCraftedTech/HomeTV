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
import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.epg.GuideNavigator
import de.thecraftedtech.hometv.epg.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Programmuebersicht im Vollbild (wie bei Fernsehern): links die Sender, rechts das Programm auf einer Zeitachse –
 * bereits Gelaufenes gedimmt, Laufendes mit Fortschritt, Kommendes normal; ein Strich markiert "jetzt".
 * Unten die Details der ausgewaehlten Sendung. Favoriten tragen einen Stern.
 *
 * Alles wird selbst gezeichnet (eine View statt hunderter Zellen). Die Auswahl steuert [GuideNavigator]; die Tasten verteilt die
 * Activity ueber [moveRow], [moveTime] und [jump].
 */
class GuideView(context: Context) : View(context) {

    var channels: List<Channel> = emptyList()
    /** Sendungen eines Senders, nach Beginn sortiert (leer = keine Daten). */
    var programmes: (Channel) -> Array<Programme> = { emptyArray() }
    var isFavorite: (Channel) -> Boolean = { false }
    /** Index (in [channels]) des laufenden Senders; -1 = keiner. */
    var playingChannel = -1
    /** Text, der statt "Keine Programminformation" erklaert, warum Daten fehlen (z. B. Programmfuehrer nicht eingerichtet). */
    var noDataHint = ""
    /** Beschriftung des Filters oben rechts, z. B. "Alle Sender" oder "★ Favoriten". */
    var filterLabel = "Alle Sender"

    private var rows: List<Int> = emptyList()
    private val nav = GuideNavigator({ rows.size }, { r -> rows.getOrNull(r)?.let { programmes(channels[it]) } ?: emptyArray() })
    private var firstRow = 0
    private var opened = false

    val isOpen: Boolean get() = opened
    /** Index (in [channels]) des ausgewaehlten Senders; -1 = keiner. */
    val selectedChannelIndex: Int get() = rows.getOrNull(nav.row) ?: -1

    // ---- Farben
    private val cBg = 0xF50B101C.toInt()
    private val cRowAlt = 0x14FFFFFF
    private val cPast = 0xFF151B2A.toInt()
    private val cFuture = 0xFF1C2842.toInt()
    private val cNow = 0xFF22407A.toInt()
    private val cNowDone = 0xFF2F58B0.toInt()
    private val cSelected = Ui.ACCENT
    private val cLine = 0xFFFF5A5F.toInt()
    private val cLive = 0xFF4CD08A.toInt()
    private val cPastText = 0xFF6E7788.toInt()
    private val cStar = Ui.EDIT

    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
    private val dayFmt = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY)
    private val dur = StringBuilder()

    private val ticker = object : Runnable {
        override fun run() { if (!opened) return; invalidate(); postDelayed(this, 30_000) }
    }

    init { visibility = GONE; setBackgroundColor(cBg) }

    // ------------------------------------------------------------------ Steuerung

    /** Zeilen (Indizes in [channels]) setzen. Bleibt [keepChannel] enthalten, bleibt er ausgewaehlt. */
    fun setRows(list: List<Int>, keepChannel: Int = selectedChannelIndex) {
        rows = list
        val idx = list.indexOf(keepChannel)
        nav.setRow(if (idx >= 0) idx else nav.row)
        if (opened) invalidate()
    }

    fun open(currentChannel: Int) {
        opened = true
        val idx = rows.indexOf(currentChannel).takeIf { it >= 0 } ?: 0
        nav.reset(idx, System.currentTimeMillis())
        firstRow = 0
        showAnimated()
        invalidate()
        removeCallbacks(ticker); postDelayed(ticker, 30_000)
    }

    fun close() { opened = false; removeCallbacks(ticker); hideAnimated() }

    fun forceClose() { opened = false; removeCallbacks(ticker); animate().cancel(); visibility = GONE; alpha = 1f }

    fun moveRow(d: Int) { if (nav.moveRow(d)) invalidate() }
    fun moveTime(d: Int) { if (nav.moveTime(d)) invalidate() }
    fun jump(ms: Long) { if (nav.jump(ms)) invalidate() }
    fun refresh() { if (opened) invalidate() }

    // ------------------------------------------------------------------ Zeichnen

    private fun d() = resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val d = d()
        val now = System.currentTimeMillis()

        val padX = 36 * d
        val top = 20 * d
        val headerH = 30 * d
        val rulerH = 22 * d
        val chanW = 150 * d
        val gridLeft = padX + chanW + 6 * d
        val gridRight = w - padX - 10 * d
        val rowsTop = top + headerH + rulerH
        val hintH = 16 * d
        val detailH = 86 * d
        val rowsBottom = h - 12 * d - hintH - 6 * d - detailH - 8 * d
        val rowH = 34 * d
        val visibleRows = ((rowsBottom - rowsTop) / rowH).toInt().coerceAtLeast(1)
        val span = nav.spanMs
        val ws = nav.windowStart
        val ppm = (gridRight - gridLeft) / span.toFloat()
        fun xOf(t: Long) = gridLeft + (t - ws) * ppm

        // Zeile in den sichtbaren Bereich scrollen
        if (nav.row < firstRow) firstRow = nav.row
        if (nav.row >= firstRow + visibleRows) firstRow = nav.row - visibleRows + 1
        firstRow = firstRow.coerceIn(0, (rows.size - visibleRows).coerceAtLeast(0))

        // ---- Kopf: Titel, Tag, Filter, Uhr
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = sp(18f); p.color = Ui.TEXT; p.textAlign = Paint.Align.LEFT
        c.drawText("Programmübersicht", padX, top + 20 * d, p)
        val sel = nav.selected()
        p.typeface = Typeface.DEFAULT; p.textSize = sp(14f); p.color = Ui.ACCENT_LIGHT
        c.drawText(dayFmt.format(Date(sel?.start ?: nav.anchor)), padX + 190 * d, top + 19 * d, p)
        p.textAlign = Paint.Align.RIGHT
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(15f); p.color = Ui.TEXT
        c.drawText(timeFmt.format(Date(now)), w - padX, top + 20 * d, p)
        val clockW = p.measureText("00:00") + 18 * d
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(12f)
        val fav = filterLabel.startsWith("★")
        p.color = if (fav) cStar else Ui.TEXT_DIM
        c.drawText(filterLabel, w - padX - clockW, top + 19 * d, p)
        p.textAlign = Paint.Align.LEFT

        // ---- Zeitleiste
        val rulerY = top + headerH
        p.typeface = Typeface.DEFAULT; p.textSize = sp(11f)
        var t = ws + (30 * GuideNavigator.MIN_MS - Math.floorMod(ws, 30 * GuideNavigator.MIN_MS)) % (30 * GuideNavigator.MIN_MS)
        while (t < ws + span) {
            val x = xOf(t)
            val isHour = Math.floorMod(t, GuideNavigator.HOUR_MS) == 0L
            p.color = if (isHour) 0x30FFFFFF else 0x14FFFFFF
            p.strokeWidth = 1f
            c.drawRect(x, rulerY + 14 * d, x + 1f, rowsTop + visibleRows.coerceAtMost(rows.size) * rowH, p)
            val label = timeFmt.format(Date(t))
            p.color = if (isHour) Ui.TEXT else Ui.TEXT_DIM
            if (x + p.measureText(label) + 4 * d < gridRight) c.drawText(label, x + 4 * d, rulerY + 12 * d, p)
            t += 30 * GuideNavigator.MIN_MS
        }

        // ---- Zeilen
        val shownRows = rows.size.coerceAtMost(visibleRows)
        for (i in 0 until shownRows) {
            val r = firstRow + i
            val ci = rows.getOrNull(r) ?: break
            val ch = channels.getOrNull(ci) ?: continue
            val y = rowsTop + i * rowH
            val selectedRow = r == nav.row

            if (i % 2 == 1) { p.color = cRowAlt; c.drawRect(padX, y, gridRight, y + rowH, p) }
            if (selectedRow) {
                p.color = Ui.BG_ROW_SELECTED_DIM
                rect.set(padX, y + 1 * d, padX + chanW, y + rowH - 1 * d); c.drawRoundRect(rect, 6 * d, 6 * d, p)
            }
            if (ci == playingChannel) { p.color = Ui.ACCENT; c.drawRect(padX, y + 4 * d, padX + 3 * d, y + rowH - 4 * d, p) }

            // Nummer, Stern, Name
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(11f); p.color = Ui.TEXT_DIM
            p.textAlign = Paint.Align.RIGHT
            c.drawText((ci + 1).toString(), padX + 30 * d, y + rowH / 2 + 4 * d, p)
            p.textAlign = Paint.Align.LEFT
            var nameX = padX + 36 * d
            if (isFavorite(ch)) {
                p.color = cStar; p.textSize = sp(13f)
                c.drawText("★", nameX, y + rowH / 2 + 5 * d, p)
                nameX += 16 * d
            }
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(13f); p.color = if (selectedRow) Ui.TEXT else 0xFFD5DAE6.toInt()
            c.drawText(TextUtils.ellipsize(ch.name, p, padX + chanW - nameX - 4 * d, TextUtils.TruncateAt.END).toString(), nameX, y + rowH / 2 + 5 * d, p)

            // Sendungen
            val list = programmes(ch)
            var any = false
            for (pr in list) {
                if (pr.end <= ws) continue
                if (pr.start >= ws + span) break
                any = true
                drawCell(c, pr, xOf(maxOf(pr.start, ws)), xOf(minOf(pr.end, ws + span)), y, rowH, d, now, selectedRow && sel === pr, ::xOf)
            }
            if (!any) {
                // Keine Daten fuer diesen Ausschnitt: ganze Breite als Platzhalter
                val isSel = selectedRow
                rect.set(gridLeft + 1 * d, y + 2 * d, gridRight, y + rowH - 2 * d)
                p.style = Paint.Style.FILL; p.color = if (isSel) 0x332E7BFF else 0x0DFFFFFF; c.drawRoundRect(rect, 5 * d, 5 * d, p)
                if (isSel) strokeRect(c, rect, d, 0xCCFFFFFF.toInt())
                p.typeface = Typeface.DEFAULT; p.textSize = sp(12f); p.color = Ui.TEXT_DISABLED
                c.drawText(if (noDataHint.isNotEmpty()) "–" else "Keine Programminformation", gridLeft + 8 * d, y + rowH / 2 + 4 * d, p)
            }
        }

        // ---- "Jetzt"-Linie
        if (now in ws..(ws + span)) {
            val x = xOf(now)
            p.style = Paint.Style.FILL; p.color = cLine
            c.drawRect(x - 1 * d, rulerY + 14 * d, x + 1 * d, rowsTop + shownRows * rowH, p)
            path.reset(); path.moveTo(x - 5 * d, rulerY + 9 * d); path.lineTo(x + 5 * d, rulerY + 9 * d); path.lineTo(x, rulerY + 16 * d); path.close()
            c.drawPath(path, p)
        }

        // ---- Rollbalken (Zeilen)
        if (rows.size > visibleRows) {
            val trackTop = rowsTop; val trackH = shownRows * rowH
            p.color = 0x22FFFFFF; rect.set(w - padX + 2 * d, trackTop, w - padX + 5 * d, trackTop + trackH); c.drawRoundRect(rect, 2 * d, 2 * d, p)
            val th = (trackH * visibleRows / rows.size).coerceAtLeast(16 * d)
            val ty = trackTop + (trackH - th) * (firstRow.toFloat() / (rows.size - visibleRows).coerceAtLeast(1))
            p.color = Ui.ACCENT_LIGHT; rect.set(w - padX + 2 * d, ty, w - padX + 5 * d, ty + th); c.drawRoundRect(rect, 2 * d, 2 * d, p)
        }

        drawDetail(c, w, h, d, now, sel, padX, h - 12 * d - hintH - 6 * d - detailH, detailH)

        // ---- Tastenhinweis
        p.typeface = Typeface.DEFAULT; p.textSize = sp(11f); p.color = Ui.TEXT_DIM; p.textAlign = Paint.Align.CENTER
        c.drawText("▲▼ Sender    ◄► Sendung    ⏪⏩ ±3 h    OK ansehen    OK lang ★ Favorit    Menü Filter    Zurück schließen", w / 2, h - 12 * d, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawCell(c: Canvas, pr: Programme, x1: Float, x2: Float, y: Float, rowH: Float, d: Float, now: Long, selected: Boolean, xOf: (Long) -> Float) {
        val l = x1 + 1 * d; val r = x2 - 1 * d
        if (r - l < 3 * d) return
        rect.set(l, y + 2 * d, r, y + rowH - 2 * d)
        val past = pr.end <= now
        val live = pr.start <= now && now < pr.end
        p.style = Paint.Style.FILL
        p.color = when { selected -> cSelected; past -> cPast; live -> cNow; else -> cFuture }
        c.drawRoundRect(rect, 5 * d, 5 * d, p)
        if (live && !selected) {
            // Fortschritt der laufenden Sendung als hellere Flaeche
            val px = xOf(now).coerceIn(l, r)
            if (px > l + 2 * d) {
                c.save(); c.clipRect(l, rect.top, px, rect.bottom)
                p.color = cNowDone; c.drawRoundRect(rect, 5 * d, 5 * d, p)
                c.restore()
            }
        }
        if (selected) strokeRect(c, rect, d, 0xE6FFFFFF.toInt())

        val availW = r - l - 12 * d
        if (availW > 24 * d) {
            p.style = Paint.Style.FILL
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(12f)
            p.color = if (selected) Ui.TEXT else if (past) cPastText else Ui.TEXT
            c.drawText(TextUtils.ellipsize(pr.title, p, availW, TextUtils.TruncateAt.END).toString(), l + 7 * d, rect.top + 14 * d, p)
            if (availW > 80 * d) {
                p.typeface = Typeface.DEFAULT; p.textSize = sp(10f)
                p.color = if (selected) 0xFFDCE8FF.toInt() else if (past) 0xFF566074.toInt() else Ui.TEXT_DIM
                c.drawText("${timeFmt.format(Date(pr.start))} – ${timeFmt.format(Date(pr.end))}", l + 7 * d, rect.top + 26 * d, p)
            }
        }
    }

    private fun strokeRect(c: Canvas, r: RectF, d: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = color
        c.drawRoundRect(r, 5 * d, 5 * d, p)
        p.style = Paint.Style.FILL
    }

    private fun drawDetail(c: Canvas, w: Float, h: Float, d: Float, now: Long, sel: Programme?, padX: Float, y: Float, hh: Float) {
        rect.set(padX, y, w - padX, y + hh)
        p.style = Paint.Style.FILL; p.color = 0xFF121A2E.toInt(); c.drawRoundRect(rect, 10 * d, 10 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d; p.color = Ui.BORDER; c.drawRoundRect(rect, 10 * d, 10 * d, p)
        p.style = Paint.Style.FILL

        val ci = selectedChannelIndex
        val ch = channels.getOrNull(ci)
        val x = padX + 16 * d
        val textW = w - 2 * padX - 32 * d
        p.textAlign = Paint.Align.LEFT

        // Zeile 1: Sender + Status
        if (ch != null) {
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(12f); p.color = Ui.ACCENT_LIGHT
            val star = if (isFavorite(ch)) "★ " else ""
            c.drawText("${ci + 1}  $star${ch.name}", x, y + 18 * d, p)
        }
        if (sel != null) {
            val (tag, color) = when {
                sel.end <= now -> "BEREITS GELAUFEN" to cPastText
                sel.start <= now -> "LÄUFT · ${remainingText(sel.end - now).ifEmpty { "gleich Ende" }}" to cLive
                else -> "BEGINNT UM ${timeFmt.format(Date(sel.start))}" to Ui.ACCENT_LIGHT
            }
            p.textAlign = Paint.Align.RIGHT; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(11f); p.color = color
            c.drawText(tag, w - padX - 16 * d, y + 18 * d, p)
            p.textAlign = Paint.Align.LEFT

            // Zeile 2: Titel + Zeit
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(16f); p.color = Ui.TEXT
            c.drawText(TextUtils.ellipsize(sel.title, p, textW, TextUtils.TruncateAt.END).toString(), x, y + 40 * d, p)
            p.typeface = Typeface.DEFAULT; p.textSize = sp(12f); p.color = Ui.TEXT_DIM
            dur.setLength(0)
            dur.append(timeFmt.format(Date(sel.start))).append(" – ").append(timeFmt.format(Date(sel.end)))
                .append("  ·  ").append(((sel.end - sel.start) / 60_000).toInt()).append(" min")
            c.drawText(dur.toString(), x, y + 54 * d, p)
            // Beschreibung (max. 2 Zeilen) rechts neben der Zeitangabe
            val desc = sel.desc.orEmpty()
            if (desc.isNotBlank()) drawWrapped(c, desc, x + 160 * d, y + 54 * d, textW - 160 * d, 2, 14 * d)
        } else {
            p.typeface = Typeface.DEFAULT; p.textSize = sp(14f); p.color = Ui.TEXT_DIM
            val msg = noDataHint.ifEmpty { "Für diesen Zeitpunkt liegt keine Programminformation vor." }
            c.drawText(TextUtils.ellipsize(msg, p, textW, TextUtils.TruncateAt.END).toString(), x, y + 44 * d, p)
        }
    }

    /** Zeichnet [text] ab ([x], erste Grundlinie [y]) umgebrochen in hoechstens [maxLines] Zeilen (letzte Zeile mit "…"). */
    private fun drawWrapped(c: Canvas, text: String, x: Float, y: Float, width: Float, maxLines: Int, lineH: Float) {
        p.typeface = Typeface.DEFAULT; p.textSize = sp(12f); p.color = 0xFFC4CBDA.toInt()
        var start = 0
        var line = 0
        var yy = y
        while (start < text.length && line < maxLines) {
            val last = line == maxLines - 1
            val count = p.breakText(text, start, text.length, true, width, null)
            if (count <= 0) break
            var end = start + count
            if (end < text.length && !last) {
                val space = text.lastIndexOf(' ', end - 1)
                if (space > start) end = space + 1
            }
            val shown = if (last && end < text.length) TextUtils.ellipsize(text.substring(start), p, width, TextUtils.TruncateAt.END).toString()
                        else text.substring(start, end).trimEnd()
            c.drawText(shown, x, yy, p)
            start = end
            yy += lineH
            line++
        }
    }
}
