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
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(owner: Any, name: String, vararg args: Any?): Any? = owner.javaClass.declaredMethods
        .first { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(owner, *args)
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
                invoke(activity, "persist")
                val before = store.restoreTabs()
                invoke(activity, "switchGroup", true)
                val tabs = field(activity, "tabs").get(activity) as List<*>
                val privateTab = tabs.last()!!
                field(privateTab, "url").set(privateTab, "https://private-only.example/secret")
                field(privateTab, "title").set(privateTab, "Secret title")
                val session = field(privateTab, "session").get(privateTab) as BrowserSession
                session.visit(store, "Secret title", "https://private-only.example/secret")
                invoke(activity, "persist")
                assertEquals(before, store.restoreTabs())
                assertFalse(store.entries(false).any { it.url.contains("private-only.example") })
                // Close last private tab via the same action used by the tab list and popups.
                invoke(activity, "closeTab", tabs.lastIndex)
                assertNull(field(activity, "privateSession").get(activity))
                assertEquals(before, store.restoreTabs())
                invoke(activity, "switchGroup", true)
                val fresh = field(activity, "privateSession").get(activity)
                assertNotSame(session, fresh)
                invoke(activity, "closeGroup", true, null)
                assertNull(field(activity, "privateSession").get(activity))
                assertEquals(before, store.restoreTabs())
                invoke(activity, "switchGroup", true)
                store.close()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNull(field(activity, "privateSession").get(activity))
                val tabs = field(activity, "tabs").get(activity) as List<*>
                assertTrue(tabs.all { !(field(it!!, "session").get(it) as BrowserSession).isPrivate })
            }
        }
    }

    @Test fun closingOrdinaryTabsNeverEntersPrivateMode() {
        supported()
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun current() = invoke(activity, "getTab")!!
                fun currentIsPrivate() = (field(current(), "session").get(current()) as BrowserSession).isPrivate
                val tabs = field(activity, "tabs").get(activity) as List<*>
                invoke(activity, "switchGroup", true)
                invoke(activity, "switchGroup", false)
                assertFalse(currentIsPrivate())
                invoke(activity, "closeGroup", false, null)
                assertFalse(currentIsPrivate())
                assertNotNull(field(activity, "privateSession").get(activity))
                invoke(activity, "closeTab", tabs.indexOf(current()))
                assertFalse(currentIsPrivate())
                assertEquals(1, tabs.count { (field(it!!, "session").get(it) as BrowserSession).isPrivate })
                invoke(activity, "closeGroup", true, null)
            }
        }
    }
}
