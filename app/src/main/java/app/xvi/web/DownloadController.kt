package app.xvi.web

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Starts downloads in [BrowserDownloadService]. Files go to Downloads/<app name> (images to
 * Pictures/<app name>) unless the user asked to pick a location every time, or the system is too old
 * to add files to shared storage without a permission.
 *
 * Content that exists only inside the page (`data:` and `blob:` addresses, saved pages) is first copied
 * to a private staging file, then handed to the service like any other download.
 */
internal class DownloadController(private val host: BrowserActivity) {
    private var pending: Intent? = null
    private val picker = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val request = pending
        pending = null
        if (result.resultCode != android.app.Activity.RESULT_OK || uri == null) { request?.let(::dropStaged); return@registerForActivityResult }
        runCatching { host.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        if (request == null) {
            // The private session ended while the picker was open; remove the empty file it created.
            runCatching { DocumentsContract.deleteDocument(host.contentResolver, uri) }
            host.toast(R.string.download_private_ended)
            return@registerForActivityResult
        }
        start(request, uri)
        host.toast(if (request.getBooleanExtra(BrowserDownloadService.EXTRA_PRIVATE, false)) R.string.download_started_private else R.string.download_started)
    }

    /** A private session ended: a download still waiting for its location must not start. */
    fun dropPrivatePending() {
        val request = pending ?: return
        if (request.getBooleanExtra(BrowserDownloadService.EXTRA_PRIVATE, false)) { pending = null; dropStaged(request) }
    }

    fun download(source: BrowserTab, url: String, ua: String?, disposition: String?, mime: String?) {
        if (source !in host.tabs) return
        when {
            url.startsWith("data:", ignoreCase = true) -> { saveData(source, url, disposition, mime); return }
            url.startsWith("blob:", ignoreCase = true) -> { saveBlob(source, url, disposition, mime); return }
            !BrowserInput.isWebUrl(url) -> { host.toast(R.string.download_unsupported); return }
        }
        val filename = safeFileName(url, disposition, mime)
        save(request(url, filename, source.isPrivate, ua, source.session.cookies().getCookie(url), source.url), filename, mime, Environment.DIRECTORY_DOWNLOADS)
    }

    fun saveImage(source: BrowserTab, url: String) {
        if (source !in host.tabs) return
        // Images made by the page carry no file name; name them by time.
        val made = "image-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        if (url.startsWith("data:", ignoreCase = true)) { saveData(source, url, null, null, made); return }
        if (url.startsWith("blob:", ignoreCase = true)) { saveBlob(source, url, null, null, made); return }
        val filename = safeFileName(url, null, null)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
        val ua = source.view?.settings?.userAgentString
        val request = request(url, filename, source.isPrivate, ua, source.session.cookies().getCookie(url), source.url)
        if (mime?.startsWith("image/") == true) save(request, filename, mime, Environment.DIRECTORY_PICTURES, alwaysShared = true)
        else save(request, filename, mime, Environment.DIRECTORY_DOWNLOADS)
    }

    /**
     * Reuses the original file when it still exists, continuing from where it stopped when the server
     * identified the file; otherwise saves anew.
     */
    fun retry(entry: DownloadEntry) {
        if (!BrowserInput.isWebUrl(entry.source)) { host.toast(R.string.download_retry_unavailable); return }
        val request = request(entry.source, entry.title, false, null, host.normalSession.cookies().getCookie(entry.source), entry.referer)
            .putExtra(BrowserDownloadService.EXTRA_RECORD, entry.id)
        val existing = entry.uri.toUri()
        val writable = runCatching { host.contentResolver.openFileDescriptor(existing, "rw")?.close() != null }.getOrDefault(false)
        if (writable) {
            val resume = entry.validator.isNotEmpty() && entry.received > 0
            if (resume) request.putExtra(BrowserDownloadService.EXTRA_VALIDATOR, entry.validator)
            start(request, existing)
            host.toast(if (resume) R.string.download_resumed else R.string.download_restarted)
        } else save(request, entry.title, null, Environment.DIRECTORY_DOWNLOADS)
    }

    /** Saves the page with its images and styles as one web archive (.mht). */
    fun savePage(source: BrowserTab) {
        val web = source.view ?: return
        val name = cleanName(source.title.ifBlank { BrowserInput.display(source.url) }.replace(Regex("[:*?\"<>|]"), "_").take(120)) + ".mht"
        val file = stagingFile()
        web.saveWebArchive(file.path, false) { saved ->
            if (saved == null || source !in host.tabs) { file.delete(); host.toast(R.string.page_save_failed); return@saveWebArchive }
            saveStaged(source, file, name, "multipart/related")
        }
    }

