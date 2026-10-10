package app.xvi.web

import android.app.*
import android.content.ContentValues
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Downloads use an explicit proxy and a user-picked destination, never the system downloader. */
class BrowserDownloadService : Service() {
    private class Job(val key: Int, val private: Boolean, val title: String, val destination: Uri) {
        /** Database row; written on the store's thread, so it may arrive after the job starts. */
        @Volatile var record: Long? = null
        @Volatile var call: Call? = null
        @Volatile var cancelled = false
        @Volatile var interrupted = false
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
            jobs.values.filter { (record == null && key == null) || it.record == record || it.key == key }.forEach(::cancel)
            if (record != null && jobs.values.none { it.record == record }) store.write { downloadState(record, DownloadState.CANCELLED) }
            stopIfIdle()
            return START_NOT_STICKY
        }
        val url = intent.getStringExtra(EXTRA_URL)
        val destination = intent.getStringExtra(EXTRA_DESTINATION)?.toUri()
        if (url == null || destination == null) { stopIfIdle(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.download_default_name)
        val private = intent.getBooleanExtra(EXTRA_PRIVATE, false)
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val retried = intent.getLongExtra(EXTRA_RECORD, -1L).takeIf { it >= 0 }
        val job = Job(keys.getAndIncrement(), private, title, destination)
        if (!private) {
            job.record = retried
            store.write {
                if (retried != null) restartDownload(retried, destination.toString())
                else job.record = addDownload(title, destination.toString(), url, referer)
            }
        }
        jobs[job.key] = job
        startForeground(SUMMARY_ID, summary())
        notifications.notify(job.key, progress(job, 0, -1))
        BrowserProxyNetwork.get(this).prepare {
            if (job.cancelled) { finish(job, null); return@prepare }
            val proxy = BrowserProxyNetwork.get(this).proxy()
            executor.execute {
                val error = runCatching {
                    transfer(job, url, proxy, intent.getStringExtra(EXTRA_COOKIE).orEmpty(), referer, intent.getStringExtra(EXTRA_UA).orEmpty())
                }.exceptionOrNull()
                main.post { finish(job, error) }
            }
        }
        return START_NOT_STICKY
    }

    private fun transfer(job: Job, url: String, proxy: java.net.Proxy, cookie: String, referer: String, userAgent: String) {
        // Derived clients share the base connection pool and threads.
        val client = baseClient.newBuilder().proxy(proxy).build()
        var target = url
        var sensitiveHeaders = true
        for (redirect in 0..10) {
            check(!job.cancelled) { "cancelled" }
            require(BrowserInput.isWebUrl(target)) { "unsupported url" }
            val builder = Request.Builder().url(target).header("User-Agent", userAgent)
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
                check(it.isSuccessful) { "HTTP ${it.code}" }
                val total = it.body.contentLength()
                contentResolver.openOutputStream(job.destination, "wt").use { output ->
                    checkNotNull(output) { "destination not writable" }
                    it.body.byteStream().use { source ->
                        val buffer = ByteArray(32768)
                        var received = 0L
                        var lastUpdate = 0L
                        while (true) {
                            val size = source.read(buffer)
                            if (size < 0) break
                            output.write(buffer, 0, size)
                            received += size
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 1000) {
                                lastUpdate = now
                                job.record?.let { id -> store.write { downloadProgress(id, received, total) } }
                                if (!job.cancelled) notifications.notify(job.key, progress(job, received, total))
                            }
                        }
                        job.record?.let { id -> store.write { downloadProgress(id, received, received) } }
                    }
                }
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
        store.write { job.record?.let { downloadState(it, state) } }
        // Never leave a partial file looking like a finished download.
        if (state != DownloadState.COMPLETED) discard(job.destination) else publish(job.destination)
        notifications.cancel(job.key)
        if (state == DownloadState.COMPLETED || state == DownloadState.FAILED) notifications.notify(job.key, result(job, state == DownloadState.COMPLETED))
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
                uri.scheme == "file" -> uri.path?.let { java.io.File(it).delete() } == true
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
        .addAction(0, getString(R.string.downloads_cancel_all), PendingIntent.getService(this, 1,
            Intent(this, BrowserDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun progress(job: Job, received: Long, total: Long): Notification {
        val percent = if (total > 0) (received * 100 / total).toInt() else 0
        val text = when {
            total > 0 -> getString(R.string.download_status_progress, percent, size(received), size(total))
            received > 0 -> getString(R.string.download_status_received, size(received))
            else -> getString(R.string.download_status_waiting)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(displayName(job)).setContentText(text)
            .setProgress(100, percent, total <= 0).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setGroup(GROUP).setContentIntent(openApp())
            .addAction(0, getString(R.string.download_cancel), PendingIntent.getService(this, job.key,
                Intent(this, BrowserDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB, job.key), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun result(job: Job, success: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, DONE_CHANNEL)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else R.drawable.ic_star_24)
            .setContentTitle(getString(if (success) R.string.download_done_title else R.string.download_failed_title))
            .setContentText(displayName(job)).setAutoCancel(true)
        // Only content URIs can be shared with another app; file URIs would crash on send.
        val open = if (success && job.destination.scheme == "content") Intent(Intent.ACTION_VIEW).setDataAndType(job.destination, runCatching { contentResolver.getType(job.destination) }.getOrNull() ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) else null
        builder.setContentIntent(open?.let { PendingIntent.getActivity(this, job.key, Intent.createChooser(it, getString(R.string.download_open)), PendingIntent.FLAG_IMMUTABLE) } ?: openApp())
        return builder.build()
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    private fun interruptAll() {
        jobs.values.forEach { it.interrupted = true; cancel(it) }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { interruptAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        running = false
        interruptAll()
        val interrupted = jobs.values.toList()
        store.write { interrupted.forEach { job -> job.record?.let { downloadState(it, DownloadState.INTERRUPTED) } } }
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
        private val baseClient by lazy {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
        }
    }
}
