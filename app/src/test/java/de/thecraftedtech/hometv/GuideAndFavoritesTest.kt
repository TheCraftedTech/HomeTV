package de.thecraftedtech.hometv

import de.thecraftedtech.hometv.channels.Channel
import de.thecraftedtech.hometv.channels.ChannelFilter
import de.thecraftedtech.hometv.epg.GuideNavigator
import de.thecraftedtech.hometv.epg.Programme
import de.thecraftedtech.hometv.settings.AppSettings
import de.thecraftedtech.hometv.settings.MemoryStore
import de.thecraftedtech.hometv.settings.SettingItem
import de.thecraftedtech.hometv.settings.SettingsEnv
import de.thecraftedtech.hometv.settings.SettingsModel
import de.thecraftedtech.hometv.zap.Scheduler
import de.thecraftedtech.hometv.zap.ZapController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val H = 3_600_000L

/** Fester Zeitpunkt: "jetzt" = 20:10 (epoch-Millisekunden relativ zu einer glatten Stunde). */
private const val T0 = 1_700_000_000_000L - (1_700_000_000_000L % H)   // glatte Stunde
private const val NOW = T0 + 10 * MIN

private fun prog(startMin: Long, endMin: Long, title: String) = Programme(T0 + startMin * MIN, T0 + endMin * MIN, title, "Beschreibung $title")

class GuideNavigatorTest {
    // Zeile 0: Nachrichten 20:00-20:15 (laeuft), Film 20:15-21:45, Magazin 21:45-22:30, [Luecke], Spaet 23:00-24:00
    private val row0 = arrayOf(prog(-120, -60, "Frueher"), prog(0, 15, "Nachrichten"), prog(15, 105, "Film"), prog(105, 150, "Magazin"), prog(180, 240, "Spaet"))
    // Zeile 1: ohne Programmdaten
    private val row1 = emptyArray<Programme>()
    // Zeile 2: nur eine lange Sendung
    private val row2 = arrayOf(prog(-30, 300, "Marathon"))

    private val rows = listOf(row0, row1, row2)
    private fun nav() = GuideNavigator({ rows.size }, { rows[it] }).also { it.reset(0, NOW) }

    @Test fun startsOnTheRunningProgrammeWithWindowAroundNow() {
        val n = nav()
        assertEquals("Nachrichten", n.selected()?.title)
        assertEquals(NOW, n.anchor)
        assertTrue("jetzt liegt im Fenster", NOW >= n.windowStart && NOW < n.windowStart + n.spanMs)
        assertTrue("etwas Vorlauf links", n.windowStart <= NOW - 20 * MIN)
        assertEquals("Fenster auf 5 Minuten ausgerichtet", 0L, Math.floorMod(n.windowStart, 5 * MIN))
    }

    @Test fun rightAndLeftWalkThroughProgrammesOfTheRow() {
        val n = nav()
        assertTrue(n.moveTime(+1)); assertEquals("Film", n.selected()?.title)
        assertTrue(n.moveTime(+1)); assertEquals("Magazin", n.selected()?.title)
        assertTrue(n.moveTime(-1)); assertEquals("Film", n.selected()?.title)
        assertTrue(n.moveTime(-1)); assertEquals("Nachrichten", n.selected()?.title)
        assertTrue("auch das schon gelaufene Programm ist erreichbar", n.moveTime(-1)); assertEquals("Frueher", n.selected()?.title)
        assertFalse("ganz links ist Schluss", n.moveTime(-1))
    }

    @Test fun gapIsJumpedOverToTheNextProgramme() {
        val n = nav()
        n.moveTime(+1); n.moveTime(+1)                     // Magazin
        assertTrue(n.moveTime(+1))                         // Luecke 22:30-23:00 uebersprungen
        assertEquals("Spaet", n.selected()?.title)
        assertFalse("nach dem letzten gibt es nichts mehr", n.moveTime(+1))
        assertEquals("Spaet", n.selected()?.title)
    }

