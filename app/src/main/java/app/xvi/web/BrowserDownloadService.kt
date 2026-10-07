package app.xvi.web

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Downloads use an explicit proxy and a user-picked destination, never the system downloader. */
class BrowserDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: BrowserStore
    private var pending = 0
    private var stopped = false
    private val calls = java.util.Collections.synchronizedSet(mutableSetOf<okhttp3.Call>())
    override fun onCreate() {
        super.onCreate()
        running = true
        store = BrowserStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads", "文件下载", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        if (intent.action == "cancel") { cancelDownloads(); return START_NOT_STICKY }
        val url = intent.getStringExtra("url") ?: return START_NOT_STICKY
        val destination = Uri.parse(intent.getStringExtra("destination") ?: return START_NOT_STICKY)
        val title = intent.getStringExtra("title") ?: "下载文件"
        startForeground(41, notification("正在下载 $title"))
        val id = store.addDownload(title, destination.toString())
        pending++
        BrowserProxyNetwork.get(this).prepare {
            if (stopped) return@prepare
            val proxy = BrowserProxyNetwork.get(this).proxy()
            executor.execute {
                try {
                    val client = OkHttpClient.Builder().proxy(proxy).connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
                    val cookie = intent.getStringExtra("cookie").orEmpty()
                    val referer = intent.getStringExtra("referer").orEmpty()
                    val userAgent = intent.getStringExtra("ua").orEmpty()
                    var target = url
                    var sensitiveHeaders = true
                    var completed = false
                    for (redirect in 0..10) {
                        check(!stopped) { "下载已取消" }
                        require(BrowserInput.isWebUrl(target)) { "不支持的下载地址" }
                        val builder = Request.Builder().url(target).header("User-Agent", userAgent)
                        if (sensitiveHeaders) {
                            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
                            if (referer.isNotBlank()) builder.header("Referer", referer)
                        }
                        val call = client.newCall(builder.build())
                        calls.add(call)
                        if (stopped) call.cancel()
                        val response = call.execute()
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            val next = response.header("Location")?.let { response.request.url.resolve(it) }
                            val old = response.request.url
                            response.close()
                            require(next != null && !(old.isHttps && !next.isHttps)) { "下载重定向无效" }
                            if (old.host != next.host || old.port != next.port || old.scheme != next.scheme) sensitiveHeaders = false
                            target = next.toString()
                            continue
                        }
                        response.use {
                            check(it.isSuccessful) { "服务器返回 ${it.code}" }
                            val body = it.body ?: error("文件内容为空")
                            contentResolver.openOutputStream(destination, "wt").use { output ->
                                checkNotNull(output) { "无法写入所选位置" }
                                body.byteStream().use { source ->
                                    val buffer = ByteArray(32768)
                                    var total = 0L
                                    var lastUpdate = 0L
                                    while (true) {
                                        val size = source.read(buffer)
                                        if (size < 0) break
                                        output.write(buffer, 0, size)
                                        total += size
                                        if (System.currentTimeMillis() - lastUpdate > 1000) {
                                            lastUpdate = System.currentTimeMillis()
                                            getSystemService(NotificationManager::class.java).notify(41, notification("$title · ${total / 1024} KB"))
                                        }
                                    }
                                }
                            }
                        }
                        completed = true
                        break
                    }
                    check(completed) { "重定向次数过多" }
                    store.downloadState(id, "已完成")
                } catch (error: Exception) {
                    store.downloadState(id, if (stopped) "已取消" else "失败，请重新下载（${error.javaClass.simpleName}）")
                    // Do not leave a partial file presented as a completed download.
                    runCatching { contentResolver.openOutputStream(destination, "wt")?.close() }
                } finally {
                    android.os.Handler(mainLooper).post {
                        pending--
                        if (pending == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, "downloads")
        .setSmallIcon(R.drawable.ic_browser).setContentTitle("行间 · 下载").setContentText(text).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "取消下载", PendingIntent.getService(this, 1, Intent(this, BrowserDownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun cancelDownloads() {
        stopped = true
        synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }
        store.interruptDownloads("已取消")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) = cancelDownloads()
    override fun onDestroy() {
        stopped = true; running = false
        synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }
        executor.shutdown(); super.onDestroy()
    }
    companion object { @Volatile var running = false; private set }
}
