package de.thecraftedtech.hometv

import de.thecraftedtech.hometv.playback.TuneCoordinator
import de.thecraftedtech.hometv.playback.TuneHandle
import de.thecraftedtech.hometv.settings.AppSettings
import de.thecraftedtech.hometv.settings.MemoryStore
import de.thecraftedtech.hometv.settings.SettingItem
import de.thecraftedtech.hometv.settings.SettingsEnv
import de.thecraftedtech.hometv.settings.SettingsModel
import de.thecraftedtech.hometv.settings.Transport
import de.thecraftedtech.hometv.zap.HoldTracker
import de.thecraftedtech.hometv.zap.Scheduler
import de.thecraftedtech.hometv.zap.ZapController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Virtuelle Uhr fuer deterministische Tests der Entprellung. */
private class FakeScheduler : Scheduler {
    var now = 0L
    private val tasks = LinkedHashMap<Any, Pair<Long, Runnable>>()
    override fun postDelayed(token: Any, delayMs: Long, action: Runnable) { tasks[token] = now + delayMs to action }
    override fun cancel(token: Any) { tasks.remove(token) }
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.entries.filter { it.value.first <= target }.minByOrNull { it.value.first } ?: break
            tasks.remove(next.key); now = next.value.first; next.value.second.run()
        }
        now = target
    }
    val pendingCount get() = tasks.size
}

class ZapControllerTest {
    private val previews = ArrayList<Int>()
    private val commits = ArrayList<Int>()
    private val sched = FakeScheduler()
    private var count = 97

    private fun controller(debounce: Long = 300) = ZapController({ count }, sched, { debounce }, object : ZapController.Listener {
        override fun onPreview(index: Int) { previews += index }
        override fun onCommit(index: Int) { commits += index }
    })

    @Test fun fivePressesStartExactlyOneChannel() {
        val z = controller().also { it.setCurrent(0) }
        repeat(5) { z.step(+1); sched.advance(100) }       // 5x CH+ in 100-ms-Abstaenden (< Entprellzeit)
        assertEquals(listOf(1, 2, 3, 4, 5), previews)      // jede Taste zeigt sofort Banner/Programm
        assertTrue("noch nichts geladen", commits.isEmpty())
        sched.advance(400)
        assertEquals(listOf(5), commits)                   // genau ein Ladevorgang: der zuletzt gewaehlte Sender
        assertEquals(5, z.current)
        assertNull(z.pending)
    }

    @Test fun slowPressesLoadEachChannel() {
        val z = controller().also { it.setCurrent(10) }
        z.step(+1); sched.advance(350)
        z.step(+1); sched.advance(350)
        assertEquals(listOf(11, 12), commits)
    }

    @Test fun wrapsAroundBothWays() {
        val z = controller().also { it.setCurrent(96) }
        z.step(+1); sched.advance(500)
        assertEquals(listOf(0), commits)
        z.step(-1); sched.advance(500)
        assertEquals(listOf(0, 96), commits)
    }

    @Test fun returningToRunningChannelDoesNotReload() {
        val z = controller().also { it.setCurrent(5) }
        z.step(+1); z.step(-1); sched.advance(500)
        assertTrue(commits.isEmpty())
        assertEquals(5, z.current)
    }

    @Test fun selectCommitsImmediately() {
        val z = controller().also { it.setCurrent(5) }
        z.select(42)
        assertEquals(listOf(42), commits)
        assertEquals(0, sched.pendingCount)
    }

    @Test fun selectOverridesPendingSteps() {
        val z = controller().also { it.setCurrent(5) }
        z.step(+1); z.step(+1)
        z.select(40)
        sched.advance(1000)
        assertEquals(listOf(40), commits)
    }

    @Test fun cancelDropsPending() {
        val z = controller().also { it.setCurrent(5) }
        z.step(+1); z.cancel(); sched.advance(1000)
        assertTrue(commits.isEmpty())
    }

    @Test fun emptyListIsIgnored() {
        count = 0
        val z = controller()
        z.step(+1); z.select(0); sched.advance(1000)
        assertTrue(previews.isEmpty() && commits.isEmpty())
    }

    @Test fun debounceIsConfigurable() {
        val z = controller(debounce = 800).also { it.setCurrent(0) }
        z.step(+1); sched.advance(700)
        assertTrue(commits.isEmpty())
        sched.advance(200)
        assertEquals(listOf(1), commits)
    }
}

