package de.thecraftedtech.hometv

import de.thecraftedtech.hometv.channels.Playlist
import de.thecraftedtech.hometv.settings.AppSettings
import de.thecraftedtech.hometv.settings.MemoryStore
import de.thecraftedtech.hometv.settings.MenuNavigator
import de.thecraftedtech.hometv.settings.MenuNavigator.Effect
import de.thecraftedtech.hometv.settings.MenuNavigator.Mode
import de.thecraftedtech.hometv.settings.MenuNavigator.Nav
import de.thecraftedtech.hometv.settings.SettingItem
import de.thecraftedtech.hometv.settings.SettingsCategory
import de.thecraftedtech.hometv.settings.SettingsEnv
import de.thecraftedtech.hometv.settings.SettingsModel
import de.thecraftedtech.hometv.ui.remainingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Bedienlogik des Einstellungsmenues: markieren (hover) aendert nichts, OK "klickt" an, Bearbeiten-Modus aendert Werte. */
class MenuNavigatorTest {
    private var flag = false
    private var choice = "b"
    private var amount = 0
    private var text = "x"
    private var ran = 0
    private var actionEnabled = true

    private val toggle = SettingItem.Toggle("flag", "Schalter", { flag }, { flag = it })
    private val choiceItem = SettingItem.Choice("choice", "Auswahl", listOf("A" to "a", "B" to "b", "C" to "c"), { choice }, { choice = it })
    private val stepper = SettingItem.Stepper("amount", "Zahl", { amount }, { amount = it }, 1, 0, 2, { "$it" })
    private val textItem = SettingItem.Text("text", "Text", { text }, { text = it }, "hint")
    private val action = SettingItem.Action("act", "Aktion", { ran++ }, { actionEnabled })
    private val info = SettingItem.Info("info", "Info", { "v" })

    // Reihenfolge: 0 Info, 1 Schalter, 2 Auswahl, 3 Zahl, 4 Text, 5 Aktion
    private val nav = MenuNavigator(listOf(
        SettingsCategory("a", "A") { listOf(info, toggle, choiceItem, stepper, textItem, action) },
        SettingsCategory("b", "B") { listOf(SettingItem.Info("i2", "Zwei", { "2" })) },
    ))

    private fun goToItem(index: Int) {
        nav.onNav(Nav.RIGHT)                       // Bereich -> Eintraege
        repeat(index) { nav.onNav(Nav.DOWN) }
    }

    @Test fun startsInCategoriesAndRightEnters() {
        assertEquals(Mode.CATEGORIES, nav.mode)
        assertTrue(nav.onNav(Nav.RIGHT) is Effect.Redraw)
        assertEquals(Mode.ITEMS, nav.mode)
    }

    @Test fun categoryMoveResetsItemIndexAndClampsAtEnds() {
        goToItem(3)
        nav.onNav(Nav.LEFT)                        // zurueck zu den Bereichen
        assertEquals(Mode.CATEGORIES, nav.mode)
        assertTrue(nav.onNav(Nav.UP) is Effect.None)   // schon ganz oben
        assertTrue(nav.onNav(Nav.DOWN) is Effect.Redraw)
        assertEquals(1, nav.catIndex)
        assertEquals(0, nav.itemIndex)
        assertTrue(nav.onNav(Nav.DOWN) is Effect.None) // schon ganz unten
    }

    @Test fun hoveringNeverChangesValues() {
        goToItem(2)                                // Auswahl markiert
        nav.onNav(Nav.RIGHT)                       // frueher: Wert geaendert
        assertEquals("b", choice)
        assertEquals(Mode.ITEMS, nav.mode)
        nav.onNav(Nav.UP); nav.onNav(Nav.DOWN); nav.onNav(Nav.DOWN)   // Schalter, Auswahl, Zahl
        assertEquals("b", choice); assertEquals(0, amount); assertFalse(flag)
    }

    @Test fun leftInItemsGoesBackToCategories() {
        goToItem(3)
        nav.onNav(Nav.LEFT)
        assertEquals(Mode.CATEGORIES, nav.mode)
        assertEquals(0, amount)
    }

    @Test fun okTogglesSwitchesImmediately() {
        goToItem(1)
        val e = nav.onNav(Nav.OK) as Effect.Changed
        assertTrue(e.changed); assertTrue(flag)
        assertEquals(Mode.ITEMS, nav.mode)         // kein Bearbeiten-Modus
        nav.onNav(Nav.OK)
        assertFalse(flag)
    }