    @Test fun movingRowsKeepsTheTimeAndPicksTheProgrammeAtThatTime() {
        val n = nav()
        n.moveTime(+1)                                     // Film, anchor = 20:15
        assertTrue(n.moveRow(+1))                          // Zeile ohne Daten
        assertNull(n.selected())
        assertTrue(n.moveRow(+1))
        assertEquals("Marathon", n.selected()?.title)      // laeuft 19:30-01:00, deckt 20:15 ab
        assertFalse("unten ist Schluss (kein Umlauf)", n.moveRow(+1))
        assertTrue(n.moveRow(-2))
        assertEquals("Film", n.selected()?.title)
        assertFalse("oben ist Schluss", n.moveRow(-1))
    }

    @Test fun rowWithoutDataScrollsInThirtyMinuteSteps() {
        val n = nav(); n.moveRow(+1)
        val before = n.anchor
        assertTrue(n.moveTime(+1)); assertEquals(before + 30 * MIN, n.anchor)
        assertTrue(n.moveTime(-1)); assertEquals(before, n.anchor)
    }

    @Test fun windowScrollsPageWiseWhenSelectionLeavesTheView() {
        val n = nav()
        val ws0 = n.windowStart
        repeat(3) { n.moveTime(+1) }                       // bis "Spaet" (23:00, ausserhalb des 3-h-Fensters)
        assertTrue("Fenster ist nachgerueckt", n.windowStart > ws0)
        assertTrue(n.anchor >= n.windowStart && n.anchor < n.windowStart + n.spanMs)
        val ws1 = n.windowStart
        repeat(4) { n.moveTime(-1) }                       // zurueck bis "Frueher"
        assertTrue("und wieder zurueck", n.windowStart < ws1)
        assertTrue(n.anchor >= n.windowStart)
    }

    @Test fun jumpMovesByHoursAndIsBounded() {
        val n = nav()
        assertTrue(n.jump(3 * H)); assertEquals(NOW + 3 * H, n.anchor)
        assertTrue(n.anchor >= n.windowStart && n.anchor < n.windowStart + n.spanMs)
        assertTrue(n.jump(-100 * H)); assertEquals("nicht weiter als 6 h zurueck", NOW - 6 * H, n.anchor)
        assertFalse(n.jump(-1 * H))
        assertTrue(n.jump(1000 * H)); assertEquals("nicht weiter als 7 Tage voraus", NOW + 7 * 24 * H, n.anchor)
        assertTrue(n.windowStart >= NOW - 6 * H - H)
    }

    @Test fun emptyRowListIsHarmless() {
        val n = GuideNavigator({ 0 }, { emptyArray() })
        n.reset(0, NOW)
        assertNull(n.selected()); assertFalse(n.moveRow(+1)); assertFalse(n.moveTime(+1)); assertFalse(n.moveTime(-1))
    }

    @Test fun setRowClampsToTheNewRowCount() {
        var count = 3
        val n = GuideNavigator({ count }, { rows[it.coerceAtMost(2)] })
        n.reset(2, NOW)
        count = 1; n.setRow(n.row)
        assertEquals(0, n.row)
    }
}

class ChannelFilterTest {
    private val ch = listOf("Eins", "Zwei", "Drei", "Vier").map { Channel(it, "rtsp://x/$it") }

    @Test fun offShowsEverything() {
        assertEquals(listOf(0, 1, 2, 3), ChannelFilter.shown(ch, false) { it.name == "Zwei" })
        assertFalse(ChannelFilter.isActive(ch, false) { true })
    }

    @Test fun onShowsOnlyFavouritesInListOrder() {
        val fav = setOf("Vier", "Zwei")
        assertEquals(listOf(1, 3), ChannelFilter.shown(ch, true) { it.name in fav })
        assertTrue(ChannelFilter.isActive(ch, true) { it.name in fav })
    }

    @Test fun onWithoutAnyFavouriteFallsBackToAllChannels() {
        assertEquals(listOf(0, 1, 2, 3), ChannelFilter.shown(ch, true) { false })
        assertFalse("kein Favorit -> Filter wirkt nicht", ChannelFilter.isActive(ch, true) { false })
    }
}

