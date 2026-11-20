package de.thecraftedtech.hometv

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.channels.ChannelFilter
import de.thecraftedtech.hometv.channels.Playlist
import de.thecraftedtech.hometv.core.AppLog
import de.thecraftedtech.hometv.epg.EpgRepository
import de.thecraftedtech.hometv.playback.PlaybackManager
import de.thecraftedtech.hometv.settings.AppSettings
import de.thecraftedtech.hometv.settings.SettingItem
import de.thecraftedtech.hometv.settings.SettingsEnv
import de.thecraftedtech.hometv.settings.SettingsModel
import de.thecraftedtech.hometv.settings.SharedPrefsStore
import de.thecraftedtech.hometv.ui.ChannelListView
import de.thecraftedtech.hometv.ui.DebugOverlayView
import de.thecraftedtech.hometv.ui.GuideView
import de.thecraftedtech.hometv.ui.InfoBarView
import de.thecraftedtech.hometv.ui.MenuView
import de.thecraftedtech.hometv.ui.MessageView
import de.thecraftedtech.hometv.ui.VolumeIndicatorView
import de.thecraftedtech.hometv.ui.dp
import de.thecraftedtech.hometv.zap.HoldTracker
import de.thecraftedtech.hometv.zap.Scheduler
import de.thecraftedtech.hometv.zap.ZapController
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * Home TV – Host der Oberflaeche. Die Activity verbindet nur noch die Bausteine:
 *   Wiedergabe  -> [PlaybackManager] (LibVLC, SAT>IP, Senderwechsel mit Abbruchlogik)
 *   Senderwahl  -> [ZapController]   (Vorschau sofort, Laden erst nach Entprellung)
 *   Programm    -> [EpgRepository]   (XMLTV, lokal gecacht, unabhaengig von der Wiedergabe)
 *   Einstellungen -> [AppSettings] / [SettingsModel]
 *   Darstellung -> Views in de.thecraftedtech.hometv.ui
 *
 * Tasten: siehe docs/firetv-setup.md. Alle Eingaben laufen zentral durch [dispatchKeyEvent] (kein Android-Fokus).
 */
class MainActivity : Activity(), PlaybackManager.Listener, ZapController.Listener, EpgRepository.Listener, MenuView.Callbacks {

