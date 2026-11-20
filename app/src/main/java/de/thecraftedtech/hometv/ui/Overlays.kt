package de.thecraftedtech.hometv.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import de.thecraftedtech.hometv.BuildConfig
import de.thecraftedtech.hometv.playback.DebugSnapshot
import java.util.Locale

/**
 * Technisches Debug-Overlay (links oben). Erscheint NUR bei aktivem Debug-Modus und dann per Menue-Taste (lang) –
 * nie durch normale Fernbedienungsaktionen. Alle Werte stammen aus der App bzw. aus LibVLC.
 */
class DebugOverlayView(context: Context) : TextView(context) {
    init {
        visibility = View.GONE
        typeface = Typeface.MONOSPACE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Ui.TEXT)
        setLineSpacing(context.dpf(3f), 1f)
        setBackgroundColor(0xB3000000.toInt())
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE

    fun update(s: DebugSnapshot, channelCount: Int, lastKey: String) {
        val l = Locale.US
        text = buildString {
            append("Home TV ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})  [Debug]\n")
            append("Sender    : ${s.channel?.let { "${s.index + 1}/$channelCount  ${it.name}" } ?: "–"}\n")
            append("Status    : ${s.state.name.lowercase()}   Bild nach: ${if (s.timeToPictureMs >= 0) "%.1f s".format(l, s.timeToPictureMs / 1000.0) else "–"}\n")
            append("Zeitleiste: ${s.timeline.ifEmpty { "–" }}\n")
            append("Modus     : ${s.transport.label}\n")
            append("Puffer    : ${s.cacheMs} ms   Audio-Versatz: ${"%+d".format(s.audioDelayMs)} ms\n")
            append("Video     : ${s.videoInfo}\n")
            append("Audio     : ${s.audioInfo}\n")
            append("Decoder   : ${s.decoderInfo}\n")
            s.net?.let { n ->
                append("Netz      : ${"%.1f".format(l, s.rateMbit)} Mbit/s, ${n.packets} RTP-Pakete, Jitter ${"%.1f".format(l, n.jitterMs)} ms\n")
                append("RTP       : verloren ${n.lost}, umsortiert ${n.reordered}, spät/doppelt ${n.lateOrDuplicate}\n")
                append("TS        : Fehler ${n.tsCcErrors} von ${n.tsPackets}, Sync ${n.tsBadSync}\n")
                append("UDP-Puffer: ${n.rcvBufBytes / 1024} KiB   Feed: ${n.feedQueued / 1024} KiB wartend, ${n.feedOverflow} B verworfen\n")
                append("Datenzeit : ${n.dataTimeMs} ms (PCR)   VLC-Zeit: ${s.vlcTimeMs} ms\n")
                append("Start-Gate: ${if (s.gateOpenedAfterMs >= 0) "offen nach ${s.gateOpenedAfterMs} ms, ${s.gateDroppedKiB} KiB verworfen" else "wartet / aus"}\n")
            } ?: append("Netz      : (direkter Modus – keine App-Messwerte)\n")
            append("VLC       : korrupt ${s.vlcCorrupt}, Diskont. ${s.vlcDiscont}, Bilder ${s.vlcShown} (verloren ${s.vlcLostPictures}), Audio verloren ${s.vlcLostAudio}\n")
            append("Taste     : $lastKey\n")
            append("[▲▼ Puffer  ◀▶ Audio-Versatz  Menü lang = schließen]")
        }
    }
}

/** Kleine Anzeige oben rechts fuer Lautstaerke/Stumm (nur wenn die Fernbedienung die Tasten an die App weitergibt). */
class VolumeIndicatorView(context: Context) : TextView(context) {
    init {
        visibility = View.GONE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(Ui.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        background = roundRect(Ui.BG_PANEL_SOFT, context.dpf(12f))
        setPadding(context.dp(22), context.dp(12), context.dp(22), context.dp(12))
    }

    fun showVolume(level: Int, max: Int, muted: Boolean) {
        val bars = 15
        val filled = if (max <= 0) 0 else (level * bars + max / 2) / max
        text = if (muted) "🔇  Stumm" else "🔊  " + "▮".repeat(filled) + "▯".repeat(bars - filled) + "  $level"
        showAnimated(fromY = -context.dpf(20f))
    }
}

/** Kurze Meldung unten mittig (z. B. "Programmführer aktualisiert"). */
class MessageView(context: Context) : TextView(context) {
    init {
        visibility = View.GONE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER
        background = roundRect(Ui.BG_PANEL_SOFT, context.dpf(12f))
        setPadding(context.dp(26), context.dp(14), context.dp(26), context.dp(14))
    }

    fun show(msg: String) { text = msg; showAnimated(fromY = context.dpf(20f)) }
    fun hide() = hideAnimated(toY = context.dpf(20f))
}
