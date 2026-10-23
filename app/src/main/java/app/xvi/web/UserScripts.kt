package app.xvi.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.security.SecureRandom
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Installed user scripts, shared by every window, and their way into pages.
 *
 * Each page view gets the scripts as document-start scripts: the runtime, then one registration per
 * script with its settings and code, limited to the origins its `@match` names, so a script that fails to
 * parse leaves the others running and pages it never runs on never receive it. What each script stored
 * follows in a registration of its own, and a last one starts the scripts and takes the runtime off the
 * page. Changes reach pages from their next load; only the registrations from the first that changed on
 * are added again, which is why the values, changing most often, come last.
 *
 * Stored values go back to the app through a message listener that only accepts messages carrying the
 * token of the session's runtime. Private sessions see the ordinary values but keep their own changes
 * in memory, so a private page leaves nothing behind; only scripts allowed in private tabs run there.
 *
 * State lives on the main thread; files are read and written on one background thread.
 */
// Every WebView call is behind [available]; the context kept is the application's, which lives as long as this does.
@SuppressLint("RequiresFeature", "StaticFieldLeak")
@UiThread
internal object UserScripts {
    enum class Failure { NETWORK, INVALID, TOO_LARGE, REQUIRE }
    /** A script read and ready to install; [replaces] is the installed one it takes the place of. */
    class Pending(val script: UserScript, val replaces: UserScript?)

    private class State(val scripts: MutableList<UserScript>, val values: MutableMap<String, MutableMap<String, String>>)
    /**
     * One page view's registrations, each with the script and origins it was added with; [session] only
     * for a private one, whose values are its own.
     */
    private class Attachment(val session: BrowserSession?, var added: List<Pair<Pair<String, Set<String>>, ScriptHandler>> = emptyList(),
                             var listening: Set<String> = emptySet())

    val available: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    private const val HANDLER = "Xvi"
    private const val UPDATE_INTERVAL = 7L * 24 * 60 * 60 * 1000
    /** Value changes are written and handed to pages together, at most this often. */
    private const val SETTLE_MS = 1_000L
    private val random = SecureRandom()
    private fun nonce() = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    /** Names only this process knows, so a page cannot prepare a fake hook or bridge under them. */
    private val hook = "__xvi" + nonce()
    private val bridge = "__xvi" + nonce()
    private val token = nonce()

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var loading: Future<State>? = null
    private var state: State? = null
    private val attached = WeakHashMap<WebView, Attachment>()
    /** Registrations for ordinary pages, and for each private session; rebuilt after any change. */
    private var ordinary: List<Pair<String, Set<String>>>? = null
    private val privateRegistrations = WeakHashMap<BrowserSession, List<Pair<String, Set<String>>>>()
    /** Each script's registration by id, with the script it was made from; made again only when that script changes. */
    private val codes = HashMap<String, Pair<UserScript, String>>()
    /** Registrations of what each script stored, by id, for ordinary pages and for each private session; dropped on a change. */
    private val ordinaryValues = HashMap<String, String>()
    private val privateValues = WeakHashMap<BrowserSession, MutableMap<String, String>>()
    private val tokens = WeakHashMap<BrowserSession, String>()
    private val dirtyValues = HashSet<String>()
    private val runtime by lazy { app.assets.open("userscript-runtime.js").bufferedReader().use { it.readText() } }
    private val appVersion by lazy { runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty() }

    /** Starts reading the scripts once per process; pages wait for it only if they come first. */
    fun ensureLoaded(context: Context) {
        if (loading != null) return
        app = context.applicationContext
        loading = worker.submit<State> { read() }
    }

    private fun loaded(): State = state ?: (runCatching { loading?.get() }.getOrNull() ?: State(mutableListOf(), mutableMapOf())).also { state = it }

    val scripts: List<UserScript> get() = loaded().scripts.toList()
    fun find(id: String) = loaded().scripts.firstOrNull { it.id == id }

    /** Scripts that name [url], whether or not they are switched on. */
    fun forPage(url: String): List<UserScript> = loaded().scripts.filter { it.meta.runsOn(url) }

    // Pages.

    /** Gives [web]'s pages the scripts from its next load on; called once for every page view. */
    fun attach(web: WebView, session: BrowserSession) {
        if (!available) return
        val attachment = Attachment(session.takeIf { it.isPrivate })
        attached[web] = attachment
        register(web, attachment)
    }

    fun detach(web: WebView) {
        val attachment = attached.remove(web) ?: return
        attachment.added.forEach { runCatching { it.second.remove() } }
    }

