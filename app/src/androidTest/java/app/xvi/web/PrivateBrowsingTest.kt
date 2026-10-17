package app.xvi.web

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PrivateBrowsingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun supported() {
        var available = false
        ui { available = BrowserSession.privateAvailable() }
        assumeTrue("WebView needs MULTI_PROFILE and DELETE_BROWSING_DATA", available)
    }
    private fun js(view: WebView, script: String): String {
        val answer = CompletableFuture<String>()
        ui { view.evaluateJavascript(script) { answer.complete(it) } }
        return answer.get(10, TimeUnit.SECONDS)
    }
    private fun load(view: WebView, value: String?) {
        val loaded = CompletableFuture<Unit>()
        ui {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
            }
            view.loadDataWithBaseURL("https://private-test.example/", if (value == null) "<p>probe</p>" else "<script>localStorage.setItem('identity', '$value')</script>", "text/html", "UTF-8", null)
        }
        loaded.get(10, TimeUnit.SECONDS)
    }

    @Test fun isolatesCookiesAndStorageAndCleansOnlyPrivateProfile() {
        supported()
        lateinit var ordinary: BrowserSession
        lateinit var private: BrowserSession
        lateinit var normalWeb: WebView
        lateinit var privateWeb: WebView
        val origin = "https://private-test.example/"
        ui {
            ordinary = BrowserSession()
            private = BrowserSession(true)
            normalWeb = ordinary.createWebView(context)
            privateWeb = private.createWebView(context)
        }
        try {
            load(normalWeb, "ordinary")
            load(privateWeb, "private")
            lateinit var sibling: WebView
            ui { sibling = private.createWebView(context) }
            load(sibling, null)
            assertEquals("\"private\"", js(sibling, "localStorage.getItem('identity')"))
            val cookiesSet = CompletableFuture<Unit>()
            ui {
                ordinary.cookies().setCookie(origin, "identity=ordinary") {
                    private.cookies().setCookie(origin, "identity=private") { cookiesSet.complete(Unit) }
                }
            }
            cookiesSet.get(10, TimeUnit.SECONDS)
            assertEquals("\"ordinary\"", js(normalWeb, "localStorage.getItem('identity')"))
            assertEquals("\"private\"", js(privateWeb, "localStorage.getItem('identity')"))
            val cleared = CompletableFuture<Boolean>()
            lateinit var oldName: String
            ui {
                assertEquals("identity=ordinary", ordinary.cookies().getCookie(origin))
                assertEquals("identity=private", private.cookies().getCookie(origin))
                oldName = WebViewCompat.getProfile(privateWeb).name
                private.end { cleared.complete(it) }
            }
            assertTrue(cleared.get(15, TimeUnit.SECONDS))
            ui {
                assertEquals("identity=ordinary", ordinary.cookies().getCookie(origin))
            }
            assertEquals("\"ordinary\"", js(normalWeb, "localStorage.getItem('identity')"))
            // Inspect the cleaned profile and a new session through actual WebViews.
            lateinit var probe: WebView
            lateinit var next: BrowserSession
            lateinit var nextWeb: WebView
            ui {
                probe = WebView(context)
                // The ended profile may already be deleted; the same name must not expose old data either way.
                WebViewCompat.setProfile(probe, oldName)
                assertNull(WebViewCompat.getProfile(probe).cookieManager.getCookie(origin))
                next = BrowserSession(true)
                nextWeb = next.createWebView(context)
                assertNotEquals(oldName, WebViewCompat.getProfile(nextWeb).name)
                assertNull(next.cookies().getCookie(origin))
            }
            try {
                load(probe, null)
                assertEquals("null", js(probe, "localStorage.getItem('identity')"))
            } finally { ui { probe.destroy(); next.end() } }
        } finally { ui { private.end(); ordinary.end() } }
    }

    @Test fun privateTabsNeverPersistAndClosingGroupKeepsOrdinaryTabs() {
        supported()
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = BrowserStore(activity)
                val prefs = BrowserPrefs(activity)
                activity.persist()
                val before = prefs.restoreTabs()
                activity.switchGroup(true)
                val privateTab = activity.tabs.last()
                privateTab.url = "https://private-only.example/secret"
                privateTab.title = "Secret title"
                val session = privateTab.session
                session.visit(store, "Secret title", "https://private-only.example/secret")
                activity.persist()
                assertEquals(before, prefs.restoreTabs())
                assertFalse(store.entries(false).any { it.url.contains("private-only.example") })
                // Close last private tab via the same action used by the tab list and popups.
                activity.closeTab(activity.tabs.lastIndex)
                assertNull(activity.privateSession)
                assertNull("Undo cannot revive an ended private session", activity.tabManager.lastClosed)
                assertEquals(before, prefs.restoreTabs())
                activity.switchGroup(true)
                assertNotSame(session, activity.privateSession)
                activity.closeGroup(true, null)
                assertNull(activity.privateSession)
                assertEquals(before, prefs.restoreTabs())
                activity.switchGroup(true)
                store.close()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNull(activity.privateSession)
                assertTrue(activity.tabs.all { !it.isPrivate })
            }
        }
    }

    @Test fun closingOrdinaryTabsNeverEntersPrivateMode() {
        supported()
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun currentIsPrivate() = activity.tab!!.isPrivate
                activity.switchGroup(true)
                activity.switchGroup(false)
                assertFalse(currentIsPrivate())
                activity.closeGroup(false, null)
                assertFalse(currentIsPrivate())
                assertNotNull(activity.privateSession)
                activity.closeTab(activity.tabs.indexOf(activity.tab))
                assertFalse(currentIsPrivate())
                assertEquals(1, activity.tabs.count { it.isPrivate })
                activity.closeGroup(true, null)
            }
        }
    }

    @Test fun undoRestoresClosedOrdinaryTabsWithoutTheBlankPlaceholder() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = activity.addTab("", true, activity.normalSession)!!.apply { url = "https://undo.test/1" }
                val second = activity.addTab("", true, activity.normalSession)!!.apply { url = "https://undo.test/2" }
                val before = activity.tabs.toList()
                activity.closeGroup(false, null)
                val placeholder = activity.tabs.single()
                activity.undoCloseTabs()
                // Same tabs in the same order; the blank placeholder is gone.
                assertEquals(before, activity.tabs.toList())
                assertFalse(placeholder in activity.tabs)
                assertSame(before.first(), activity.tab)
                assertTrue(first in activity.tabs)
                assertTrue(second in activity.tabs)
            }
        }
    }
}