class FavoritesSettingsTest {
    @Test fun toggleAddsRemovesAndPersists() {
        val st = MemoryStore()
        val s = AppSettings(st)
        assertTrue(s.favorites.isEmpty()); assertFalse(s.isFavorite("Das Erste HD"))
        assertTrue(s.toggleFavorite("Das Erste HD"))
        assertTrue(s.toggleFavorite("ZDF HD"))
        assertTrue(AppSettings(st).isFavorite("Das Erste HD"))      // gespeichert
        assertEquals(setOf("Das Erste HD", "ZDF HD"), AppSettings(st).favorites)
        assertFalse(s.toggleFavorite("Das Erste HD"))                // wieder entfernt
        assertEquals(setOf("ZDF HD"), s.favorites)
        s.clearFavorites()
        assertTrue(s.favorites.isEmpty())
    }

    @Test fun favoritesOnlyDefaultsOffAndPersists() {
        val st = MemoryStore()
        assertFalse(AppSettings(st).favoritesOnly)
        AppSettings(st).favoritesOnly = true
        assertTrue(AppSettings(st).favoritesOnly)
    }

    private fun model(s: AppSettings, log: MutableList<String> = ArrayList()) = SettingsModel(s, SettingsEnv(
        versionText = "1.4.0", channelInfo = { "97" }, reloadChannels = {},
        epgStatus = { "" }, epgLastUpdate = { "" }, epgMatchInfo = { "" }, epgCacheInfo = { "" },
        refreshEpg = {}, clearEpgCache = {}, showDebugOverlay = {}, logPath = "/x", openGuide = { log += "guide" },
    ))

    private fun item(m: SettingsModel, id: String): SettingItem = m.categories.flatMap { it.items() }.first { it.id == id }

    @Test fun settingsOfferFavouriteControlsAndGuideShortcut() {
        val s = AppSettings(MemoryStore()); val log = ArrayList<String>()
        val m = model(s, log)
        assertEquals("keine", item(m, "favCount").display())
        assertFalse("ohne Favoriten nichts zu entfernen", item(m, "favClear").enabled)
        s.toggleFavorite("A"); s.toggleFavorite("B")
        assertEquals("2", item(m, "favCount").display())
        assertTrue(item(m, "favClear").enabled)
        item(m, "favClear").activate()
        assertTrue(s.favorites.isEmpty())
        assertEquals("Aus", item(m, "favOnly").display())
        item(m, "favOnly").activate(); assertTrue(s.favoritesOnly)
        item(m, "openGuide").activate(); assertEquals(listOf("guide"), log)
    }
}

class ZapFavouritesTest {
    private class Sched : Scheduler {
        val tasks = ArrayList<Runnable>()
        override fun postDelayed(token: Any, delayMs: Long, action: Runnable) { tasks.clear(); tasks += action }
        override fun cancel(token: Any) { tasks.clear() }
    }

    private val previews = ArrayList<Int>()
    private val commits = ArrayList<Int>()
    private val listener = object : ZapController.Listener {
        override fun onPreview(index: Int) { previews += index }
        override fun onCommit(index: Int) { commits += index }
    }

    @Test fun stepSkipsNonFavouritesInBothDirectionsWithWrap() {
        val fav = setOf(1, 4, 7)
        val sched = Sched()
        val z = ZapController({ 10 }, sched, { 300 }, listener, allowed = { it in fav })
        z.setCurrent(1)
        z.step(+1); assertEquals(4, previews.last())
        z.step(+1); assertEquals(7, previews.last())
        z.step(+1); assertEquals("Umlauf auf den ersten Favoriten", 1, previews.last())
        z.step(-1); assertEquals(7, previews.last())
        sched.tasks.single().run()
        assertEquals(listOf(7), commits)
    }

    @Test fun stepFromNonFavouriteGoesToTheNextFavourite() {
        val sched = Sched()
        val z = ZapController({ 10 }, sched, { 300 }, listener, allowed = { it == 2 || it == 8 })
        z.setCurrent(5)
        z.step(+1); assertEquals(8, previews.last())
        z.setCurrent(5)
        z.step(-1); assertEquals(2, previews.last())
    }

    @Test fun noAllowedChannelFallsBackToPlainStepping() {
        val z = ZapController({ 5 }, Sched(), { 300 }, listener, allowed = { false })
        z.setCurrent(0); z.step(+1)
        assertEquals(1, previews.last())
    }

    @Test fun selectIsNeverFiltered() {
        val z = ZapController({ 10 }, Sched(), { 300 }, listener, allowed = { it == 4 })
        z.setCurrent(4); z.select(6)
        assertEquals(listOf(6), commits)
    }
}
