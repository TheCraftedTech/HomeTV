package de.thecraftedtech.hometv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Home TV: Live-TV von der FRITZ!Box (SAT>IP) auf dem Fire TV.
 *
 * Wiedergabe: eigener SAT>IP-Client (Punch, Keep-Alive, TEARDOWN, Resequenzierung) -> lokaler Loopback-Feed
 * (TCP verlustfrei mit Rueckstau, alternativ UDP) -> LibVLC. Zusaetzlich "direkt": LibVLC oeffnet rtsp:// selbst.
 * Senderwechsel: CH+ / CH- (Keycode 166 / 167) – die alte Session wird per TEARDOWN beendet, erst dann beginnt die neue.
 */
class MainActivity : Activity() {

    private enum class UiState { LOADING, PLAYING, PAUSED, ERROR }

    private val tag = "HomeTV"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hometv-io") }

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var loading: View
    private lateinit var status: TextView
    private lateinit var errorBox: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var overlay: TextView
    private lateinit var banner: TextView
    private lateinit var settings: Settings

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var session: SatIpSession? = null
    private var feed: LocalFeed? = null

    private var channels: List<Channel> = emptyList()
    private var channelSource = "–"
    private var index = 0
    private var transport = Transport.TCP
    private val triedTransports = HashSet<Transport>()
    private var everPlayed = false
    private var state = UiState.LOADING
    private var generation = 0
    private var started = false
    private var overlayVisible = false
    private var requestedChannel: String? = null

    // Messwerte fuer Overlay/Log
    private var lastKey = "–"
    private var videoInfo = "–"
    private var audioInfo = "–"
    private var decoderInfo = "noch nicht ermittelt"
    private var tuneStartedAt = 0L
    private var timeToPictureMs = -1L
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var rateMbit = 0.0
    private var maxGap10s = 0L
    private var restartsAfterStall = 0

    private val watchdog = Runnable { onNoPicture(hard = false) }
    private val bannerHider = Runnable { banner.visibility = View.GONE }

    private val overlayTicker = object : Runnable {
        override fun run() {
            updateOverlay()
            if (overlayVisible) main.postDelayed(this, 1000)
        }
    }

    /** Alle 10 s: Messwerte ins Log (die Datei ueberlebt, der logcat-Puffer nicht). */
    private val statsLogger = object : Runnable {
        override fun run() {
            if (state == UiState.PLAYING) logStats()
            main.postDelayed(this, 10_000)
        }
    }

    /** Datenstillstand waehrend der Wiedergabe (nur SAT>IP-Client-Modi): bis zu 2x neu starten, dann Fehler. */
    private val stallChecker = object : Runnable {
        override fun run() {
            val s = session
            if (state == UiState.PLAYING && s != null && s.lastPacketAgoMs > 8_000) {
                AppLog.w(tag, "Datenstillstand > 8 s")
                if (restartsAfterStall < 2) { restartsAfterStall++; tune(index) }
                else showError("Stream unterbrochen", "Die FRITZ!Box sendet keine Daten mehr.", generation)
                return
            }
            main.postDelayed(this, 2_000)
        }
    }

