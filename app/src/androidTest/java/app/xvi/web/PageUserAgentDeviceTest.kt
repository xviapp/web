package app.xvi.web

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PageUserAgentDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @SuppressLint("SetJavaScriptEnabled")
    private fun identity(web: WebView): JSONObject {
        val loaded = CompletableFuture<Unit>()
        ui {
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
            }
            web.loadDataWithBaseURL("https://user-agent.test/", "<title>Identity</title>", "text/html", "UTF-8", null)
        }
        loaded.get(10, TimeUnit.SECONDS)
        val answer = CompletableFuture<String>()
        ui {
            web.evaluateJavascript("JSON.stringify({ua:navigator.userAgent,hints:navigator.userAgentData.toJSON()})") {
                answer.complete(it)
            }
        }
        return JSONObject(JSONTokener(answer.get(10, TimeUnit.SECONDS)).nextValue() as String)
    }

    @Test fun desktopHintsMatchTheUaAndMobileRestoresTheOriginalIdentity() {
        var supported = false
        ui { supported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA) }
        assumeTrue("WebView needs user-agent metadata support", supported)
        lateinit var web: BrowserWebView
        lateinit var original: UserAgentMetadata
        ui {
            web = BrowserWebView(instrumentation.targetContext)
            original = WebSettingsCompat.getUserAgentMetadata(web.settings)
        }
        try {
            val mobile = identity(web)
            ui {
                assertFalse(web.pageUserAgent.isDesktop)
                web.pageUserAgent.apply(true)
                assertTrue(web.pageUserAgent.isDesktop)
            }
            val desktop = identity(web)
            assertTrue(desktop.getString("ua").contains("X11; Linux x86_64"))
            assertEquals("Linux", desktop.getJSONObject("hints").getString("platform"))
            assertFalse(desktop.getJSONObject("hints").getBoolean("mobile"))
            ui {
                val hints = WebSettingsCompat.getUserAgentMetadata(web.settings)
                assertEquals(original.brandVersionList, hints.brandVersionList)
                assertEquals(original.fullVersion, hints.fullVersion)
                assertEquals("x86", hints.architecture)
                assertEquals(64, hints.bitness)
                assertEquals("", hints.model)
                assertEquals("", hints.platformVersion)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS))
                    assertEquals(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP), hints.formFactors)
                web.pageUserAgent.apply(false)
                assertFalse(web.pageUserAgent.isDesktop)
                assertEquals(original, WebSettingsCompat.getUserAgentMetadata(web.settings))
            }
            assertEquals(mobile.toString(), identity(web).toString())
        } finally { ui { web.destroy() } }
    }
}
