package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.TextView

/** Designsystem: dunkle, ruhige TV-Oberflaeche (Navy + Akzentblau). Alle Groessen in dp/sp fuer den 10-Fuss-Abstand. */
object Ui {
    val BG_PANEL = Color.parseColor("#F2101626")       // fast deckendes Panel
    val BG_PANEL_SOFT = Color.parseColor("#D90E1320")
    val BG_ROW_SELECTED = Color.parseColor("#2E7BFF")
    val BG_ROW_SELECTED_DIM = Color.parseColor("#33406A")
    val ACCENT = Color.parseColor("#2E7BFF")
    val ACCENT_LIGHT = Color.parseColor("#8FB6FF")
    val TEXT = Color.parseColor("#F2F4F8")
    val TEXT_DIM = Color.parseColor("#9AA3B5")
    val TEXT_DISABLED = Color.parseColor("#5C6577")
    val ERROR = Color.parseColor("#FF6B6B")

    // Bearbeiten-Modus (Eintrag "angeklickt"): warmes Amber hebt sich klar vom blauen "markiert" ab
    val EDIT = Color.parseColor("#FFB020")
    val EDIT_TEXT = Color.parseColor("#1A1300")
    val BG_ROW_EDIT = Color.parseColor("#1D2B4F")
    // Schalter
    val ON_BG = Color.parseColor("#1F6F4A")
    val ON_TEXT = Color.parseColor("#A6F2CB")
    val OFF_BG = Color.parseColor("#3A4156")
    val BORDER = Color.parseColor("#40FFFFFF")

    const val ANIM_MS = 180L
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun roundRect(color: Int, radiusPx: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

fun Context.label(sizeSp: Float, color: Int = Ui.TEXT, bold: Boolean = false, lines: Int = 1): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
}

/** Schwebende Karte: abgerundet, dunkel, mit feinem Rand – der Rand zeigt auch bei Overscan klar, wo das Panel endet. */
fun Context.cardBackground(radiusDp: Float = 14f): GradientDrawable = GradientDrawable().apply {
    setColor(Ui.BG_PANEL)
    cornerRadius = dpf(radiusDp)
    setStroke(dp(1), Ui.BORDER)
}

/**
 * Einblenden mit leichter Verschiebung (z. B. Panels von der Seite). Die Animation laeuft ueber eine Hardware-Ebene
 * (withLayer) und zeichnet am Ende alles komplett neu – so bleiben keine halb aktualisierten Raender stehen.
 */
fun View.showAnimated(fromX: Float = 0f, fromY: Float = 0f) {
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f; translationX = fromX; translationY = fromY
        visibility = View.VISIBLE
    }
    animate().alpha(1f).translationX(0f).translationY(0f).setDuration(Ui.ANIM_MS).setListener(null)
        .withLayer()
        .withEndAction { redrawFully() }
        .start()
}

fun View.hideAnimated(toX: Float = 0f, toY: Float = 0f) {
    if (visibility != View.VISIBLE) return
    val anim: ViewPropertyAnimator = animate()
    anim.cancel()
    anim.alpha(0f).translationX(toX).translationY(toY).setDuration(Ui.ANIM_MS)
        .withLayer()
        .withEndAction { visibility = View.GONE; alpha = 1f; translationX = 0f; translationY = 0f }.start()
}

/** Layout und Zeichnung des Views samt Eltern neu anstossen. */
fun View.redrawFully() {
    requestLayout()
    invalidate()
    (parent as? View)?.invalidate()
}

/** Duenner Rollbalken fuer Listen (die Systemleiste ist auf dem Fernseher kaum sichtbar). */
class ScrollIndicator(context: Context) : View(context) {
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val thumb = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT_LIGHT }
    private val r = android.graphics.RectF()
    private var first = 0f
    private var visible = 1f
    private var total = 1f

    /** @param firstVisible Position der ersten sichtbaren Zeile, [visibleCount] sichtbare Zeilen, [totalCount] alle Zeilen. */
    fun update(firstVisible: Int, visibleCount: Int, totalCount: Int) {
        first = firstVisible.toFloat(); visible = visibleCount.toFloat(); total = totalCount.toFloat()
        visibility = if (totalCount <= 0 || visibleCount >= totalCount) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (total <= 0f || h <= 0f) return
        r.set(0f, 0f, w, h); c.drawRoundRect(r, w / 2, w / 2, track)
        val thumbH = (h * (visible / total)).coerceIn(h * 0.08f, h)
        val maxFirst = (total - visible).coerceAtLeast(1f)
        val top = (h - thumbH) * (first / maxFirst).coerceIn(0f, 1f)
        r.set(0f, top, w, top + thumbH); c.drawRoundRect(r, w / 2, w / 2, thumb)
    }
}

/** Schmaler Fortschrittsbalken (z. B. Anteil der laufenden Sendung). */
class ProgressStrip(context: Context) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val r = android.graphics.RectF()
    override fun onDraw(c: android.graphics.Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, track)
        if (fraction > 0f) { r.set(0f, 0f, width * fraction, h); c.drawRoundRect(r, h / 2, h / 2, fill) }
    }
}