    @Test fun okOnChoiceEntersEditingThenArrowsChangeAndOkEnds() {
        goToItem(2)
        assertTrue(nav.onNav(Nav.OK) is Effect.Redraw)
        assertEquals(Mode.EDITING, nav.mode)
        assertEquals("b", choice)                  // OK allein aendert noch nichts
        assertTrue((nav.onNav(Nav.RIGHT) as Effect.Changed).changed); assertEquals("c", choice)
        nav.onNav(Nav.LEFT); nav.onNav(Nav.LEFT); assertEquals("a", choice)
        nav.onNav(Nav.OK)
        assertEquals(Mode.ITEMS, nav.mode)
        nav.onNav(Nav.RIGHT)                       // ausserhalb des Bearbeitens: nichts
        assertEquals("a", choice)
    }

    @Test fun editingUpDownAlsoAdjustsAndNeverMovesSelection() {
        goToItem(3)
        nav.onNav(Nav.OK)
        nav.onNav(Nav.UP); assertEquals(1, amount)
        nav.onNav(Nav.DOWN); nav.onNav(Nav.DOWN); assertEquals(0, amount)   // unten bei 0 begrenzt
        assertEquals(3, nav.itemIndex)
        val atLimit = nav.onNav(Nav.LEFT) as Effect.Changed
        assertFalse("am Limit keine Aenderung", atLimit.changed)
    }

    @Test fun backLeavesOneLevelAtATime() {
        goToItem(2)
        nav.onNav(Nav.OK)
        assertEquals(Mode.EDITING, nav.mode)
        nav.onNav(Nav.BACK); assertEquals(Mode.ITEMS, nav.mode)
        nav.onNav(Nav.BACK); assertEquals(Mode.CATEGORIES, nav.mode)
        assertTrue(nav.onNav(Nav.BACK) is Effect.Close)
    }

    @Test fun menuKeyClosesFromEveryLevelAndEditingEnds() {
        goToItem(2); nav.onNav(Nav.OK)
        assertTrue(nav.onNav(Nav.MENU) is Effect.Close)
        assertEquals(Mode.ITEMS, nav.mode)
        nav.reset()
        assertEquals(Mode.CATEGORIES, nav.mode)
        assertTrue(nav.onNav(Nav.MENU) is Effect.Close)
    }

    @Test fun leftInCategoriesCloses() {
        assertTrue(nav.onNav(Nav.LEFT) is Effect.Close)
    }

    @Test fun textItemRequestsDialogWithoutChangingAnything() {
        goToItem(4)
        val e = nav.onNav(Nav.OK) as Effect.EditText
        assertEquals("text", e.item.id)
        assertEquals("x", text)
        assertEquals(Mode.ITEMS, nav.mode)
    }

    @Test fun actionRunsOnlyWhenEnabled() {
        goToItem(5)
        val e = nav.onNav(Nav.OK) as Effect.Changed
        assertFalse(e.changed); assertEquals(1, ran)
        actionEnabled = false
        assertTrue(nav.onNav(Nav.OK) is Effect.None)
        assertEquals(1, ran)
    }

    @Test fun infoRowIsOnlyMarkedNeverActivated() {
        nav.onNav(Nav.RIGHT)                       // Info (Index 0)
        assertTrue(nav.onNav(Nav.OK) is Effect.None)
        assertEquals(Mode.ITEMS, nav.mode)
    }

    @Test fun resetAlwaysStartsInCategories() {
        goToItem(2); nav.onNav(Nav.OK)
        nav.reset()
        assertEquals(Mode.CATEGORIES, nav.mode)
    }
}

class SourceSettingsTest {
    @Test fun hostDefaultsToFritzBoxAndNormalizes() {
        val s = AppSettings(MemoryStore(), "192.168.200.1")
        assertEquals("192.168.200.1", s.fritzboxHost)
        s.fritzboxHost = " http://192.168.178.1/dvb/m3u/tvhd.m3u "
        assertEquals("192.168.178.1", s.fritzboxHost)
        s.fritzboxHost = "https://box.local:8080/x"
        assertEquals("box.local:8080", s.fritzboxHost)
        s.fritzboxHost = "   "
        assertEquals("leer = Standard", "192.168.200.1", s.fritzboxHost)
    }

    @Test fun hostIsPersistedButDefaultStoresNothing() {
        val st = MemoryStore()
        AppSettings(st, "192.168.200.1").fritzboxHost = "10.0.0.5"
        assertEquals("10.0.0.5", AppSettings(st, "192.168.200.1").fritzboxHost)
        AppSettings(st, "192.168.200.1").fritzboxHost = "192.168.200.1"
        assertEquals("", st.getString("fritzboxHost", "x"))
    }

    @Test fun customPlaylistUrlIsTrimmedAndSwitchesSource() {
        val s = AppSettings(MemoryStore())
        assertFalse(s.customPlaylist); assertEquals("", s.playlistUrl)
        s.playlistUrl = "  http://example.org/tv.m3u "
        assertEquals("http://example.org/tv.m3u", s.playlistUrl)
        assertTrue(s.customPlaylist)
        s.playlistUrl = ""
        assertFalse(s.customPlaylist)
    }

