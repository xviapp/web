package app.xvi.web

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebResourceRequest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

class CallbackTestActivity : Activity()

/** Browser-side dispatch only; an OEM's cross-profile clone picker still needs device verification. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ExternalCallbackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private class Launches : Instrumentation.ActivityMonitor() {
        val intents = mutableListOf<Intent>()
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_CHOOSER) return null
            intents.add(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun clickButton(id: Int) {
        val button = WindowInspector.getGlobalWindowViews().flatMap(::views).last { it.id == id && it.isShown }
        assertTrue(button.performClick())
    }

    private fun withBrowser(check: (ActivityScenario<BrowserActivity>, Launches) -> Unit) {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                scenario.onActivity { ready = it.ready }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Browser did not initialize", ready)
            val launches = Launches()
            instrumentation.addMonitor(launches)
            try { check(scenario, launches) } finally { instrumentation.removeMonitor(launches) }
        }
    }

    private fun redirect(activity: BrowserActivity, value: String) {
        val tab = activity.addTab("", true, activity.normalSession)!!
        val web = activity.createWebView(tab)
        tab.view = web
        tab.url = "https://login.xvi.test/"
        activity.showTab(false)
        val request = object : WebResourceRequest {
            override fun getUrl() = Uri.parse(value)
            override fun isForMainFrame() = true
            override fun isRedirect() = true
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        assertTrue(PageClient(activity, tab).shouldOverrideUrlLoading(web, request))
    }

    @Suppress("DEPRECATION")
    private fun selectedTarget(launches: Launches): Intent {
        val launched = launches.intents.single()
        assertEquals("A login callback must offer app selection instead of opening the default instance", Intent.ACTION_CHOOSER, launched.action)
        return requireNotNull(launched.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
    }

    @Test fun loginRedirectOffersAppSelectionAndKeepsTheCompleteCallback() = withBrowser { scenario, launches ->
        val callback = "xvi-callback-test://oauth/callback?code=fixture-code&state=fixture-state"
        scenario.onActivity {
            redirect(it, callback)
            assertTrue("Opening another app requires confirmation", launches.intents.isEmpty())
            clickButton(android.R.id.button1)
        }
        val target = selectedTarget(launches)
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(callback, target.dataString)
        assertTrue(target.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(target.component)
        assertNull(target.`package`)
    }

    @Test fun choosingAnAppPreservesTheRequestedPackageAndDropsPageSuppliedPrivileges() = withBrowser { scenario, launches ->
        val targetPackage = instrumentation.context.packageName
        val callback = "intent://oauth/callback?code=fixture-code&state=fixture-state#Intent;scheme=xvi-callback-test;" +
            "package=$targetPackage;component=$targetPackage/app.xvi.web.CallbackTestActivity;" +
            "action=android.intent.action.EDIT;launchFlags=0x10000001;S.untrusted=discard;end"
        scenario.onActivity {
            redirect(it, callback)
            clickButton(android.R.id.button1)
        }
        val target = selectedTarget(launches)
        assertEquals("xvi-callback-test://oauth/callback?code=fixture-code&state=fixture-state", target.dataString)
        assertEquals(targetPackage, target.`package`)
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertTrue(target.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(target.component)
        assertNull(target.selector)
        assertNull(target.extras)
        assertEquals(0, target.flags)
    }

    @Test fun cancellingTheLoginCallbackDoesNotLaunchAnything() = withBrowser { scenario, launches ->
        scenario.onActivity {
            redirect(it, "xvi-callback-test://oauth/callback?code=fixture-code")
            clickButton(android.R.id.button2)
        }
        assertTrue(launches.intents.isEmpty())
    }

    @Test fun anUnresolvedCallbackStillAsksTheSystemToChoose() = withBrowser { scenario, launches ->
        val callback = "xvi-unlisted-callback-test://oauth/callback?code=fixture-code"
        scenario.onActivity {
            assertNull(it.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(callback))
                .addCategory(Intent.CATEGORY_BROWSABLE), 0))
            redirect(it, callback)
            clickButton(android.R.id.button1)
        }
        assertEquals(callback, selectedTarget(launches).dataString)
    }

    @Test fun absentTargetStillFallsBackToTheWebPage() = withBrowser { scenario, launches ->
        val fallback = "https://fallback.xvi.test/continue"
        scenario.onActivity {
            redirect(it, "intent://oauth/callback#Intent;scheme=xvi-missing-test;package=app.xvi.missing;" +
                "S.browser_fallback_url=${Uri.encode(fallback)};end")
        }
        // Let the actual start fail; intercepting it would turn a missing app into a successful launch.
        instrumentation.removeMonitor(launches)
        scenario.onActivity {
            clickButton(android.R.id.button1)
            assertEquals(fallback, it.tab!!.url)
        }
    }
}
