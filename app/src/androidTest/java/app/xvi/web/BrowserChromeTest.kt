package app.xvi.web

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserChromeTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun menu(activity: BrowserActivity) = views(activity.window.decorView)
        .single { it.contentDescription == activity.getString(R.string.nav_menu) }

    private fun menuVisible(activity: BrowserActivity): Boolean = menu(activity).let {
        val bounds = Rect()
        it.isShown && it.getGlobalVisibleRect(bounds) && bounds.height() == it.height
    }

    private fun await(scenario: ActivityScenario<BrowserActivity>, message: String, condition: (BrowserActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            var satisfied = false
            scenario.onActivity { satisfied = condition(it) }
            if (satisfied) return
            Thread.sleep(50)
        }
        var state = ""
        scenario.onActivity {
            val web = it.tab?.view
            val chrome = menu(it).parent.parent as View
            state = "scrollY=${web?.scrollY}, canScrollDown=${web?.canScrollVertically(1)}, " +
                "chromeVisibility=${chrome.visibility}, translationY=${chrome.translationY}"
        }
        fail("$message ($state)")
    }

    private fun withPage(block: (ActivityScenario<BrowserActivity>) -> Unit) {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            await(scenario, "Browser did not become ready") { it.ready }
            scenario.onActivity { activity ->
                val entry = activity.addTab("", true, activity.normalSession)!!
                val web = activity.createWebView(entry)
                entry.view = web
                entry.url = "https://chrome.test/"
                activity.showTab(false)
                web.loadDataWithBaseURL(entry.url, """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Chrome recovery</title></head>
                    <body style="margin:0;height:8000px">
                    <input id="password" type="password" style="display:none;position:fixed;top:16px;left:16px;width:80%;height:48px;z-index:2">
                    <div id="inner" style="display:none;position:fixed;inset:0;overflow:auto;background:white">
                        <div style="height:8000px">Inner scrolling area</div>
                    </div></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            await(scenario, "Test page did not load") {
                it.tab?.view?.let { web -> web.title == "Chrome recovery" && web.progress == 100 && web.canScrollVertically(1) } == true
            }
            await(scenario, "Toolbar did not start visible", ::menuVisible)
            try {
                block(scenario)
            } finally {
                scenario.onActivity { BrowserSheet.dismissAll(it.root) }
            }
        }
    }

    private fun collapse(scenario: ActivityScenario<BrowserActivity>) {
        scenario.onActivity { it.tab!!.view!!.scrollTo(0, 1000) }
        await(scenario, "Toolbar did not collapse") { !menu(it).isShown }
    }

    private fun swipe(scenario: ActivityScenario<BrowserActivity>, horizontal: Boolean = false) {
        var x = 0f
        var startY = 0f
        var travel = 0f
        scenario.onActivity {
            val web = it.tab!!.view!!
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            x = location[0] + web.width * .3f
            startY = location[1] + web.height * .3f
            travel = if (horizontal) web.width * .4f else web.height * .35f
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, distance: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x + if (horizontal) distance else 0f, startY + if (horizontal) 0f else distance, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        repeat(12) { step ->
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, travel * (step + 1) / 12)
        }
        send(MotionEvent.ACTION_UP, travel)
        instrumentation.waitForIdleSync()
    }

    private fun tap(x: Float, y: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun openMenu(scenario: ActivityScenario<BrowserActivity>) {
        await(scenario, "Toolbar did not fully return") { it.hasWindowFocus() && menuVisible(it) }
        // Resuming with a focused page field can bring the IME back after onResume returns.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 3000)
        val bounds = Rect()
        var settingsTitle = ""
        scenario.onActivity {
            menu(it).getGlobalVisibleRect(bounds)
            settingsTitle = it.getString(R.string.settings_title)
        }
        tap(bounds.exactCenterX(), bounds.exactCenterY())
        // BrowserSheet uses a separate PopupWindow, outside the activity's view tree.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(settingsTitle)?.any { it.isVisibleToUser } == true) return
            Thread.sleep(50)
        }
        fail("Restored menu could not be opened")
    }

    @Test fun editingPageAddressShowsSuggestions() = withPage { scenario ->
        scenario.onActivity { activity ->
            val address = views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single { it.isShown }
            address.requestFocus()
            assertSame("Address focus did not start editing", address, activity.searchOverlay.input)
            assertFalse("Focusing the address hid the page", activity.searchOverlay.isVisible)
            address.setText("chrome")
            assertTrue("Typing in the address showed no suggestions", activity.searchOverlay.isVisible)
            address.clearFocus()
            assertFalse(activity.searchOverlay.isVisible)
        }
    }

    @Test fun tappingThePageEndsAddressEditing() = withPage { scenario ->
        var x = 0f
        var y = 0f
        scenario.onActivity { activity ->
            views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single { it.isShown }.requestFocus()
            val web = activity.tab!!.view!!
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            x = location[0] + web.width * .5f
            y = location[1] + web.height * .25f
        }
        tap(x, y)
        scenario.onActivity { assertNull("Tapping the page left the address being edited", it.searchOverlay.input) }
    }

    @Test fun downwardPageSwipeRestoresToolbar() = withPage { scenario ->
        collapse(scenario)
        swipe(scenario)
        await(scenario, "Downward page swipe did not restore toolbar", ::menuVisible)
        openMenu(scenario)
    }

    @Test fun returningToBrowserRestoresToolbar() = withPage { scenario ->
        collapse(scenario)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await(scenario, "Toolbar stayed hidden after returning to browser", ::menuVisible)
        openMenu(scenario)
    }

    @Test fun downwardSwipeInsidePageContainerRestoresToolbar() = withPage { scenario ->
        collapse(scenario)
        var innerReady = false
        scenario.onActivity {
            it.tab!!.view!!.evaluateJavascript("""
                const inner = document.getElementById('inner');
                inner.style.display = 'block'; inner.scrollTop = 1000; inner.scrollTop;
            """.trimIndent()) { value -> innerReady = value == "1000" }
        }
        await(scenario, "Inner scroll area did not become ready") { innerReady }
        swipe(scenario)
        scenario.onActivity { assertEquals("Outer page should stay still during inner scrolling", 1000, it.tab!!.view!!.scrollY) }
        await(scenario, "Downward swipe in inner scroll area did not restore toolbar", ::menuVisible)
        var innerScrolled = false
        scenario.onActivity {
            it.tab!!.view!!.evaluateJavascript("document.getElementById('inner').scrollTop < 1000") { value -> innerScrolled = value == "true" }
        }
        await(scenario, "Toolbar recovery consumed the page's gesture") { innerScrolled }
        openMenu(scenario)
    }

    @Test fun horizontalSwipeDoesNotRevealToolbar() = withPage { scenario ->
        collapse(scenario)
        swipe(scenario, horizontal = true)
        SystemClock.sleep(250)
        scenario.onActivity { assertFalse("Horizontal swipe revealed toolbar", menuVisible(it)) }
    }

    @Test fun returningDuringCollapseAnimationRestoresToolbar() = withPage { scenario ->
        scenario.onActivity { it.tab!!.view!!.scrollTo(0, 1000) }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        SystemClock.sleep(250)
        openMenu(scenario)
    }

    @Test fun returningToPasswordFieldKeepsToolbarAvailable() = withPage { scenario ->
        collapse(scenario)
        var fieldReady = false
        var x = 0f
        var y = 0f
        scenario.onActivity { activity ->
            val web = activity.tab!!.view!!
            web.evaluateJavascript("document.getElementById('password').style.display = 'block'") { fieldReady = true }
            val location = IntArray(2)
            web.getLocationOnScreen(location)
            x = location[0] + web.width * .5f
            y = location[1] + 40 * activity.resources.displayMetrics.density
        }
        await(scenario, "Password field did not become ready") { fieldReady }
        tap(x, y)
        await(scenario, "Password field did not open keyboard") {
            ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        // Copying the password and then a verification code means leaving the same page twice.
        repeat(2) {
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(scenario, "Toolbar stayed hidden after returning to password field", ::menuVisible)
        }
        openMenu(scenario)
    }
}
