package app.xvi.web

import android.webkit.WebView
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.webkit.WebViewFeature
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SiteDataLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private var observed = ""

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var ready = false
            ui { ready = condition() }
            if (ready) return
            Thread.sleep(25)
        }
        fail("Page reset did not reach the expected state: $observed")
    }

    @Test fun releasesTheWritingPageBeforeDeletionAndRestoresACleanDocument() = checkReset(true)
    @Test fun clearsAStoppedPageWithoutPendingWrites() = checkReset(false)

    private fun checkReset(continuous: Boolean) {
        var supported = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        }
        assumeTrue("WebView needs site deletion", supported)
        val executor = Executors.newCachedThreadPool()
        val visits = AtomicInteger()
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            executor.execute {
                while (!server.isClosed) runCatching {
                    val socket = server.accept()
                    executor.execute {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine().orEmpty().split(' ').getOrNull(1)
                            while (!input.readLine().isNullOrEmpty()) { }
                            val first = path == "/page" && visits.incrementAndGet() == 1
                            val script = if (first) """
                                function write() {
                                    document.cookie = 'old=writer; Path=/';
                                    localStorage.setItem('old', 'writer');
                                    sessionStorage.setItem('old', 'writer');
                                }
                                write(); ${if (continuous) "setInterval(write, 5);" else ""} document.title = 'Writing';
                            """.trimIndent() else """
                                function check() {
                                    const state = [document.cookie.includes('old=writer'),
                                        !!localStorage.getItem('old'), !!sessionStorage.getItem('old')];
                                    document.title = state.some(Boolean) ? 'Data returned: ' + state.join(',') : 'Cleared';
                                }
                                check(); setInterval(check, 5);
                            """.trimIndent()
                            val html = "<script>$script</script>".toByteArray()
                            it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                                "Cache-Control: no-store\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            it.getOutputStream().write(html)
                        }
                    }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/page"
            try {
                val component = instrumentation.targetContext.packageName + "/app.xvi.web.BrowserActivity"
                // Shell launch is needed on devices that refuse an instrumentation app's background
                // Activity launch. Avoid waitForIdleSync: the fixture deliberately writes continuously.
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component"
                )).use { it.readBytes() }
                lateinit var activity: BrowserActivity
                await {
                    val shown = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<BrowserActivity>().firstOrNull()
                    if (shown?.ready == true) { activity = shown; true } else false
                }
                try {
                    lateinit var target: BrowserTab
                    lateinit var oldWeb: WebView
                    ui { target = activity.addTab(url, true, activity.normalSession)!! }
                    await { target.view?.title == "Writing" && target.view?.progress == 100 }
                    val cleared = CompletableFuture<Boolean>()
                    ui {
                        oldWeb = target.view!!
                        assertTrue(target.session.cookies().getCookie(url).orEmpty().contains("old=writer"))
                        activity.clearSiteData(target.session, url) { cleared.complete(it) }
                        // Check immediately, before the asynchronous deletion can complete.
                        assertNotSame("The writing document survived the start of deletion", oldWeb, target.view)
                    }
                    assertTrue(cleared.get(10, TimeUnit.SECONDS))
                    ui { assertFalse("Cookie survived deletion", target.session.cookies().getCookie(url).orEmpty().contains("old=writer")) }
                    await {
                        observed = "title=${target.view?.title}, progress=${target.view?.progress}, error=${target.error?.title}, visits=${visits.get()}"
                        target.view?.title == "Cleared" && target.view?.progress == 100
                    }
                    Thread.sleep(200) // A surviving 5 ms writer would have repopulated storage here.
                    ui {
                        assertNotSame(oldWeb, target.view)
                        assertEquals("Cleared", target.view?.title)
                        assertFalse(target.session.cookies().getCookie(url).orEmpty().contains("old=writer"))
                    }
                } finally {
                    ui { activity.finish() }
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
