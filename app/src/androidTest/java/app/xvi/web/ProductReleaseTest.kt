package app.xvi.web

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.AbsListView
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
import org.json.JSONObject

/** Exercises real dialogs and offline documents in the isolated .verify installation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ProductReleaseTest {
    private fun await(scenario: ActivityScenario<BrowserActivity>, message: String = "AI panel did not reach the expected state",
                      condition: (BrowserActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var done = false
            scenario.onActivity { done = condition(it) }
            if (done) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun allViews() = WindowInspector.getGlobalWindowViews().flatMap(::views)

    private fun labels() = allViews().filterIsInstance<TextView>()

    private fun shown(text: String) = allViews().any {
        it.isShown && ((it as? TextView)?.text?.toString() == text || it.contentDescription?.toString() == text)
    }

    /** Picks item [index] of the list or grid most recently shown, such as a popup menu. */
    private fun pick(scenario: ActivityScenario<BrowserActivity>, index: Int) {
        await(scenario, "No list to pick from") { (allViews().filterIsInstance<AbsListView>().lastOrNull { it.isShown }?.childCount ?: 0) > index }
        scenario.onActivity {
            val list = allViews().filterIsInstance<AbsListView>().last { it.isShown }
            assertTrue(list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index)))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun click(scenario: ActivityScenario<BrowserActivity>, label: Int) {
        scenario.onActivity { activity ->
            val text = activity.getString(label)
            val view = allViews().last {
                it.isShown && ((it as? TextView)?.text?.toString() == text || it.contentDescription?.toString() == text)
            }
            var target: View = view
            while (!target.isClickable && target.parent is View) target = target.parent as View
            assertTrue("Could not click $text", target.performClick())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun withBrowser(block: (ActivityScenario<BrowserActivity>) -> Unit) {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            await(scenario, "Browser did not become ready") { it.ready }
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
            assertTrue(labels().any { it.text.toString() == activity.getString(R.string.privacy_ai) })
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

    /** Shows [body] as [url] in the current tab, with the AI service at [server]. */
    private fun openPage(scenario: ActivityScenario<BrowserActivity>, server: ServerSocket, url: String, title: String, body: String) {
        scenario.onActivity { activity ->
            activity.aiConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
            activity.aiConfig.apiKey = "test-only-key"
            val tab = activity.tab!!
            val web = activity.createWebView(tab)
            tab.view = web
            tab.url = url
            activity.showTab(false)
            web.loadDataWithBaseURL(url, "<html><head><title>$title</title></head><body>$body</body></html>", "text/html", "UTF-8", null)
        }
        await(scenario, "Test page did not load") { it.tab?.view?.title == title && it.tab?.view?.progress == 100 }
    }

    private fun assertNoRequest(server: ServerSocket, why: String) {
        try { server.accept().use { fail(why) } }
        catch (_: SocketTimeoutException) { /* No request reached the AI service. */ }
    }

    /** Answers the next [count] chat requests with a short streamed reply; completes with their bodies. */
    private fun answer(server: ServerSocket, count: Int): CompletableFuture<List<JSONObject>> {
        val received = CompletableFuture<List<JSONObject>>()
        Thread {
            try {
                received.complete((1..count).map {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream().buffered()
                        val headers = java.io.ByteArrayOutputStream()
                        while (!headers.toString("UTF-8").endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte >= 0 && headers.size() < 16_384)
                            headers.write(byte)
                        }
                        val length = Regex("(?im)^content-length: (\\d+)").find(headers.toString("UTF-8"))!!.groupValues[1].toInt()
                        val body = ByteArray(length).also { java.io.DataInputStream(input).readFully(it) }
                        val reply = "data: {\"choices\":[{\"delta\":{\"content\":\"图片回答\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n".toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(reply)
                            flush()
                        }
                        JSONObject(String(body, Charsets.UTF_8))
                    }
                })
            } catch (failure: Throwable) { received.completeExceptionally(failure) }
        }.apply { isDaemon = true; start() }
        return received
    }

    @Test fun pageSummaryMakesNoRequestUntilConsentAndCancelLeavesItUnsent() = withBrowser { scenario ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 700
            openPage(scenario, server, "https://consent.test/", "Consent",
                "<article><p>Content that must stay local until consent.</p></article>")
            scenario.onActivity { it.assistant.page(it.tab!!) }
            await(scenario, "Page actions did not open") { shown(it.getString(R.string.ai_summary_question)) }
            assertNoRequest(server, "Page content was sent without an action")
            scenario.onActivity { assertEquals(1, it.tab!!.ai!!.messages.size) }
            click(scenario, R.string.ai_summary_question)
            await(scenario, "Page summary did not ask before sending") { activity ->
                labels().any { it.isShown && it.text == activity.getString(R.string.ai_consent_send) }
            }
            assertNoRequest(server, "Page content was sent without consent")
            click(scenario, R.string.action_cancel)
            assertNoRequest(server, "Page content was sent after cancelling")
            scenario.onActivity { assertFalse(it.aiConfig.sendConsent) }
        }
    }

    @Test fun picturesAreOptInSentAsImagesAndRemainInFollowUpRequests() = withBrowser { scenario ->
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 700
            val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
            val png = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val picture = "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
            scenario.onActivity { it.aiConfig.sendConsent = true }
            openPage(scenario, server, "https://pictures.test/", "Pictures",
                "<article><p>Page with a chart.</p><img src='$picture' alt='测试图表'></article>")
            scenario.onActivity { it.assistant.page(it.tab!!) }
            await(scenario) { shown(it.getString(R.string.ai_summary_question)) }
            assertNoRequest(server, "Opening an approved conversation must not send a request")

            // The grid leads with the album tile; the page's picture follows it.
            click(scenario, R.string.ai_add_images)
            pick(scenario, 1)
            click(scenario, R.string.action_done)
            await(scenario, "The page picture was not attached") { shown(it.getString(R.string.ai_remove_image, "测试图表")) }
            scenario.onActivity { assertEquals(1, it.tab!!.ai!!.messages.size) }
            assertNoRequest(server, "Choosing a picture must not send a request")

            server.soTimeout = 10_000
            val received = answer(server, 2)
            click(scenario, R.string.ai_summary_question)
            await(scenario) { it.tab!!.ai!!.messages.last().role == DeepSeek.Message.ASSISTANT }
            scenario.onActivity { activity ->
                allViews().filterIsInstance<android.widget.EditText>().last { it.hint == activity.getString(R.string.ai_ask_hint) }.setText("再解释一下")
            }
            click(scenario, R.string.ai_send)
            val requests = received.get(15, TimeUnit.SECONDS)
            requests.forEach { body ->
                val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
                assertEquals(AiPrompts.SUMMARY, parts.getJSONObject(0).getString("text"))
                // Sent as the picture itself, re-encoded, never as an address for the service to fetch.
                assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
            }
            assertEquals("再解释一下", requests[1].getJSONArray("messages").getJSONObject(3).getString("content"))
            await(scenario) { it.tab!!.ai!!.messages.size == 5 }

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val file = java.io.File(java.io.File(context.cacheDir, "uploads").apply { mkdirs() }, "ai-picker-test.png")
            file.writeBytes(png)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_GET_CONTENT).apply {
                addDataType("image/*")
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
            }
            val result = android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
            val monitor = instrumentation.addMonitor(filter, result, true)
            try {
                server.soTimeout = 700
                click(scenario, R.string.ai_add_images)
                pick(scenario, 0)
                // Numbered after the picture already sent in this conversation.
                val name = context.getString(R.string.ai_remove_image, context.getString(R.string.ai_image_label, 2))
                await(scenario, "The album picture was not attached") { shown(name) }
                assertEquals(1, monitor.hits)
                scenario.onActivity { assertEquals(5, it.tab!!.ai!!.messages.size) }
                assertNoRequest(server, "Picking an album picture must not send a request")
                scenario.onActivity {
                    allViews().last { view -> view.isShown && view.contentDescription?.toString() == name }.performClick()
                    assertFalse(shown(name))
                }
            } finally { instrumentation.removeMonitor(monitor); file.delete() }
        }
    }
}