    // ------------------------------------------------------------------ Lebenszyklus

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
        banner = findViewById(R.id.banner)
        AppLog.init(this)
        settings = Settings(this)
        handleIntent(intent)
        transport = settings.transport
        hideSystemUi()
        AppLog.i(tag, "Start: Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Modus=${transport.key}, Puffer=${settings.cacheMs} ms, Audio-Versatz=${settings.audioDelayMs} ms")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        transport = settings.transport
        if (started && channels.isNotEmpty()) {
            requestedChannel?.let { name -> resolveChannel(name)?.let { index = it } }
            tune(index)
        }
    }

    private fun handleIntent(intent: Intent?) {
        settings.applyIntent(intent)
        requestedChannel = intent?.getStringExtra("channel")
    }

    private fun resolveChannel(spec: String): Int? {
        spec.toIntOrNull()?.let { n -> if (n in 1..channels.size) return n - 1 }
        return Playlist.indexOfName(channels, spec).takeIf { it >= 0 }
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
        begin()
    }

    override fun onStop() {
        AppLog.i(tag, "onStop – beende Wiedergabe")
        stopPlayback()
        releasePlayer()
        main.removeCallbacksAndMessages(null)
        started = false
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

    // ------------------------------------------------------------------ Player

    private fun createPlayer() {
        if (player != null) return
        // -vv: ausfuehrliches VLC-Log (logcat Tag "VLC"); die App liest daraus den Decoder (mediacodec/avcodec)
        val vlc = LibVLC(this, arrayListOf("-vv"))
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { ev -> val g = generation; main.post { if (g == generation) onPlayerEvent(ev) } }
        player = mp
    }

    private fun releasePlayer() {
        val mp = player; player = null
        val vlc = libVlc; libVlc = null
        runCatching { mp?.setEventListener(null) }
        runCatching { mp?.stop() }
        runCatching { mp?.detachViews() }
        runCatching { mp?.release() }
        runCatching { vlc?.release() }
    }

    // ------------------------------------------------------------------ Start / Senderwechsel

    private fun begin() {
        if (started) return
        started = true
        triedTransports.clear()
        restartsAfterStall = 0
        val gen = ++generation
        if (channels.isNotEmpty()) { tune(index); return }
        setState(UiState.LOADING, "Lade Senderliste von ${BuildConfig.FRITZBOX_HOST} …")
        io.execute {
            val r = runCatching { Playlist.load(this, BuildConfig.FRITZBOX_HOST) }
            main.post {
                if (gen != generation) return@post
                r.onSuccess { loaded ->
                    channels = loaded.channels
                    channelSource = loaded.source
                    index = requestedChannel?.let { resolveChannel(it) } ?: Playlist.defaultIndex(channels)
                    AppLog.i(tag, "Senderliste: ${channels.size} Sender (Quelle: $channelSource), Start: ${index + 1} ${channels[index].name}")
                    tune(index)
                }.onFailure { e ->
                    showError("Keine Senderliste", e.message ?: e.toString(), gen)
                }
            }
        }
    }

    /**
     * Wechselt auf Sender [newIndex] (mit Umlauf). Reihenfolge: Player stoppen -> alte SAT>IP-Session per TEARDOWN beenden
     * (im Hintergrund-Thread) -> erst danach die neue Session starten. Veraltete Wechsel brechen ab (keine parallelen Streams).
     */
    private fun tune(newIndex: Int) {
        if (channels.isEmpty()) return
        index = Math.floorMod(newIndex, channels.size)
        val gen = ++generation
        main.removeCallbacks(watchdog); main.removeCallbacks(stallChecker)
        timeToPictureMs = -1; videoInfo = "–"; audioInfo = "–"; decoderInfo = "noch nicht ermittelt"
        lastBytes = 0; lastBytesAt = 0; rateMbit = 0.0
        tuneStartedAt = System.currentTimeMillis()
        val ch = channels[index]
        setState(UiState.LOADING, "${index + 1}  ${ch.name}")
        showBanner(ch)
        AppLog.i(tag, "Sender ${index + 1}/${channels.size}: ${ch.name} (Modus ${transport.key}, Puffer ${settings.cacheMs} ms)")

        val oldSession = session; session = null
        val oldFeed = feed; feed = null
        runCatching { player?.stop() }
        io.execute {
            oldSession?.stop(2000)               // TEARDOWN, bevor etwas Neues startet
            runCatching { oldFeed?.close() }
            main.post { if (gen == generation) startMedia(gen) }
        }
    }

    private fun startMedia(gen: Int) {
        val ch = channels[index]
        val vlc = libVlc ?: return
        val mp = player ?: return
        val cache = settings.cacheMs
        val media: Media
        if (transport == Transport.DIRECT) {
            media = Media(vlc, Uri.parse(ch.url))
            media.addOption(":network-caching=$cache")
        } else {
            val f: LocalFeed = if (transport == Transport.TCP) TcpFeed() else UdpFeed()
            f.start()
            feed = f
            media = Media(vlc, Uri.parse(f.url))
            media.addOption(":network-caching=$cache") // UDP: Eingangspuffer = pts-delay
            media.addOption(":tcp-caching=$cache")     // TCP
        }
        media.setHWDecoderEnabled(true, false)         // Hardware bevorzugt, nicht erzwungen
        mp.media = media
        media.release()
        mp.play()                                      // Player lauscht/verbindet, bevor die Box angefordert wird
        if (transport != Transport.DIRECT) {
            val s = SatIpSession(ch.url, feed!!, object : SatIpSession.Listener {
                override fun onState(text: String) { main.post { if (gen == generation && state == UiState.LOADING) status.text = "${index + 1}  ${ch.name}\n$text" } }
                override fun onStreaming() { AppLog.i(tag, "Erste RTP-Daten nach ${System.currentTimeMillis() - tuneStartedAt} ms") }
                override fun onError(text: String) { main.post { if (gen == generation) onSessionError(text, gen) } }
            })
            session = s
            s.start()
        }
        main.postDelayed(watchdog, 10_000L + cache)
    }

    private fun stopPlayback() {
        main.removeCallbacks(watchdog); main.removeCallbacks(stallChecker); main.removeCallbacks(statsLogger)
        generation++
        val s = session; session = null
        val f = feed; feed = null
        runCatching { player?.stop() }
        s?.stop(2000)          // TEARDOWN vor dem Beenden
        runCatching { f?.close() }
    }

    // ------------------------------------------------------------------ Player-Ereignisse und Fehler

    private fun onPlayerEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> applyAudioDelay()
            MediaPlayer.Event.Vout -> if (ev.voutCount > 0) onPicture()
            MediaPlayer.Event.EncounteredError -> { AppLog.e(tag, "LibVLC EncounteredError"); onNoPicture(hard = true) }
            MediaPlayer.Event.EndReached -> {
                AppLog.w(tag, "LibVLC EndReached")
                if (state == UiState.PLAYING) showError("Wiedergabe beendet", "Der Stream wurde beendet.", generation)
            }
        }
    }

    private fun onPicture() {
        main.removeCallbacks(watchdog)
        if (state == UiState.PLAYING) return
        timeToPictureMs = System.currentTimeMillis() - tuneStartedAt
        if (!everPlayed) { everPlayed = true; settings.transport = transport }   // funktionierenden Weg merken
        if (state != UiState.PAUSED) setState(UiState.PLAYING)
        refreshTrackInfo()
        applyAudioDelay()
        AppLog.i(tag, "Bild nach $timeToPictureMs ms: $videoInfo | $audioInfo")
        main.removeCallbacks(stallChecker); main.postDelayed(stallChecker, 4_000)
        main.removeCallbacks(statsLogger); main.postDelayed(statsLogger, 10_000)
        probeDecoder(retry = true)
    }

    private fun probeDecoder(retry: Boolean) {
        val gen = generation
        io.execute {
            val d = DecoderProbe.probe()
            main.post {
                if (gen != generation) return@post
                decoderInfo = d
                AppLog.i(tag, "Decoder: $d")
                if (retry && d.startsWith("unbekannt")) main.postDelayed({ if (gen == generation) probeDecoder(false) }, 5_000)
            }
        }
    }

    private fun applyAudioDelay() {
        runCatching { player?.audioDelay = settings.audioDelayMs * 1000L }
    }

    private fun refreshTrackInfo() {
        val m = player?.media ?: return
        try {
            for (i in 0 until m.trackCount) {
                when (val t = m.getTrack(i)) {
                    is IMedia.VideoTrack -> if (videoInfo == "–") videoInfo = String.format(
                        Locale.US, "%s %dx%d %s", t.codec, t.width, t.height,
                        if (t.frameRateDen > 0) String.format(Locale.US, "%.0f fps", t.frameRateNum.toDouble() / t.frameRateDen) else "",
                    ).trim()
                    is IMedia.AudioTrack -> if (audioInfo == "–") audioInfo = "${t.codec} ${t.channels} ch ${t.rate} Hz" + (t.language?.let { " ($it)" } ?: "")
                    else -> Unit
                }
            }
        } finally { m.release() }
    }

    private fun onSessionError(text: String, gen: Int) {
        AppLog.w(tag, "Session-Fehler: $text")
        if (!everPlayed && fallbackTransport()) return
        showError(channels.getOrNull(index)?.name ?: "Fehler", text, gen)
    }

    private fun onNoPicture(hard: Boolean) {
        if (state == UiState.PLAYING || state == UiState.PAUSED) return
        AppLog.w(tag, "Kein Bild (${if (hard) "Fehler" else "Zeitüberschreitung"}) im Modus ${transport.key}")
        if (!everPlayed && fallbackTransport()) return
        val got = session?.stats()?.packets ?: 0
        val ch = channels.getOrNull(index)?.name ?: ""
        val detail = if (transport != Transport.DIRECT && got == 0L)
            "Von der FRITZ!Box kommen keine Daten an (UDP). Prüfe, ob Fire TV und FRITZ!Box im selben Netz sind und ob die Box einen freien Tuner hat."
        else "Es konnte kein Bild aufgebaut werden. Der Sender ist evtl. verschlüsselt oder nicht empfangbar."
        showError("Kein Bild: $ch", detail, generation)
    }

    /** Vor dem ersten erfolgreichen Bild: TCP -> UDP -> direkt. Gibt true zurueck, wenn ein neuer Versuch gestartet wurde. */
    private fun fallbackTransport(): Boolean {
        triedTransports.add(transport)
        val next = Transport.values().firstOrNull { it !in triedTransports } ?: return false
        AppLog.w(tag, "Fallback: ${transport.key} -> ${next.key}")
        transport = next
        tune(index)
        return true
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
        AppLog.e(tag, "Fehler: $title – $text")
        generation++
        main.removeCallbacks(watchdog); main.removeCallbacks(stallChecker)
        val s = session; session = null
        val f = feed; feed = null
        runCatching { player?.stop() }
        io.execute { s?.stop(2000); runCatching { f?.close() } }
        errorTitle.text = title
        errorText.text = text
        setState(UiState.ERROR)
    }

    private fun showBanner(ch: Channel) {
        banner.text = "${index + 1}   ${ch.name}"
        banner.visibility = View.VISIBLE
        main.removeCallbacks(bannerHider)
        main.postDelayed(bannerHider, 3_000)
    }

    private fun vlcStats(): IMedia.Stats? {
        val m = player?.media ?: return null
        return try { m.stats } catch (_: Exception) { null } finally { m.release() }
    }

    private fun updateOverlay() {
        if (!overlayVisible) { overlay.visibility = View.GONE; return }
        val s = session?.stats()
        if (s != null) {
            val now = System.currentTimeMillis()
            if (lastBytesAt != 0L && now > lastBytesAt) rateMbit = (s.bytes - lastBytes) * 8.0 / ((now - lastBytesAt) * 1000.0)
            lastBytes = s.bytes; lastBytesAt = now
        }
        val st = vlcStats()
        val ch = channels.getOrNull(index)
        val cache = settings.cacheMs
        overlay.text = buildString {
            append("Home TV ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})\n")
            append("Sender    : ${if (ch != null) "${index + 1}/${channels.size}  ${ch.name}  [Liste: $channelSource]" else "–"}\n")
            append("Status    : ${state.name.lowercase()}   Bild nach: ${if (timeToPictureMs >= 0) "%.1f s".format(Locale.US, timeToPictureMs / 1000.0) else "–"}\n")
            append("Modus     : ${transport.label}\n")
            append("Puffer    : $cache ms (zusätzliche Live-Verzögerung ≈ $cache ms + Box/Decoder)\n")
            append("Video     : $videoInfo\n")
            append("Audio     : $audioInfo   Versatz: ${"%+d".format(settings.audioDelayMs)} ms\n")
            append("Decoder   : $decoderInfo\n")
            if (s != null) {
                append("Netz      : ${"%.1f".format(Locale.US, rateMbit)} Mbit/s, ${s.packets} RTP-Pakete, Jitter ${"%.1f".format(Locale.US, s.jitterMs)} ms\n")
                append("RTP       : verloren ${s.lost}, umsortiert ${s.reordered}, spät/doppelt ${s.lateOrDuplicate}\n")
                append("TS        : Fehler ${s.tsCcErrors} von ${s.tsPackets}, Sync ${s.tsBadSync}\n")
                append("UDP-Puffer: ${s.rcvBufBytes / 1024} KiB   Feed: ${s.feedQueued / 1024} KiB wartend, ${s.feedOverflow} B verworfen\n")
                append("Datenzeit : ${s.dataTimeMs} ms (PCR)${player?.let { "   VLC-Zeit: ${it.time} ms" } ?: ""}\n")
                val gate = session?.gateOpenedAfterMs ?: -1
                append("Start-Gate: ${if (gate >= 0) "offen nach $gate ms, ${(session?.gateDroppedBytes ?: 0) / 1024} KiB verworfen" else "wartet auf ruhigen Start"}\n")
            } else append("Netz      : (direkter Modus – keine App-Messwerte)\n")
            if (st != null) append("VLC       : korrupt ${st.demuxCorrupted}, Diskont. ${st.demuxDiscontinuity}, Bilder ${st.displayedPictures} (verloren ${st.lostPictures}), Audio verloren ${st.lostAbuffers}\n")
            append("Taste     : $lastKey\n")
            append("[▲▼ Puffer  ◀▶ Audio-Versatz  MENÜ Modus  CH+/CH− Sender]")
        }
        overlay.visibility = View.VISIBLE
    }

    private fun logStats() {
        val s = session?.stats()
        val st = vlcStats()
        val gap = session?.takeMaxGapMs() ?: 0
        val sb = StringBuilder("STATS ${channels.getOrNull(index)?.name} ${transport.key} cache=${settings.cacheMs}")
        if (s != null) {
            sb.append(" | rx=${s.packets}pk/${s.bytes / 1024}KiB lost=${s.lost} reord=${s.reordered} late=${s.lateOrDuplicate}")
            sb.append(" tsCC=${s.tsCcErrors}/${s.tsPackets} jitter=${"%.1f".format(Locale.US, s.jitterMs)}ms maxGap10s=${gap}ms")
            sb.append(" feedQ=${s.feedQueued / 1024}KiB feedDrop=${s.feedOverflow}B dataT=${s.dataTimeMs}ms vlcT=${player?.time}ms")
        }
        if (st != null) {
            sb.append(" | vlc corrupt=${st.demuxCorrupted} discont=${st.demuxDiscontinuity} shown=${st.displayedPictures} lostPic=${st.lostPictures} lostAudio=${st.lostAbuffers} decV=${st.decodedVideo} decA=${st.decodedAudio}")
        }
        AppLog.i("Stats", sb.toString())
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
        AppLog.i(tag, "KeyDown $lastKey rep=${event.repeatCount}")
        val repeatOk = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (event.repeatCount > 0 && !repeatOk) return true // gehaltene Tasten ignorieren (ausser Audio-Versatz)
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { tune(index + 1); return true }      // 166
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(index - 1); return true }    // 167
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (overlayVisible) changeCache(+1) else tune(index + 1)         // ohne Overlay: wie CH+ (Fire-TV-Fernbedienung hat keine CH-Tasten)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (overlayVisible) changeCache(-1) else tune(index - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (overlayVisible) changeAudioDelay(-50); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (overlayVisible) changeAudioDelay(+50); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (state == UiState.ERROR) tune(index) else toggleOverlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (state == UiState.PAUSED) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (state == UiState.PLAYING) togglePause(); return true }
            KeyEvent.KEYCODE_MENU -> {
                transport = transport.next(); settings.transport = transport; triedTransports.clear(); triedTransports.add(transport)
                AppLog.i(tag, "Modus per Taste: ${transport.key}")
                tune(index)
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (overlayVisible) toggleOverlay() else finish(); return true }
        }
        updateOverlay()
        return super.onKeyDown(keyCode, event)
    }

    private fun changeCache(dir: Int) {
        val presets = Settings.CACHE_PRESETS
        val cur = settings.cacheMs
        val next = if (dir > 0) presets.firstOrNull { it > cur } ?: presets.first()
                   else presets.lastOrNull { it < cur } ?: presets.last()
        settings.cacheMs = next
        AppLog.i(tag, "Puffer per Taste: $next ms")
        tune(index)
    }
    private fun changeAudioDelay(deltaMs: Int) {
        settings.audioDelayMs = settings.audioDelayMs + deltaMs
        applyAudioDelay()
        AppLog.i(tag, "Audio-Versatz: ${settings.audioDelayMs} ms")
        updateOverlay()
    }

    private fun togglePause() {
        val mp = player ?: return
        when (state) {
            UiState.PLAYING -> {
                main.removeCallbacks(stallChecker)
                mp.pause()
                setState(UiState.PAUSED)
                if (!overlayVisible) toggleOverlay()
            }
            UiState.PAUSED -> tune(index) // Live-TV: nach der Pause live neu aufsetzen statt alten Puffer abzuspielen
            else -> Unit
        }
    }
}

/** Liest aus dem eigenen logcat (App darf nur den eigenen Prozess sehen), welcher Video-Decoder von LibVLC verwendet wird. */
object DecoderProbe {
    fun probe(): String {
        return try {
            val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-t", "4000").redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().useLines { seq -> seq.filter { it.contains("VLC", true) || it.contains("mediacodec", true) }.toList() }
            p.destroy()
            val mc = lines.filter { it.contains("mediacodec", true) }
            val name = Regex("(OMX\\.[A-Za-z0-9_.\\-]+|c2\\.[A-Za-z0-9_.\\-]+)").find(mc.joinToString("\n"))?.value
            val sw = lines.any { it.contains("avcodec", true) && it.contains("h264", true) }
            mc.take(6).forEach { AppLog.i("Decoder", it.trim().take(220)) }
            when {
                mc.isNotEmpty() && name != null -> "MediaCodec (Hardware) – $name"
                mc.isNotEmpty() -> "MediaCodec (Hardware), Codec-Name nicht im Log"
                sw -> "avcodec (Software)"
                else -> "unbekannt (keine passenden Logzeilen)"
            }
        } catch (e: Exception) {
            "unbekannt (${e.javaClass.simpleName})"
        }
    }
}
