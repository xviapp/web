package app.xvi.web

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Starts downloads in [BrowserDownloadService]. Files go to Downloads/<app name> (images to
 * Pictures/<app name>) unless the user asked to pick a location every time, or the system is too old
 * to add files to shared storage without a permission.
 */
internal class DownloadController(private val host: BrowserActivity) {
    private var pending: Intent? = null
    private val picker = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val request = pending
        pending = null
        if (result.resultCode != android.app.Activity.RESULT_OK || uri == null) return@registerForActivityResult
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
        if (pending?.getBooleanExtra(BrowserDownloadService.EXTRA_PRIVATE, false) == true) pending = null
    }

    fun download(source: BrowserTab, url: String, ua: String?, disposition: String?, mime: String?) {
        if (source !in host.tabs) return
        if (!BrowserInput.isWebUrl(url)) { host.toast(R.string.download_unsupported); return }
        val filename = safeFileName(url, disposition, mime)
        save(request(url, filename, source.isPrivate, ua, source.session.cookies().getCookie(url), source.url), filename, mime, Environment.DIRECTORY_DOWNLOADS)
    }

    fun saveImage(source: BrowserTab, url: String) {
        if (source !in host.tabs) return
        val filename = safeFileName(url, null, null)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
        val ua = source.view?.settings?.userAgentString
        val request = request(url, filename, source.isPrivate, ua, source.session.cookies().getCookie(url), source.url)
        if (mime?.startsWith("image/") == true) save(request, filename, mime, Environment.DIRECTORY_PICTURES, alwaysShared = true)
        else save(request, filename, mime, Environment.DIRECTORY_DOWNLOADS)
    }

    /** Reuses the original file when it still exists; otherwise saves anew. */
    fun retry(entry: DownloadEntry) {
        if (!BrowserInput.isWebUrl(entry.source)) { host.toast(R.string.download_retry_unavailable); return }
        val request = request(entry.source, entry.title, false, null, host.normalSession.cookies().getCookie(entry.source), entry.referer)
            .putExtra(BrowserDownloadService.EXTRA_RECORD, entry.id)
        val existing = entry.uri.toUri()
        val writable = runCatching { host.contentResolver.openFileDescriptor(existing, "w")?.close() != null }.getOrDefault(false)
        if (writable) {
            start(request, existing)
            host.toast(R.string.download_restarted)
        } else save(request, entry.title, null, Environment.DIRECTORY_DOWNLOADS)
    }

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
        } catch (_: ActivityNotFoundException) { pending = null; host.toast(R.string.download_no_picker) }
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

    private fun safeFileName(url: String, disposition: String?, mime: String?) =
        URLUtil.guessFileName(url, disposition, mime).replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(180)
}
