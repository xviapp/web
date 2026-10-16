package app.xvi.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * File uploads for pages. When a page accepts photos or videos it can also take one with the camera;
 * captures go to a private cache folder shared only with the camera app, and are cleared on the next start.
 */
internal class UploadController(private val host: PageHost) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var capture: Uri? = null
    /** Whether the page asked for several files at once. */
    private var multiple = false
    private val picker = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        deliver(if (result.resultCode == Activity.RESULT_OK) picked(result.data) else null)
    }
    private val camera = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val taken = capture
        capture = null
        // Some camera apps return the file in the result instead of writing to the given location.
        val uri = result.data?.data?.takeIf { it.scheme == "content" } ?: taken
        deliver(if (result.resultCode == Activity.RESULT_OK && uri != null) arrayOf(uri) else null)
    }

    fun choose(target: BrowserTab, callback: ValueCallback<Array<Uri>>, params: FileChooserParams) {
        deliver(null)
        this.callback = callback
        multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        val accepts = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val any = accepts.isEmpty()
        val photo = any || accepts.any { it.startsWith("image/") || it in IMAGE_EXTENSIONS }
        val video = any || accepts.any { it.startsWith("video/") || it in VIDEO_EXTENSIONS }
        if (!photo && !video) { pick(params); return }
        // A page asking for capture wants the camera straight away.
        if (params.isCaptureEnabled && photo != video) { capture(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE); return }
        val options = buildList<Pair<String, () -> Unit>> {
            if (photo) add(host.str(R.string.upload_take_photo) to { capture(MediaStore.ACTION_IMAGE_CAPTURE) })
            if (video) add(host.str(R.string.upload_record_video) to { capture(MediaStore.ACTION_VIDEO_CAPTURE) })
            add(host.str(R.string.upload_choose_file) to { pick(params) })
        }
        host.browserDialog(target).setTitle(R.string.upload_title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setOnCancelListener { deliver(null) }
            .show()
    }

    /** Called when the browser closes, so the page's request is never left waiting. */
    fun release() = deliver(null)

    private fun pick(params: FileChooserParams) {
        try { picker.launch(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE)) }
        catch (_: ActivityNotFoundException) { deliver(null); host.toast(R.string.upload_unavailable) }
    }

    private fun capture(action: String) {
        host.permissions.camera { allowed ->
            if (!allowed) { deliver(null); host.toast(R.string.upload_camera_denied); return@camera }
            val folder = File(host.cacheDir, FOLDER).apply { mkdirs() }
            val file = File(folder, "capture-${System.currentTimeMillis()}" + if (action == MediaStore.ACTION_VIDEO_CAPTURE) ".mp4" else ".jpg")
            val uri = FileProvider.getUriForFile(host, "${host.packageName}.files", file)
            capture = uri
            val intent = Intent(action).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { camera.launch(intent) }
            catch (_: ActivityNotFoundException) { capture = null; deliver(null); host.toast(R.string.upload_no_camera) }
        }
    }

    /**
     * The files a picker returned. With several allowed, as on Telegram, pickers put them in the clip
     * data and often leave the single result empty, which [FileChooserParams.parseResult] does not read.
     */
    private fun picked(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val clip = data.clipData
        val all = if (clip != null && clip.itemCount > 0) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(data.data)
        return all.filter { it.scheme == "content" }.distinct().take(if (multiple) Int.MAX_VALUE else 1).toTypedArray().takeIf { it.isNotEmpty() }
    }

    private fun deliver(uris: Array<Uri>?) {
        val waiting = callback ?: return
        callback = null
        waiting.onReceiveValue(uris)
    }

    companion object {
        private const val FOLDER = "uploads"
        private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp")
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".webm", ".mov", ".3gp", ".mkv")

        /** Captures only need to live until the page has read them. */
        fun clearOld(cacheDir: File) {
            File(cacheDir, FOLDER).listFiles()?.forEach { it.delete() }
        }
    }
}
