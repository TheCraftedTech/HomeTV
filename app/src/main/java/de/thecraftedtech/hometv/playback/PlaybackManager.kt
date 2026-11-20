package de.thecraftedtech.hometv.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.core.AppLog
import de.thecraftedtech.hometv.satip.LocalFeed
import de.thecraftedtech.hometv.satip.NetStats
import de.thecraftedtech.hometv.satip.SatIpSession
import de.thecraftedtech.hometv.satip.TcpFeed
import de.thecraftedtech.hometv.satip.UdpFeed
import de.thecraftedtech.hometv.settings.AppSettings
import de.thecraftedtech.hometv.settings.Transport
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Momentaufnahme fuer das Debug-Overlay (alles gemessene Werte, nichts geschaetzt). */
data class DebugSnapshot(
    val channel: Channel?, val index: Int, val state: PlaybackManager.State,
    val transport: Transport, val cacheMs: Int, val audioDelayMs: Int,
    val videoInfo: String, val audioInfo: String, val decoderInfo: String,
    val timeToPictureMs: Long, val timeline: String,
    val net: NetStats?, val rateMbit: Double, val gateOpenedAfterMs: Long, val gateDroppedKiB: Long,
    val vlcCorrupt: Int, val vlcDiscont: Int, val vlcShown: Int, val vlcLostPictures: Int, val vlcLostAudio: Int,
    val vlcTimeMs: Long,
)

/**
 * Wiedergabe: LibVLC + SAT>IP-Client + lokaler Feed. Die gesamte Pipeline stammt aus Version 1.1 (dort auf dem Fire TV
 * mit Bild, Ton und Synchronisation bestaetigt) und wurde hier nur aus der Activity herausgeloest – Puffer, Start-Gate,
 * Modi und Synchronisation sind unveraendert.
 *
 * Senderwechsel laufen ueber den [TuneCoordinator]: nur der neueste Wechsel startet, die alte Session wird vorher per
 * TEARDOWN beendet. Alle oeffentlichen Methoden gehoeren in den Haupt-Thread.
 */
