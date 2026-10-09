package app.xvi.web

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabRetentionTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(owner: Any, name: String, vararg values: Any): Any? = owner.javaClass.declaredMethods
        .first { it.name == name && it.parameterCount == values.size }.apply { isAccessible = true }.invoke(owner, *values)
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
            waitUntil(scenario) { field(it, "ready").getBoolean(it) }
            val entries = mutableListOf<Any>()
            repeat(4) { index ->
                scenario.onActivity { activity ->
                    invoke(activity, "addTab", "", true, field(activity, "normalSession").get(activity)!!)
                    val tabs = field(activity, "tabs").get(activity) as List<*>
                    val entry = tabs.last()!!
                    entries.add(entry)
                    val web = invoke(activity, "createWebView", entry) as WebView
                    field(entry, "view").set(entry, web)
                    field(entry, "url").set(entry, "https://tabs.test/$index")
                    if (index == 2) field(entry, "keepAlive").setBoolean(entry, true)
                    invoke(activity, "showTab", false)
                    val body = if (index == 1) "<textarea>unsaved draft</textarea>" else "<p>read-only page</p>"
                    web.loadDataWithBaseURL("https://tabs.test/$index", "<html><head><title>Tab $index</title></head><body>$body</body></html>", "text/html", "UTF-8", null)
                }
                waitUntil(scenario) {
                    val web = field(entries[index], "view").get(entries[index]) as? WebView
                    web?.title == "Tab $index" && web.progress == 100
                }
            }
            scenario.onActivity { activity ->
                assertEquals(4, entries.count { field(it, "view").get(it) != null })
                invoke(activity, "trimTabs", TabMemoryPolicy.Pressure.CRITICAL)
            }
            waitUntil(scenario) { field(entries[0], "view").get(entries[0]) == null && !field(it, "trimming").getBoolean(it) }
            scenario.onActivity {
                assertNotNull("Draft page", field(entries[1], "view").get(entries[1]))
                assertNotNull("Pinned page", field(entries[2], "view").get(entries[2]))
                assertNotNull("Current page", field(entries[3], "view").get(entries[3]))
                assertNotNull("Back/forward state", field(entries[0], "state").get(entries[0]))
            }
        }
    }
}
