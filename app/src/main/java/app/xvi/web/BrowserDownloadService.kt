package app.xvi.web

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Downloads use an explicit proxy and a user-picked destination, never the system downloader. */
class BrowserDownloadService : Service() {
    private class Job(val key: Int, val private: Boolean, val title: String, val destination: Uri, val local: File?) {
        /** Database row; written on the store's thread, so it may arrive after the job starts. */
        @Volatile var record: Long? = null
        @Volatile var call: Call? = null
        @Volatile var cancelled = false
        @Volatile var interrupted = false
        /** The server named a version of the file, so a partial copy can be continued later. */
        @Volatile var resumable = false
    }

    private val executor = Executors.newFixedThreadPool(MAX_PARALLEL)
    private val main = Handler(Looper.getMainLooper())
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val keys = AtomicInteger(FIRST_JOB_ID)
    private lateinit var store: BrowserStore
    private lateinit var notifications: NotificationManager
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        running = true
        store = BrowserStore.get(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(DONE_CHANNEL, getString(R.string.download_done_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent == null) { stopIfIdle(); return START_NOT_STICKY }
        if (intent.action == ACTION_CANCEL) {
            val record = intent.getLongExtra(EXTRA_RECORD, -1L).takeIf { it >= 0 }
            val key = intent.getIntExtra(EXTRA_JOB, -1).takeIf { it >= 0 }
            // Private jobs and fresh ones have no record yet, so a key or record must only ever match its own job.
            jobs.values.filter { when { key != null -> it.key == key; record != null -> it.record == record; else -> true } }.forEach(::cancel)
            if (record != null && jobs.values.none { it.record == record }) store.writeAsync { downloadState(record, DownloadState.CANCELLED) }
            stopIfIdle()
            return START_NOT_STICKY
        }
        val local = intent.getStringExtra(EXTRA_LOCAL)?.let(::File)?.takeIf { it.isFile && it.parentFile?.parentFile == cacheDir }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val destination = intent.getStringExtra(EXTRA_DESTINATION)?.toUri()
        if ((url.isEmpty() && local == null) || destination == null) { stopIfIdle(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.download_default_name)
        val private = intent.getBooleanExtra(EXTRA_PRIVATE, false)
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val retried = intent.getLongExtra(EXTRA_RECORD, -1L).takeIf { it >= 0 }
        val validator = intent.getStringExtra(EXTRA_VALIDATOR).orEmpty()
        val job = Job(keys.getAndIncrement(), private, title, destination, local)
        // Continuing keeps the part already saved until a response says the server cannot continue it.
        job.resumable = validator.isNotEmpty()
        if (!private) {
            job.record = retried
            store.writeAsync {
                if (retried != null) restartDownload(retried, destination.toString(), resume = validator.isNotEmpty())
                else job.record = addDownload(title, destination.toString(), url, referer)
            }
        }
        jobs[job.key] = job
        startForeground(SUMMARY_ID, summary())
        notifications.notify(job.key, progress(job, 0, -1))
        if (local != null) {
            perform(job) { copy(job, local) }
            return START_NOT_STICKY
        }
        val network = BrowserProxyNetwork.get(this)
        network.prepare {
            if (job.cancelled) { finish(job, null); return@prepare }
            val proxy = network.proxy()
            val cookie = intent.getStringExtra(EXTRA_COOKIE).orEmpty()
            val userAgent = intent.getStringExtra(EXTRA_UA).orEmpty()
            perform(job) { transfer(job, url, proxy, cookie, referer, userAgent, validator) }
        }
        return START_NOT_STICKY
    }

    /** Runs [work] on the transfer threads, then finishes [job] on the main thread with its outcome. */
    private fun perform(job: Job, work: () -> Unit) = executor.execute {
        val error = runCatching(work).exceptionOrNull()
        main.post { finish(job, error) }
    }

    /** Writes [job]'s destination: `wt` starts over, `wa` continues a partial file. */
    private fun write(job: Job, mode: String, block: (OutputStream) -> Unit) {
        val output = try { contentResolver.openOutputStream(job.destination, mode) } catch (e: Exception) { throw DownloadFailure.Storage(e) }
        output.use { block(it ?: throw DownloadFailure.Storage(IllegalStateException("destination not writable"))) }
    }

    /** Content staged by the browser (page files, saved pages) is copied without the network. */
    private fun copy(job: Job, local: File) {
        val total = local.length()
        // Reading the staged copy fails only with the device's own storage.
        try { write(job, "wt") { output -> local.inputStream().use { source -> pump(job, source, output, 0, total) } } }
        catch (e: java.io.IOException) { throw e as? DownloadFailure.Storage ?: DownloadFailure.Storage(e) }
    }

    private fun pump(job: Job, source: InputStream, output: OutputStream, start: Long, total: Long) {
        val buffer = ByteArray(32768)
        var received = start
        var lastUpdate = 0L
        while (true) {
            check(!job.cancelled) { "cancelled" }
            val size = source.read(buffer)
            if (size < 0) break
            stored { output.write(buffer, 0, size) }
            received += size
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 1000) {
                lastUpdate = now
                job.record?.let { id -> store.writeAsync { downloadProgress(id, received, total) } }
                if (!job.cancelled) notifications.notify(job.key, progress(job, received, total))
            }
        }
        stored { output.flush() }
        job.record?.let { id -> store.writeAsync { downloadProgress(id, received, received) } }
    }

    /** Runs a write to the destination, so its failure reads as storage rather than network. */
    private inline fun stored(write: () -> Unit) = try { write() } catch (e: java.io.IOException) { throw DownloadFailure.Storage(e) }

    /** Bytes already in [uri] from an earlier attempt; 0 when unknown. */
    private fun existingLength(uri: Uri): Long =
        runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * With [validator] (from an earlier response) the transfer asks only for the missing part. The
     * server answers with the rest, or with the whole file when it changed in the meantime.
     */
    private fun transfer(job: Job, url: String, proxy: java.net.Proxy, cookie: String, referer: String, userAgent: String, validator: String) {
        val client = transferClient.newBuilder().proxySelector(LocalDirect(proxy)).build()
        val offset = if (validator.isNotEmpty()) existingLength(job.destination) else 0L
        var target = url
        var sensitiveHeaders = true
        for (redirect in 0..10) {
            check(!job.cancelled) { "cancelled" }
            require(BrowserInput.isWebUrl(target)) { "unsupported url" }
            val builder = Request.Builder().url(target).header("User-Agent", userAgent)
            if (offset > 0) builder.header("Range", "bytes=$offset-").header("If-Range", validator)
            if (sensitiveHeaders) {
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
                if (referer.isNotBlank()) builder.header("Referer", referer)
            }
            val call = client.newCall(builder.build())
            job.call = call
            if (job.cancelled) call.cancel()
            val response = call.execute()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                val old = response.request.url
                response.close()
                require(next != null && !(old.isHttps && !next.isHttps)) { "invalid redirect" }
                // Credentials never follow a redirect to another origin.
                if (old.host != next.host || old.port != next.port || old.scheme != next.scheme) sensitiveHeaders = false
                target = next.toString()
                continue
            }
            response.use {
                if (!it.isSuccessful) throw DownloadFailure.HttpStatus(it.code)
                val partial = it.code == 206 && offset > 0 && DownloadResume.rangeStart(it.header("Content-Range")) == offset
                check(it.code != 206 || partial) { "unexpected range" }
                val next = DownloadResume.validator(it.header("ETag"), it.header("Last-Modified"))
                job.resumable = next.isNotEmpty()
                job.record?.let { id -> store.writeAsync { downloadValidator(id, next) } }
                val start = if (partial) offset else 0L
                val total = if (partial) DownloadResume.rangeTotal(it.header("Content-Range")) ?: -1L
                    else it.body.contentLength()
                write(job, if (partial) "wa" else "wt") { output -> it.body.byteStream().use { source -> pump(job, source, output, start, total) } }
            }
            return
        }
        error("too many redirects")
    }

