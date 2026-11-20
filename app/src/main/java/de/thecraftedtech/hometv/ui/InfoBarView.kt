package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.epg.Programme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Schmaler Fortschrittsbalken (Anteil der laufenden Sendung). */
private class ProgressStrip(context: Context) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, track)
        if (fraction > 0f) { r.set(0f, 0f, width * fraction, h); c.drawRoundRect(r, h / 2, h / 2, fill) }
    }
}

/**
 * Info-Leiste am unteren Rand: Sendernummer + Name, laufende Sendung mit Zeit/Fortschritt/Beschreibung und die naechste Sendung.
 * Wird bei OK, beim Senderwechsel (sofort, noch vor dem Bild) und bei Statusmeldungen eingeblendet.
 */
class InfoBarView(context: Context) : FrameLayout(context) {

    private val number = context.label(28f, Ui.ACCENT_LIGHT, bold = true)
    private val name = context.label(20f, Ui.TEXT, bold = true)
    private val status = context.label(14f, Ui.TEXT_DIM)
    private val nowTitle = context.label(17f, Ui.TEXT, bold = true)
    private val nowTime = context.label(14f, Ui.TEXT_DIM)
    private val progress = ProgressStrip(context)
    private val desc = context.label(14f, Ui.TEXT_DIM, lines = 1)
    private val next = context.label(14f, Ui.TEXT_DIM)
    private val hint = context.label(14f, Ui.TEXT_DIM)
    private val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.GERMANY)

    private val nowBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        visibility = GONE
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(Ui.BG_PANEL_SOFT, context.dpf(10f))
            setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(number, LinearLayout.LayoutParams(context.dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = context.dp(10) })

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(head)

        val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(nowTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(nowTime, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(16) })
        nowBlock.addView(titleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) })
        nowBlock.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(3)).apply { topMargin = context.dp(5) })
        nowBlock.addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(5) })
        nowBlock.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(3) })
        col.addView(nowBlock)
        col.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
        panel.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Sicherheitsabstand zum Bildschirmrand (Overscan mancher Fernseher): unten/seitlich grosszuegig, Leiste selbst kompakt
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(context.dp(64), 0, context.dp(64), context.dp(44))
        })
    }

    /**
     * @param epgConfigured Programmfuehrer ist aktiv (sonst wird kein Programmbereich gezeigt)
     * @param statusText    z. B. "Lädt …", "Live", "Pausiert" (rechts neben dem Sendernamen)
     */
    fun bind(channel: Channel, num: Int, nowP: Programme?, nextP: Programme?, epgConfigured: Boolean, statusText: String, nowMs: Long) {
        number.text = num.toString()
        name.text = channel.name
        status.text = statusText
        if (!epgConfigured) {
            nowBlock.visibility = GONE; hint.visibility = GONE
            return
        }
        nowBlock.visibility = VISIBLE
        if (nowP == null) {
            nowTitle.text = "Keine Programminformation"; nowTitle.setTextColor(Ui.TEXT_DIM)
            nowTime.text = ""; progress.visibility = GONE; desc.visibility = GONE
        } else {
            nowTitle.text = nowP.title; nowTitle.setTextColor(Ui.TEXT)
            nowTime.text = "${timeFmt.format(Date(nowP.start))} – ${timeFmt.format(Date(nowP.end))}"
            progress.visibility = VISIBLE
            progress.fraction = ((nowMs - nowP.start).toFloat() / nowP.durationMs.coerceAtLeast(1))
            desc.visibility = if (nowP.desc.isNullOrBlank()) GONE else VISIBLE
            desc.text = nowP.desc.orEmpty()
        }
        if (nextP == null) next.visibility = GONE
        else { next.visibility = VISIBLE; next.text = "Danach  ${timeFmt.format(Date(nextP.start))}  ${nextP.title}" }
        hint.visibility = GONE
    }

    fun show() { showAnimated(fromY = context.dpf(40f)) }
    fun hide() { hideAnimated(toY = context.dpf(40f)) }
    val isShown2: Boolean get() = visibility == VISIBLE
}