    /** `data:` addresses carry the file itself; it is decoded here, within the size limit. */
    private fun saveData(source: BrowserTab, url: String, disposition: String?, mime: String?, baseName: String = "download") {
        host.lifecycleScope.launch {
            val linked = source.view?.takeIf { url.length < 1_048_576 }
                ?.eval("(() => { const n = window.__xviDownloadNames; return n ? n.get(${JSONObject.quote(url)}) || '' : ''; })()")
                ?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }?.takeIf { it.isNotBlank() }
            val file = stagingFile()
            val type = withContext(Dispatchers.IO) { runCatching { DataUrl.write(url, file, MAX_PAGE_FILE) }.getOrNull() }
            if (type == null) { file.delete(); host.toast(R.string.download_page_failed); return@launch }
            if (source !in host.tabs) { file.delete(); return@launch }
            val kind = mime?.takeIf { it.contains('/') } ?: type
            saveStaged(source, file, linked?.let(::cleanName) ?: madeName(baseName, disposition, kind), kind)
        }
    }

    /**
     * `blob:` addresses only exist inside the page that made them, so the page reads the file and the
     * browser copies it out in pieces. The page learns nothing it did not already have.
     */
    private fun saveBlob(source: BrowserTab, url: String, disposition: String?, mime: String?, baseName: String = "download") {
        val web = source.view ?: return
        val key = "__xviDownload_" + UUID.randomUUID().toString().replace("-", "")
        host.toast(R.string.download_page_preparing)
        host.lifecycleScope.launch {
            val file = stagingFile()
            val saved = runCatching { readBlob(web, key, url, file) }.getOrNull()
            runCatching { web.evaluateJavascript("delete window['$key']", null) }
            if (saved == null || source !in host.tabs || source.view !== web) {
                file.delete()
                if (saved?.tooLarge == true) host.toast(R.string.download_page_too_large) else host.toast(R.string.download_page_failed)
                return@launch
            }
            if (saved.tooLarge) { file.delete(); host.toast(R.string.download_page_too_large); return@launch }
            val kind = mime?.takeIf { it.contains('/') && it != "application/octet-stream" } ?: saved.type.takeIf { it.contains('/') } ?: mime
            val name = saved.name.takeIf { it.isNotBlank() }?.let(::cleanName) ?: madeName(baseName, disposition, kind)
            saveStaged(source, file, name, kind)
        }
    }

    private class Blob(val type: String, val name: String, val tooLarge: Boolean = false)

    private suspend fun readBlob(web: WebView, key: String, url: String, file: File): Blob? {
        web.eval(BLOB_START.replace("__BLOB_KEY__", key).replace("__BLOB_LIMIT__", MAX_PAGE_FILE.toString()).replace("__BLOB_URL__", JSONObject.quote(url))) ?: return null
        // Wait for the page to finish reading the file.
        val status = withTimeoutOrNull<JSONObject?>(60_000) {
            while (true) {
                val raw = web.eval("(() => { const v = window['$key']; return v ? JSON.stringify({ state: v.state, type: v.type || '', name: v.name || '', length: v.data ? v.data.length : 0 }) : null; })()")
                val text = raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() } ?: return@withTimeoutOrNull null
                val json = JSONObject(text)
                when (json.optString("state")) {
                    "done" -> return@withTimeoutOrNull json
                    "large" -> return@withTimeoutOrNull json
                    "error" -> return@withTimeoutOrNull null
                }
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: return null
        if (status.optString("state") == "large") return Blob("", "", tooLarge = true)
        val length = status.optInt("length")
        withContext(Dispatchers.IO) { file.outputStream().close() }
        var offset = 0
        while (offset < length) {
            val raw = web.eval("(() => { const v = window['$key']; return v && v.data ? v.data.substr($offset, $CHUNK) : null; })()") ?: return null
            val chunk = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
            if (chunk.isEmpty()) return null
            withContext(Dispatchers.IO) { java.io.FileOutputStream(file, true).use { it.write(Base64.decode(chunk, Base64.DEFAULT)) } }
            offset += chunk.length
        }
        return Blob(status.optString("type"), status.optString("name"))
    }

    /** Result of a script, or null if the page went away before answering. */
    private suspend fun WebView.eval(script: String): String? = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            try { evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it?.takeIf { value -> value != "null" }) } }
            catch (_: RuntimeException) { if (continuation.isActive) continuation.resume(null) }
        }
    }

    private fun saveStaged(source: BrowserTab, file: File, filename: String, mime: String?) {
        val request = request("", filename, source.isPrivate, null, null, null).putExtra(BrowserDownloadService.EXTRA_LOCAL, file.path)
        val image = mime?.startsWith("image/") == true
        save(request, filename, mime, if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS, alwaysShared = image)
    }

    private fun dropStaged(request: Intent) {
        request.getStringExtra(BrowserDownloadService.EXTRA_LOCAL)?.let { File(it).delete() }
    }

    private fun stagingFile() = File(File(host.cacheDir, STAGING).apply { mkdirs() }, UUID.randomUUID().toString())

    private fun save(request: Intent, filename: String, mime: String?, directory: String, alwaysShared: Boolean = false) {
        val shared = Build.VERSION.SDK_INT >= 29 && (alwaysShared || !host.prefs.askDownloadLocation)
        val folder = "$directory/${host.str(R.string.app_name)}"
        val destination = if (shared) sharedFile(filename, mime, directory, folder) else null
        if (destination == null) { pick(request, filename, mime); return }
        start(request, destination)
        host.toast(host.str(if (request.getBooleanExtra(BrowserDownloadService.EXTRA_PRIVATE, false)) R.string.download_saving_private else R.string.download_saving, folder))
    }

    /** New pending entry in shared storage; the service publishes it once the transfer completes. */
    private fun sharedFile(filename: String, mime: String?, directory: String, folder: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = if (directory == Environment.DIRECTORY_PICTURES) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return runCatching {
            host.contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                mime?.takeIf { it.contains('/') }?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
        }.getOrNull()
    }

    private fun pick(request: Intent, title: String, mime: String?) {
        pending = request
        try {
            picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime?.takeIf { it.contains('/') } ?: "application/octet-stream").putExtra(Intent.EXTRA_TITLE, title))
        } catch (_: ActivityNotFoundException) { pending = null; dropStaged(request); host.toast(R.string.download_no_picker) }
    }

    private fun start(request: Intent, destination: Uri) {
        host.permissions.requestNotifications()
        ContextCompat.startForegroundService(host, request.putExtra(BrowserDownloadService.EXTRA_DESTINATION, destination.toString()))
    }

    private fun request(url: String, title: String, private: Boolean, ua: String?, cookie: String?, referer: String?): Intent =
        Intent(host, BrowserDownloadService::class.java)
            .putExtra(BrowserDownloadService.EXTRA_URL, url)
            .putExtra(BrowserDownloadService.EXTRA_UA, ua ?: WebSettings.getDefaultUserAgent(host))
            .putExtra(BrowserDownloadService.EXTRA_COOKIE, cookie)
            .putExtra(BrowserDownloadService.EXTRA_REFERER, referer)
            .putExtra(BrowserDownloadService.EXTRA_TITLE, title)
            .putExtra(BrowserDownloadService.EXTRA_PRIVATE, private)

    private fun safeFileName(url: String, disposition: String?, mime: String?) = cleanName(URLUtil.guessFileName(url, disposition, mime))

    /** Name for content without an address: [baseName] plus the extension of its type, unless the response named it. */
    private fun madeName(baseName: String, disposition: String?, mime: String?): String {
        if (!disposition.isNullOrBlank()) return safeFileName("", disposition, mime)
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.substringBefore(';').trim()) }
        return cleanName(if (extension != null) "$baseName.$extension" else baseName)
    }

    private fun cleanName(name: String) = name.replace(Regex("[/\\\\\\p{Cntrl}]"), "_").trim().take(180).ifBlank { "download" }

    companion object {
        private const val STAGING = "downloads"
        /** Largest file copied out of a page; it passes through the page's memory first. */
        private const val MAX_PAGE_FILE = 200L * 1024 * 1024
        /** Base64 characters per read; a multiple of 4 so each piece decodes on its own. */
        private const val CHUNK = 2 * 1024 * 1024

        /** Staged copies only matter until the service has saved them. */
        fun clearStaging(cacheDir: File) {
            if (!BrowserDownloadService.running) File(cacheDir, STAGING).listFiles()?.forEach { it.delete() }
        }

        private const val BLOB_START = "(() => { const key = '__BLOB_KEY__'; window[key] = { state: 'pending' };" +
            " const names = window.__xviDownloadNames;" +
            " fetch(__BLOB_URL__).then(r => r.blob()).then(b => {" +
            "  if (b.size > __BLOB_LIMIT__) { window[key] = { state: 'large' }; return; }" +
            "  const reader = new FileReader();" +
            "  reader.onload = () => { const s = String(reader.result); window[key] = { state: 'done', type: b.type, name: (names && names.get(__BLOB_URL__)) || '', data: s.slice(s.indexOf(',') + 1) }; };" +
            "  reader.onerror = () => { window[key] = { state: 'error' }; };" +
            "  reader.readAsDataURL(b);" +
            " }).catch(() => { window[key] = { state: 'error' }; }); return true; })()"
    }
}