class HoldTrackerTest {
    @Test fun firstPressStepsImmediately() {
        assertTrue(HoldTracker().shouldStep(0, 1000))
    }

    @Test fun repeatsAreThrottledAndAccelerate() {
        val h = HoldTracker()
        val steps = ArrayList<Long>()
        var t = 0L
        h.shouldStep(0, t); steps += t
        // Key-Repeat alle 40 ms nach 500 ms Verzoegerung, 5 Sekunden lang
        t = 500
        while (t <= 5000) { if (h.shouldStep(1, t)) steps += t; t += 40 }
        val gaps = steps.zipWithNext { a, b -> b - a }
        assertTrue("nie schneller als 70 ms", gaps.all { it >= 70 })
        assertTrue("beschleunigt: spaete Abstaende kuerzer als frueheste", gaps.last() < gaps[1])
        assertTrue("deutlich weniger Schritte als Key-Repeat-Events", steps.size < 60)
    }

    @Test fun releaseAndNewPressRestarts() {
        val h = HoldTracker()
        h.shouldStep(0, 0); h.shouldStep(1, 500)
        assertTrue(h.shouldStep(0, 10_000))   // neuer Tastendruck zaehlt sofort
        assertFalse(h.shouldStep(1, 10_050))  // und beginnt wieder langsam
    }
}

/** Seriell abarbeitender Executor, der erst auf Kommando laeuft (simuliert Hintergrund- und Haupt-Thread). */
private class ManualExecutor : Executor {
    val queue = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { queue.addLast(command) }
    fun runOne(): Boolean { val r = queue.removeFirstOrNull() ?: return false; r.run(); return true }
    fun runAll() { while (runOne()) Unit }
}

class TuneCoordinatorTest {
    private val worker = ManualExecutor()
    private val main = ManualExecutor()
    private val events = ArrayList<String>()
    private var active = 0
    private var maxActive = 0

    private inner class FakeHandle(val index: Int) : TuneHandle {
        init { active++; maxActive = maxOf(maxActive, active); events += "start$index" }
        override fun stopBlocking() { active--; events += "stop$index" }
    }

    private val coordinator = TuneCoordinator<FakeHandle>(worker, { r -> main.execute(r) }) { index, _ -> FakeHandle(index) }

    private fun drain() { do { worker.runAll(); main.runAll() } while (worker.queue.isNotEmpty() || main.queue.isNotEmpty()) }

    @Test fun rapidTuningStartsOnlyTheLatestChannel() {
        coordinator.tune(1); coordinator.tune(2); coordinator.tune(3); coordinator.tune(4); coordinator.tune(5)
        drain()
        assertEquals(listOf("start5"), events)              // 1-4 wurden nie geoeffnet
        assertEquals(1, active)
    }

    @Test fun oldSessionIsStoppedBeforeNewOneStarts() {
        coordinator.tune(1); drain()
        coordinator.tune(2); drain()
        assertEquals(listOf("start1", "stop1", "start2"), events)
        assertEquals(1, maxActive)
    }

    @Test fun preStopRunsOnWorkerBeforeOldSessionIsStoppedAndNewOneStarted() {
        coordinator.tune(1); drain()
        events.clear()
        coordinator.tune(2) { events += "preStop" }
        assertEquals("nichts laeuft im Aufrufer-Thread", emptyList<String>(), events)
        drain()
        assertEquals(listOf("preStop", "stop1", "start2"), events)
    }

    @Test fun neverTwoSessionsAtOnce() {
        coordinator.tune(1); drain()
        coordinator.tune(2); coordinator.tune(3); coordinator.tune(4)   // schnell hintereinander, bevor Hintergrundarbeit lief
        // gemischte, verschraenkte Abarbeitung
        worker.runOne(); main.runOne(); worker.runOne(); main.runOne(); drain()
        assertEquals("nie mehr als eine aktive Session", 1, maxActive)
        assertEquals("start4", events.last())
        assertEquals(1, active)
        assertEquals(events.count { it.startsWith("start") } - 1, events.count { it.startsWith("stop") })
    }

    @Test fun stopNowEndsSessionAndCancelsPendingStart() {
        coordinator.tune(1); drain()
        coordinator.tune(2)                                  // Start noch nicht ausgefuehrt
        coordinator.stopNow()
        drain()
        assertEquals(0, active)
        assertEquals("start1", events.first())
        assertFalse("start2 darf nach stopNow nicht mehr passieren", events.contains("start2"))
    }

