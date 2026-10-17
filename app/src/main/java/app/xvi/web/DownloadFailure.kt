package app.xvi.web

import android.content.Context
import java.io.IOException

/**
 * Why a download failed, kept with its record as a short stable code: `network`, `storage`,
 * `http:<status>`, or empty when the cause is not one the user can act on.
 */
internal object DownloadFailure {
    /** The server answered with a status that is not a file. */
    class HttpStatus(val code: Int) : IOException("HTTP $code")

    /** The destination could not be opened or written, such as a full disk or a removed folder. */
    class Storage(cause: Throwable) : IOException(cause.message, cause)

    enum class Reason { NETWORK, STORAGE, DENIED, MISSING, SERVER, HTTP, UNKNOWN }

    fun code(error: Throwable?): String = when (error) {
        is HttpStatus -> "http:${error.code}"
        is Storage -> "storage"
        is IOException -> "network"
        else -> ""
    }

    /** The reason and, for an HTTP answer, its status. */
    fun parse(code: String): Pair<Reason, Int?> {
        val status = code.removePrefix("http:").takeIf { it != code }?.toIntOrNull()
        val reason = when {
            code == "network" -> Reason.NETWORK
            code == "storage" -> Reason.STORAGE
            status == null -> Reason.UNKNOWN
            status == 401 || status == 403 -> Reason.DENIED
            status == 404 || status == 410 -> Reason.MISSING
            status >= 500 -> Reason.SERVER
            else -> Reason.HTTP
        }
        return reason to status
    }

    /** Text for [code], or null when there is nothing more to say than that it failed. */
    fun describe(context: Context, code: String): String? {
        val (reason, status) = parse(code)
        return when (reason) {
            Reason.NETWORK -> context.getString(R.string.download_error_network)
            Reason.STORAGE -> context.getString(R.string.download_error_storage)
            Reason.DENIED -> context.getString(R.string.download_error_denied, status)
            Reason.MISSING -> context.getString(R.string.download_error_missing, status)
            Reason.SERVER -> context.getString(R.string.download_error_server, status)
            Reason.HTTP -> context.getString(R.string.download_error_http, status)
            Reason.UNKNOWN -> null
        }
    }
}