    private val tag = "HomeTV"
    private val main = Handler(Looper.getMainLooper())
    private val playWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "hometv-play") }
    private val epgWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "hometv-epg") }

    private lateinit var settings: AppSettings
    private lateinit var playback: PlaybackManager
    private lateinit var epgRepo: EpgRepository
    private lateinit var zap: ZapController
    private lateinit var audio: AudioManager

    private lateinit var root: FrameLayout
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var loading: View
    private lateinit var status: TextView
    private lateinit var errorBox: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var infoBar: InfoBarView
    private lateinit var channelList: ChannelListView
    private lateinit var guide: GuideView
    private lateinit var menu: MenuView
    private lateinit var debugOverlay: DebugOverlayView
    private lateinit var volumeView: VolumeIndicatorView
    private lateinit var message: MessageView

    private var channels: List<Channel> = emptyList()
    private var channelSource = "–"
    private var started = false
    private var playbackActive = false
    private var requestedChannel: String? = null
    private var lastKey = "–"
    private var playbackDirty = false
    private var infoAutoHide = false
    private val consumedDown = HashSet<Int>()
    private val zapHold = HoldTracker()
    private val listHold = HoldTracker()
    private val guideRowHold = HoldTracker()
    private val guideTimeHold = HoldTracker()
    private var okPending = false      // OK in Senderliste/Programmuebersicht: kurz = ansehen (beim Loslassen), lang = Favorit
    private var okLong = false
    private var menuLongHandled = false

    private val idleStop = Runnable {
        AppLog.i(tag, "Inaktivitäts-Timer (${settings.idleStopHours} h ohne Tastendruck) – beende Wiedergabe")
        stopPlayback(); finish()
    }
    private val infoHide = Runnable { infoBar.hide() }
    private val listAutoClose = Runnable { closeChannelList() }
    private val messageHide = Runnable { message.hide() }
    private val volumeHide = Runnable { volumeView.hideAnimatedSafe() }
    private val infoTicker = object : Runnable {
        override fun run() {
            if (infoBar.visibility == View.VISIBLE) bindInfo(shownInfoIndex, shownInfoEpg)
            main.postDelayed(this, 20_000)
        }
    }
    private val debugTicker = object : Runnable {
        override fun run() {
            if (debugOverlay.isOpen) {
                debugOverlay.update(playback.debugSnapshot(), channels.size, lastKey)
                main.postDelayed(this, 1000)
            }
        }
    }
    private var shownInfoIndex = 0
    private var shownInfoEpg = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { AppLog.i(tag, "Bildschirm aus – Wiedergabe beenden (Tuner freigeben)"); stopPlayback() }
                Intent.ACTION_SCREEN_ON -> if (started) { AppLog.i(tag, "Bildschirm an"); startPlayback() }
            }
        }
    }

    // ------------------------------------------------------------------ Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        AppLog.init(this)
        settings = AppSettings(SharedPrefsStore(this), BuildConfig.FRITZBOX_HOST)
        handleIntent(intent)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        root = findViewById(R.id.root)
        videoLayout = findViewById(R.id.video)
        loading = findViewById(R.id.loading)
        status = findViewById(R.id.status)
        errorBox = findViewById(R.id.error)
        errorTitle = findViewById(R.id.errorTitle)
        errorText = findViewById(R.id.errorText)

        playback = PlaybackManager(this, settings, main, playWorker, this)
        epgRepo = EpgRepository(File(filesDir, "epg.bin"), settings, epgWorker, { r -> main.post(r) })
        epgRepo.listener = this
        zap = ZapController({ channels.size }, HandlerScheduler(), { settings.zapDebounceMs.toLong() }, this, allowed = { i -> allowedForZap(i) })

        buildOverlays()
        hideSystemUi()
        AppLog.i(tag, "Start: Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Modus=${settings.transport.key}, Puffer=${settings.cacheMs} ms, Audio-Versatz=${settings.audioDelayMs} ms, Debug=${settings.debugMode}, EPG=${settings.epgActive}")
    }

    private fun buildOverlays() {
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        infoBar = InfoBarView(this)
        channelList = ChannelListView(this)
        channelList.isFavorite = { ch -> isFav(ch) }
        guide = GuideView(this)
        guide.isFavorite = { ch -> isFav(ch) }
        guide.programmes = { ch -> epgRepo.programmes(ch) }
        channelList.nowProgramme = { ch -> if (settings.showEpgInChannelList && settings.epgActive) epgRepo.now(ch) else null }

        val env = SettingsEnv(
            versionText = BuildConfig.VERSION_NAME,
            channelInfo = { if (channels.isEmpty()) "keine geladen" else "${channels.size} (Quelle: $channelSource)" },
            reloadChannels = { reloadChannels() },
            epgStatus = { epgRepo.statusText() },
            epgLastUpdate = { epgRepo.lastUpdateText() },
            epgMatchInfo = { epgRepo.matchText() },
            epgCacheInfo = { epgRepo.cacheText() },
            refreshEpg = { epgRepo.refresh(force = true); showMessage("Programmführer wird aktualisiert …") },
            clearEpgCache = { epgRepo.clearCache(); showMessage("Zwischenspeicher gelöscht"); channelList.refresh() },
            showDebugOverlay = { menu.close(); showDebugOverlay() },
            openGuide = { menu.close(); openGuide() },
            logPath = (getExternalFilesDir(null) ?: filesDir).absolutePath + "/hometv.log",
        )
        menu = MenuView(this, SettingsModel(settings, env).categories)
        menu.callbacks = this

        debugOverlay = DebugOverlayView(this)
        volumeView = VolumeIndicatorView(this)
        message = MessageView(this)

        root.addView(infoBar, FrameLayout.LayoutParams(match, match))
        root.addView(guide, FrameLayout.LayoutParams(match, match))
        // schwebende Karten mit Abstand zum Rand (Overscan-sicher); Breite bewusst schmal, damit viel Bild sichtbar bleibt
        root.addView(channelList, FrameLayout.LayoutParams(dp(340), match, Gravity.END).apply { setMargins(0, dp(20), dp(20), dp(20)) })
        root.addView(menu, FrameLayout.LayoutParams(dp(520), match, Gravity.START).apply { setMargins(dp(20), dp(20), 0, dp(20)) })
        root.addView(debugOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(24), 0, 0) })
        root.addView(volumeView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(36), 0) })
        root.addView(message, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(110)) })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        if (started && channels.isNotEmpty()) {
            requestedChannel?.let { name -> resolveChannel(name)?.let { zap.select(it) } } ?: playback.restartCurrent()
            if (intent.hasExtra("epgUrl")) epgRepo.refresh(force = true)
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
        started = true
        registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) startPlayback()
        armIdleTimer()
    }

    override fun onStop() {
        started = false
        runCatching { unregisterReceiver(screenReceiver) }
        AppLog.i(tag, "onStop – beende Wiedergabe")
        stopPlayback()
        closeAllLayers()
        main.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        playWorker.shutdownNow()
        epgWorker.shutdownNow()
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

    // ------------------------------------------------------------------ Wiedergabe starten/stoppen

    private fun startPlayback() {
        if (playbackActive) return
        playbackActive = true
        playback.attach(videoLayout)
        if (channels.isNotEmpty()) {
            playback.tune(zap.current.coerceAtLeast(0))
            main.postDelayed({ epgRepo.refreshIfStale() }, 25_000)
            return
        }
        setLoading("Lade Senderliste von ${sourceLabel()} …")
        playWorker.execute {
            val r = runCatching { loadPlaylist() }
            main.post {
                if (!playbackActive) return@post
                r.onSuccess { loaded ->
                    applyChannels(loaded.channels, loaded.source)
                    val start = requestedChannel?.let { resolveChannel(it) }
                        ?: settings.lastChannelName?.takeIf { settings.startWithLastChannel }?.let { Playlist.indexOfName(channels, it).takeIf { i -> i >= 0 } }
                        ?: Playlist.defaultIndex(channels)
                    AppLog.i(tag, "Senderliste: ${channels.size} Sender (Quelle: $channelSource), Start: ${start + 1} ${channels[start].name}")
                    zap.setCurrent(start)
                    playback.tune(start)
                    scheduleEpgWork()
                }.onFailure { e -> showErrorScreen(if (settings.customPlaylist) "Senderliste nicht ladbar" else "Keine Verbindung zur FRITZ!Box", e.message ?: e.toString()) }
            }
        }
    }

    private fun stopPlayback() {
        if (!playbackActive) return
        playbackActive = false
        zap.cancel()
        playback.detach()
    }

    private fun applyChannels(list: List<Channel>, source: String) {
        channels = list
        channelSource = source
        playback.channels = list
        epgRepo.channels = list
        channelList.setChannels(list)
        guide.channels = list
        applyFilter()
    }

    // ------------------------------------------------------------------ Favoriten / Filter

    private fun isFav(ch: Channel) = settings.isFavorite(ch.name)

    private fun filterActive() = ChannelFilter.isActive(channels, settings.favoritesOnly) { isFav(it) }

    /** CH+/CH- ueberspringen Nicht-Favoriten, solange der Filter wirkt. */
    private fun allowedForZap(i: Int): Boolean = !filterActive() || channels.getOrNull(i)?.let { isFav(it) } == true

    /** Senderliste und Programmuebersicht an den Favoriten-Filter anpassen. */
    private fun applyFilter() {
        val active = filterActive()
        val idx = ChannelFilter.shown(channels, settings.favoritesOnly) { isFav(it) }
        channelList.setShown(idx, active)
        guide.filterLabel = if (active) "★ Favoriten" else "Alle Sender"
        guide.noDataHint = if (!settings.epgActive) "Programmführer nicht eingerichtet – Einstellungen → Programmführer → XMLTV-URL" else ""
        guide.setRows(idx)
    }

    private fun toggleFavoriteSelected() {
        val ci = if (guide.isOpen) guide.selectedChannelIndex else channelList.selectedChannel
        val ch = channels.getOrNull(ci) ?: return
        val nowFav = settings.toggleFavorite(ch.name)
        showMessage(if (nowFav) "★ ${ch.name} ist jetzt Favorit" else "${ch.name} aus den Favoriten entfernt")
        applyFilter()
    }

    private fun toggleFavoritesFilter() {
        if (channels.none { isFav(it) }) { showMessage("Noch keine Favoriten – OK lange drücken, um einen Sender zu markieren"); return }
        settings.favoritesOnly = !settings.favoritesOnly
        applyFilter()
        showMessage(if (settings.favoritesOnly) "Nur Favoriten" else "Alle Sender")
    }

    private fun sourceLabel() = if (settings.customPlaylist) settings.playlistUrl else settings.fritzboxHost

    /** Blockierend (Netzwerk) – nur auf dem Worker-Thread aufrufen. */
    private fun loadPlaylist(): Playlist.Loaded {
        val custom = settings.playlistUrl
        val host = settings.fritzboxHost
        return Playlist.load(this, host, custom, assetFallback = custom.isBlank() && host == settings.defaultHost)
    }

    /** @param sourceChanged  Adresse/URL wurde geaendert: nicht mehr vorhandener Sender -> Standardsender laden. */
    private fun reloadChannels(sourceChanged: Boolean = false) {
        showMessage("Lade Senderliste …")
        playWorker.execute {
            val r = runCatching { loadPlaylist() }
            main.post {
                r.onSuccess { l ->
                    val cur = channels.getOrNull(zap.current)?.name
                    applyChannels(l.channels, l.source)
                    val keep = cur?.let { Playlist.indexOfName(channels, it) }?.takeIf { it >= 0 }
                    if (keep != null) zap.setCurrent(keep)
                    else if (sourceChanged && playbackActive) {
                        // neue Quelle ohne den bisherigen Sender: mit dem Standardsender der neuen Liste weitermachen
                        val start = Playlist.defaultIndex(channels)
                        zap.setCurrent(start)
                        channelList.setPlaying(start)
                        playback.tune(start)
                        showInfo(start, epg = settings.showEpgOnZap, autoHide = true)
                    }
                    showMessage("${l.channels.size} Sender geladen (${l.source})")
                }.onFailure { showMessage("Senderliste nicht ladbar: ${it.message}") }
            }
        }
    }

    /** EPG: Cache sofort laden, Aktualisierung erst, wenn die Wiedergabe laeuft (kein Wettbewerb um die CPU beim Start). */
    private fun scheduleEpgWork() {
        epgWorker.execute { epgRepo.loadCache(); main.post { channelList.refresh() } }
        main.postDelayed({ epgRepo.refreshIfStale() }, 25_000)
        if (intent?.hasExtra("epgUrl") == true) epgRepo.refresh(force = true)
    }

    // ------------------------------------------------------------------ PlaybackManager.Listener

    override fun onStateChanged(state: PlaybackManager.State, channel: Channel?) {
        when (state) {
            PlaybackManager.State.LOADING -> { errorBox.visibility = View.GONE; loading.visibility = View.VISIBLE; status.text = loadingText() }
            PlaybackManager.State.PLAYING -> { errorBox.visibility = View.GONE; loading.visibility = View.GONE }
            PlaybackManager.State.PAUSED -> { loading.visibility = View.GONE; showInfo(zap.current, epg = true, autoHide = true) }
            PlaybackManager.State.ERROR -> { loading.visibility = View.GONE }
            PlaybackManager.State.IDLE -> Unit
        }
        if (infoBar.visibility == View.VISIBLE) bindInfo(shownInfoIndex, shownInfoEpg)
    }

    override fun onStatus(text: String) { if (playback.state == PlaybackManager.State.LOADING) status.text = "${loadingText()}\n$text" }

    private fun loadingText(): String = channels.getOrNull(zap.current)?.let { "${zap.current + 1}  ${it.name}" } ?: ""

    override fun onPicture(channel: Channel, timeToPictureMs: Long) {
        if (infoBar.visibility == View.VISIBLE && infoAutoHide) {
            main.removeCallbacks(infoHide)
            main.postDelayed(infoHide, settings.infoBarSeconds * 1000L)
        }
    }

    override fun onError(channel: Channel?, title: String, text: String) { showErrorScreen(title, text) }

    private fun showErrorScreen(title: String, text: String) {
        loading.visibility = View.GONE
        errorTitle.text = title
        errorText.text = text
        errorBox.visibility = View.VISIBLE
    }

    private fun setLoading(text: String) {
        errorBox.visibility = View.GONE
        loading.visibility = View.VISIBLE
        status.text = text
    }

    // ------------------------------------------------------------------ ZapController.Listener

    override fun onPreview(index: Int) {
        // sofort: Banner + Programm der vorgemerkten Auswahl (noch vor dem Laden des Bildes)
        showInfo(index, epg = settings.showEpgOnZap, autoHide = true, preview = true)
    }

    override fun onCommit(index: Int) {
        channels.getOrNull(index)?.let { settings.lastChannelName = it.name }
        channelList.setPlaying(index)
        errorBox.visibility = View.GONE
        playback.tune(index)
        showInfo(index, epg = settings.showEpgOnZap, autoHide = true)
    }

    // ------------------------------------------------------------------ Info-Leiste

    private fun showInfo(index: Int, epg: Boolean, autoHide: Boolean, preview: Boolean = false) {
        if (channels.isEmpty()) return
        if (menu.isOpen || channelList.isOpen || guide.isOpen) return   // Menue/Liste/Uebersicht haben Vorrang, die Leiste legt sich nicht darueber
        shownInfoIndex = index.coerceIn(0, channels.size - 1)
        shownInfoEpg = epg
        infoAutoHide = autoHide
        bindInfo(shownInfoIndex, epg, preview)
        infoBar.show()
        main.removeCallbacks(infoHide)
        // waehrend des Ladens/Vorwaehlens bleibt die Leiste stehen; sie blendet erst nach dem Bild bzw. der Auswahl aus
        val loadingNow = playback.state == PlaybackManager.State.LOADING || zap.pending != null
        if (autoHide && !loadingNow) main.postDelayed(infoHide, settings.infoBarSeconds * 1000L)
    }

    private fun bindInfo(index: Int, epg: Boolean, preview: Boolean = false) {
        val ch = channels.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        val epgOn = settings.epgActive && epg
        val st = when {
            preview || index != zap.current -> ""
            else -> when (playback.state) {
                PlaybackManager.State.LOADING -> "Lädt …"
                PlaybackManager.State.PLAYING -> "Live"
                PlaybackManager.State.PAUSED -> "Pausiert"
                PlaybackManager.State.ERROR -> "Fehler"
                PlaybackManager.State.IDLE -> ""
            }
        }
        infoBar.bind(ch, index + 1, if (epgOn) epgRepo.now(ch, now) else null, if (epgOn) epgRepo.next(ch, now) else null, epgOn, st, now)
    }

    // ------------------------------------------------------------------ Ebenen (Senderliste, Menue, Debug)

    private fun openChannelList() {
        if (channels.isEmpty()) return
        infoBar.hide()
        channelList.open(zap.pending ?: zap.current.coerceAtLeast(0))
        armListTimeout()
    }

    private fun openGuide() {
        if (channels.isEmpty()) return
        closeChannelList(); infoBar.hide(); closeDebugOverlay()
        applyFilter()
        guide.open(zap.pending ?: zap.current.coerceAtLeast(0))
    }

    private fun closeGuide() { if (guide.isOpen) guide.close() }

    private fun closeChannelList() {
        main.removeCallbacks(listAutoClose)
        if (channelList.isOpen) channelList.close()
    }

    private fun armListTimeout() {
        main.removeCallbacks(listAutoClose)
        val s = settings.channelListTimeoutSeconds
        if (s > 0) main.postDelayed(listAutoClose, s * 1000L)
    }

    private fun openMenu() {
        closeChannelList(); closeGuide(); infoBar.hide(); closeDebugOverlay()
        playbackDirty = false
        menu.open()
    }

    override fun onClose() {
        menu.close()
        if (playbackDirty) { playbackDirty = false; playback.restartCurrent() }
    }

    private fun closeAllLayers() {
        main.removeCallbacks(listAutoClose)
        channelList.forceClose()
        guide.forceClose()
        menu.forceClose()
        infoBar.visibility = View.GONE
        debugOverlay.visibility = View.GONE
    }

    private fun showDebugOverlay() {
        if (!settings.debugMode) return
        debugOverlay.update(playback.debugSnapshot(), channels.size, lastKey)
        debugOverlay.visibility = View.VISIBLE
        main.removeCallbacks(debugTicker); main.postDelayed(debugTicker, 1000)
    }

    private fun closeDebugOverlay() { debugOverlay.visibility = View.GONE; main.removeCallbacks(debugTicker) }

    private fun showMessage(text: String) {
        message.show(text)
        main.removeCallbacks(messageHide); main.postDelayed(messageHide, 2600)
    }

    // ------------------------------------------------------------------ MenuView.Callbacks / EpgRepository.Listener

    override fun onEditText(item: SettingItem.Text) {
        val input = EditText(this).apply {
            setText(item.current); hint = item.hint
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(item.label)
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                item.commit(input.text.toString().trim())
                onItemChanged(item)
                menu.refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    override fun onItemChanged(item: SettingItem) {
        when (item.id) {
            "cache", "transport", "gate" -> playbackDirty = true
            "favOnly", "favClear" -> applyFilter()
            "idle" -> armIdleTimer()
            "host", "playlistUrl" -> reloadChannels(sourceChanged = true)
            "audio" -> playback.applyAudioDelay()
            "debug" -> { if (!settings.debugMode) closeDebugOverlay(); showMessage(if (settings.debugMode) "Debug-Modus an – Menü-Taste lang drücken. VLC-Log ab nächstem Wiedergabestart." else "Debug-Modus aus") }
            "epgUrl" -> { if (settings.epgActive) { epgRepo.refresh(force = true); showMessage("Programmführer wird geladen …") } }
            "epgOn" -> if (settings.epgActive) epgRepo.refreshIfStale()
            "epgDays" -> Unit
        }
        channelList.refresh()
    }

    override fun onEpgStatusChanged() {
        if (menu.isOpen) menu.refresh()
        if (channelList.isOpen) channelList.refresh()
        if (guide.isOpen) { applyFilter(); guide.refresh() }
        if (infoBar.visibility == View.VISIBLE) bindInfo(shownInfoIndex, shownInfoEpg)
    }

    // ------------------------------------------------------------------ Fernbedienung

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN) armIdleTimer()
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) logKey(e)
        // Tasten, die schon bei DOWN verbraucht wurden, auch bei UP verbrauchen
        if (e.action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_MENU) return onMenuUp()
            if (isOkKey(code) && okPending) {
                okPending = false
                val wasLong = okLong; okLong = false
                consumedDown.remove(code)
                if (!wasLong) onLayerOkShort()
                return true
            }
            if (consumedDown.remove(code)) return true
            return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val handled = routeKey(e)
        if (handled) consumedDown.add(code)
        return handled || super.dispatchKeyEvent(e)
    }

    /** Inaktivitaets-Timer neu starten (jede Taste setzt ihn zurueck). 0 Stunden = aus. */
    private fun armIdleTimer() {
        main.removeCallbacks(idleStop)
        val h = settings.idleStopHours
        if (h > 0) main.postDelayed(idleStop, h * 3_600_000L)
    }

    private fun logKey(e: KeyEvent) {
        val dev = InputDevice.getDevice(e.deviceId)?.name ?: "?"
        lastKey = "${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode}, scan ${e.scanCode})"
        AppLog.i("Key", "DOWN $lastKey device='$dev'")
    }

    private fun routeKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        // Lautstaerke/Stumm gelten in jeder Ebene (falls die Fernbedienung sie an die App weitergibt)
        when (code) {
            KeyEvent.KEYCODE_VOLUME_UP -> { changeVolume(AudioManager.ADJUST_RAISE); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { changeVolume(AudioManager.ADJUST_LOWER); return true }
            KeyEvent.KEYCODE_VOLUME_MUTE -> { toggleMute(); return true }
        }
        if (code == KeyEvent.KEYCODE_MENU) return onMenuDown(e)
        return when {
            menu.isOpen -> menu.handleKey(code, e.repeatCount)
            guide.isOpen -> handleGuideKey(e)
            channelList.isOpen -> handleListKey(e)
            else -> handlePlayerKey(e)
        }
    }

    // ---- Menue-Taste: kurz = Menue, lang = Debug-Overlay (nur bei aktivem Debug-Modus)
    private fun onMenuDown(e: KeyEvent): Boolean {
        if (e.repeatCount == 0) menuLongHandled = false
        if (e.repeatCount == 1 && !menuLongHandled && settings.debugMode && !menu.isOpen && !guide.isOpen && !channelList.isOpen) {
            menuLongHandled = true
            if (debugOverlay.isOpen) closeDebugOverlay() else showDebugOverlay()
        }
        return true
    }

    private fun onMenuUp(): Boolean {
        if (menuLongHandled) { menuLongHandled = false; return true }
        when {
            menu.isOpen -> onClose()
            guide.isOpen || channelList.isOpen -> toggleFavoritesFilter()   // Menue-Taste = Favoriten-Filter
            else -> openMenu()
        }
        return true
    }

    // ---- Senderliste
    private fun handleListKey(e: KeyEvent): Boolean {
        armListTimeout()
        val now = System.currentTimeMillis()
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (listHold.shouldStep(e.repeatCount, now)) channelList.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (listHold.shouldStep(e.repeatCount, now)) channelList.moveSelection(+1)
            KeyEvent.KEYCODE_PAGE_UP -> channelList.moveSelection(-8)
            KeyEvent.KEYCODE_PAGE_DOWN -> channelList.moveSelection(+8)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> handleLayerOk(e)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) closeChannelList()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (e.repeatCount == 0) openGuide()
            else -> return false
        }
        return true
    }

    // ---- OK in Senderliste/Programmuebersicht: kurz (beim Loslassen) = Sender ansehen, lang = Favorit umschalten
    private fun isOkKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun handleLayerOk(e: KeyEvent): Boolean {
        if (e.repeatCount == 0) { okPending = true; okLong = false }
        else if (e.repeatCount == 1 && okPending && !okLong) { okLong = true; toggleFavoriteSelected() }
        return true
    }

    private fun onLayerOkShort() {
        when {
            guide.isOpen -> { val ci = guide.selectedChannelIndex; closeGuide(); if (ci >= 0) zap.select(ci) }
            channelList.isOpen -> { val ci = channelList.selectedChannel; closeChannelList(); if (ci >= 0) zap.select(ci) }
        }
    }

    // ---- Programmuebersicht
    private fun handleGuideKey(e: KeyEvent): Boolean {
        val now = System.currentTimeMillis()
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (guideRowHold.shouldStep(e.repeatCount, now)) guide.moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (guideRowHold.shouldStep(e.repeatCount, now)) guide.moveRow(+1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (guideTimeHold.shouldStep(e.repeatCount, now)) guide.moveTime(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (guideTimeHold.shouldStep(e.repeatCount, now)) guide.moveTime(+1)
            KeyEvent.KEYCODE_PAGE_UP -> guide.moveRow(-8)
            KeyEvent.KEYCODE_PAGE_DOWN -> guide.moveRow(+8)
            KeyEvent.KEYCODE_MEDIA_REWIND -> guide.jump(-3 * 3_600_000L)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> guide.jump(+3 * 3_600_000L)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> handleLayerOk(e)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) closeGuide()
            else -> return false
        }
        return true
    }

    // ---- Wiedergabe (nichts geoeffnet)
    private fun handlePlayerKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val now = System.currentTimeMillis()
        // Debug-Overlay offen: ▲▼ Puffer, ◀▶ Audio-Versatz
        if (debugOverlay.isOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (e.repeatCount == 0) changeCache(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (e.repeatCount == 0) changeCache(-1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { changeAudioDelay(-50); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { changeAudioDelay(+50); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { if (e.repeatCount == 0) closeDebugOverlay(); return true }
            }
        }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> { if (zapHold.shouldStep(e.repeatCount, now)) zap.step(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> { if (zapHold.shouldStep(e.repeatCount, now)) zap.step(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (e.repeatCount == 0) openChannelList(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (e.repeatCount == 0) openMenu(); return true }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> { if (e.repeatCount == 0) openGuide(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.repeatCount == 1) { openGuide(); return true }   // OK lange gedrueckt: Programmuebersicht
                if (e.repeatCount > 1) return true
                if (playback.state == PlaybackManager.State.ERROR) playback.restartCurrent()
                else if (infoBar.visibility == View.VISIBLE) infoBar.hide()
                else showInfo(zap.current, epg = true, autoHide = true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { if (e.repeatCount == 0) playback.togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (e.repeatCount == 0 && playback.state == PlaybackManager.State.PAUSED) playback.togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (e.repeatCount == 0 && playback.state == PlaybackManager.State.PLAYING) playback.togglePause(); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (e.repeatCount > 0) return true
                if (infoBar.visibility == View.VISIBLE) infoBar.hide() else finish()
                return true
            }
        }
        return false
    }

    private fun changeCache(dir: Int) {
        val presets = AppSettings.CACHE_PRESETS
        val cur = settings.cacheMs
        settings.cacheMs = if (dir > 0) presets.firstOrNull { it > cur } ?: presets.first() else presets.lastOrNull { it < cur } ?: presets.last()
        AppLog.i(tag, "Puffer per Taste: ${settings.cacheMs} ms")
        playback.restartCurrent()
    }

    private fun changeAudioDelay(delta: Int) {
        settings.audioDelayMs = settings.audioDelayMs + delta
        playback.applyAudioDelay()
        AppLog.i(tag, "Audio-Versatz: ${settings.audioDelayMs} ms")
    }

    // ---- Lautstaerke / Stumm (kommt nur an, wenn Fire OS die Tasten an die App weitergibt – siehe Doku)
    private fun changeVolume(dir: Int) {
        if (playback.isMuted) { playback.setMuted(false) }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        showVolume()
    }

    private fun toggleMute() { playback.setMuted(!playback.isMuted); showVolume() }

    private fun showVolume() {
        volumeView.showVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), playback.isMuted)
        main.removeCallbacks(volumeHide); main.postDelayed(volumeHide, 1800)
    }

    private fun View.hideAnimatedSafe() { animate().cancel(); visibility = View.GONE }

    // ------------------------------------------------------------------ Hilfsklassen

    private inner class HandlerScheduler : Scheduler {
        private val map = HashMap<Any, Runnable>()
        override fun postDelayed(token: Any, delayMs: Long, action: Runnable) {
            cancel(token)
            val r = Runnable { map.remove(token); action.run() }
            map[token] = r
            main.postDelayed(r, delayMs)
        }
        override fun cancel(token: Any) { map.remove(token)?.let { main.removeCallbacks(it) } }
    }
}
