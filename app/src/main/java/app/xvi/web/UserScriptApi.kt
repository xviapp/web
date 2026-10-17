package app.xvi.web

import android.annotation.SuppressLint
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.webkit.JavaScriptReplyProxy
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.Base64
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What user scripts ask of the app beyond stored values: requests to other sites, menu commands,
 * tabs, the clipboard, notifications, downloads, and hearing about values other pages changed.
 *
 * GM_xmlhttpRequest is made here, through the browser's network settings and with the cookies of the
 * page's own session, so a private page's request carries only private cookies. A script may reach the
 * page's own host, the hosts its `@connect` names and those the user allowed; for any other host the
 * page in view asks, and redirects to hosts the script may not reach end the request.
 *
 * What a page registered is forgotten when its next document starts.
 */
@SuppressLint("RequiresFeature") // Only reached through [UserScripts]' message listener.
@UiThread
internal object UserScriptApi {
    class Command(val script: String, val id: String, val name: String, internal val reply: JavaScriptReplyProxy)

    private class Page(val session: BrowserSession?) {
        val commands = LinkedHashMap<String, Command>()
        /** Frames that listen for changes to a script's values, with that script. */
        val listeners = mutableListOf<Pair<String, JavaScriptReplyProxy>>()
        /** Requests in flight; the network threads add and remove them. */
        val requests = ConcurrentHashMap<Long, Call>()
        /** Requests aborted before or while they ran. */
        val aborted: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        /** Hosts allowed or refused for one script on this page only. */
        val allowed = HashSet<String>()
        val refused = HashSet<String>()
        /** When tabs were opened or files downloaded lately, for the rate limit. */
        val recent = ArrayDeque<Long>()
        var copied = 0L
    }

    private const val MAX_COMMANDS = 50
    private const val MAX_LISTENERS = 50
    private const val MAX_REDIRECTS = 10
    private const val MAX_RESPONSE = 16L * 1024 * 1024
    private const val MAX_BODY = 16 * 1024 * 1024
    /** Tabs opened and downloads started by a page's scripts, at most this many per [BURST_MS]. */
    private const val BURST = 3
    private const val BURST_MS = 10_000L
    private val pages = WeakHashMap<WebView, Page>()
    /** Requests waiting for the user's answer about a host, by script and host. */
    private val asking = HashMap<String, MutableList<(Boolean) -> Unit>>()
    private val network = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** A new document in [web]'s main frame: what the last one registered goes, and its requests end. */
    fun pageStarted(web: WebView) {
        pages.remove(web)?.requests?.values?.forEach(Call::cancel)
    }

    /** The menu commands [web]'s scripts registered, in order. */
    fun commands(web: WebView?): List<Command> = web?.let { pages[it]?.commands?.values?.toList() }.orEmpty()

    fun run(command: Command) = send(command.reply, JSONObject().put("t", "menu").put("s", command.script).put("id", command.id))

    private fun page(web: WebView, session: BrowserSession?) = pages.getOrPut(web) { Page(session) }
    private fun host(web: WebView) = ((web.context as? MutableContextWrapper)?.baseContext as? PageHost)?.takeIf { !it.isDestroyed }
    private fun tab(host: PageHost, web: WebView) = host.tabs.firstOrNull { it.view === web }
    private fun send(reply: JavaScriptReplyProxy, message: JSONObject) { runCatching { reply.postMessage(message.toString()) } }

