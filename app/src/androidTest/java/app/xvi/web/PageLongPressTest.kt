package app.xvi.web

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PageLongPressTest {
    private fun await(scenario: ActivityScenario<BrowserActivity>, condition: (BrowserActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for page")
    }

    private fun script(scenario: ActivityScenario<BrowserActivity>, js: String): String {
        val result = CompletableFuture<String>()
        scenario.onActivity { it.tab!!.view!!.evaluateJavascript(js) { value -> result.complete(value) } }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun press(scenario: ActivityScenario<BrowserActivity>) {
        var x = 0f
        var y = 0f
        scenario.onActivity {
            val web = it.tab!!.view!!
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            x = location[0] + web.width / 2f
            y = location[1] + web.height / 3f
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 500)
        send(MotionEvent.ACTION_UP)
        instrumentation.waitForIdleSync()
    }

    private fun withPage(cancel: Boolean, image: Boolean = false, check: (ActivityScenario<BrowserActivity>) -> Unit) {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            await(scenario) { it.ready }
            scenario.onActivity {
                val tab = it.addTab("", true, it.normalSession)!!
                val web = it.createWebView(tab)
                tab.view = web
                tab.url = "https://longpress.test/"
                it.showTab(false)
                web.loadDataWithBaseURL(tab.url, """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Long press</title></head><body style="margin:0">
                    ${if (image) """<img style="width:100vw;height:100vh" src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='100' height='100'%3E%3Crect width='100' height='100' fill='blue'/%3E%3C/svg%3E">"""
                    else """<a href="https://example.org/" style="display:block;width:100vw;height:100vh">Press here</a>"""}
                    <div id="menu" style="display:none;position:fixed;bottom:0">Website menu</div>
                    <script>
                    window.menuCount = 0;
                    document.addEventListener('contextmenu', e => {
                        window.menuCount++;
                        if ($cancel) {
                            e.preventDefault();
                            document.getElementById('menu').style.display = 'block';
                        }
                    });
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            await(scenario) { it.tab?.view?.let { web -> web.title == "Long press" && web.progress == 100 } == true && it.hasWindowFocus() }
            check(scenario)
        }
    }

    @Test fun websiteContextMenuDoesNotAlsoOpenBrowserMenu() = withPage(cancel = true) { scenario ->
        press(scenario)
        assertEquals("Website should receive exactly one contextmenu event", "1", script(scenario, "window.menuCount"))
        assertEquals("\"block\"", script(scenario, "document.getElementById('menu').style.display"))
        scenario.onActivity { assertTrue("Browser menu covered the website's own menu", it.hasWindowFocus()) }
    }

    @Test fun ordinaryLinksStillOpenBrowserMenu() = withPage(cancel = false) { scenario ->
        press(scenario)
        assertEquals("1", script(scenario, "window.menuCount"))
        scenario.onActivity { assertFalse("Ordinary link lost its browser menu", it.hasWindowFocus()) }
    }

    @Test fun websiteImageMenuDoesNotAlsoOpenBrowserMenu() = withPage(cancel = true, image = true) { scenario ->
        press(scenario)
        assertEquals("1", script(scenario, "window.menuCount"))
        scenario.onActivity { assertTrue("Browser menu covered the website's image menu", it.hasWindowFocus()) }
    }

    @Test fun ordinaryImagesStillOpenBrowserMenu() = withPage(cancel = false, image = true) { scenario ->
        press(scenario)
        scenario.onActivity { assertFalse("Ordinary image lost its browser menu", it.hasWindowFocus()) }
    }

    @Test fun disabledJavaScriptStillAllowsBrowserMenu() = withPage(cancel = false) { scenario ->
        scenario.onActivity { it.tab!!.view!!.settings.javaScriptEnabled = false }
        press(scenario)
        scenario.onActivity { assertFalse("Disabling JavaScript disabled the browser menu", it.hasWindowFocus()) }
    }

    @Test fun earlyTouchMenuOverlayDoesNotAlsoOpenBrowserMenu() = withPage(cancel = false) { scenario ->
        // Telegram Web A's list opens a portal menu after 200 ms, before Android's long press.
        script(scenario, """
            (() => {
                const row = document.querySelector('a');
                row.addEventListener('contextmenu', e => e.preventDefault());
                row.addEventListener('touchstart', () => {
                    setTimeout(() => {
                        const menu = document.getElementById('menu');
                        menu.style.cssText = 'display:block;position:fixed;inset:0;background:white;z-index:10';
                        window.touchMenuOpened = true;
                        document.addEventListener('touchend', e => {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                        }, { capture: true, once: true });
                    }, 200);
                }, { passive: true });
            })()
        """.trimIndent())
        press(scenario)
        assertEquals("true", script(scenario, "window.touchMenuOpened"))
        scenario.onActivity { assertTrue("Browser menu covered the early website menu", it.hasWindowFocus()) }
    }
}