    private fun model(s: AppSettings) = SettingsModel(s, SettingsEnv(
        versionText = "1.3.0", channelInfo = { "97" }, reloadChannels = {},
        epgStatus = { "" }, epgLastUpdate = { "" }, epgMatchInfo = { "" }, epgCacheInfo = { "" },
        refreshEpg = {}, clearEpgCache = {}, showDebugOverlay = {}, logPath = "/x",
    ))

    private fun item(m: SettingsModel, id: String) = m.categories.flatMap { it.items() }.first { it.id == id }

    @Test fun generalCategoryShowsPlainVersionCreditAndSourceFields() {
        val s = AppSettings(MemoryStore())
        val m = model(s)
        val version = item(m, "version").display()
        assertEquals("1.3.0", version)
        assertFalse("keine Code-Angabe in Klammern", version.contains("Code") || version.contains("("))
        assertEquals("Samuel Zenker", item(m, "developer").display())
        assertEquals("192.168.200.1", item(m, "host").display())
        assertEquals("FRITZ!Box verwenden", item(m, "playlistUrl").display())
        (item(m, "playlistUrl") as SettingItem.Text).commit("http://example.org/tv.m3u")
        assertEquals("http://example.org/tv.m3u", item(model(s), "playlistUrl").display())
    }
}

class PlaylistSourceTest {

    /** Minimaler HTTP-Server (ServerSocket – com.sun.net.httpserver steht im Android-Testklassenpfad nicht zur Verfuegung). */
    private fun serve(status: Int, body: String, block: (port: Int) -> Unit) {
        ServerSocket(0).use { server ->
            val t = thread {
                runCatching {
                    server.accept().use { c ->
                        val r = c.getInputStream().bufferedReader()
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $status X\r\nContent-Type: audio/x-mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                    }
                }
            }
            block(server.localPort)
            t.join(3000)
        }
    }

    private val m3u = "#EXTM3U\n#EXTINF:0,Eins\nrtsp://10.0.0.1/stream=1\n#EXTINF:0,Zwei\nrtsp://10.0.0.1/stream=2\n#EXTINF:0,Web\nhttp://x/y.ts\n"

    @Test fun fetchesCustomUrlAndKeepsOnlyRtspChannels() {
        serve(200, m3u) { port ->
            val ch = Playlist.fetchUrl("http://127.0.0.1:$port/list.m3u", "Senderliste")
            assertEquals(listOf("Eins", "Zwei"), ch.map { it.name })
        }
    }

    @Test fun addressWithoutSchemeIsTreatedAsHttp() {
        serve(200, m3u) { port ->
            assertEquals(2, Playlist.fetchUrl("127.0.0.1:$port/list.m3u", "Senderliste").size)
        }
    }

    @Test fun httpErrorGivesReadableMessage() {
        serve(404, "nope") { port ->
            try { Playlist.fetchUrl("http://127.0.0.1:$port/x.m3u", "Senderliste"); fail("erwartet PlaylistException") }
            catch (e: Playlist.PlaylistException) { assertTrue(e.message!!, e.message!!.contains("404")) }
        }
    }

    @Test fun unsupportedSchemeIsRejectedWithoutNetwork() {
        try { Playlist.fetchUrl("ftp://example.org/x.m3u", "Senderliste"); fail("erwartet PlaylistException") }
        catch (e: Playlist.PlaylistException) { assertTrue(e.message!!, e.message!!.contains("http")) }
    }

    @Test fun listWithoutRtspChannelsParsesToEmpty() {
        serve(200, "#EXTM3U\n#EXTINF:0,Nur Web\nhttp://x/y.ts\n") { port ->
            assertTrue(Playlist.fetchUrl("http://127.0.0.1:$port/l.m3u", "Senderliste").isEmpty())
        }
    }

    @Test fun cacheIsSeparatePerSource() {
        val box = Playlist.cacheName("192.168.200.1", "")
        assertEquals(box, Playlist.cacheName("192.168.200.1", ""))
        assertNotEquals(box, Playlist.cacheName("192.168.178.1", ""))
        val url = Playlist.cacheName("192.168.200.1", "http://example.org/a.m3u")
        assertNotEquals(box, url)
        assertNotEquals(url, Playlist.cacheName("192.168.200.1", "http://example.org/b.m3u"))
        assertEquals("eigene URL hat Vorrang vor der Box-Adresse", url, Playlist.cacheName("10.1.1.1", " http://example.org/a.m3u "))
    }
}

class RemainingTextTest {
    @Test fun formatsMinutesAndHours() {
        assertEquals("noch 23 min", remainingText(23 * 60_000L + 10_000))
        assertEquals("noch 1:05 h", remainingText(65 * 60_000L))
        assertEquals("noch 2:00 h", remainingText(120 * 60_000L))
        assertEquals("gleich Ende", remainingText(30_000))
        assertEquals("", remainingText(0))
        assertEquals("", remainingText(-5_000))
    }
}
