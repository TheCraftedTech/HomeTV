package de.thecraftedtech.hometv

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.DatagramSocket
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Home TV – Prototyp: startet direkt "Das Erste HD" von der FRITZ!Box.
 *
 * Modus LOKAL (Standard): eigener SAT>IP-Client (Punch/Keep-Alive/TEARDOWN) -> Loopback-UDP -> LibVLC.
 * Modus DIREKT (Fallback): LibVLC oeffnet die rtsp://-Adresse selbst.
 * Bei ausbleibendem Bild wird einmal automatisch auf den jeweils anderen Modus gewechselt;
 * mit der MENU-Taste kann der Modus von Hand umgeschaltet werden.
 */
class MainActivity : Activity() {

    private enum class Mode(val label: String) { LOCAL("SAT>IP-Client + LibVLC"), DIRECT("LibVLC direkt (RTSP)") }
    private enum class UiState { LOADING, PLAYING, PAUSED, ERROR }

    private val tag = "HomeTV"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var loading: View
    private lateinit var status: TextView
    private lateinit var errorBox: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var overlay: TextView

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var session: SatIpSession? = null

    private var channel: Channel? = null
    private var mode = Mode.LOCAL
    private var triedOtherMode = false
    private var state = UiState.LOADING
    private var started = false
    private var generation = 0 // verwirft Rueckmeldungen veralteter Startversuche
    private var overlayVisible = false
    private var lastKey = "–"
    private var videoInfo = "–"
    private var playStartedAt = 0L
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var rateMbit = 0.0

    private val watchdog = Runnable { onNoPicture() }
    private var restartsAfterStall = 0

