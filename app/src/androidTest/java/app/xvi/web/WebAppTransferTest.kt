package app.xvi.web

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.MutableContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebAppTransferTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for window transfer")
    }

    private fun script(page: BrowserTab, code: String): String {
        val result = CompletableFuture<String>()
        instrumentation.runOnMainSync { page.view!!.evaluateJavascript(code) { result.complete(it) } }
        return result.get(5, TimeUnit.SECONDS)
    }

    @Test fun movesTheLivePageAndKeepsItAliveAfterTheBrowserCloses() {
        val monitor = instrumentation.addMonitor(WebAppActivity::class.java.name, null, false)
        var window: WebAppActivity? = null
        try {
            ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
                lateinit var browser: BrowserActivity
                lateinit var page: BrowserTab
                scenario.onActivity { browser = it }
                await { browser.ready }
                scenario.onActivity {
                    it.closeGroup(false)
                    page = it.tab!!
                    page.url = "https://window.test/"
                    page.view = it.createWebView(page)
                    it.showTab(false)
                    page.view!!.loadDataWithBaseURL(page.url, """
                        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>Transfer page</title></head><body>
                        <textarea id="draft"></textarea><div style="height:6000px">Long page</div>
                        </body></html>
                    """.trimIndent(), "text/html", "UTF-8", null)
                }
                await { page.view?.title == "Transfer page" && page.view?.progress == 100 }
                script(page, "document.getElementById('draft').value='unsaved draft'; window.transferMarker=42; history.pushState({}, '', '#reading'); window.scrollTo(0, 700)")
                await { page.view!!.scrollY > 0 && page.url.endsWith("#reading") }
                val original = page.view
                var scroll = 0
                scenario.onActivity {
                    scroll = original!!.scrollY
                    assertTrue(original.canGoBack())
                    it.pageTools.openInWindow(page)
                }
                window = monitor.waitForActivityWithTimeout(10_000) as? WebAppActivity
                assertNotNull("Independent window opened", window)
                await { window!!.tab === page && page.view?.parent != null }
                instrumentation.runOnMainSync {
                    assertFalse(page in browser.tabs)
                    assertEquals("Last moved tab leaves home", "", browser.tab!!.url)
                    assertFalse(browser.tabManager.lastClosed?.items?.any { it.second === page } == true)
                    assertSame(original, window!!.tab!!.view)
                    assertSame(window, (original!!.context as MutableContextWrapper).baseContext)
                    assertTrue(original.canGoBack())
                    assertEquals(scroll, original.scrollY)
                    assertSame(window!!.normalSession, page.session)
                    browser.finish()
                }
                await { browser.isDestroyed }
                assertEquals("\"unsaved draft\"", script(page, "document.getElementById('draft').value"))
                assertEquals("42", script(page, "window.transferMarker"))
                // History callbacks must update the new host after the old activity and session end.
                script(page, "history.pushState({}, '', '#moved')")
                await { page.url.endsWith("#moved") }
            }
        } finally {
            instrumentation.runOnMainSync { window?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun staleTargetsAndNonWebPagesStayInTheBrowser() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            lateinit var browser: BrowserActivity
            scenario.onActivity { browser = it }
            await { browser.ready }
            scenario.onActivity {
                val home = it.addTab("", session = it.normalSession)!!
                val count = it.tabs.size
                it.pageTools.openInWindow(home)
                it.pageTools.openInWindow(BrowserTab("https://closed.test/", session = it.normalSession))
                assertSame(home, it.tab)
                assertEquals(count, it.tabs.size)
            }
        }
    }

    @Test fun aWindowThatDoesNotLaunchLeavesTheOriginalTabInPlace() {
        val monitor = instrumentation.addMonitor(WebAppActivity::class.java.name, ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
                lateinit var browser: BrowserActivity
                scenario.onActivity { browser = it }
                await { browser.ready }
                scenario.onActivity {
                    val page = it.tab!!
                    page.url = "https://blocked-window.test/"
                    val count = it.tabs.size
                    it.pageTools.openInWindow(page)
                    assertTrue(page in it.tabs)
                    assertSame(page, it.tab)
                    assertEquals(count, it.tabs.size)
                    assertEquals(1, monitor.hits)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