class PlaybackManager(
    private val context: Context,
    private val settings: AppSettings,
    private val main: Handler,
    private val worker: Executor,
    private val listener: Listener,
) {
    enum class State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    interface Listener {
        fun onStateChanged(state: State, channel: Channel?)
        /** Statuszeile waehrend des Ladens (z. B. "Warte auf Daten …"). */
        fun onStatus(text: String)
        fun onPicture(channel: Channel, timeToPictureMs: Long)
        fun onError(channel: Channel?, title: String, text: String)
    }

    private val tag = "HomeTV/Play"

    var state = State.IDLE
        private set
    var channels: List<Channel> = emptyList()
    var index = 0
        private set
    val channel: Channel? get() = channels.getOrNull(index)

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var handle: PlayHandle? = null
    /** Generation des Mediums, dessen Player-Ereignisse gelten; -1 = keines (waehrend eines Senderwechsels werden Ereignisse des alten Mediums verworfen). */
    @Volatile private var mediaGen = -1

    private var transport = Transport.TCP
    private val triedTransports = HashSet<Transport>()
    private var everPlayed = false
    private var restartsAfterStall = 0
    private var muted = false

    // Messwerte
    private var videoInfo = "–"
    private var audioInfo = "–"
    private var decoderInfo = "(Debug-Modus aus)"
    private var tuneStartedAt = 0L
    private var vlcPlayingAt = 0L
    private var timeToPictureMs = -1L
    private var timeline = ""
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var rateMbit = 0.0

    private inner class PlayHandle(val feed: LocalFeed?, val session: SatIpSession?) : TuneHandle {
        override fun stopBlocking() {
            session?.stop(2000)          // TEARDOWN
            runCatching { feed?.close() }
        }
    }

    private val coordinator = TuneCoordinator<PlayHandle>(worker, { r -> main.post(r) }) { _, gen -> startMedia(gen) }

    private val watchdog = Runnable { onNoPicture(hard = false) }
    private val stallChecker = object : Runnable {
        override fun run() {
            val s = handle?.session
            if (state == State.PLAYING && s != null && s.lastPacketAgoMs > 8_000) {
                AppLog.w(tag, "Datenstillstand > 8 s")
                if (restartsAfterStall < 2) { restartsAfterStall++; tune(index) }
                else fail("Stream unterbrochen", "Die FRITZ!Box sendet keine Daten mehr.")
                return
            }
            main.postDelayed(this, 2_000)
        }
    }
    private val statsLogger = object : Runnable {
        override fun run() {
            if (state == State.PLAYING) logStats()
            main.postDelayed(this, 10_000)
        }
    }

    // ------------------------------------------------------------------ Lebenszyklus

    fun attach(videoLayout: VLCVideoLayout) {
        if (player != null) return
        transport = settings.transport
        triedTransports.clear()
        restartsAfterStall = 0
        // Ausfuehrliches VLC-Log nur im Debug-Modus: spart CPU und flutet logcat nicht (Tag "VLC").
        val opts = arrayListOf<String>().apply { if (settings.debugMode) add("-vv") }
        val vlc = LibVLC(context, opts)
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { ev ->
            val g = mediaGen
            if (g >= 0) main.post { if (g == coordinator.generation) onPlayerEvent(ev) }   // Ereignisse des alten Mediums (Ende/Fehler durch den Stopp) verwerfen
        }
        player = mp
        applyVolume()
    }

    /** Beendet alles sofort (inkl. TEARDOWN) und gibt LibVLC frei – fuer onStop. */
    fun detach() {
        stopTimers()
        mediaGen = -1
        awaitWorker(3_000)          // ein noch laufendes MediaPlayer.stop() auf dem Worker abwarten, bevor release() es ueberholt
        coordinator.stopNow()
        handle = null
        val mp = player; player = null
        val vlc = libVlc; libVlc = null
        runCatching { mp?.setEventListener(null) }
        runCatching { mp?.stop() }
        runCatching { mp?.detachViews() }
        runCatching { mp?.release() }
        runCatching { vlc?.release() }
        setState(State.IDLE)
    }

    /** Wartet, bis alle bisher eingereihten Worker-Aufgaben (z. B. ein blockierendes Player-Stop) fertig sind. */
    private fun awaitWorker(timeoutMs: Long) {
        val latch = CountDownLatch(1)
        try { worker.execute { latch.countDown() } } catch (_: Exception) { return }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun stopTimers() {
        main.removeCallbacks(watchdog); main.removeCallbacks(stallChecker); main.removeCallbacks(statsLogger)
    }

    // ------------------------------------------------------------------ Senderwechsel

    /** Startet Sender [newIndex] (Umlauf). Ein noch laufender Wechsel wird verworfen. */
    fun tune(newIndex: Int) {
        if (channels.isEmpty() || player == null) return
        index = Math.floorMod(newIndex, channels.size)
        stopTimers()
        timeToPictureMs = -1; videoInfo = "–"; audioInfo = "–"; timeline = ""
        if (settings.debugMode) decoderInfo = "noch nicht ermittelt"
        lastBytes = 0; lastBytesAt = 0; rateMbit = 0.0; vlcPlayingAt = 0
        tuneStartedAt = System.currentTimeMillis()
        val ch = channels[index]
        AppLog.i(tag, "Sender ${index + 1}/${channels.size}: ${ch.name} (Modus ${transport.key}, Puffer ${settings.cacheMs} ms)")
        // Beobachtung (Log 1.4.0): MediaPlayer.stop() dauert ~2 s, wenn Daten fliessen (Sender spielt), aber nur 0-7 ms, solange
        // der Sender noch laedt. Test: lokalen TCP-Feed ZUERST schliessen (VLC liest dann sofort EOF statt auf Daten zu warten)
        // und erst danach stoppen; beide Zeiten getrennt loggen. Laeuft auf dem Worker, nie im Haupt-Thread.
        mediaGen = -1
        val oldFeed = handle?.feed as? TcpFeed
        val mp = player
        handle = null
        setState(State.LOADING)
        coordinator.tune(index) {
            val t0 = System.currentTimeMillis()
            runCatching { oldFeed?.close() }
            val t1 = System.currentTimeMillis()
            runCatching { mp?.stop() }
            AppLog.i(tag, "Stop: Feed zu ${t1 - t0} ms, LibVLC-Stop (Worker-Thread) ${System.currentTimeMillis() - t1} ms")
        }
    }

    /** Erzeugt Feed + Session + Media fuer den aktuellen Sender (Haupt-Thread, nur fuer die neueste Generation). */
    private fun startMedia(gen: Int): PlayHandle {
        val ch = channels[index]
        val vlc = libVlc
        val mp = player
        if (vlc == null || mp == null) return PlayHandle(null, null).also { handle = it }
        val cache = settings.cacheMs
        val media: Media
        var feed: LocalFeed? = null
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
        mediaGen = gen
        mp.play()                                      // Player lauscht/verbindet, bevor die Box angefordert wird
        var session: SatIpSession? = null
        if (feed != null) {
            session = SatIpSession(ch.url, feed, object : SatIpSession.Listener {
                override fun onState(text: String) { main.post { if (gen == coordinator.generation && state == State.LOADING) listener.onStatus(text) } }
                override fun onStreaming() { AppLog.i(tag, "Erste RTP-Daten nach ${System.currentTimeMillis() - tuneStartedAt} ms") }
                override fun onError(text: String) { main.post { if (gen == coordinator.generation) onSessionError(text) } }
            }, gateEnabled = settings.startGate)
            session.start()
        }
        main.postDelayed(watchdog, 10_000L + cache)
        return PlayHandle(feed, session).also { handle = it }
    }

    // ------------------------------------------------------------------ Player-Ereignisse

    private fun onPlayerEvent(ev: MediaPlayer.Event) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> { if (vlcPlayingAt == 0L) vlcPlayingAt = System.currentTimeMillis(); applyAudioDelay() }
            MediaPlayer.Event.Vout -> if (ev.voutCount > 0) onPicture()
            MediaPlayer.Event.EncounteredError -> { AppLog.e(tag, "LibVLC EncounteredError"); onNoPicture(hard = true) }
            MediaPlayer.Event.EndReached -> {
                AppLog.w(tag, "LibVLC EndReached")
                if (state == State.PLAYING) fail("Wiedergabe beendet", "Der Stream wurde beendet.")
            }
        }
    }

    private fun onPicture() {
        main.removeCallbacks(watchdog)
        if (state == State.PLAYING) return
        val now = System.currentTimeMillis()
        timeToPictureMs = now - tuneStartedAt
        buildTimeline(now)
        if (!everPlayed) { everPlayed = true; settings.transport = transport }   // funktionierenden Weg merken
        if (state != State.PAUSED) setState(State.PLAYING)
        refreshTrackInfo()
        applyAudioDelay()
        AppLog.i(tag, "Bild nach $timeToPictureMs ms: $videoInfo | $audioInfo")
        AppLog.i(tag, "TIMELINE ${channel?.name} $timeline")
        channel?.let { listener.onPicture(it, timeToPictureMs) }
        main.removeCallbacks(stallChecker); main.postDelayed(stallChecker, 4_000)
        main.removeCallbacks(statsLogger); main.postDelayed(statsLogger, 10_000)
        if (settings.debugMode) probeDecoder(retry = true)
    }

    /** Zeitleiste der Startphasen (ms seit Sender-Start) – zeigt, wo die Zeit bis zum Bild bleibt. */
    private fun buildTimeline(voutAt: Long) {
        val s = handle?.session
        fun rel(t: Long) = if (t > 0) "+${t - tuneStartedAt}" else "-"
        timeline = buildString {
            append("tune=0")
            if (s != null) {
                append(" session=").append(rel(s.startedAtMs))
                append(" PLAY_ok=").append(rel(s.playAckedAtMs))
                append(" erstes_RTP=").append(rel(s.firstPacketAtMs))
                append(" Gate_offen=").append(rel(s.gateOpenedAtMs))
            }
            append(" VLC_Playing=").append(rel(vlcPlayingAt))
            append(" Bild=+").append(voutAt - tuneStartedAt)
            append(" (ms)")
        }
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

    private fun probeDecoder(retry: Boolean) {
        val gen = coordinator.generation
        val executor = decoderExecutor
        executor.execute {
            val d = DecoderProbe.probe()
            main.post {
                if (gen != coordinator.generation) return@post
                decoderInfo = d
                AppLog.i(tag, "Decoder: $d")
                if (retry && d.startsWith("unbekannt")) main.postDelayed({ if (gen == coordinator.generation) probeDecoder(false) }, 5_000)
            }
        }
    }

    private val decoderExecutor: Executor = Executor { r -> Thread(r, "decoder-probe").start() }

    // ------------------------------------------------------------------ Fehler / Fallback

    private fun onSessionError(text: String) {
        AppLog.w(tag, "Session-Fehler: $text")
        if (!everPlayed && fallbackTransport()) return
        fail(channel?.name ?: "Fehler", text)
    }

    private fun onNoPicture(hard: Boolean) {
        if (state == State.PLAYING || state == State.PAUSED) return
        AppLog.w(tag, "Kein Bild (${if (hard) "Fehler" else "Zeitüberschreitung"}) im Modus ${transport.key}")
        if (!everPlayed && fallbackTransport()) return
        val got = handle?.session?.stats()?.packets ?: 0
        val detail = if (transport != Transport.DIRECT && got == 0L)
            "Von der FRITZ!Box kommen keine Daten an (UDP). Prüfe, ob Fire TV und FRITZ!Box im selben Netz sind und ob die Box einen freien Tuner hat."
        else "Es konnte kein Bild aufgebaut werden. Der Sender ist evtl. verschlüsselt oder nicht empfangbar."
        fail("Kein Bild: ${channel?.name ?: ""}", detail)
    }

    /** Vor dem ersten erfolgreichen Bild: TCP -> UDP -> direkt. true = neuer Versuch gestartet. */
    private fun fallbackTransport(): Boolean {
        triedTransports.add(transport)
        val next = Transport.values().firstOrNull { it !in triedTransports } ?: return false
        AppLog.w(tag, "Fallback: ${transport.key} -> ${next.key}")
        transport = next
        tune(index)
        return true
    }

    private fun fail(title: String, text: String) {
        AppLog.e(tag, "Fehler: $title – $text")
        stopTimers()
        runCatching { player?.stop() }
        coordinator.stopAsync()
        handle = null
        setState(State.ERROR)
        listener.onError(channel, title, text)
    }

    // ------------------------------------------------------------------ Steuerung

    /** Pause = Standbild; Fortsetzen startet den Live-Stream neu (kein Timeshift). */
    fun togglePause() {
        val mp = player ?: return
        when (state) {
            State.PLAYING -> { main.removeCallbacks(stallChecker); mp.pause(); setState(State.PAUSED) }
            State.PAUSED -> tune(index)
            else -> Unit
        }
    }

    fun setMuted(m: Boolean) { muted = m; applyVolume() }
    val isMuted: Boolean get() = muted
    private fun applyVolume() { runCatching { player?.volume = if (muted) 0 else 100 } }

    fun applyAudioDelay() { runCatching { player?.audioDelay = settings.audioDelayMs * 1000L } }

    /** Uebernimmt geaenderte Wiedergabe-Einstellungen (Modus/Puffer/Gate) durch Neustart des aktuellen Senders. */
    fun restartCurrent() {
        transport = settings.transport
        triedTransports.clear()
        tune(index)
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        listener.onStateChanged(s, channel)
    }

    // ------------------------------------------------------------------ Messwerte

    private fun vlcStats(): IMedia.Stats? {
        val m = player?.media ?: return null
        return try { m.stats } catch (_: Exception) { null } finally { m.release() }
    }

    fun debugSnapshot(): DebugSnapshot {
        val s = handle?.session
        val net = s?.stats()
        if (net != null) {
            val now = System.currentTimeMillis()
            if (lastBytesAt != 0L && now > lastBytesAt) rateMbit = (net.bytes - lastBytes) * 8.0 / ((now - lastBytesAt) * 1000.0)
            lastBytes = net.bytes; lastBytesAt = now
        }
        val st = vlcStats()
        return DebugSnapshot(
            channel, index, state, transport, settings.cacheMs, settings.audioDelayMs, videoInfo, audioInfo, decoderInfo,
            timeToPictureMs, timeline, net, rateMbit, s?.gateOpenedAfterMs ?: -1, (s?.gateDroppedBytes ?: 0) / 1024,
            st?.demuxCorrupted ?: 0, st?.demuxDiscontinuity ?: 0, st?.displayedPictures ?: 0, st?.lostPictures ?: 0, st?.lostAbuffers ?: 0,
            player?.time ?: 0,
        )
    }

    private fun logStats() {
        val s = handle?.session
        val net = s?.stats()
        val st = vlcStats()
        val gap = s?.takeMaxGapMs() ?: 0
        val sb = StringBuilder("STATS ${channel?.name} ${transport.key} cache=${settings.cacheMs}")
        if (net != null) {
            sb.append(" | rx=${net.packets}pk/${net.bytes / 1024}KiB lost=${net.lost} reord=${net.reordered} late=${net.lateOrDuplicate}")
            sb.append(" tsCC=${net.tsCcErrors}/${net.tsPackets} jitter=${"%.1f".format(Locale.US, net.jitterMs)}ms maxGap10s=${gap}ms")
            sb.append(" feedQ=${net.feedQueued / 1024}KiB feedDrop=${net.feedOverflow}B dataT=${net.dataTimeMs}ms vlcT=${player?.time}ms")
        }
        if (st != null) {
            sb.append(" | vlc corrupt=${st.demuxCorrupted} discont=${st.demuxDiscontinuity} shown=${st.displayedPictures} lostPic=${st.lostPictures} lostAudio=${st.lostAbuffers} decV=${st.decodedVideo} decA=${st.decodedAudio}")
        }
        AppLog.i("Stats", sb.toString())
    }
}

/** Liest aus dem eigenen logcat (App darf nur den eigenen Prozess sehen), welcher Video-Decoder von LibVLC verwendet wird. Nur im Debug-Modus. */
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
