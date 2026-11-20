package de.thecraftedtech.hometv

import de.thecraftedtech.hometv.core.ListNav
import de.thecraftedtech.hometv.epg.GuideNavigator
import de.thecraftedtech.hometv.epg.Programme
import de.thecraftedtech.hometv.settings.AppSettings
import de.thecraftedtech.hometv.settings.MemoryStore
import de.thecraftedtech.hometv.settings.MenuNavigator
import de.thecraftedtech.hometv.settings.MenuNavigator.Effect
import de.thecraftedtech.hometv.settings.MenuNavigator.Nav
import de.thecraftedtech.hometv.settings.SettingItem
import de.thecraftedtech.hometv.settings.SettingsCategory
import de.thecraftedtech.hometv.settings.SettingsEnv
import de.thecraftedtech.hometv.settings.SettingsModel
import de.thecraftedtech.hometv.ui.HelpContent
import de.thecraftedtech.hometv.ui.RemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListNavTest {
    @Test fun movesInsideTheListNormally() {
        assertEquals(3, ListNav.move(2, +1, 10, wrap = true))
        assertEquals(1, ListNav.move(2, -1, 10, wrap = false))
    }

    @Test fun wrapsOverBothEdgesOnSingleSteps() {
        assertEquals("oben -> ganz unten", 9, ListNav.move(0, -1, 10, wrap = true))
        assertEquals("unten -> ganz oben", 0, ListNav.move(9, +1, 10, wrap = true))
    }

    @Test fun withoutWrapTheEdgesHold() {
        assertEquals(0, ListNav.move(0, -1, 10, wrap = false))
        assertEquals(9, ListNav.move(9, +1, 10, wrap = false))
    }

    @Test fun bigJumpsNeverWrapButClamp() {
        assertEquals(0, ListNav.move(3, -8, 10, wrap = true))
        assertEquals(9, ListNav.move(5, +8, 10, wrap = true))
    }

    @Test fun emptyOrSingleItemLists() {
        assertEquals(0, ListNav.move(0, +1, 0, wrap = true))
        assertEquals(0, ListNav.move(0, +1, 1, wrap = true))
        assertEquals(0, ListNav.move(0, -1, 1, wrap = true))
    }
}

class WrapAndConfirmTest {
    private var ran = 0
    private var flag = false

    private val toggle = SettingItem.Toggle("flag", "Schalter", { flag }, { flag = it })
    private val plain = SettingItem.Action("plain", "Normal", { ran++ })
    private val danger = SettingItem.Action("danger", "Gefahr", { ran += 10 }, confirm = "Wirklich?")
    private val cats = listOf(
        SettingsCategory("a", "A") { listOf(toggle, plain, danger) },
        SettingsCategory("b", "B") { listOf(SettingItem.Info("i", "I", { "x" })) },
        SettingsCategory("c", "C") { listOf(SettingItem.Info("j", "J", { "y" })) },
    )

    @Test fun categoriesWrapOnAFreshPress() {
        val nav = MenuNavigator(cats)
        nav.onNav(Nav.UP)
        assertEquals("von A nach oben -> letzter Bereich", 2, nav.catIndex)
        nav.onNav(Nav.DOWN)
        assertEquals("unten -> wieder oben", 0, nav.catIndex)
    }

    @Test fun itemsWrapOnAFreshPress() {
        val nav = MenuNavigator(cats)
        nav.onNav(Nav.RIGHT)
        nav.onNav(Nav.UP)
        assertEquals(2, nav.itemIndex)
        nav.onNav(Nav.DOWN)
        assertEquals(0, nav.itemIndex)
    }

    @Test fun heldKeyDoesNotWrapAroundTheEnd() {
        val nav = MenuNavigator(cats)
        nav.onNav(Nav.RIGHT)
        assertTrue(nav.onNav(Nav.UP, fresh = false) is Effect.None)   // gehalten: am Rand stehen bleiben
        assertEquals(0, nav.itemIndex)
        nav.onNav(Nav.DOWN, fresh = false); nav.onNav(Nav.DOWN, fresh = false); nav.onNav(Nav.DOWN, fresh = false)
        assertEquals("gehalten bleibt es am unteren Rand", 2, nav.itemIndex)
    }