    private fun register(web: WebView, attachment: Attachment) {
        val wanted = registrations(attachment.session)
        // Registrations run in the order they were added, so all from the first that changed on are added again.
        val kept = attachment.added.zip(wanted).takeWhile { (old, new) -> old.first == new }.size
        attachment.added.drop(kept).forEach { runCatching { it.second.remove() } }
        attachment.added = attachment.added.take(kept) + wanted.drop(kept).mapNotNull { piece ->
            runCatching { piece to WebViewCompat.addDocumentStartJavaScript(web, piece.first, piece.second) }.getOrNull()
        }
        // The bridge goes only where the runtime runs, since the runtime is what takes it off the page.
        val origins = wanted.firstOrNull()?.second.orEmpty()
        if (origins == attachment.listening) return
        if (attachment.listening.isNotEmpty()) runCatching { WebViewCompat.removeWebMessageListener(web, bridge) }
        attachment.listening = if (origins.isEmpty()) emptySet()
            else origins.takeIf { runCatching { WebViewCompat.addWebMessageListener(web, bridge, it, listener) }.isSuccess }.orEmpty()
    }

    /** Hands the current scripts and values to every page view, for its next load. */
    private val refresh = Runnable {
        flushValues()
        attached.entries.toList().forEach { (web, attachment) -> register(web, attachment) }
    }

    /** Scripts or their settings changed: pages get them at once, and they are saved. */
    private fun changed() {
        ordinary = null
        privateRegistrations.clear()
        // Values may have moved with a renamed script or come in from a backup.
        ordinaryValues.clear()
        privateValues.clear()
        val installed = loaded().scripts.mapTo(HashSet()) { it.id }
        codes.keys.retainAll(installed)
        main.removeCallbacks(refresh)
        refresh.run()
    }

    /** The registrations for a page view: the runtime, each script, what they stored, and the start of the scripts. */
    private fun registrations(session: BrowserSession?): List<Pair<String, Set<String>>> {
        if (session == null) ordinary?.let { return it } else privateRegistrations[session]?.let { return it }
        val built = build(session)
        if (session == null) ordinary = built else privateRegistrations[session] = built
        return built
    }

    private fun build(session: BrowserSession?): List<Pair<String, Set<String>>> {
        if (!BrowserPrefs(app).userScripts) return emptyList()
        val active = loaded().scripts.filter { it.enabled && it.meta.hasTargets && (session == null || it.allowPrivate) }
            .map { it to it.meta.originRules() }.filter { it.second.isNotEmpty() }
        if (active.isEmpty()) return emptyList()
        val origins = if (active.any { it.second == UserScriptMeta.ALL_ORIGINS }) UserScriptMeta.ALL_ORIGINS else active.flatMapTo(LinkedHashSet()) { it.second }
        val config = JSONObject().put("hook", hook).put("bridge", bridge).put("token", tokenFor(session))
            .put("handler", JSONObject().put("name", HANDLER).put("version", appVersion)).put("params", JSONArray(UserScriptMeta.API))
        // Parsed rather than written as a literal, where a "__proto__" key would set the prototype.
        val start = "(() => { const config = JSON.parse(${JSONObject.quote(config.toString())}); ($runtime)(config); })();"
        // The runtime leaves the page before the scripts run, and so long before the page's own do.
        val end = "(() => { const runtime = window[${JSONObject.quote(hook)}]; try { delete window[${JSONObject.quote(hook)}]; } catch (_) {}" +
            " if (runtime) runtime.start(); })();"
        return listOf(start to origins) + active.map { (script, rules) -> code(script) to rules } +
            active.mapNotNull { (script, rules) -> values(session, script.id)?.let { it to rules } } + listOf(end to origins)
    }

    private fun settings(script: UserScript): JSONObject {
        val meta = script.meta
        fun patterns(list: List<UserScriptMeta.Pattern>) = JSONArray().apply { list.forEach { put(JSONArray().put(it.source).put(if (it.ignoreCase) "i" else "")) } }
        return JSONObject().put("name", meta.name).put("namespace", meta.namespace).put("version", meta.version).put("description", meta.description)
            .put("author", meta.author).put("matches", JSONArray(meta.matches)).put("includes", JSONArray(meta.includes)).put("excludes", JSONArray(meta.excludes))
            .put("grants", JSONArray(meta.grants)).put("runAt", meta.runAt.key).put("noFrames", meta.noFrames).put("block", meta.block)
            .put("run", patterns(meta.runPatterns)).put("skip", patterns(meta.skipPatterns))
            .put("resources", JSONObject().apply { script.resources.forEach { (name, r) -> put(name, JSONObject().put("mime", r.mime).put("data", r.data)) } })
    }

