package app.xvi.web

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProxyPanelTest {
    private fun change(network: BrowserProxyNetwork, action: (java.util.function.Consumer<BrowserProxyNetwork.Status>) -> Unit) {
        val ready = CountDownLatch(1)
        action(java.util.function.Consumer { ready.countDown() })
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }

    private fun checkResult(scenario: ActivityScenario<BrowserActivity>): String? {
        val result = AtomicReference<String>()
        val deadline = System.currentTimeMillis() + 5_000
        do {
            scenario.onActivity { activity ->
                val panel = activity.proxyPanel!!
                val button = ProxyPanel::class.java.getDeclaredField("checkButton")
                    .apply { isAccessible = true }.get(panel) as TextView
                val label = ProxyPanel::class.java.getDeclaredField("testResult")
                    .apply { isAccessible = true }.get(panel) as TextView
                if (button.isEnabled) result.set(if (label.visibility == View.VISIBLE) label.text.toString() else "")
            }
            if (result.get() != null) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() < deadline)
        return result.get()
    }

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

    @Test fun checkingSavedEnabledProxyShowsAResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = BrowserProxyNetwork.get(context)
        val original = snapshot()
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                change(network) { network.setEnabled(false, it) }
                change(network) { network.setUrl("http://127.0.0.1:${server.localPort}", it) }
                change(network) { network.setEnabled(true, it) }
                val configured = snapshot()
                ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val panel = ProxyPanel(activity)
                        panel.show()
                        panel.render(configured)
                        val button = ProxyPanel::class.java.getDeclaredField("checkButton")
                            .apply { isAccessible = true }.get(panel) as TextView
                        button.performClick()
                    }
                    assertEquals(context.getString(R.string.proxy_test_ok), checkResult(scenario))
                    server.close()
                    scenario.onActivity { activity ->
                        val button = ProxyPanel::class.java.getDeclaredField("checkButton")
                            .apply { isAccessible = true }.get(activity.proxyPanel) as TextView
                        button.performClick()
                    }
                    assertEquals(context.getString(R.string.proxy_error_refused), checkResult(scenario))
                    val refreshed = snapshot()
                    scenario.onActivity { activity ->
                        val panel = activity.proxyPanel!!
                        panel.render(refreshed)
                        val label = ProxyPanel::class.java.getDeclaredField("testResult")
                            .apply { isAccessible = true }.get(panel) as TextView
                        assertEquals(context.getString(R.string.proxy_error_refused), label.text.toString())
                    }
                    scenario.onActivity { it.proxyPanel?.dismiss() }
                }
            } finally {
                change(network) { network.setEnabled(false, it) }
                change(network) { network.setUrl(original.url, it) }
                change(network) { network.setBypassOnVpn(original.bypassOnVpn, it) }
                change(network) { network.setEnabled(original.enabled, it) }
            }
        }
    }
}