    private fun finish(job: Job, error: Throwable?) {
        jobs.remove(job.key)
        val state = when {
            error == null && !job.cancelled -> DownloadState.COMPLETED
            job.interrupted -> DownloadState.INTERRUPTED
            job.cancelled -> DownloadState.CANCELLED
            else -> DownloadState.FAILED
        }
        // The record may still be in the store's queue, so read it there.
        val reason = if (state == DownloadState.FAILED) DownloadFailure.code(error) else ""
        store.writeAsync { job.record?.let { downloadState(it, state, reason) } }
        job.local?.delete()
        // Never leave a partial file looking like a finished download. One the server can continue stays
        // (hidden while pending in shared storage) until the user retries.
        val keep = job.resumable && job.record != null && (state == DownloadState.FAILED || state == DownloadState.INTERRUPTED)
        if (state == DownloadState.COMPLETED) publish(job.destination) else if (!keep) discard(job.destination)
        notifications.cancel(job.key)
        if (state == DownloadState.COMPLETED || state == DownloadState.FAILED) notifications.notify(job.key, result(job, state == DownloadState.COMPLETED, reason))
        stopIfIdle()
    }

    /** Shared-storage files stay hidden from other apps until complete. */
    private fun publish(uri: Uri) {
        if (uri.authority != MediaStore.AUTHORITY || android.os.Build.VERSION.SDK_INT < 29) return
        runCatching { contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
    }

    private fun discard(uri: Uri) {
        val deleted = runCatching {
            when {
                DocumentsContract.isDocumentUri(this, uri) -> DocumentsContract.deleteDocument(contentResolver, uri)
                uri.scheme == "content" -> contentResolver.delete(uri, null, null) > 0
                uri.scheme == "file" -> uri.path?.let { File(it).delete() } == true
                else -> false
            }
        }.getOrDefault(false)
        if (!deleted) runCatching { contentResolver.openOutputStream(uri, "wt")?.close() }
    }

    private fun stopIfIdle() {
        if (jobs.isNotEmpty()) { notifications.notify(SUMMARY_ID, summary()); return }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun cancel(job: Job) {
        job.cancelled = true
        job.call?.cancel()
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun displayName(job: Job) = if (job.private) getString(R.string.download_private_name) else job.title

    private fun summary(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_star_24).setContentTitle(getString(R.string.download_summary_title, getString(R.string.app_name)))
        .setContentText(getString(R.string.download_summary_text, jobs.size.coerceAtLeast(1)))
        .setGroup(GROUP).setGroupSummary(true).setOngoing(true).setSilent(true).setContentIntent(openApp())
        .addAction(0, getString(R.string.downloads_cancel_all), PendingIntent.getService(this, 1, cancelIntent(this), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun progress(job: Job, received: Long, total: Long): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(displayName(job)).setContentText(progressText(this, received, total))
        .setProgress(100, percent(received, total), total <= 0).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
        .setGroup(GROUP).setContentIntent(openApp())
        .addAction(0, getString(R.string.download_cancel), PendingIntent.getService(this, job.key,
            cancelIntent(this).putExtra(EXTRA_JOB, job.key), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun result(job: Job, success: Boolean, reason: String): Notification {
        val builder = NotificationCompat.Builder(this, DONE_CHANNEL)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else R.drawable.ic_star_24)
            .setContentTitle(getString(if (success) R.string.download_done_title else R.string.download_failed_title))
            .setContentText(DownloadFailure.describe(this, reason)?.let { "${displayName(job)} · $it" } ?: displayName(job)).setAutoCancel(true)
        // Only content URIs can be shared with another app; file URIs would crash on send.
        val open = if (success && job.destination.scheme == "content") {
            val type = runCatching { contentResolver.getType(job.destination) }.getOrNull() ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(job.destination, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(this, job.key, Intent.createChooser(view, getString(R.string.download_open)), PendingIntent.FLAG_IMMUTABLE)
        } else null
        builder.setContentIntent(open ?: openApp())
        return builder.build()
    }

    private fun interruptAll() {
        jobs.values.forEach { it.interrupted = true; cancel(it) }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { interruptAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        running = false
        interruptAll()
        val interrupted = jobs.values.toList()
        store.writeAsync { interrupted.forEach { job -> job.record?.let { downloadState(it, DownloadState.INTERRUPTED) } } }
        interrupted.forEach { notifications.cancel(it.key) }
        jobs.clear()
        executor.shutdown(); super.onDestroy()
    }

    companion object {
        @Volatile var running = false; private set
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_URL = "url"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_UA = "ua"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_TITLE = "title"
        /** Staged file in the browser's cache to save instead of downloading. */
        const val EXTRA_LOCAL = "local"
        /** Validator of the partial file to continue. */
        const val EXTRA_VALIDATOR = "validator"
        const val EXTRA_PRIVATE = "private"
        /** Existing record to reuse when retrying. */
        const val EXTRA_RECORD = "record"
        private const val EXTRA_JOB = "job"
        private const val CHANNEL = "downloads"
        private const val DONE_CHANNEL = "downloads_done"
        private const val GROUP = "app.xvi.web.DOWNLOADS"
        private const val SUMMARY_ID = 41
        private const val FIRST_JOB_ID = 1000
        private const val MAX_PARALLEL = 3
        /** The browser's shared pool and threads, with longer waits; redirects are followed by hand. */
        private val transferClient by lazy {
            BrowserHttp.base().newBuilder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
        }

        /** Cancels every download, or with [EXTRA_RECORD] the one for that record. */
        fun cancelIntent(context: Context): Intent = Intent(context, BrowserDownloadService::class.java).setAction(ACTION_CANCEL)

        private fun percent(received: Long, total: Long) = if (total > 0) (received * 100 / total).toInt() else 0

        /** Progress of a running download, as the notification and the downloads panel both show it. */
        fun progressText(context: Context, received: Long, total: Long): String = when {
            total > 0 -> context.getString(R.string.download_status_progress, percent(received, total), size(context, received), size(context, total))
            received > 0 -> context.getString(R.string.download_status_received, size(context, received))
            else -> context.getString(R.string.download_status_waiting)
        }

        fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
    }
}