    /** Erkennt Datenstillstand waehrend der Wiedergabe (nur Modus LOKAL, dort sehen wir die RTP-Pakete). */
    private val stallChecker = object : Runnable {
        override fun run() {
            val s = session
            if (state == UiState.PLAYING && mode == Mode.LOCAL && s != null && s.lastPacketAtMs > 0 &&
                System.currentTimeMillis() - s.lastPacketAtMs > 8_000
            ) {
                Log.w(tag, "Datenstillstand > 8 s")
                if (restartsAfterStall < 2) {
                    restartsAfterStall++
                    val ch = channel
                    endPlayback()
                    channel = ch
                    beginPlayback(resetMode = false)
                } else {
                    showError("Stream unterbrochen", "Die FRITZ!Box sendet keine Daten mehr. OK: erneut versuchen.", generation)
                }
                return
            }
            main.postDelayed(this, 2_000)
        }
    }
    private val overlayTicker = object : Runnable {
        override fun run() {
            updateOverlay()
            if (overlayVisible) main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        videoLayout = findViewById(R.id.video)
        loading = findViewById(R.id.loading)
        status = findViewById(R.id.status)
        errorBox = findViewById(R.id.error)
        errorTitle = findViewById(R.id.errorTitle)
        errorText = findViewById(R.id.errorText)
        overlay = findViewById(R.id.overlay)
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        beginPlayback(resetMode = true)
    }

    override fun onStop() {
        endPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ------------------------------------------------------------------ Wiedergabe

    private fun beginPlayback(resetMode: Boolean) {
        if (started) return
        started = true
        if (resetMode) { mode = Mode.LOCAL; triedOtherMode = false; restartsAfterStall = 0 }
        val gen = ++generation
        setState(UiState.LOADING, "Lade Senderliste von ${BuildConfig.FRITZBOX_HOST} …")
        io.execute {
            val result = runCatching { channel ?: Playlist.findDasErste(BuildConfig.FRITZBOX_HOST) }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { ch ->
                    channel = ch
                    Log.i(tag, "Sender: ${ch.name}")
                    startMedia(ch, gen)
                }.onFailure { e ->
                    channel = null
                    showError("Keine Verbindung zur FRITZ!Box", e.message ?: e.toString(), gen)
                }
            }
        }
    }

    private fun startMedia(ch: Channel, gen: Int) {
        setState(UiState.LOADING, "${ch.name} · ${mode.label}")
        playStartedAt = System.currentTimeMillis()
        lastBytes = 0; lastBytesAt = 0; rateMbit = 0.0; videoInfo = "–"

        // -vv: ausfuehrliches VLC-Log in logcat (Tag "VLC"), u. a. zur Pruefung der Hardware-Dekodierung (mediacodec)
        val vlc = LibVLC(this, arrayListOf("-vv"))
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        player = mp
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { ev ->
            main.post { if (gen == generation) onPlayerEvent(ev) }
        }

        val media: Media
        when (mode) {
            Mode.LOCAL -> {
                val port = DatagramSocket(0).use { it.localPort }
                media = Media(vlc, Uri.parse("udp://@127.0.0.1:$port"))
                media.addOption(":network-caching=800")
                // Player zuerst starten (lauscht auf dem lokalen Port), dann erst die Box anfordern.
                media.setHWDecoderEnabled(true, false)
                mp.media = media
                media.release()
                mp.play()
                val s = SatIpSession(ch.url, port, object : SatIpSession.Listener {
                    override fun onState(text: String) { main.post { if (gen == generation && state == UiState.LOADING) status.text = text } }
                    override fun onStreaming() { Log.i(tag, "Erste RTP-Daten empfangen") }
                    override fun onError(text: String) { main.post { if (gen == generation) onSessionError(text, gen) } }
                })
                session = s
                s.start()
            }
            Mode.DIRECT -> {
                media = Media(vlc, Uri.parse(ch.url))
                media.addOption(":network-caching=1000")
                media.setHWDecoderEnabled(true, false)
                mp.media = media
                media.release()
                mp.play()
            }
        }
        main.postDelayed(watchdog, 15_000)
    }

    private fun onPlayerEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Vout -> if (ev.voutCount > 0) {
                main.removeCallbacks(watchdog)
                val t = player?.currentVideoTrack
                videoInfo = if (t != null) "${t.codec} ${t.width}x${t.height} " + (if (t.frameRateDen > 0) String.format(Locale.US, "%.0f fps", t.frameRateNum.toDouble() / t.frameRateDen) else "") else "–"
                if (state != UiState.PAUSED) {
                    setState(UiState.PLAYING)
                    main.removeCallbacks(stallChecker)
                    main.postDelayed(stallChecker, 4_000)
                }
                Log.i(tag, "Bild da nach ${System.currentTimeMillis() - playStartedAt} ms ($videoInfo)")
            }
            MediaPlayer.Event.EncounteredError -> {
                Log.e(tag, "LibVLC EncounteredError")
                onNoPicture(hard = true)
            }
            MediaPlayer.Event.EndReached -> {
                Log.w(tag, "LibVLC EndReached")
                if (state == UiState.PLAYING) showError("Wiedergabe beendet", "Der Stream wurde von der FRITZ!Box beendet.", generation)
            }
        }
    }

    private fun onSessionError(text: String, gen: Int) {
        // Fehler im SAT>IP-Client -> einmal Fallback auf direkten Modus, sonst Fehleranzeige
        if (mode == Mode.LOCAL && !triedOtherMode) {
            Log.w(tag, "SAT>IP-Client fehlgeschlagen ($text) – Fallback auf LibVLC direkt")
            switchMode(Mode.DIRECT, automatic = true)
        } else {
            showError("Stream nicht verfügbar", text, gen)
        }
    }

    private fun onNoPicture(hard: Boolean = false) {
        if (state == UiState.PLAYING || state == UiState.PAUSED) return
        val gen = generation
        if (!triedOtherMode) {
            Log.w(tag, "Kein Bild (${if (hard) "Fehler" else "Zeitüberschreitung"}) im Modus $mode – wechsle Modus")
            switchMode(if (mode == Mode.LOCAL) Mode.DIRECT else Mode.LOCAL, automatic = true)
        } else {
            val got = session?.packetsReceived?.get() ?: 0
            val detail = if (mode == Mode.LOCAL && got == 0L)
                "Von der FRITZ!Box kommen keine Daten an (UDP). Prüfe, ob Fire TV und FRITZ!Box im selben Netz sind und ob die Box gerade einen freien Tuner hat."
            else "Es konnte kein Videobild aufgebaut werden (Modus: ${mode.label}). Bitte später erneut versuchen."
            showError("Kein Bild", detail, gen)
        }
    }

    private fun switchMode(newMode: Mode, automatic: Boolean) {
        endPlayback()
        mode = newMode
        if (automatic) triedOtherMode = true
        beginPlayback(resetMode = false)
    }

    private fun endPlayback() {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(stallChecker)
        generation++
        started = false
        val s = session; session = null
        val mp = player; player = null
        val vlc = libVlc; libVlc = null
        runCatching { mp?.setEventListener(null) }
        // Zuerst RTSP sauber beenden (TEARDOWN), dann den Player freigeben.
        s?.stop(2000)
        runCatching { mp?.stop() }
        runCatching { mp?.detachViews() }
        runCatching { mp?.release() }
        runCatching { vlc?.release() }
    }

    // ------------------------------------------------------------------ UI

    private fun setState(s: UiState, text: String? = null) {
        state = s
        errorBox.visibility = if (s == UiState.ERROR) View.VISIBLE else View.GONE
        loading.visibility = if (s == UiState.LOADING) View.VISIBLE else View.GONE
        if (text != null) status.text = text
        updateOverlay()
    }

    private fun showError(title: String, text: String, gen: Int) {
        if (gen != generation) return
        Log.e(tag, "Fehler: $title – $text")
        endPlayback()
        errorTitle.text = title
        errorText.text = text
        setState(UiState.ERROR)
    }

    private fun updateOverlay() {
        if (!overlayVisible) { overlay.visibility = View.GONE; return }
        val s = session
        if (s != null) {
            val now = System.currentTimeMillis()
            val b = s.bytesReceived.get()
            if (lastBytesAt != 0L && now > lastBytesAt) rateMbit = (b - lastBytes) * 8.0 / ((now - lastBytesAt) * 1000.0)
            lastBytes = b; lastBytesAt = now
        }
        val net = if (s != null) String.format(Locale.US, "%.1f Mbit/s, %d Pakete, %d verloren", rateMbit, s.packetsReceived.get(), s.packetsLost.get()) else "–"
        overlay.text = buildString {
            append("Home TV 1.0.0\n")
            append("Sender : ${channel?.name ?: "–"}\n")
            append("Status : ${state.name.lowercase()}\n")
            append("Modus  : ${mode.label}\n")
            append("Video  : $videoInfo\n")
            append("Netz   : $net\n")
            append("Taste  : $lastKey")
        }
        overlay.visibility = View.VISIBLE
    }

    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        main.removeCallbacks(overlayTicker)
        updateOverlay()
        if (overlayVisible) main.post(overlayTicker)
    }

    // ------------------------------------------------------------------ Fernbedienung

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        lastKey = "${KeyEvent.keyCodeToString(keyCode)} ($keyCode)"
        Log.i(tag, "KeyDown $lastKey rep=${event.repeatCount}")
        if (event.repeatCount > 0) return true // gehaltene Tasten ignorieren
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (state == UiState.ERROR) { retry() } else toggleOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (state == UiState.PAUSED) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (state == UiState.PLAYING) togglePause(); return true }
            KeyEvent.KEYCODE_MENU -> {
                switchMode(if (mode == Mode.LOCAL) Mode.DIRECT else Mode.LOCAL, automatic = false)
                triedOtherMode = true
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (overlayVisible) toggleOverlay() else finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                updateOverlay(); return true // im Prototyp ohne Funktion (kein Senderwechsel)
            }
        }
        updateOverlay()
        return super.onKeyDown(keyCode, event)
    }

    private fun retry() {
        started = false
        channel = null
        triedOtherMode = false
        beginPlayback(resetMode = true)
    }

    private fun togglePause() {
        val mp = player ?: return
        when (state) {
            UiState.PLAYING -> {
                main.removeCallbacks(stallChecker)
                mp.pause()
                setState(UiState.PAUSED)
                if (!overlayVisible) toggleOverlay() // Overlay zeigt "paused"
            }
            UiState.PAUSED -> {
                // Live-TV: nach der Pause nicht am alten Puffer weiterspielen, sondern live neu aufsetzen.
                val ch = channel
                endPlayback()
                if (ch != null) { channel = ch; beginPlayback(resetMode = false) }
            }
            else -> Unit
        }
    }
}
