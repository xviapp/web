package app.xvi.web

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** One user-visible browser session; the embedded proxy has no separate notification. */
class BrowserKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.keepalive_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, BrowserKeepAliveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(42, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_star_24).setContentTitle(getString(R.string.keepalive_title, getString(R.string.app_name)))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.keepalive_stop), stop).build())
        running = true
        BrowserProxyNetwork.get(this).setBackgroundActive(true)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    override fun onDestroy() {
        running = false
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
