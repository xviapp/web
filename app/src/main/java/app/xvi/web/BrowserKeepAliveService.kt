package app.xvi.web

import android.app.*
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.embedded.EmbeddedProxy
import com.v2ray.ang.service.CoreProxyOnlyService
import java.util.function.Consumer

/** One user-visible browser session; the embedded proxy has no separate notification. */
class BrowserKeepAliveService : Service() {
    private var bound = false
    private var coreRunning = false
    private val coreConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    private val observer = Consumer<ProxyState> {
        coreRunning = it.text("phase") == "running"
        syncCoreBinding()
    }

    override fun onCreate() {
        super.onCreate()
        BrowserProxyNetwork.get(this).observe(observer)
        BrowserProxyNetwork.get(this).status(observer)
    }

    private fun syncCoreBinding() {
        if (running && coreRunning && EmbeddedProxy.isEnabled()) {
            if (!bound) bound = bindService(Intent(this, CoreProxyOnlyService::class.java),
                coreConnection, Context.BIND_AUTO_CREATE)
        } else if (bound) {
            unbindService(coreConnection)
            bound = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "浏览器后台运行", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, BrowserKeepAliveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(42, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_home_24).setContentTitle("星野正在后台运行")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "停止后台运行", stop).build())
        running = true
        syncCoreBinding()
        BrowserProxyNetwork.get(this).setBackgroundActive(true)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    override fun onDestroy() {
        running = false
        syncCoreBinding()
        BrowserProxyNetwork.get(this).unobserve(observer)
        BrowserProxyNetwork.get(this).setBackgroundActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set
        private const val CHANNEL = "browser_background"
        private const val STOP = "app.xvi.web.STOP_BACKGROUND"
    }
}
