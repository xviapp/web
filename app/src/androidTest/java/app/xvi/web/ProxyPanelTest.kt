package app.xvi.web

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private fun snapshot(): BrowserProxyNetwork.Status {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = AtomicReference<BrowserProxyNetwork.Status>()
        val ready = CountDownLatch(1)
        BrowserProxyNetwork.get(context).status { snapshot.set(it); ready.countDown() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        return snapshot.get()
    }

    @Test fun addressFieldShowsTheSavedAddress() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            val saved = BrowserProxyNetwork.Status(false, "socks5://127.0.0.1:7890", false, BrowserProxyNetwork.Route.OFF, 0, "")
            scenario.onActivity { activity ->
                val panel = ProxyPanel(activity)
                panel.show()
                panel.render(saved)
                val field = ProxyPanel::class.java.getDeclaredField("url").apply { isAccessible = true }.get(panel) as EditText
                assertEquals(saved.url, field.text.toString())
                // A later update with the same address leaves what is being typed.
                field.setText("http://10.0.0.2:8080")
                panel.render(saved)
                assertEquals("http://10.0.0.2:8080", field.text.toString())
                panel.dismiss()
            }
        }
    }

    @Test fun lateSnapshotsDoNotTakeOwnershipFromTheCurrentPanel() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            val snapshot = snapshot()
            scenario.onActivity { activity ->
                val panel = ProxyPanel(activity)
                panel.show()
                panel.render(snapshot)
                val replacement = ProxyPanel(activity)
                replacement.show()
                panel.render(snapshot)
                assertSame(replacement, activity.proxyPanel)
                replacement.dismiss()
                assertNull(activity.proxyPanel)
            }
        }
    }
}
