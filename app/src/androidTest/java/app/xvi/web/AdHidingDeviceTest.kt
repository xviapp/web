package app.xvi.web

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdHidingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> ui(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun evaluate(web: WebView, script: String): String {
        val answer = CompletableFuture<String>()
        ui { web.evaluateJavascript(script) { answer.complete(it) } }
        return answer.get(5, TimeUnit.SECONDS)
    }

    @Test fun incrementalKeysAndStylesKeepEarlierMatchesAndReachTheBottomOfLongPages() {
        val loaded = CompletableFuture<Unit>()
        val web = ui {
            WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
                }
                val body = (0..6500).joinToString("") { "<div class='key$it'></div>" }
                loadDataWithBaseURL("https://ad-hiding.test/", "<html><body>$body</body></html>", "text/html", "UTF-8", null)
            }
        }
        try {
            loaded.get(10, TimeUnit.SECONDS)
            val collector = instrumentation.targetContext.assets.open("ad-keys.js").bufferedReader().use { it.readText() }
            val filter = AdFilter.build(sequenceOf("##.key0", "##.key6500", "##.late-ad"))
            fun collect(): JSONObject = JSONObject(JSONTokener(evaluate(web, "($collector)(${filter.revision})")).nextValue() as String)
            var pass: JSONObject
            do {
                pass = collect()
                val array = pass.getJSONArray("keys")
                val keys = (0 until array.length()).map { array.getString(it) }
                val css = filter.hidingCss("https://ad-hiding.test/", keys, pass.getBoolean("first")).orEmpty()
                evaluate(web, AdFilter.injectScript(css, filter.revision))
            } while (pass.getBoolean("more"))
            fun hidden(selector: String) = evaluate(web, "getComputedStyle(document.querySelector('$selector')).display") == "\"none\""
            assertTrue(hidden(".key0"))
            assertTrue(hidden(".key6500"))
            evaluate(web, "document.body.insertAdjacentHTML('beforeend', '<div class=late-ad>late</div>')")
            val changed = collect().getJSONArray("keys")
            val keys = (0 until changed.length()).map { changed.getString(it) }
            assertTrue(keys.contains(".late-ad"))
            evaluate(web, AdFilter.injectScript(filter.hidingCss("https://ad-hiding.test/", keys, false)!!, filter.revision))
            assertTrue(hidden(".key0"))
            assertTrue(hidden(".late-ad"))
        } finally { ui { web.destroy() } }
    }
}