    fun handle(web: WebView, session: BrowserSession?, script: UserScript, json: JSONObject, pageHost: String?, reply: JavaScriptReplyProxy) {
        val page = page(web, session)
        when (json.optString("op")) {
            "xhr" -> request(web, page, script, json, pageHost, reply)
            "abort" -> { page.aborted.add(json.optLong("id")); page.requests.remove(json.optLong("id"))?.cancel() }
            "menu" -> {
                val key = script.id + "\n" + json.optString("id")
                if (key in page.commands || page.commands.size < MAX_COMMANDS) {
                    page.commands[key] = Command(script.id, json.optString("id"), json.optString("name").trim().take(100).ifEmpty { script.meta.name }, reply)
                }
            }
            "unmenu" -> page.commands.remove(script.id + "\n" + json.optString("id"))
            "listen" -> if (page.listeners.size < MAX_LISTENERS) page.listeners.add(script.id to reply)
            "open" -> {
                val url = json.optString("url").takeIf(BrowserInput::isWebUrl) ?: return
                val host = host(web) ?: return
                val tab = tab(host, web) ?: return
                if (burst(page)) host.openTab(tab, url, json.optBoolean("background", true))
            }
            "clip" -> {
                val host = host(web) ?: return
                val now = SystemClock.elapsedRealtime()
                if (now - page.copied < 1_000) return
                page.copied = now
                host.copy(json.optString("text").take(1024 * 1024), sensitive = session != null)
            }
            "notify" -> notify(web, json, reply)
            "download" -> {
                val url = json.optString("url").takeIf(BrowserInput::isWebUrl) ?: return
                val host = host(web) ?: return
                val tab = tab(host, web) ?: return
                val name = json.optString("name").takeIf { json.has("name") && !json.isNull("name") && it.isNotBlank() }
                if (burst(page)) host.downloads.download(tab, url, web.settings.userAgentString, name?.let { "attachment; filename=\"${it.replace("\"", "")}\"" }, null)
            }
        }
    }

    /** True while the page's scripts stay within [BURST] tabs and downloads per [BURST_MS]. */
    private fun burst(page: Page): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (page.recent.isNotEmpty() && now - page.recent.first() > BURST_MS) page.recent.removeFirst()
        if (page.recent.size >= BURST) return false
        page.recent.addLast(now)
        return true
    }

    /** Shown only for the page in view; a notification that can be tapped says so with its action. */
    private fun notify(web: WebView, json: JSONObject, reply: JavaScriptReplyProxy) {
        val id = json.optLong("id")
        fun event(name: String) = send(reply, JSONObject().put("t", "notify").put("id", id).put("event", name))
        val host = host(web)
        val tab = host?.let { tab(it, web) }
        if (host == null || tab == null || host.tab !== tab) { event("done"); return }
        val title = json.optString("title").take(100)
        val text = json.optString("text").take(300)
        val message = listOf(title, text).filter { it.isNotBlank() }.joinToString("：")
        if (json.optBoolean("clickable")) host.showSnackbar(message, host.str(R.string.userscript_notification_open)) { event("click"); event("done") }
        else { host.showSnackbar(message); event("done") }
    }

    /** Pages where [scriptId] listens hear about a change to one of its values, except the page that made it. */
    fun valueChanged(session: BrowserSession?, scriptId: String, key: String, value: String?, from: JavaScriptReplyProxy) {
        val message = JSONObject().put("t", "value").put("s", scriptId).put("key", key).put("value", value ?: JSONObject.NULL)
        pages.values.filter { it.session === session }.forEach { page ->
            page.listeners.forEach { (id, reply) -> if (id == scriptId && reply !== from) send(reply, message) }
        }
    }

    // Requests.

    private fun request(web: WebView, page: Page, script: UserScript, json: JSONObject, pageHost: String?, reply: JavaScriptReplyProxy) {
        val id = json.optLong("id")
        fun fail(event: String, error: String) = send(reply, JSONObject().put("t", "xhr").put("id", id).put("event", event).put("error", error))
        val url = json.optString("url").toHttpUrlOrNull() ?: return fail("error", "unsupported address")
        permit(web, page, script, url.host, pageHost) { allowed ->
            if (!allowed) return@permit fail("error", "refused")
            val current = UserScripts.find(script.id) ?: script
            val reach = { host: String -> current.meta.mayConnect(host, pageHost, current.allowedHosts) || "${script.id}\n$host" in page.allowed }
            val cookies = if (json.optBoolean("anonymous")) null else page.session?.let { runCatching { it.cookies() }.getOrNull() } ?: CookieManager.getInstance()
            val agent = web.settings.userAgentString
            val route = host(web)?.network ?: BrowserProxyNetwork.get(web.context.applicationContext)
            route.prepare { _ ->
                val proxy = route.proxy()
                network.execute {
                    val answer = runCatching { perform(page, id, json, url, reach, cookies, agent, proxy) }
                    main.post {
                        page.requests.remove(id)
                        page.aborted.remove(id)
                        answer.onSuccess { send(reply, it) }.onFailure { error ->
                            when {
                                error is Canceled -> Unit
                                error is InterruptedIOException && error.message?.contains("timeout", ignoreCase = true) == true -> fail("timeout", "timeout")
                                error is Refused -> fail("error", "refused")
                                else -> fail("error", error.message ?: "network")
                            }
                        }
                    }
                }
            }
        }
    }

    private val NEEDS_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    private class Refused : Exception()
    private class Canceled : Exception()

    /** Runs on a network thread; follows redirects itself, so cookies and the host check apply to each one. */
    private fun perform(page: Page, id: Long, json: JSONObject, start: HttpUrl, reach: (String) -> Boolean, cookies: CookieManager?,
                        agent: String, proxy: java.net.Proxy): JSONObject {
        val timeout = json.optLong("timeout").coerceAtLeast(0)
        val client = BrowserHttp.through(proxy).newBuilder().followRedirects(false).followSslRedirects(false)
            .apply { if (timeout > 0) callTimeout(timeout, TimeUnit.MILLISECONDS) }.build()
        val headers = json.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        fun has(name: String) = headers.keys.any { it.equals(name, ignoreCase = true) }
        var method = json.optString("method").ifBlank { "GET" }.uppercase(Locale.ROOT)
        var body = body(json.optJSONObject("data"), headers)
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(url)
            headers.forEach { (name, value) -> runCatching { request.header(name, value) } }
            if (!has("User-Agent")) request.header("User-Agent", agent)
            if (!has("Cookie")) cookies?.getCookie(url.toString())?.takeIf { it.isNotBlank() }?.let { request.header("Cookie", it) }
            val payload = body ?: if (method in NEEDS_BODY) ByteArray(0).toRequestBody(null) else null
            request.method(method, if (method == "GET" || method == "HEAD") null else payload)
            val call = client.newCall(request.build())
            page.requests[id] = call
            if (id in page.aborted) throw Canceled()
            val response = try { call.execute() } catch (error: java.io.IOException) { if (call.isCanceled()) throw Canceled() else throw error }
            response.use {
                response.headers("Set-Cookie").forEach { cookie -> cookies?.setCookie(url.toString(), cookie) }
                val next = response.header("Location")?.let(url::resolve)
                if (!response.isRedirect || next == null || !json.optBoolean("redirect", true)) return answer(id, response, url, json.optBoolean("binary"))
                if (!reach(next.host)) throw Refused()
                if (response.code == 303 || (response.code in 301..302 && method == "POST")) { method = "GET"; body = null }
                url = next
            }
        }
        throw java.io.IOException("too many redirects")
    }

    private fun body(data: JSONObject?, headers: Map<String, String>): RequestBody? {
        data ?: return null
        val type = (headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: data.optString("type").takeIf { data.has("type") && !data.isNull("type") })?.toMediaTypeOrNull()
        val bytes = when {
            data.has("base64") -> runCatching { Base64.getDecoder().decode(data.optString("base64")) }.getOrNull() ?: ByteArray(0)
            else -> data.optString("text").toByteArray()
        }
        if (bytes.size > MAX_BODY) throw java.io.IOException("body too large")
        return bytes.toRequestBody(type)
    }

    private fun answer(id: Long, response: okhttp3.Response, url: HttpUrl, binary: Boolean): JSONObject {
        val source = response.body.source()
        val buffer = Buffer()
        while (source.read(buffer, 64 * 1024) != -1L) if (buffer.size > MAX_RESPONSE) throw java.io.IOException("response too large")
        val type = response.body.contentType()
        val headers = response.headers.joinToString("") { (name, value) -> "${name.lowercase(Locale.ROOT)}: $value\r\n" }
        return JSONObject().put("t", "xhr").put("id", id).put("event", "load").put("status", response.code).put("statusText", response.message)
            .put("finalUrl", url.toString()).put("headers", headers).put("mime", type?.toString().orEmpty()).apply {
                if (binary) put("base64", Base64.getEncoder().encodeToString(buffer.readByteArray()))
                else put("text", buffer.readString(type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
            }
    }

    /**
     * Whether [script] may reach [host]: at once for a host it names or the user allowed, otherwise the
     * page in view asks. A host refused on this page is not asked about again until the page changes.
     */
    private fun permit(web: WebView, page: Page, script: UserScript, host: String, pageHost: String?, then: (Boolean) -> Unit) {
        val current = UserScripts.find(script.id) ?: script
        val key = script.id + "\n" + host
        if (current.meta.mayConnect(host, pageHost, current.allowedHosts) || key in page.allowed) return then(true)
        if (key in page.refused) return then(false)
        asking[key]?.let { it.add(then); return }
        val window = host(web)
        val tab = window?.let { tab(it, web) }
        if (window == null || tab == null || window.tab !== tab) return then(false)
        asking[key] = mutableListOf(then)
        fun answer(allowed: Boolean, always: Boolean) {
            if (always) UserScripts.allowHost(script.id, host)
            if (allowed) page.allowed.add(key) else page.refused.add(key)
            asking.remove(key)?.forEach { it(allowed) }
        }
        window.browserDialog(tab).setTitle(R.string.userscript_connect_title)
            .setMessage(window.str(R.string.userscript_connect_message, current.meta.name, host))
            .setPositiveButton(R.string.userscript_connect_always) { _, _ -> answer(true, always = true) }
            .setNeutralButton(R.string.userscript_connect_page) { _, _ -> answer(true, always = false) }
            .setNegativeButton(R.string.userscript_connect_deny) { _, _ -> answer(false, always = false) }
            .setOnCancelListener { answer(false, always = false) }
            .show()
    }
}