    /**
     * [script]'s registration: its settings, then its code and requires inside a function whose parameters
     * are the API it gets. Made once per version of the script, as it can run to megabytes.
     */
    private fun code(script: UserScript): String = codes[script.id]?.takeIf { it.first === script }?.second ?: run {
        val body = (script.meta.requires.mapNotNull { script.requires[it] } + script.code).joinToString("\n;\n")
        ("(function () { const runtime = window[${JSONObject.quote(hook)}]; if (!runtime) return; runtime.define(${JSONObject.quote(script.id)}, " +
            "JSON.parse(${JSONObject.quote(settings(script).toString())}), function (${UserScriptMeta.API.joinToString(", ")}) {\n$body\n}); })();")
            .also { codes[script.id] = script to it }
    }

    // Stored values.

    private fun tokenFor(session: BrowserSession?) = if (session == null) token else tokens.getOrPut(session, ::nonce)

    /** What [session] sees: a private session's own copy once it changed something, else the stored values. */
    private fun storedValues(session: BrowserSession?, id: String): Map<String, String> =
        session?.scriptValues?.get(id) ?: loaded().values[id].orEmpty()

    /** The registration of what script [id] stored, as [session]'s pages see it; null while it has stored nothing. */
    private fun values(session: BrowserSession?, id: String): String? {
        val made = if (session == null) ordinaryValues else privateValues.getOrPut(session) { HashMap() }
        made[id]?.let { return it }
        val values = storedValues(session, id).takeIf { it.isNotEmpty() } ?: return null
        return ("(() => { const runtime = window[${JSONObject.quote(hook)}]; if (runtime) runtime.values(${JSONObject.quote(id)}, " +
            "JSON.parse(${JSONObject.quote(JSONObject(values).toString())})); })();").also { made[id] = it }
    }

    private val listener = WebViewCompat.WebMessageListener { view, message, origin, _, reply ->
        val attachment = attached[view] ?: return@WebMessageListener
        val json = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@WebMessageListener
        if (json.optString("k") != tokenFor(attachment.session)) return@WebMessageListener
        val script = find(json.optString("s")) ?: return@WebMessageListener
        val key = json.optString("key").takeIf { it.length <= UserScript.MAX_KEY } ?: return@WebMessageListener
        when (json.optString("op")) {
            "set" -> setValue(attachment.session, script, key, json.optString("value"), reply)
            "delete" -> setValue(attachment.session, script, key, null, reply)
            else -> UserScriptApi.handle(view, attachment.session, script, json, origin.host, reply)
        }
    }