    @Test fun wrapCanBeSwitchedOff() {
        val nav = MenuNavigator(cats, wrap = { false })
        nav.onNav(Nav.UP)
        assertEquals(0, nav.catIndex)
    }

    @Test fun dangerousActionAsksFirstAndRunsOnlyAfterConfirmation() {
        val nav = MenuNavigator(cats)
        nav.onNav(Nav.RIGHT); nav.onNav(Nav.DOWN); nav.onNav(Nav.DOWN)      // "Gefahr"
        val e = nav.onNav(Nav.OK)
        assertTrue(e is Effect.Confirm)
        assertEquals("nichts passiert vor der Bestaetigung", 0, ran)
        val done = nav.confirmed((e as Effect.Confirm).item) as Effect.Changed
        assertEquals(10, ran)
        assertFalse(done.changed)
    }

    @Test fun normalActionRunsImmediately() {
        val nav = MenuNavigator(cats)
        nav.onNav(Nav.RIGHT); nav.onNav(Nav.DOWN)                          // "Normal"
        assertTrue(nav.onNav(Nav.OK) is Effect.Changed)
        assertEquals(1, ran)
    }
}

class ResetAndControlsSettingsTest {
    @Test fun newControlSettingsHaveSensibleDefaults() {
        val s = AppSettings(MemoryStore())
        assertFalse("Kanaltasten normal", s.invertChannelKeys)
        assertTrue("Umspringen an", s.wrapLists)
    }

    @Test fun invertAndWrapPersist() {
        val st = MemoryStore()
        AppSettings(st).apply { invertChannelKeys = true; wrapLists = false }
        val s = AppSettings(st)
        assertTrue(s.invertChannelKeys); assertFalse(s.wrapLists)
    }

    @Test fun resetSettingsRestoresDefaultsButKeepsFavouritesAndLastChannel() {
        val st = MemoryStore()
        val s = AppSettings(st)
        s.cacheMs = 5000; s.audioDelayMs = 300; s.invertChannelKeys = true; s.wrapLists = false
        s.epgUrl = "https://example.org/e.xml"; s.fritzboxHost = "10.1.1.1"; s.playlistUrl = "http://x/y.m3u"; s.debugMode = true
        s.favoritesOnly = true
        s.toggleFavorite("ZDF HD"); s.lastChannelName = "ZDF HD"
        s.resetSettings()
        assertEquals(AppSettings.DEFAULT_CACHE_MS, s.cacheMs); assertEquals(0, s.audioDelayMs)
        assertFalse(s.invertChannelKeys); assertTrue(s.wrapLists)
        assertEquals("", s.epgUrl); assertEquals(s.defaultHost, s.fritzboxHost); assertEquals("", s.playlistUrl)
        assertFalse(s.debugMode); assertFalse(s.favoritesOnly)
        assertEquals(setOf("ZDF HD"), s.favorites)
        assertEquals("ZDF HD", s.lastChannelName)
    }

    @Test fun resetAllWipesEverythingIncludingFavourites() {
        val s = AppSettings(MemoryStore())
        s.cacheMs = 5000; s.toggleFavorite("ZDF HD"); s.lastChannelName = "ZDF HD"; s.epgLastSuccessMs = 123
        s.resetAll()
        assertEquals(AppSettings.DEFAULT_CACHE_MS, s.cacheMs)
        assertTrue(s.favorites.isEmpty()); assertEquals(null, s.lastChannelName); assertEquals(0L, s.epgLastSuccessMs)
    }

    private fun model(s: AppSettings) = SettingsModel(s, SettingsEnv(
        versionText = "1.5.0", channelInfo = { "97" }, reloadChannels = {},
        epgStatus = { "" }, epgLastUpdate = { "" }, epgMatchInfo = { "" }, epgCacheInfo = { "" },
        refreshEpg = {}, clearEpgCache = {}, showDebugOverlay = {}, logPath = "/x",
    ))

    private fun item(m: SettingsModel, id: String): SettingItem = m.categories.flatMap { it.items() }.first { it.id == id }

