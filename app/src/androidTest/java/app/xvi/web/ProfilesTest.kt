package app.xvi.web

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProfilesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> ui(action: () -> T): T { val out = AtomicReference<T>(); instrumentation.runOnMainSync { out.set(action()) }; return out.get() }
    private val created = mutableListOf<String>()
    private fun profile(name: String): BrowserProfile = ui { BrowserProfiles.create(context, name)!! }.also { created += it.id }
    private fun link(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url), context, BrowserActivity::class.java)

    @Before fun supported() {
        assumeTrue("WebView needs MULTI_PROFILE and DELETE_BROWSING_DATA", ui { BrowserProfiles.supported })
    }

    @After fun cleanUp() {
        ui {
            BrowserProfiles.setActive(context, BrowserProfile.MAIN)
            BrowserProfiles.setPreferred(context, BrowserProfile.MAIN)
            created.forEach { BrowserProfiles.remove(context, it) }
        }
    }

    /**
     * The browser showing now. An ActivityScenario would lose it: it follows activities by their intent,
     * and a link arriving replaces that intent.
     */
    private fun shown(): BrowserActivity? = ui {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<BrowserActivity>().firstOrNull()
    }

    private fun waitForShown(what: String, condition: (BrowserActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            val now = shown()
            if (now != null && ui { condition(now) }) return
            SystemClock.sleep(100)
        }
        val last = shown()
        fail("Timed out waiting for $what; shows ${last?.let { b -> ui { b.profile.id to b.tabs.map { it.url } } }}, active ${ui { BrowserProfiles.active(context).id }}")
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            if (ui(condition)) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $what")
    }

    @Test fun cookiesAreSeparateAndSharedWithinAProfile() {
        val work = profile("工作")
        val origin = "https://profile-test.example/"
        ui {
            val main = BrowserSession()
            val first = BrowserSession(namedProfile = work.webProfile)
            val second = BrowserSession(namedProfile = work.webProfile)
            main.cookies().removeAllCookies(null)
            first.cookies().setCookie(origin, "who=work")
            assertTrue(second.cookies().getCookie(origin).orEmpty().contains("who=work"))
            assertFalse(main.cookies().getCookie(origin).orEmpty().contains("who=work"))
        }
    }

    @Test fun tabsAndStoresAreSeparate() {
        val work = profile("工作")
        val main = BrowserPrefs(context)
        val before = main.restoreTabs()
        val prefs = BrowserPrefs(context, work.id)
        assertTrue("a new profile starts without tabs", prefs.restoreTabs().isEmpty())
        prefs.saveTabs(listOf(SavedTab("https://work.example/", "工作")), 0)
        assertEquals(before, BrowserPrefs(context).restoreTabs())
        assertEquals("https://work.example/", BrowserPrefs(context, work.id).restoreTabs().single().url)
        assertNotSame(BrowserStore.get(context), BrowserStore.get(context, work.id))
        assertSame(BrowserStore.get(context, work.id), BrowserStore.get(context, work.id))
        assertNotSame(SiteChoices.get(BrowserStore.get(context)), SiteChoices.get(BrowserStore.get(context, work.id)))
        assertTrue(context.getDatabasePath("browser-${work.id}.db").exists() || BrowserStore.get(context, work.id).readableDatabase.isOpen)
    }

    @Test fun removingDeletesItsFilesAndNeverTheMainProfile() {
        val gone = profile("临时")
        BrowserStore.get(context, gone.id).readableDatabase
        BrowserPrefs(context, gone.id).saveTabs(listOf(SavedTab("https://gone.example/", "")), 0)
        assertTrue(context.getDatabasePath("browser-${gone.id}.db").exists())
        assertTrue(ui { BrowserProfiles.remove(context, gone.id) })
        created -= gone.id
        assertFalse(context.getDatabasePath("browser-${gone.id}.db").exists())
        assertTrue(BrowserPrefs(context, gone.id).restoreTabs().isEmpty())
        assertNull(ui { BrowserProfiles.usable(context, gone.id) })
        assertFalse(ui { BrowserProfiles.remove(context, BrowserProfile.MAIN) })
    }

    @Test fun webAppWindowsAreToldApartByProfile() {
        val work = profile("工作")
        val url = "https://app.example/start"
        val main = WebAppActivity.launchIntent(context, url, "应用")
        val other = WebAppActivity.launchIntent(context, url, "应用", work.id)
        assertEquals(Uri.parse(url), main.data)
        assertNotEquals(main.data, other.data)
        assertEquals(url, WebAppActivity.address(main))
        assertEquals(url, WebAppActivity.address(other))
        assertNotEquals(WebAppActivity.shortcutId(url), WebAppActivity.shortcutId(url, work.id))
    }

    @Test fun linkStartingTheBrowserOpensInThePreferredProfile() {
        val work = profile("工作")
        ui { BrowserProfiles.setPreferred(context, work.id) }
        val url = "https://profile-start.example/"
        ActivityScenario.launch<BrowserActivity>(link(url)).use { scenario ->
            var opened: BrowserActivity? = null
            scenario.onActivity { opened = it }
            waitFor("the link's tab") { opened!!.tabs.any { it.url == url } }
            assertEquals(work.id, ui { opened!!.profile.id })
            assertEquals(work.id, ui { BrowserProfiles.active(context).id })
        }
    }

    @Test fun linkArrivingLaterSwitchesToThePreferredProfileAndBack() {
        val work = profile("工作")
        ui { BrowserProfiles.setPreferred(context, work.id) }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForShown("the browser") { it.ready }
        val first = shown()!!
        try {
            assertEquals("opening the app stays on the profile last shown", BrowserProfile.MAIN, ui { first.profile.id })
            val url = "https://profile-later.example/"
            context.startActivity(link(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForShown("the switch") { it !== first && it.profile.id == work.id && it.tabs.any { tab -> tab.url == url } }

            // Our own icons name their profile, so the main profile can be reached the same way.
            val back = "https://profile-main.example/"
            context.startActivity(link(back).putExtra(BrowserProfiles.EXTRA_PROFILE, BrowserProfile.MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForShown("the switch back") { it.profile.isMain && it.tabs.any { tab -> tab.url == back } }
            val last = shown()!!
            assertFalse("the other profile's tabs stay with it", ui { last.tabs.any { it.url == url } })
            assertTrue("and are there to come back to", BrowserPrefs(context, work.id).restoreTabs().any { it.url == url })
            // A later start must not open either link again, nor lose the main profile's tabs to the switch.
            ui { last.persist() }
            assertTrue(BrowserPrefs(context).restoreTabs().any { it.url == back })
        } finally {
            shown()?.let { browser -> ui { browser.finish() } }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun scriptValuesStayWithTheirProfile() {
        val work = profile("工作")
        val code = "// ==UserScript==\n// @name profile-values\n// @match https://profile-values.example/*\n// ==/UserScript==\n"
        val script = UserScript(code, requireNotNull(UserScriptMeta.parse(code)))
        ui {
            UserScripts.ensureLoaded(context)
            UserScripts.install(UserScripts.Pending(script, null))
        }
        try {
            val scope = ui { BrowserSession(namedProfile = work.webProfile).scriptScope }
            ui { UserScripts.restore(listOf(BrowserBackup.Script(script, mapOf("who" to "work"))), scope) }
            fun stored(from: BrowserSession?) = ui { UserScripts.backup(from).first { it.script.id == script.id }.values }
            assertEquals(mapOf("who" to "work"), stored(scope))
            assertTrue("the main profile does not see them", stored(null).isEmpty())
            val file = java.io.File(context.filesDir, "userscripts/profiles/${work.webProfile}/${script.id}.values.json")
            waitFor("the saved values") { file.exists() }
            ui { BrowserProfiles.remove(context, work.id) }
            created -= work.id
            waitFor("their removal") { !file.exists() }
        } finally {
            ui { UserScripts.remove(script.id) }
        }
    }

    @Test fun webAppWindowOpensInItsProfileAndClosesWithIt() {
        val work = profile("工作")
        val monitor = instrumentation.addMonitor(WebAppActivity::class.java.name, null, false)
        try {
            context.startActivity(WebAppActivity.launchIntent(context, "https://profile-app.example/", "应用", work.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val window = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as WebAppActivity
            waitFor("the window") { window.tab != null }
            assertEquals(work.id, ui { window.profile.id })
            assertEquals(work.webProfile, ui { window.normalSession.namedProfile })
            assertSame(BrowserStore.get(context, work.id), ui { window.store })
            ui { BrowserProfiles.remove(context, work.id) }
            created -= work.id
            waitFor("the window to close") { window.isFinishing || window.isDestroyed }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun exitCleanupReachesEveryProfile() {
        val work = profile("工作")
        val general = BrowserPrefs(context)
        val before = general.clearOnExit
        val mainTabs = general.restoreTabs()
        val selected = general.selectedTab
        BrowserPrefs(context, work.id).saveTabs(listOf(SavedTab("https://work.example/", "")), 0)
        try {
            general.clearOnExit = setOf(ExitCleanup.TABS)
            val done = java.util.concurrent.CountDownLatch(1)
            ActivityScenario.launch<BrowserActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!).use { scenario ->
                scenario.onActivity { ExitCleanup.run(it) { done.countDown() } }
                assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(BrowserPrefs(context, work.id).restoreTabs().isEmpty())
            }
        } finally {
            general.clearOnExit = before
            general.exitCleanupPending = false
            general.saveTabs(mainTabs, selected)
        }
    }
}