    private fun setValue(session: BrowserSession?, script: UserScript, key: String, value: String?, from: androidx.webkit.JavaScriptReplyProxy) {
        val values = if (session == null) loaded().values.getOrPut(script.id) { LinkedHashMap() }
            else session.scriptValues.getOrPut(script.id) { LinkedHashMap(loaded().values[script.id].orEmpty()) }
        if (value == null) { if (values.remove(key) == null) return }
        else {
            val total = values.entries.sumOf { (k, v) -> if (k == key) 0 else k.length + v.length } + key.length + value.length
            if (total > UserScript.MAX_VALUES || values[key] == value) return
            values[key] = value
        }
        if (session == null) { dirtyValues.add(script.id); ordinary = null; ordinaryValues.remove(script.id) }
        // A private session sees the ordinary values until it changes its own.
        privateValues.values.forEach { it.remove(script.id) }
        UserScriptApi.valueChanged(session, script.id, key, value, from)
        privateRegistrations.clear()
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, SETTLE_MS)
    }

    private fun flushValues() {
        val all = loaded().values
        dirtyValues.forEach { id -> val copy = all[id].orEmpty().toMap(); worker.execute { writeValues(id, copy) } }
        dirtyValues.clear()
    }

    /** Everything one script stored, for the panel's count. */
    fun valueCount(id: String) = loaded().values[id]?.size ?: 0

    // Changes.

    fun install(pending: Pending, now: Long = System.currentTimeMillis()) {
        val state = loaded()
        val old = pending.replaces ?: state.scripts.firstOrNull { it.id == pending.script.id }
        val script = pending.script.copy(
            enabled = old?.enabled ?: true, allowPrivate = old?.allowPrivate ?: false, allowedHosts = old?.allowedHosts.orEmpty(),
            source = pending.script.source ?: old?.source, installed = old?.installed ?: now, updated = now, checked = now)
        // A renamed script keeps its place and what it stored.
        if (old != null && old.id != script.id) {
            state.scripts.removeAll { it.id == old.id }
            state.values.remove(old.id)?.let { state.values[script.id] = it; val copy = it.toMap(); worker.execute { writeValues(script.id, copy) } }
            val gone = old.id
            worker.execute { file(gone).delete(); valuesFile(gone).delete() }
        }
        val index = state.scripts.indexOfFirst { it.id == script.id }
        if (index >= 0) state.scripts[index] = script else state.scripts.add(script)
        save(script)
        changed()
    }

    fun setEnabled(id: String, on: Boolean) = edit(id) { it.copy(enabled = on) }
    fun setAllowPrivate(id: String, on: Boolean) = edit(id) { it.copy(allowPrivate = on) }
    /** Requests are checked in the app, so pages need not hear about these. */
    fun allowHost(id: String, host: String) = edit(id, quietly = true) { it.copy(allowedHosts = it.allowedHosts + host) }
    fun clearAllowedHosts(id: String) = edit(id, quietly = true) { it.copy(allowedHosts = emptySet()) }

    /** [quietly] saves a change pages do not need to hear about. */
    private fun edit(id: String, quietly: Boolean = false, change: (UserScript) -> UserScript) {
        val scripts = loaded().scripts
        val index = scripts.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        scripts[index] = change(scripts[index])
        save(scripts[index])
        if (!quietly) changed()
    }

    fun remove(id: String) {
        val state = loaded()
        if (!state.scripts.removeAll { it.id == id }) return
        state.values.remove(id)
        dirtyValues.remove(id)
        worker.execute { file(id).delete(); valuesFile(id).delete() }
        changed()
    }

    /** The master switch or something else that decides what runs changed. */
    fun settingsChanged() = changed()

    // Backups.

    fun backup(): List<BrowserBackup.Script> { flushValues(); return loaded().scripts.map { BrowserBackup.Script(it, loaded().values[it.id].orEmpty().toMap()) } }

    /** A restored script replaces the installed one of the same name; its values are merged in, the backup's winning. */
    fun restore(scripts: List<BrowserBackup.Script>) {
        if (scripts.isEmpty()) return
        val state = loaded()
        scripts.forEach { (script, values) ->
            val index = state.scripts.indexOfFirst { it.id == script.id }
            if (index >= 0) state.scripts[index] = script else state.scripts.add(script)
            save(script)
            if (values.isNotEmpty()) {
                state.values.getOrPut(script.id) { LinkedHashMap() }.putAll(values)
                dirtyValues.add(script.id)
            }
        }
        flushValues()
        changed()
    }

    // Reading scripts from the network.

    /** Reads the script at [url] through [proxy]; [done] runs on the main thread. */
    fun fetch(context: Context, proxy: Proxy, url: String, done: (Result<Pending>) -> Unit) {
        ensureLoaded(context)
        worker.execute {
            val client = client(proxy)
            val result = runCatching { prepare(client, download(client, url, UserScript.MAX_CODE), url) }
            main.post { done(result.map { Pending(it, null) }) }
        }
    }

    /** A script typed or edited in the app; its requires are fetched through [proxy]. */
    fun fromCode(context: Context, proxy: Proxy, code: String, replaces: UserScript?, done: (Result<Pending>) -> Unit) {
        ensureLoaded(context)
        worker.execute {
            val result = runCatching {
                if (code.length > UserScript.MAX_CODE) throw Problem(Failure.TOO_LARGE)
                val meta = UserScriptMeta.parse(code) ?: throw Problem(Failure.INVALID)
                // Requires and resources the installed copy already has are not fetched again.
                val known = replaces?.requires.orEmpty()
                val client = client(proxy)
                UserScript(code, meta, meta.requires.associateWith { known[it] ?: download(client, it, UserScript.MAX_REQUIRE, Failure.REQUIRE) },
                    source = replaces?.source, resources = resources(client, meta, replaces))
            }
            main.post { done(result.map { Pending(it, replaces) }) }
        }
    }

    class Problem(val failure: Failure) : Exception(failure.name)

    private fun client(proxy: Proxy): OkHttpClient = BrowserHttp.through(proxy)

    /** Parses [code] from [source] and downloads its requires; runs on the worker. */
    private fun prepare(client: OkHttpClient, code: String, source: String?): UserScript {
        val meta = UserScriptMeta.parse(code) ?: throw Problem(Failure.INVALID)
        val requires = meta.requires.associateWith { download(client, it, UserScript.MAX_REQUIRE, Failure.REQUIRE) }
        return UserScript(code, meta, requires, source = source, resources = resources(client, meta, null))
    }

    /** The script's `@resource` files; those [installed] fetched from the same address are kept. */
    private fun resources(client: OkHttpClient, meta: UserScriptMeta, installed: UserScript?): Map<String, UserScript.Resource> =
        meta.resources.mapValues { (name, url) ->
            installed?.resources?.get(name)?.takeIf { installed.meta.resources[name] == url } ?: fetchResource(client, url)
        }

    private fun fetchResource(client: OkHttpClient, url: String): UserScript.Resource {
        val result = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body.source()
                val buffer = Buffer()
                while (source.read(buffer, 64 * 1024) != -1L) if (buffer.size > UserScript.MAX_RESOURCE) throw Problem(Failure.TOO_LARGE)
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
                UserScript.Resource(mime, java.util.Base64.getEncoder().encodeToString(buffer.readByteArray()))
            }
        }
        result.exceptionOrNull()?.let { if (it is Problem) throw it }
        return result.getOrNull() ?: throw Problem(Failure.REQUIRE)
    }

    private fun download(client: OkHttpClient, url: String, limit: Int, failure: Failure = Failure.NETWORK): String {
        val text = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body.source()
                val buffer = Buffer()
                while (source.read(buffer, 64 * 1024) != -1L) if (buffer.size > limit) throw Problem(Failure.TOO_LARGE)
                buffer.readUtf8()
            }
        }
        text.exceptionOrNull()?.let { if (it is Problem) throw it }
        return text.getOrNull() ?: throw Problem(failure)
    }

    /**
     * Looks for newer versions of the installed scripts through [proxy]: all of them when [force] is
     * set, otherwise those not checked for a week. A newer version replaces the old one, keeping the
     * user's choices and stored values. [done] gets how many were updated and how many failed.
     */
    fun update(context: Context, proxy: Proxy, force: Boolean, done: ((updated: Int, failed: Int) -> Unit)? = null) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val due = loaded().scripts.filter { it.updateCheckUrl != null && (force || now - it.checked >= UPDATE_INTERVAL) }
        if (due.isEmpty()) { done?.invoke(0, 0); return }
        worker.execute {
            val client = client(proxy)
            val found = ArrayList<Pending>()
            var failed = 0
            due.forEach { installed ->
                runCatching {
                    val url = checkNotNull(installed.updateCheckUrl)
                    val text = download(client, url, UserScript.MAX_CODE)
                    val meta = UserScriptMeta.parse(text) ?: throw Problem(Failure.INVALID)
                    if (!UserScriptMeta.isNewer(meta.version, installed.meta.version)) return@runCatching
                    // A .meta.js address holds only the block; the code is at the download address.
                    val code = if (url.endsWith(".meta.js", ignoreCase = true)) download(client, meta.downloadUrl ?: installed.meta.downloadUrl ?: installed.source
                        ?: throw Problem(Failure.INVALID), UserScript.MAX_CODE) else text
                    found.add(Pending(prepare(client, code, installed.source), installed))
                }.onFailure { failed++ }
            }
            main.post {
                found.forEach { install(it) }
                // Scripts that were looked at wait another week, whether or not anything was newer.
                val updated = found.mapNotNull { it.replaces?.id }.toSet()
                due.filter { it.id !in updated }.forEach { looked -> edit(looked.id, quietly = true) { it.copy(checked = now) } }
                done?.invoke(found.size, failed)
            }
        }
    }

    // Files.

    private fun folder() = File(app.filesDir, "userscripts")
    private fun file(id: String) = File(folder(), "$id.json")
    private fun valuesFile(id: String) = File(folder(), "$id.values.json")

    private fun save(script: UserScript) {
        val text = script.toJson().toString()
        val id = script.id
        worker.execute { write(file(id), text) }
    }

    private fun writeValues(id: String, values: Map<String, String>) {
        if (values.isEmpty()) valuesFile(id).delete() else write(valuesFile(id), JSONObject(values).toString())
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".part")
        runCatching { partial.writeText(text); if (!partial.renameTo(file)) partial.delete() }
    }

    private fun read(): State {
        val scripts = folder().listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".values.json") }.orEmpty()
            .mapNotNull { runCatching { UserScript.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedBy { it.installed }.toMutableList()
        val values = HashMap<String, MutableMap<String, String>>()
        scripts.forEach { script ->
            values[script.id] = LinkedHashMap(runCatching { UserScript.valuesFromJson(JSONObject(valuesFile(script.id).readText())) }.getOrDefault(emptyMap()))
        }
        return State(scripts, values)
    }
}
