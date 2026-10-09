package app.xvi.web

import android.app.AlertDialog
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.api.ProxyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProxyPanelTest {
    @Test fun subscriptionEditorUsesTheSavedIntervals() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val snapshot = AtomicReference<ProxyState>()
            val ready = CountDownLatch(1)
            BrowserProxyNetwork.get(context).status { snapshot.set(it); ready.countDown() }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val panel = ProxyPanel(activity)
                panel.show()
                panel.render(snapshot.get())
                ProxyPanel::class.java.getDeclaredMethod("openSubscriptionEditor").apply { isAccessible = true }.invoke(panel)
                val dialog = ProxyPanel::class.java.getDeclaredField("configDialog").apply { isAccessible = true }
                    .get(panel) as AlertDialog
                val updateInterval = ProxyPanel::class.java.getDeclaredField("formUpdateInterval").apply { isAccessible = true }
                    .get(panel) as EditText
                val testInterval = ProxyPanel::class.java.getDeclaredField("formTestInterval").apply { isAccessible = true }
                    .get(panel) as EditText
                assertEquals(snapshot.get().subscription.getUpdateIntervalMinutes().toString(), updateInterval.text.toString())
                assertEquals(snapshot.get().subscription.getTestIntervalMinutes().toString(), testInterval.text.toString())
                assertTrue(dialog.isShowing)
                dialog.dismiss()
                panel.dismiss()
            }
        }
    }

    @Test fun lateSnapshotsDoNotTakeOwnershipFromTheCurrentPanel() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val snapshot = AtomicReference<ProxyState>()
            val ready = CountDownLatch(1)
            BrowserProxyNetwork.get(context).status { snapshot.set(it); ready.countDown() }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val panel = ProxyPanel(activity)
                panel.show()
                panel.render(snapshot.get())
                val replacement = ProxyPanel(activity)
                replacement.show()
                panel.render(snapshot.get())
                assertSame(replacement, activity.proxyPanel)
                replacement.dismiss()
                assertNull(activity.proxyPanel)
            }
        }
    }

}