    @Test fun stopAsyncStopsRunningSession() {
        coordinator.tune(7); drain()
        coordinator.stopAsync(); drain()
        assertEquals(listOf("start7", "stop7"), events)
        assertFalse(coordinator.hasActive)
    }

    @Test fun generationInvalidatesOldRequests() {
        val g1 = coordinator.tune(1)
        val g2 = coordinator.tune(2)
        assertFalse(coordinator.isCurrent(g1))
        assertTrue(coordinator.isCurrent(g2))
    }
}

class AppSettingsTest {
    @Test fun defaults() {
        val s = AppSettings(MemoryStore())
        assertEquals(2000, s.cacheMs)
        assertEquals(0, s.audioDelayMs)
        assertEquals(Transport.TCP, s.transport)
        assertTrue(s.startGate)
        assertEquals(300, s.zapDebounceMs)
        assertEquals(5, s.infoBarSeconds)
        assertEquals("", s.epgUrl)                 // keine erfundene Standard-URL
        assertFalse(s.epgActive)
        assertFalse("Debug-Modus standardmaessig aus", s.debugMode)
        assertEquals("Inaktivitaets-Timer standardmaessig aus", 0, s.idleStopHours)
        assertTrue(s.startWithLastChannel)
        assertNull(s.lastChannelName)
    }

    @Test fun persistsAcrossInstances() {
        val store = MemoryStore()
        AppSettings(store).apply {
            cacheMs = 3000; audioDelayMs = -150; transport = Transport.UDP; debugMode = true
            epgUrl = "  https://example.org/epg.xml.gz "; epgLastSuccessMs = 123456L; lastChannelName = "ZDF HD"
        }
        val s = AppSettings(store)
        assertEquals(3000, s.cacheMs); assertEquals(-150, s.audioDelayMs); assertEquals(Transport.UDP, s.transport)
        assertTrue(s.debugMode)
        assertEquals("https://example.org/epg.xml.gz", s.epgUrl)    // getrimmt
        assertEquals(123456L, s.epgLastSuccessMs)
        assertEquals("ZDF HD", s.lastChannelName)
        assertTrue(s.epgActive)
    }

    @Test fun existingKeysFromVersion11AreRead() {
        val store = MemoryStore(mutableMapOf("cacheMs" to 5000, "audioDelayMs" to 100, "transport" to "direct"))
        val s = AppSettings(store)
        assertEquals(5000, s.cacheMs); assertEquals(100, s.audioDelayMs); assertEquals(Transport.DIRECT, s.transport)
    }

    @Test fun valuesAreClamped() {
        val s = AppSettings(MemoryStore())
        s.cacheMs = 5; assertEquals(200, s.cacheMs)
        s.cacheMs = 999_999; assertEquals(15000, s.cacheMs)
        s.audioDelayMs = 9999; assertEquals(2000, s.audioDelayMs)
        s.zapDebounceMs = 1; assertEquals(100, s.zapDebounceMs)
        s.channelListTimeoutSeconds = -3; assertEquals(0, s.channelListTimeoutSeconds)
    }

    @Test fun unknownTransportFallsBackToDefault() {
        assertEquals(Transport.TCP, AppSettings(MemoryStore(mutableMapOf("transport" to "quatsch"))).transport)
    }

    @Test fun epgDisabledOrWithoutUrlIsInactive() {
        val s = AppSettings(MemoryStore())
        s.epgUrl = "http://x/y.xml"; assertTrue(s.epgActive)
        s.epgEnabled = false; assertFalse(s.epgActive)
        s.epgEnabled = true; s.epgUrl = "  "; assertFalse(s.epgActive)
    }
}

class SettingsModelTest {
    private val log = ArrayList<String>()
    private fun model(s: AppSettings) = SettingsModel(s, SettingsEnv(
        versionText = "1.3.0", channelInfo = { "97" }, reloadChannels = { log += "reload" },
        epgStatus = { "ok" }, epgLastUpdate = { "nie" }, epgMatchInfo = { "0" }, epgCacheInfo = { "leer" },
        refreshEpg = { log += "refresh" }, clearEpgCache = { log += "clear" }, showDebugOverlay = { log += "debug" }, logPath = "/x/hometv.log",
    ))

    private fun item(m: SettingsModel, id: String): SettingItem = m.categories.flatMap { it.items() }.first { it.id == id }

