package app.xvi.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Chat API in the OpenAI format: DeepSeek by default (https://api-docs.deepseek.com/), or another
 * compatible service the user names. The key goes only to that address; nothing else about the
 * browser is sent beyond the messages themselves.
 */
internal object DeepSeek {
    const val BASE = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-flash"
    val models = listOf(DEFAULT_MODEL, "deepseek-v4-pro")

    fun isDefault(base: String) = base == BASE

    /**
     * [input] as an API address (`https://host/v1`, without `/chat/completions`), or null when it is
     * not one. Plain HTTP is accepted only for local addresses, such as Ollama on the home network,
     * since the key and page text would otherwise cross the internet unencrypted.
     */
    fun normalizeBase(input: String): String? {
        val trimmed = input.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (url.query != null || url.fragment != null || url.username.isNotEmpty()) return null
        if (!url.isHttps && !LocalAddress.isLocal(url.host)) return null
        return trimmed
    }

    /** A picture sent as [url]; [preview] is a thumbnail for the transcript, [source] the page address it came from. */
    data class Image(val url: String, val name: String, val preview: String? = null, val source: String? = null)

    class Message(val role: String, val content: String, val images: List<Image> = emptyList()) {
        companion object {
            const val SYSTEM = "system"
            const val USER = "user"
            const val ASSISTANT = "assistant"
        }
    }

    /** How hard the model thinks before answering, as DeepSeek's `reasoning_effort` names it; [OFF] skips thinking. */
    enum class Effort(val key: String, val maxTokens: Int) {
        OFF("off", 2048), LOW("low", 8192), HIGH("high", 8192), MAX("max", 16384);
        companion object { fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: OFF }
    }

    enum class Problem { KEY, BALANCE, RATE, SERVER, REQUEST, NETWORK }

    class Failure(val problem: Problem, val detail: String? = null) : IOException(detail ?: problem.name)

    /** One server-sent event line: answer text, thinking text, the end of the stream, or nothing to show. */
    sealed interface Event {
        class Text(val value: String) : Event
        class Reasoning(val value: String) : Event
        class Finish(val reason: String) : Event
        data object Done : Event
        data object Skip : Event
    }

    /**
     * Request body. On DeepSeek thinking is off unless the user turned it on: page summaries and
     * selections need a quick answer, and thinking mode spends tokens and seconds before the first
     * visible word. Other services do not know that field, and some reject what they do not know.
     */
    fun body(model: String, messages: List<Message>, maxTokens: Int, deepSeek: Boolean = true, effort: Effort = Effort.OFF): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray().apply { messages.forEach { message ->
            val content: Any = if (message.images.isEmpty()) message.content else JSONArray().apply {
                if (message.content.isNotEmpty()) put(JSONObject().put("type", "text").put("text", message.content))
                message.images.forEach { image ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image.url)))
                }
            }
            put(JSONObject().put("role", message.role).put("content", content))
        } })
        .apply {
            if (!deepSeek) return@apply
            put("thinking", JSONObject().put("type", if (effort == Effort.OFF) "disabled" else "enabled"))
            if (effort != Effort.OFF) put("reasoning_effort", effort.key)
        }
        .put("max_tokens", maxTokens)
        .put("stream", true)
        .toString()

    /** Blank lines and `: keep-alive` comments arrive while the server waits to start; they carry nothing. */
    fun parse(line: String): Event {
        if (!line.startsWith("data:")) return Event.Skip
        val data = line.substring(5).trim()
        if (data == "[DONE]") return Event.Done
        val choice = runCatching { JSONObject(data).optJSONArray("choices")?.optJSONObject(0) }.getOrNull() ?: return Event.Skip
        val reason = if (choice.isNull("finish_reason")) null else choice.optString("finish_reason").ifEmpty { null }
        val delta = choice.optJSONObject("delta")
        // Thinking mode sends `reasoning_content` first; only `content` is the answer.
        val text = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
        val reasoning = if (delta == null || delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content")
        return when {
            text.isNotEmpty() -> Event.Text(text)
            reasoning.isNotEmpty() -> Event.Reasoning(reasoning)
            reason != null -> Event.Finish(reason)
            else -> Event.Skip
        }
    }

    /** HTTP status to what the user can do about it; see the API's error code table. */
    fun failure(code: Int, body: String?): Failure {
        val message = runCatching { JSONObject(body.orEmpty()).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
        return Failure(when (code) {
            401, 403 -> Problem.KEY
            402 -> Problem.BALANCE
            429 -> Problem.RATE
            in 500..599 -> Problem.SERVER
            else -> Problem.REQUEST
        }, message)
    }

    /**
     * Streams an answer, calling [onText] on the caller's dispatcher with each piece, and [onReasoning]
     * with each piece of thinking that comes before it. Returns the finish reason (`stop`, `length`,
     * ...). Cancelling the coroutine cancels the request.
     */
    suspend fun chat(base: String, key: String, model: String, messages: List<Message>, effort: Effort = Effort.OFF,
                     onReasoning: (String) -> Unit = {}, onText: (String) -> Unit): String {
        val caller = currentCoroutineContext()
        var reason = "stop"
        // Thinking counts against the limit too, so it needs room on top of the answer.
        val deepSeek = isDefault(base)
        val maxTokens = (if (deepSeek) effort else Effort.OFF).maxTokens
        send(post(base, key, "/chat/completions", body(model, messages, maxTokens, deepSeek, effort))) { response ->
            val source = response.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when (val event = parse(line)) {
                    is Event.Text -> withContext(caller) { onText(event.value) }
                    is Event.Reasoning -> withContext(caller) { onReasoning(event.value) }
                    is Event.Finish -> reason = event.reason
                    Event.Done -> break
                    Event.Skip -> Unit
                }
            }
        }
        return reason
    }

    /** Checks the key by reading the account balance; returns it as shown, for example `CNY 110.00`. */
    suspend fun balance(key: String): String =
        send(authorized(BASE, key, "/user/balance").build()) { response -> balanceText(response.body.string()) }

    /** Checks the key on another service by listing its models, which every compatible service offers; returns how many. */
    suspend fun modelCount(base: String, key: String): Int =
        send(authorized(base, key, "/models").build()) { response ->
            runCatching { JSONObject(response.body.string()).optJSONArray("data")?.length() }.getOrNull() ?: 0
        }

    /** A key is printable ASCII; anything else, such as text pasted along with it, cannot go in a header. */
    fun isValidKey(key: String) = key.isNotEmpty() && key.all { it in '!'..'~' }

    private fun authorized(base: String, key: String, path: String): Request.Builder {
        if (!isValidKey(key)) throw Failure(Problem.KEY)
        return Request.Builder().url(base + path).header("Authorization", "Bearer $key")
    }

    private fun post(base: String, key: String, path: String, json: String) =
        authorized(base, key, path).header("Accept", "text/event-stream").post(json.toRequestBody(JSON)).build()

    /**
     * Runs [request] off the main thread and hands a successful response to [read]. Failures become
     * [Failure]. A blocking read does not notice coroutine cancellation by itself, so a watcher
     * cancels the call the moment the caller is cancelled, even while the server sends keep-alives.
     *
     * The service is reached directly, never through the in-app proxy: DeepSeek is a mainland service,
     * a proxy node would only add latency, and page text should not pass through a third party.
     */
    private suspend fun <T> send(request: Request, read: suspend (Response) -> T): T = coroutineScope {
        val call = BrowserHttp.base().newCall(request)
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw failure(response.code, response.body.string().take(2000))
                    read(response)
                }
            }
        } catch (error: IOException) {
            ensureActive()
            throw error as? Failure ?: Failure(Problem.NETWORK, error.message)
        } finally {
            watcher.cancel()
        }
    }

    fun balanceText(json: String): String {
        val infos = runCatching { JSONObject(json).optJSONArray("balance_infos") }.getOrNull() ?: return ""
        return (0 until infos.length()).mapNotNull { infos.optJSONObject(it) }
            .joinToString(" · ") { it.optString("currency") + " " + it.optString("total_balance") }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