    @Test fun resetItemsAskForConfirmationAndWork() {
        val s = AppSettings(MemoryStore()); val m = model(s)
        val soft = item(m, "resetSettings") as SettingItem.Action
        val hard = item(m, "resetAll") as SettingItem.Action
        val clearFav = item(m, "favClear") as SettingItem.Action
        assertTrue(soft.confirm != null && hard.confirm != null && clearFav.confirm != null)
        s.cacheMs = 3000; s.toggleFavorite("A")
        soft.activate()
        assertEquals(AppSettings.DEFAULT_CACHE_MS, s.cacheMs); assertEquals(setOf("A"), s.favorites)
        hard.activate()
        assertTrue(s.favorites.isEmpty())
    }

    @Test fun controlsCategoryContainsTheNewTogglesAndTheMovedItems() {
        val s = AppSettings(MemoryStore()); val m = model(s)
        val controls = m.categories.first { it.id == "controls" }.items().map { it.id }
        assertEquals(listOf("invertKeys", "wrapLists", "debounce", "listSecs"), controls)
        val inv = item(m, "invertKeys")
        assertEquals("Aus", inv.display()); inv.activate(); assertTrue(s.invertChannelKeys)
        assertEquals("Ein", item(m, "wrapLists").display())
    }

    @Test fun versionAndCreditStayInTheGeneralCategory() {
        val m = model(AppSettings(MemoryStore()))
        val general = m.categories.first { it.id == "general" }.items()
        assertEquals("1.5.0", general.first { it.id == "version" }.display())
        assertEquals("Samuel Zenker", general.first { it.id == "developer" }.display())
    }
}

class HelpContentTest {
    @Test fun hasAllPagesWithKeysAndTexts() {
        val pages = HelpContent.pages(false)
        assertEquals(listOf("Fernsehbild", "Senderliste", "Programmübersicht", "Favoriten-Leiste", "Einstellungen"), pages.map { it.title })
        for (p in pages) {
            assertTrue("${p.title} hat Zeilen", p.rows.isNotEmpty())
            for (r in p.rows) { assertTrue(r.keys.isNotEmpty()); assertTrue(r.text.isNotBlank()) }
        }
    }

    @Test fun invertedChannelKeysSwapTheDescriptions() {
        val normal = HelpContent.pages(false)[0].rows
        val inverted = HelpContent.pages(true)[0].rows
        assertTrue(normal[0].text.startsWith("Nächster Sender")); assertTrue(normal[1].text.startsWith("Vorheriger Sender"))
        assertTrue(inverted[0].text.startsWith("Vorheriger Sender")); assertTrue(inverted[1].text.startsWith("Nächster Sender"))
        assertEquals(normal[0].keys, inverted[0].keys)
    }

    @Test fun longPressRowsAreMarked() {
        val tv = HelpContent.pages(false)[0].rows
        val long = tv.filter { r -> r.keys.any { it.long } }
        assertTrue(long.isNotEmpty())
        assertTrue("OK lang ist die Programmuebersicht", long.any { r -> r.keys.single().key == RemoteKey.OK && r.text.contains("Programmübersicht") })
        // Favoriten per langem OK in Liste, Uebersicht und Leiste
        for (title in listOf("Senderliste", "Programmübersicht", "Favoriten-Leiste")) {
            val rows = HelpContent.pages(false).first { it.title == title }.rows
            assertTrue("$title: OK lang", rows.any { r -> r.keys.any { it.long && it.key == RemoteKey.OK } })
        }
    }

    @Test fun everyRemoteKeyAppearsSomewhere() {
        val used = HelpContent.pages(false).flatMap { p -> p.rows.flatMap { r -> r.keys.map { it.key } } }.toSet()
        assertEquals(RemoteKey.values().toSet(), used)
    }
}

class GuideWrapTest {
    private val rows = List(4) { arrayOf(Programme(0L, 10_000_000_000L, "x", null)) }
    private fun nav() = GuideNavigator({ rows.size }, { rows[it] }).also { it.reset(0, 1000L) }

    @Test fun rowsWrapOnlyWhenAsked() {
        val n = nav()
        assertFalse(n.moveRow(-1)); assertEquals(0, n.row)
        assertTrue(n.moveRow(-1, wrap = true)); assertEquals("oben -> ganz unten", 3, n.row)
        assertTrue(n.moveRow(+1, wrap = true)); assertEquals("unten -> ganz oben", 0, n.row)
        assertNotEquals(false, n.moveRow(+3)); assertEquals(3, n.row)
        assertFalse(n.moveRow(+1)); assertEquals(3, n.row)
    }
}
