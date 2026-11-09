package app.xvi.web

import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Run the two phases in separate app processes with scripts/test-service-worker-restart.mjs. */
@RunWith(AndroidJUnit4::class)
class ServiceWorkerRestartTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun persistedWorkerSendsCookiesAfterProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("workerPhase")
        assumeTrue("Requires the two-process test runner", phase == "seed" || phase == "probe")
        val state = context.getSharedPreferences("worker-restart-test", 0)
        if (phase == "probe") assertNotEquals("Seed phase did not finish", 0, state.getInt("port", 0))
        val port = if (phase == "seed") 0 else state.getInt("port", 0)
        val renewal = if (phase == "seed") UUID.randomUUID().toString() else state.getString("renewal", "")!!
        val result = CompletableFuture<String>()
        val executor = Executors.newCachedThreadPool()
        lateinit var session: BrowserSession
        lateinit var web: WebView
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 10)
        }.use { server ->
            executor.execute {
                while (!server.isClosed) runCatching {
                    val socket = server.accept()
                    executor.execute {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine().orEmpty().split(' ').getOrNull(1)
                            val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                            val cookie = headers.firstOrNull { h -> h.startsWith("Cookie:", true) }.orEmpty()
                            val worker = headers.any { h -> h.equals("X-Worker-Probe: yes", true) }
                            val signedIn = cookie.contains("persisted=present") &&
                                (phase == "seed" || cookie.contains("renewed=$renewal"))
                            val body = when (path) {
                                "/sw.js" -> """
                                    self.addEventListener('install', e => e.waitUntil(self.skipWaiting()));
                                    self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
                                    self.addEventListener('fetch', e => {
                                        const headers = new Headers(e.request.headers);
                                        headers.set('X-Worker-Probe', 'yes');
                                        e.respondWith(fetch(new Request(e.request, {mode: 'cors', headers})));
                                    });
                                """.trimIndent()
                                "/seed" -> """
                                    <script>(async () => {
                                        await navigator.serviceWorker.register('/sw.js');
                                        await navigator.serviceWorker.ready;
                                        if (!navigator.serviceWorker.controller) await new Promise(resolve => {
                                            navigator.serviceWorker.addEventListener('controllerchange', resolve, {once:true});
                                            if (navigator.serviceWorker.controller) resolve();
                                        });
                                        location.href = '/account';
                                    })();</script>
                                """.trimIndent()
                                "/probe" -> """
                                    <script>window.addEventListener('load', async () => {
                                        try {
                                            await fetch('/login', {method: 'POST'});
                                            const html = await (await fetch('/account')).text();
                                            document.title = new DOMParser().parseFromString(html, 'text/html').title;
                                        } catch (_) { document.title = 'Fetch error'; }
                                    });</script>
                                """.trimIndent()
                                "/login" -> "ok"
                                else -> "<title>${if (!worker) "Worker missing" else if (!signedIn) "Cookies missing" else "Worker signed in"}</title>"
                            }.toByteArray()
                            val type = if (path == "/sw.js") "application/javascript" else "text/html"
                            val setCookie = if (path == "/login")
                                "Set-Cookie: renewed=$renewal; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax\r\n" else ""
                            it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                "Cache-Control: no-store\r\n$setCookie" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            it.getOutputStream().write(body)
                        }
                    }
                }
            }
            val origin = "http://127.0.0.1:${server.localPort}"
            try {
                val saved = CompletableFuture<Unit>()
                ui {
                    session = BrowserSession()
                    web = session.createWebView(context)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    val cookies = session.cookies()
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(web, false)
                    web.webViewClient = WebViewClient()
                    web.webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            if (title in listOf("Worker signed in", "Worker missing", "Cookies missing", "Fetch error"))
                                result.complete(title.orEmpty())
                        }
                    }
                    if (phase == "seed") {
                        cookies.setCookie(origin, "persisted=present; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax") { saved.complete(Unit) }
                    } else {
                        // Do not touch any ServiceWorkerController API here: this must test the
                        // persisted worker's first fetch in the new process, before registration.
                        assertTrue("Cookie was not persisted", cookies.getCookie(origin).orEmpty().contains("persisted=present"))
                        saved.complete(Unit)
                    }
                }
                saved.get(10, TimeUnit.SECONDS)
                ui { web.loadUrl("$origin/${if (phase == "seed") "seed" else "probe"}") }
                assertEquals("Worker signed in", result.get(20, TimeUnit.SECONDS))
                ui { session.cookies().flush() }
                if (phase == "seed") {
                    assertTrue(state.edit().putInt("port", server.localPort).putString("renewal", renewal)
                        .putInt("seedPid", android.os.Process.myPid()).commit())
                } else {
                    assertNotEquals("Probe reused the seed process", state.getInt("seedPid", 0), android.os.Process.myPid())
                }
            } finally {
                ui { session.end() }
                executor.shutdownNow()
            }
        }
    }
}
