package app.xvi.web

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the JVM and Node tests cannot show: that this WebView runs the document-start registrations in
 * order, before the page's own scripts, takes the hook and bridge off the page, and hands stored values
 * back to the app, kept apart for private sessions.
 */
@RunWith(AndroidJUnit4::class)
class UserScriptsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> ui(action: () -> T): T { var result: T? = null; instrumentation.runOnMainSync { result = action() }; @Suppress("UNCHECKED_CAST") return result as T }
    private val executor = Executors.newSingleThreadExecutor()
    private val connections = Executors.newCachedThreadPool()
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"
    private val installed = mutableListOf<String>()

    /** What the page's own first script saw: the order scripts ran in so far, and any app globals left. */
    private val page = "<html><head><title>page</title><script>window.pageSaw = { order: (window.order || []).slice()," +
        " leftovers: Object.getOwnPropertyNames(window).filter(k => k.indexOf('__xvi') === 0) };</script></head><body><p>page</p></body></html>"

    /** Pages, an echo of what a request carried, a cookie being set, and redirects. */
    private fun respond(method: String, path: String, headers: Map<String, String>, sent: String): String {
        fun reply(status: String, type: String, body: String, extra: String = "") =
            "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.toByteArray().size}\r\n${extra}Connection: close\r\n\r\n$body"
        return when {
            path.startsWith("/echo") -> reply("200 OK", "application/json", JSONObject().put("method", method).put("host", headers["host"])
                .put("cookie", headers["cookie"] ?: "").put("body", sent).put("type", headers["content-type"] ?: "").toString())
            path.startsWith("/login") -> reply("200 OK", "text/plain", "ok", "Set-Cookie: token=abc; Path=/\r\n")
            path.startsWith("/redirect?to=") -> reply("302 Found", "text/plain", "", "Location: ${java.net.URLDecoder.decode(path.substringAfter("to="), "UTF-8")}\r\n")
            else -> reply("200 OK", "text/html", page)
        }
    }

    @Before fun start() {
        assumeTrue("WebView needs document-start scripts and message listeners", UserScripts.available)
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        // A connection per thread: Chromium opens spare connections that may never send a request.
        executor.execute {
            while (!server.isClosed) runCatching {
                val socket = server.accept()
                connections.execute {
                    runCatching {
                        socket.use {
                            it.soTimeout = 5_000
                            val reader = it.getInputStream().bufferedReader()
                            val (method, path) = (reader.readLine() ?: return@use).split(" ").let { parts -> parts[0] to parts[1] }
                            val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }
                                .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                            val sent = CharArray(headers["content-length"]?.toInt() ?: 0).also { chars -> var read = 0; while (read < chars.size) read += reader.read(chars, read, chars.size - read) }
                            it.getOutputStream().write(respond(method, path, headers, String(sent)).toByteArray())
                        }
                    }
                }
            }
        }
        ui { UserScripts.ensureLoaded(context) }
    }

    @After fun stop() {
        ui { installed.forEach(UserScripts::remove) }
        if (::server.isInitialized) server.close()
        executor.shutdownNow()
        connections.shutdownNow()
    }

    private fun install(name: String, code: String, vararg meta: String, allowPrivate: Boolean = false): String {
        val text = (listOf("// ==UserScript==", "// @name $name", "// @namespace device-test") + meta.map { "// $it" } + listOf("// ==/UserScript==", code)).joinToString("\n")
        val script = UserScript(text, requireNotNull(UserScriptMeta.parse(text)), allowPrivate = allowPrivate)
        ui { UserScripts.install(UserScripts.Pending(script, null)); if (allowPrivate) UserScripts.setAllowPrivate(script.id, true) }
        installed.add(script.id)
        return script.id
    }

    private fun view(session: BrowserSession): WebView = ui {
        session.createWebView(context).apply {
            settings.javaScriptEnabled = true
            UserScripts.attach(this, session)
        }
    }

    private fun load(view: WebView, url: String) {
        val loaded = CompletableFuture<Unit>()
        ui {
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { UserScriptApi.pageStarted(view) }
                override fun onPageFinished(view: WebView, url: String) { loaded.complete(Unit) }
            }
            view.loadUrl(url)
        }
        loaded.get(15, TimeUnit.SECONDS)
    }

    private fun js(view: WebView, script: String): String {
        val answer = CompletableFuture<String>()
        ui { view.evaluateJavascript(script) { answer.complete(it) } }
        return answer.get(10, TimeUnit.SECONDS)
    }

    @Test fun scriptsRunInOrderBeforeThePageAndLeaveNothingBehind() {
        install("first", "window.order = (window.order || []).concat('first');", "@match http://127.0.0.1/*", "@run-at document-start")
        install("broken", "this is not javascript (", "@match http://127.0.0.1/*", "@run-at document-start")
        install("second", "window.order = (window.order || []).concat('second');", "@match http://127.0.0.1/*", "@run-at document-start")
        install("later", "window.order.push('end:' + document.readyState);", "@match http://127.0.0.1/*", "@run-at document-end")
        install("elsewhere", "window.order = (window.order || []).concat('elsewhere');", "@match https://example.org/*", "@run-at document-start")
        val session = ui { BrowserSession() }
        val web = view(session)
        try {
            load(web, "$base/page")
            val saw = JSONObject(js(web, "JSON.stringify(window.pageSaw)").let { org.json.JSONTokener(it).nextValue() as String })
            assertEquals("[\"first\",\"second\"]", saw.getJSONArray("order").toString())
            assertEquals("[]", saw.getJSONArray("leftovers").toString())
            assertEquals("\"first,second,end:interactive\"", js(web, "window.order.join()"))
        } finally { ui { session.end() } }
    }

    /** Match patterns ignore the port; a local server on another port still gets its scripts. */
    @Test fun matchesWithoutAPortCoverOtherPorts() {
        install("portless", "window.order = (window.order || []).concat('portless');", "@match http://127.0.0.1/*", "@run-at document-start")
        install("ported", "window.order = (window.order || []).concat('ported');", "@match http://127.0.0.1:${server.localPort}/*", "@run-at document-start")
        install("wrong port", "window.order = (window.order || []).concat('wrong');", "@match http://127.0.0.1:1/*", "@run-at document-start")
        val session = ui { BrowserSession() }
        val web = view(session)
        try {
            load(web, "$base/page")
            assertEquals("\"portless,ported\"", js(web, "(window.order || []).join()"))
        } finally { ui { session.end() } }
    }

    @Test fun storedValuesReachTheAppAndPrivateChangesStayInMemory() {
        val id = install("store", "window.previous = GM_getValue('last', 'none'); GM_setValue('last', location.search);",
            "@match http://127.0.0.1/*", "@run-at document-start", allowPrivate = true)
        val ordinary = ui { BrowserSession() }
        val web = view(ordinary)
        try {
            load(web, "$base/page?one")
            assertEquals("\"none\"", js(web, "window.previous"))
            // Values settle for a second before they are saved and handed to new pages.
            Thread.sleep(1_500)
            assertEquals(1, ui { UserScripts.valueCount(id) })
            load(web, "$base/page?two")
            assertEquals("\"?one\"", js(web, "window.previous"))
            Thread.sleep(1_500)
            assumeTrue("private sessions need MULTI_PROFILE", ui { BrowserSession.privateAvailable() })
            val private = ui { BrowserSession(true) }
            val secret = view(private)
            try {
                load(secret, "$base/page?private")
                assertEquals("\"?two\"", js(secret, "window.previous"))
                Thread.sleep(1_500)
                load(secret, "$base/page?private-again")
                assertEquals("\"?private\"", js(secret, "window.previous"))
            } finally { ui { private.end() } }
            Thread.sleep(1_500)
            load(web, "$base/page?three")
            assertEquals("\"?two\"", js(web, "window.previous"))
            assertTrue(java.io.File(context.filesDir, "userscripts/$id.values.json").readText().contains("?two"))
        } finally { ui { ordinary.end() } }
    }

    /** Waits until the page's `window.results` holds [count] entries, and returns them as JSON. */
    private fun results(view: WebView, count: Int): org.json.JSONArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val raw = js(view, "JSON.stringify(window.results || [])").let { org.json.JSONTokener(it).nextValue() as String }
            val list = org.json.JSONArray(raw)
            if (list.length() >= count) return list
            Thread.sleep(100)
        }
        throw AssertionError("results never reached $count: " + js(view, "JSON.stringify(window.results || [])"))
    }

    private val requests = """
        window.results = [];
        const ask = (label, details) => GM_xmlhttpRequest(Object.assign({
            onload: r => results.push([label, r.status, r.responseText ? JSON.parse(r.responseText) : null]),
            onerror: r => results.push([label, 'error', r.error]) }, details));
    """

    @Test fun requestsReachThePageAndConnectHostsOnly() {
        val port = server.localPort
        install("requests", requests + """
            ask('same', { url: '/echo', method: 'POST', data: 'a=1', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
            ask('connect', { url: 'http://localhost:$port/echo' });
            ask('unlisted', { url: 'http://127.0.0.2:$port/echo' });
            ask('redirected', { url: '/redirect?to=' + encodeURIComponent('http://127.0.0.2:$port/echo') });
        """, "@match http://127.0.0.1/*", "@connect localhost", "@grant GM_xmlhttpRequest")
        val session = ui { BrowserSession() }
        val web = view(session)
        try {
            load(web, "$base/page")
            val byLabel = results(web, 4).let { list -> (0 until list.length()).associate { list.getJSONArray(it).let { r -> r.getString(0) to r } } }
            assertEquals(200, byLabel.getValue("same").getInt(1))
            assertEquals("POST", byLabel.getValue("same").getJSONObject(2).getString("method"))
            assertEquals("a=1", byLabel.getValue("same").getJSONObject(2).getString("body"))
            assertEquals("localhost:$port", byLabel.getValue("connect").getJSONObject(2).getString("host"))
            // Bare test views have no window to ask in, so an unlisted host is refused, also behind a redirect.
            assertEquals("refused", byLabel.getValue("unlisted").getString(2))
            assertEquals("refused", byLabel.getValue("redirected").getString(2))
        } finally { ui { session.end() } }
    }

    @Test fun requestsCarryTheSessionsCookiesUnlessAnonymous() {
        install("cookies", requests + """
            ask('cookie', { url: '/echo' });
            ask('anonymous', { url: '/echo', anonymous: true });
            ask('login', { url: '/login' , onload: () => results.push(['login']) });
        """, "@match http://127.0.0.1/*", "@grant GM_xmlhttpRequest", allowPrivate = true)
        val ordinary = ui { BrowserSession() }
        val web = view(ordinary)
        ui { CookieManager.getInstance().setCookie(base, "sid=normal"); CookieManager.getInstance().removeSessionCookies(null) }
        ui { CookieManager.getInstance().setCookie(base, "sid=normal") }
        try {
            load(web, "$base/page")
            val list = results(web, 3)
            val byLabel = (0 until list.length()).associate { list.getJSONArray(it).let { r -> r.getString(0) to r } }
            assertTrue(byLabel.getValue("cookie").getJSONObject(2).getString("cookie").contains("sid=normal"))
            assertEquals("", byLabel.getValue("anonymous").getJSONObject(2).getString("cookie"))
            assertTrue(ui { CookieManager.getInstance().getCookie(base).orEmpty() }.contains("token=abc"))
            assumeTrue("private sessions need MULTI_PROFILE", ui { BrowserSession.privateAvailable() })
            val private = ui { BrowserSession(true) }
            val secret = view(private)
            try {
                ui { private.cookies().setCookie(base, "sid=private") }
                load(secret, "$base/page")
                val privateList = results(secret, 3)
                val cookie = (0 until privateList.length()).map { privateList.getJSONArray(it) }.first { it.getString(0) == "cookie" }.getJSONObject(2).getString("cookie")
                assertTrue(cookie, cookie.contains("sid=private") && !cookie.contains("sid=normal") && !cookie.contains("token=abc"))
            } finally { ui { private.end() } }
        } finally {
            ui { CookieManager.getInstance().removeAllCookies(null) }
            ui { ordinary.end() }
        }
    }

    /** Commands registered at document start come after the page-start reset, not before it. */
    @Test fun menuCommandsFromDocumentStartSurviveThePageStart() {
        install("menu", "GM_registerMenuCommand('早', () => { window.ran = 'early'; });",
            "@match http://127.0.0.1/*", "@run-at document-start", "@grant GM_registerMenuCommand")
        val session = ui { BrowserSession() }
        val web = view(session)
        try {
            load(web, "$base/page")
            Thread.sleep(300)
            val commands = ui { UserScriptApi.commands(web) }
            assertEquals(listOf("早"), commands.map { it.name })
            ui { UserScriptApi.run(commands.single()) }
            Thread.sleep(300)
            assertEquals("\"early\"", js(web, "window.ran"))
            load(web, "$base/page?again")
            Thread.sleep(300)
            assertEquals(1, ui { UserScriptApi.commands(web).size })
        } finally { ui { session.end() } }
    }

    @Test fun valueChangesReachOtherPages() {
        install("shared", """
            window.heard = [];
            GM_addValueChangeListener('k', (key, before, after, remote) => heard.push([after, remote]));
            window.change = v => GM_setValue('k', v);
        """, "@match http://127.0.0.1/*", "@run-at document-start", "@grant GM_setValue", "@grant GM_addValueChangeListener")
        val session = ui { BrowserSession() }
        val one = view(session)
        val two = view(session)
        try {
            load(one, "$base/page?one")
            load(two, "$base/page?two")
            Thread.sleep(300)
            js(one, "change('hello')")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && js(two, "JSON.stringify(heard)") == "\"[]\"") Thread.sleep(100)
            assertEquals("\"[[\\\"hello\\\",true]]\"", js(two, "JSON.stringify(heard)"))
            assertEquals("\"[[\\\"hello\\\",false]]\"", js(one, "JSON.stringify(heard)"))
        } finally { ui { session.end() } }
    }
}
