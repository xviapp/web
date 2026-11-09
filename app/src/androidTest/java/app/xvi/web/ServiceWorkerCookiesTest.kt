package app.xvi.web

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ServiceWorkerCookiesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun defaultProfileKeepsLoginCookiesThroughWorkerFetches() {
        lateinit var session: BrowserSession
        ui { session = BrowserSession() }
        checkLogin(session)
    }

    @Test fun privateProfileKeepsLoginCookiesThroughWorkerFetches() {
        var available = false
        ui { available = BrowserSession.privateAvailable() }
        assumeTrue("WebView needs private profiles", available)
        lateinit var session: BrowserSession
        ui { session = BrowserSession(true) }
        checkLogin(session)
    }

    private fun checkLogin(session: BrowserSession) {
        val executor = Executors.newCachedThreadPool()
        val result = CompletableFuture<String>()
        lateinit var web: WebView
        val worker = """
            self.addEventListener('install', e => e.waitUntil(self.skipWaiting()));
            self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
            self.addEventListener('fetch', e => {
                e.respondWith(fetch(new Request(e.request, {mode: 'cors'})));
            });
        """.trimIndent()
        val start = """
            <script>
            (async () => {
                await navigator.serviceWorker.register('/sw.js');
                await navigator.serviceWorker.ready;
                if (!navigator.serviceWorker.controller) await new Promise(resolve => {
                    navigator.serviceWorker.addEventListener('controllerchange', resolve, {once: true});
                    if (navigator.serviceWorker.controller) resolve();
                });
                await fetch('/login', {method: 'POST'});
                location.href = '/account';
            })().catch(() => { document.title = 'Worker error'; });
            </script>
        """.trimIndent()
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            executor.execute {
                while (!server.isClosed) runCatching {
                    val socket = server.accept()
                    executor.execute {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine().orEmpty().split(' ').getOrNull(1)
                            val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                            val cookie = headers.firstOrNull { h -> h.startsWith("Cookie:", true) }.orEmpty()
                            val loggedIn = cookie.contains("existing=present") && cookie.contains("login=accepted")
                            val body = when (path) {
                                "/sw.js" -> worker
                                "/login" -> "ok"
                                "/account" -> "<title>${if (loggedIn) "Signed in" else "Cookies missing"}</title>"
                                else -> start
                            }.toByteArray()
                            val type = if (path == "/sw.js") "application/javascript" else "text/html"
                            val setCookie = if (path == "/login") "Set-Cookie: login=accepted; Path=/; HttpOnly; SameSite=Lax\r\n" else ""
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
                    web = session.createWebView(instrumentation.targetContext)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    val cookies = session.cookies()
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(web, false)
                    cookies.setCookie(origin, "existing=present; Path=/; HttpOnly; SameSite=Lax") { saved.complete(Unit) }
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (url.endsWith("/account")) result.complete(view.title.orEmpty())
                        }
                    }
                }
                saved.get(10, TimeUnit.SECONDS)
                ui { web.loadUrl("$origin/start") }
                assertEquals("Signed in", result.get(20, TimeUnit.SECONDS))
                ui {
                    assertTrue(session.cookies().getCookie(origin).orEmpty().contains("login=accepted"))
                    assertFalse(session.cookies().acceptThirdPartyCookies(web))
                }
            } finally {
                ui { session.end() }
                executor.shutdownNow()
            }
        }
    }
}