    @Test fun hasAllRequestedCategories() {
        val m = model(AppSettings(MemoryStore()))
        assertEquals(listOf("Allgemein", "Sender", "Favoriten", "Bedienung", "Wiedergabe", "Programmführer", "Oberfläche", "Anleitung", "Zurücksetzen", "Debugging"), m.categories.map { it.title })
    }

    @Test fun toggleChangesAndPersists() {
        val st = MemoryStore(); val s = AppSettings(st); val m = model(s)
        val debug = item(m, "debug")
        assertEquals("Aus", debug.display())
        assertTrue(debug.activate())
        assertTrue(s.debugMode)
        assertTrue(AppSettings(st).debugMode)
        assertEquals("Ein", item(model(s), "debug").display())
    }

    @Test fun debugActionsOnlyWorkWithDebugMode() {
        val s = AppSettings(MemoryStore()); val m = model(s)
        item(m, "debugShow").activate()
        assertTrue("ohne Debug-Modus ist die Aktion gesperrt", log.isEmpty())
        assertFalse(item(m, "debugShow").enabled)
        s.debugMode = true
        assertTrue(item(m, "debugShow").enabled)
        item(m, "debugShow").activate()
        assertEquals(listOf("debug"), log)
    }

    @Test fun choiceCyclesAndWraps() {
        val s = AppSettings(MemoryStore()); val m = model(s)
        val cache = item(m, "cache")
        assertEquals("2000 ms", cache.display())
        cache.adjust(+1); assertEquals(3000, s.cacheMs)
        cache.adjust(+1); cache.adjust(+1); assertEquals(1000, s.cacheMs)         // Umlauf
        cache.adjust(-1); assertEquals(5000, s.cacheMs)
    }

    @Test fun choiceWithUnknownCurrentValueJumpsToPreset() {
        val s = AppSettings(MemoryStore()); s.cacheMs = 2500
        val cache = item(model(s), "cache")
        assertEquals("2500", cache.display())
        cache.adjust(+1); assertEquals(1000, s.cacheMs)
    }

    @Test fun stepperClampsAndFormats() {
        val s = AppSettings(MemoryStore()); val m = model(s)
        val audio = item(m, "audio")
        assertEquals("0 ms", audio.display())
        audio.adjust(+1); assertEquals("+50 ms", audio.display())
        s.audioDelayMs = 1990; audio.adjust(+1); assertEquals(2000, s.audioDelayMs)
        assertFalse("am Anschlag keine Aenderung", audio.adjust(+1))
    }

    @Test fun idleTimerChoiceOffAndHours() {
        val s = AppSettings(MemoryStore()); val idle = item(model(s), "idle")
        assertEquals("Aus", idle.display())
        idle.adjust(+1); assertEquals(1, s.idleStopHours); assertEquals("1 h", item(model(s), "idle").display())
        repeat(5) { item(model(s), "idle").adjust(+1) }; assertEquals(12, s.idleStopHours)
        item(model(s), "idle").adjust(+1); assertEquals("Umlauf zurueck auf Aus", 0, s.idleStopHours)
        s.idleStopHours = 99; assertEquals(24, s.idleStopHours)
    }

    @Test fun transportChoiceListsAllModes() {
        val s = AppSettings(MemoryStore()); val m = model(s)
        val t = item(m, "transport")
        val seen = ArrayList<Transport>()
        repeat(3) { t.adjust(+1); seen += s.transport }
        assertEquals(setOf(Transport.TCP, Transport.UDP, Transport.DIRECT), seen.toSet())
    }

    @Test fun epgUrlTextAndRefreshAvailability() {
        val s = AppSettings(MemoryStore()); val m = model(s)
        val url = item(m, "epgUrl") as SettingItem.Text
        assertEquals("nicht gesetzt", url.display())
        assertFalse("ohne URL kein Aktualisieren", item(m, "epgRefresh").enabled)
        url.commit("https://example.org/guide.xml")
        assertEquals("https://example.org/guide.xml", s.epgUrl)
        assertTrue(item(model(s), "epgRefresh").enabled)
        item(model(s), "epgRefresh").activate()
        assertEquals(listOf("refresh"), log)
    }

    @Test fun infoItemsShowLiveValues() {
        val m = model(AppSettings(MemoryStore()))
        assertEquals("1.3.0", item(m, "version").display())
        assertEquals("ok", item(m, "epgStatus").display())
        assertEquals("/x/hometv.log", item(m, "debugLog").display())
    }
}
