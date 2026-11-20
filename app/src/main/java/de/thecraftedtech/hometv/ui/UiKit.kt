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

/** Einfaches Ein-/Ausblenden mit leichter Verschiebung (z. B. Panels von der Seite). */
fun View.showAnimated(fromX: Float = 0f, fromY: Float = 0f) {
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f; translationX = fromX; translationY = fromY
        visibility = View.VISIBLE
    }
    animate().alpha(1f).translationX(0f).translationY(0f).setDuration(Ui.ANIM_MS).setListener(null).start()
}

fun View.hideAnimated(toX: Float = 0f, toY: Float = 0f) {
    if (visibility != View.VISIBLE) return
    val anim: ViewPropertyAnimator = animate()
    anim.cancel()
    anim.alpha(0f).translationX(toX).translationY(toY).setDuration(Ui.ANIM_MS)
        .withEndAction { visibility = View.GONE; alpha = 1f; translationX = 0f; translationY = 0f }.start()
}
