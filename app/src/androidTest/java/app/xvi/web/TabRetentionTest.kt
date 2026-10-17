package app.xvi.web

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabRetentionTest {
    private fun waitUntil(scenario: ActivityScenario<BrowserActivity>, condition: (BrowserActivity) -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var satisfied = false
            scenario.onActivity { satisfied = condition(it) }
            if (satisfied) return
            Thread.sleep(100)
        }
        fail("Tab condition was not reached")
    }
    @Test fun keepsMoreThanThreePagesAndReclaimsOnlyUnprotectedBackgroundPages() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            waitUntil(scenario) { it.ready }
            val entries = mutableListOf<BrowserTab>()
            repeat(4) { index ->
                scenario.onActivity { activity ->
                    val entry = activity.addTab("", true, activity.normalSession)!!
                    entries.add(entry)
                    val web = activity.createWebView(entry)
                    entry.view = web
                    entry.url = "https://tabs.test/$index"
                    if (index == 2) entry.keepAlive = true
                    activity.showTab(false)
                    val body = if (index == 1) "<textarea>unsaved draft</textarea>" else "<p>read-only page</p>"
                    web.loadDataWithBaseURL("https://tabs.test/$index", "<html><head><title>Tab $index</title></head><body>$body</body></html>", "text/html", "UTF-8", null)
                }
                waitUntil(scenario) {
                    val web = entries[index].view
                    web?.title == "Tab $index" && web.progress == 100
                }
            }
            scenario.onActivity { activity ->
                assertEquals(4, entries.count { it.view != null })
                activity.trimTabs(TabMemoryPolicy.Pressure.CRITICAL)
            }
            waitUntil(scenario) { entries[0].view == null && !it.trimming }
            scenario.onActivity {
                assertNotNull("Draft page", entries[1].view)
                assertNotNull("Pinned page", entries[2].view)
                assertNotNull("Current page", entries[3].view)
                assertNotNull("Back/forward state", entries[0].state)
            }
        }
    }
}
