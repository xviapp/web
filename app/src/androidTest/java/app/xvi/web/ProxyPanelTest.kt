package app.xvi.web

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.api.ProxyState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProxyPanelTest {
    @Test fun repeatedSnapshotsKeepOneMountedInputAndPreserveItsDraft() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val snapshot = AtomicReference<ProxyState>()
            val ready = CountDownLatch(1)
            BrowserProxyNetwork.get(context).status { snapshot.set(it); ready.countDown() }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val panel = ProxyPanel(activity)
                panel.show()
                val input = ProxyPanel::class.java.getDeclaredField("url").apply { isAccessible = true }
                    .get(panel) as EditText
                val parent = input.parent
                panel.render(snapshot.get())
                input.setText("https://unsaved.example/subscription")
                repeat(5) { panel.render(snapshot.get()) }
                assertSame(parent, input.parent)
                assertEquals("https://unsaved.example/subscription", input.text.toString())
                val replacement = ProxyPanel(activity)
                replacement.show()
                assertSame(replacement, activity.proxyPanel)
                // Late callbacks to the dismissed panel do not steal ownership or clear input.
                panel.render(snapshot.get())
                assertSame(replacement, activity.proxyPanel)
                assertEquals("https://unsaved.example/subscription", input.text.toString())
                replacement.dismiss()
                assertNull(activity.proxyPanel)
            }
        }
    }
}
