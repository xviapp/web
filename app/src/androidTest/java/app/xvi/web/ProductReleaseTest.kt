package app.xvi.web

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Exercises real dialogs and offline documents in the isolated .verify installation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ProductReleaseTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun labels() = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<TextView>()

    private fun click(scenario: ActivityScenario<BrowserActivity>, label: Int) {
        scenario.onActivity { activity ->
            val text = activity.getString(label)
            val view = labels().last { it.text.toString() == text && it.isShown }
            var target: View = view
            while (!target.isClickable && target.parent is View) target = target.parent as View
            assertTrue("Could not click $text", target.performClick())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun withBrowser(block: (ActivityScenario<BrowserActivity>) -> Unit) {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                scenario.onActivity { ready = it.ready }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Browser did not become ready", ready)
            scenario.onActivity {
                it.aiConfig.baseUrl = DeepSeek.BASE
                it.aiConfig.sendConsent = false
                it.aiConfig.enabled = true
                it.addTab("", true, it.normalSession)
            }
            try { block(scenario) } finally {
                scenario.onActivity {
                    it.aiConfig.sendConsent = false
                    it.aiConfig.baseUrl = DeepSeek.BASE
                    it.aiConfig.apiKey = null
                    BrowserSheet.dismissAll(it.root)
                }
            }
        }
    }

    private fun ask(scenario: ActivityScenario<BrowserActivity>): Pair<CompletableFuture<Boolean>, Job> {
        val answer = CompletableFuture<Boolean>()
        lateinit var job: Job
        scenario.onActivity { activity ->
            job = activity.lifecycleScope.launch {
                answer.complete(AiSendConsent(activity).confirm(activity.tab!!, activity.aiConfig.baseUrl))
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return answer to job
    }

    @Test fun cancelDoesNotGrantConsentAndRetryStillAsks() = withBrowser { scenario ->
        val first = ask(scenario).first
        assertFalse(first.isDone)
        click(scenario, R.string.action_cancel)
        assertFalse(first.get(5, TimeUnit.SECONDS))
        scenario.onActivity { assertFalse(it.aiConfig.sendConsent) }
        val retry = ask(scenario).first
        assertFalse(retry.isDone)
        click(scenario, R.string.ai_consent_send)
        assertTrue(retry.get(5, TimeUnit.SECONDS))
        scenario.onActivity { assertTrue(AiConfig(it).sendConsent) }
        assertTrue(ask(scenario).first.get(5, TimeUnit.SECONDS))
    }

    @Test fun changedServiceRequiresConsentAgainIncludingWhenSwitchingBack() = withBrowser { scenario ->
        scenario.onActivity { it.aiConfig.sendConsent = true; it.aiConfig.baseUrl = "https://example.org/v1" }
        val changed = ask(scenario).first
        assertFalse(changed.isDone)
        click(scenario, R.string.ai_consent_send)
        assertTrue(changed.get(5, TimeUnit.SECONDS))
        scenario.onActivity { it.aiConfig.baseUrl = DeepSeek.BASE; assertFalse(it.aiConfig.sendConsent) }
        val returned = ask(scenario).first
        assertFalse(returned.isDone)
        click(scenario, R.string.action_cancel)
        assertFalse(returned.get(5, TimeUnit.SECONDS))
    }

    @Test fun cancellingTheRequestDismissesThePromptWithoutRememberingConsent() = withBrowser { scenario ->
        val (_, job) = ask(scenario)
        scenario.onActivity { job.cancel() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            assertFalse(it.aiConfig.sendConsent)
            assertFalse(labels().any { view -> view.isShown && view.text == it.getString(R.string.ai_consent_send) })
        }
    }

    @Test fun changingServiceWhilePromptIsOpenCannotApproveTheNewService() = withBrowser { scenario ->
        val result = ask(scenario).first
        scenario.onActivity { it.aiConfig.baseUrl = "https://example.org/v1" }
        click(scenario, R.string.ai_consent_send)
        assertFalse(result.get(5, TimeUnit.SECONDS))
        scenario.onActivity { assertFalse(it.aiConfig.sendConsent) }
    }

    @Test fun privateSendingAsksEveryTimeAndNeverGrantsOrdinaryConsent() = withBrowser { scenario ->
        var available = false
        scenario.onActivity { available = BrowserSession.privateAvailable() }
        assumeTrue("WebView needs private profiles", available)
        scenario.onActivity { activity ->
            activity.addTab("", true, activity.obtainPrivateSession()!!)
        }
        repeat(2) {
            val result = ask(scenario).first
            assertFalse(result.isDone)
            scenario.onActivity { activity ->
                assertTrue(labels().any { it.text.contains(activity.getString(R.string.ai_consent_private)) })
            }
            click(scenario, R.string.ai_consent_send)
            assertTrue(result.get(5, TimeUnit.SECONDS))
            scenario.onActivity { assertFalse(it.aiConfig.sendConsent) }
        }
    }

    @Test fun privacyAndNoticesAreReadableOfflineAndBackRestoresAbout() = withBrowser { scenario ->
        scenario.onActivity { AboutPanel(it).show() }
        click(scenario, R.string.about_privacy)
        scenario.onActivity { activity ->
            assertTrue(labels().any { it.text == activity.getString(R.string.privacy_ai) })
        }
        click(scenario, R.string.action_back)
        click(scenario, R.string.about_notices)
        scenario.onActivity {
            assertTrue(labels().any { view -> view.text.contains("EasyList") && view.text.contains("KaTeX") })
        }
        click(scenario, R.string.action_back)
        click(scenario, R.string.about_feedback)
        scenario.onActivity { activity ->
            assertTrue(labels().any { it.text.contains("WebView：") && it.text.contains("应用：") })
            assertEquals(1, labels().count { it.isShown && it.text == activity.getString(R.string.about_feedback) })
        }
    }

    @Test fun pageSummaryMakesNoRequestUntilConsentAndCancelLeavesItUnsent() = withBrowser { scenario ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 700
            scenario.onActivity { activity ->
                activity.aiConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
                activity.aiConfig.apiKey = "test-only-key"
                val tab = activity.tab!!
                val web = activity.createWebView(tab)
                tab.view = web
                tab.url = "https://consent.test/"
                activity.showTab(false)
                web.loadDataWithBaseURL(tab.url, "<html><head><title>Consent</title></head>" +
                    "<body><article><p>Content that must stay local until consent.</p></article></body></html>",
                    "text/html", "UTF-8", null)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var loaded = false
            while (!loaded && System.nanoTime() < deadline) {
                scenario.onActivity { loaded = it.tab?.view?.title == "Consent" && it.tab?.view?.progress == 100 }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue("Test page did not load", loaded)
            scenario.onActivity { it.assistant.page(it.tab!!) }
            var asking = false
            while (!asking && System.nanoTime() < deadline) {
                scenario.onActivity { activity ->
                    asking = labels().any { it.isShown && it.text == activity.getString(R.string.ai_consent_send) }
                }
                if (!asking) Thread.sleep(50)
            }
            assertTrue("Page summary did not ask before sending", asking)
            fun assertNoRequest() {
                try { server.accept().use { fail("Page content was sent without consent") } }
                catch (_: SocketTimeoutException) { /* No request reached the AI service. */ }
            }
            assertNoRequest()
            click(scenario, R.string.action_cancel)
            assertNoRequest()
            scenario.onActivity { assertFalse(it.aiConfig.sendConsent) }
        }
    }
}
