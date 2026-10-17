package app.xvi.web

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

/**
 * Questions and answers rendered by the bundled markdown-it and KaTeX (`assets/ai`). The page loads
 * only those assets and gets no JavaScript bridge: its buttons are navigations to [ACTION] that come
 * back through [handlers], and its links open as tabs through [onLink].
 *
 * The view is as tall as its content, so the sheet grows with the answer until it reaches its cap
 * and the page then scrolls inside.
 */
@SuppressLint("SetJavaScriptEnabled") // Runs only the bundled scripts; every other request is refused.
internal class AiTranscript(private val host: BrowserActivity, private val onLink: (String) -> Unit, private val onGone: () -> Unit) {
    private val ui = host.ui
    /** Button presses by message id: the action name and, for a code block, its index. */
    val handlers = mutableMapOf<Int, (action: String, index: Int) -> Unit>()
    private val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(host)).build()
    private val queue = mutableListOf<String>()
    private var loaded = false
    private var destroyed = false
    private var nextId = 0

    val view: WebView = WebView(host).apply {
        setBackgroundColor(ui.palette.surface)
        settings.javaScriptEnabled = true
        settings.blockLocalAccess()
        settings.textZoom = host.prefs.textZoom
        isVerticalScrollBarEnabled = false
        webViewClient = Client()
        loadUrl(PAGE)
    }
    /** Height follows the content; the weight lets the sheet shrink it to fit under its cap. */
    val params = LinearLayout.LayoutParams(-1, ui.dp(MIN_HEIGHT), 1f)

    init {
        val palette = ui.palette
        val colors = JSONObject()
            .put("ink", css(palette.ink)).put("muted", css(palette.muted)).put("accent", css(palette.accent))
            .put("base", css(palette.base)).put("selected", css(palette.selectedSurface)).put("danger", css(palette.danger))
            .put("surface", css(palette.surface))
        val labels = JSONObject().put("thinking", ui.str(R.string.ai_thinking)).put("copyCode", ui.str(R.string.ai_copy))
            .put("reasoning", ui.str(R.string.ai_reasoning))
        call("init(" + JSONObject().put("colors", colors).put("labels", labels) + ")")
    }

    /** Adds a question (shown as typed) or an empty answer; returns its id. */
    fun add(user: Boolean, text: String = ""): Int = nextId++.also { call("add($it, ${quote(if (user) "user" else "assistant")}, ${quote(text)})") }

    fun update(id: Int, markdown: String) = call("update($id, ${quote(markdown)})")
    /** The model's thinking before an answer, as plain text in a folded section. */
    fun reasoning(id: Int, text: String) = call("reasoning($id, ${quote(text)})")
    fun fail(id: Int, message: String) = call("fail($id, ${quote(message)})")
    fun remove(id: Int) { handlers.remove(id); call("remove($id)") }
    fun clear() { handlers.clear(); call("clear()") }

    /** Links under a message; [actions] pairs an action name with its label. */
    fun actions(id: Int, note: String?, actions: List<Pair<String, String>>) {
        val list = JSONArray().apply { actions.forEach { put(JSONArray().put(it.first).put(it.second)) } }
        call("actions($id, ${quote(note.orEmpty())}, $list)")
    }

    /** Text of a message's code block, for its copy button. */
    fun code(id: Int, index: Int, done: (String) -> Unit) {
        if (!loaded || destroyed) return
        view.evaluateJavascript("AiTranscript.code($id, $index)") { raw ->
            (runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? String)?.let(done)
        }
    }

    /** Later calls, such as an answer finishing as its stream is cancelled, are ignored. */
    fun destroy() { destroyed = true; handlers.clear(); queue.clear(); view.destroy() }

    private fun call(script: String) {
        if (destroyed) return
        if (!loaded) { queue.add(script); return }
        view.evaluateJavascript("AiTranscript.$script", ::resize)
    }

    /** Every call answers with the content height in CSS pixels, which match dp at this scale. */
    private fun resize(raw: String?) {
        val height = raw?.toFloatOrNull() ?: return
        if (destroyed) return
        val pixels = maxOf(ui.dp(MIN_HEIGHT), (height * view.resources.displayMetrics.density).roundToInt())
        if (params.height != pixels) { params.height = pixels; view.requestLayout() }
    }

    private fun quote(value: String) = JSONObject.quote(value)
    private fun css(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    @SuppressLint("MissingOnRenderProcessGone") // Implemented below; the check misses Kotlin's non-null override.
    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            assets.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith(ACTION)) {
                val parts = url.removePrefix(ACTION).split('/')
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return true
                handlers[id]?.invoke(parts[0], parts.getOrNull(2)?.toIntOrNull() ?: -1)
            } else if (BrowserInput.isWebUrl(url)) onLink(url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (loaded) return
            loaded = true
            queue.toList().forEach(::call)
            queue.clear()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { onGone(); return true }
    }

    private companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val PAGE = "$ORIGIN/assets/ai/transcript.html"
        const val ACTION = "$ORIGIN/action/"
        const val MIN_HEIGHT = 48
    }
}
