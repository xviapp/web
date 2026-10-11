package app.xvi.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * DeepSeek chat API (OpenAI format, https://api-docs.deepseek.com/). The key goes only to [BASE];
 * nothing else about the browser is sent beyond the messages themselves.
 */
internal object DeepSeek {
    const val BASE = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-flash"
    val models = listOf(DEFAULT_MODEL, "deepseek-v4-pro")

    class Message(val role: String, val content: String)

    enum class Problem { KEY, BALANCE, RATE, SERVER, REQUEST, NETWORK }

    class Failure(val problem: Problem, val detail: String? = null) : IOException(detail ?: problem.name)

    /** One server-sent event line: answer text, the end of the stream, or nothing to show. */
    sealed interface Event {
        class Text(val value: String) : Event
        class Finish(val reason: String) : Event
        data object Done : Event
        data object Skip : Event
    }

    /**
     * Request body. Thinking is turned off: page summaries and selections need a quick answer, and
     * thinking mode would spend tokens and seconds before the first visible word.
     */
    fun body(model: String, messages: List<Message>, maxTokens: Int): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
        .put("thinking", JSONObject().put("type", "disabled"))
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
        // Thinking mode would send `reasoning_content`; only `content` is the answer.
        val text = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
        return when {
            text.isNotEmpty() -> Event.Text(text)
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
     * Streams an answer, calling [onText] on the caller's dispatcher with each piece. Returns the
     * finish reason (`stop`, `length`, ...). Cancelling the coroutine cancels the request.
     */
    suspend fun chat(http: OkHttpClient, key: String, model: String, messages: List<Message>, maxTokens: Int = 2048,
                     onText: (String) -> Unit): String {
        val request = Request.Builder().url("$BASE/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(body(model, messages, maxTokens).toRequestBody(JSON))
            .build()
        val call = http.newCall(request)
        val caller = currentCoroutineContext()
        caller.job.invokeOnCompletion { call.cancel() }
        var reason = "stop"
        withContext(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw failure(response.code, response.body.string().take(2000))
                    val source = response.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when (val event = parse(line)) {
                            is Event.Text -> withContext(caller) { onText(event.value) }
                            is Event.Finish -> reason = event.reason
                            Event.Done -> break
                            Event.Skip -> Unit
                        }
                    }
                }
            } catch (failure: Failure) {
                throw failure
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw Failure(Problem.NETWORK, error.message)
            }
        }
        return reason
    }

    /** Checks the key by reading the account balance; returns it as shown, for example `CNY 110.00`. */
    suspend fun balance(http: OkHttpClient, key: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE/user/balance").header("Authorization", "Bearer $key").build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw failure(response.code, text.take(2000))
                balanceText(text)
            }
        } catch (failure: Failure) {
            throw failure
        } catch (error: IOException) {
            throw Failure(Problem.NETWORK, error.message)
        }
    }

    fun balanceText(json: String): String {
        val infos = runCatching { JSONObject(json).optJSONArray("balance_infos") }.getOrNull() ?: return ""
        return (0 until infos.length()).mapNotNull { infos.optJSONObject(it) }
            .joinToString(" · ") { it.optString("currency") + " " + it.optString("total_balance") }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
