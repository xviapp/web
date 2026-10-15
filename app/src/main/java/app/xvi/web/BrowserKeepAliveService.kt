package app.xvi.web

import android.app.*
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * One user-visible browser session. While a page plays
 * media, the notification doubles as its player: play / pause there, on the lock screen or from a headset.
 */
class BrowserKeepAliveService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { stopSelf(); return START_NOT_STICKY }
            PLAY -> MediaControl.control(true)
            PAUSE -> MediaControl.control(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.keepalive_channel), NotificationManager.IMPORTANCE_LOW)
        )
        MediaControl.listener = { main.post { if (running) refresh() } }
        running = true
        refresh()
        return START_NOT_STICKY
    }

    /** The media session exists only while a page has media, so the system never shows an empty player. */
    private fun mediaSession(): MediaSession = session ?: MediaSession(this, "browser").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { MediaControl.control(true) }
            override fun onPause() { MediaControl.control(false) }
            override fun onStop() { MediaControl.control(false) }
            override fun onSeekTo(pos: Long) { MediaControl.seek(pos) }
        })
        session = this
    }

    private fun refresh() {
        val track = MediaControl.track
        val playing = MediaControl.playing
        val title = track?.title
        if (track == null) { session?.release(); session = null }
        else mediaSession().apply {
            // The window with the media may change while the session lives.
            setSessionActivity(openBrowser(3))
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                .apply { track.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it); putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it) } }
                .build())
            val seekable = if (track.durationMs > 0) PlaybackState.ACTION_SEEK_TO else 0L
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or seekable)
                // The system advances the position from when it was read, at the page's speed.
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, track.positionMs,
                    if (playing) track.rate else 0f, MediaControl.readAt)
                .build())
            isActive = true
        }
        val stop = Notification.Action.Builder(null, getString(R.string.keepalive_stop), service(STOP, 1)).build()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_star_24)
            .setContentIntent(openBrowser(0)).setOngoing(true).setOnlyAlertOnce(true)
        if (title == null) {
            builder.setContentTitle(getString(R.string.keepalive_title, getString(R.string.app_name))).addAction(stop)
        } else {
            val toggle = Notification.Action.Builder(
                Icon.createWithResource(this, if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24),
                getString(if (playing) R.string.media_pause else R.string.media_play),
                service(if (playing) PAUSE else PLAY, 2)
            ).build()
            builder.setContentTitle(title).setContentText(getString(R.string.keepalive_title, getString(R.string.app_name)))
                .addAction(toggle).addAction(stop)
                .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0))
            track.art?.let { builder.setLargeIcon(it) }
        }
        startForeground(NOTIFICATION_ID, builder.build())
    }

    /** Opens the window playing the media, or the browser when nothing plays. */
    private fun openBrowser(code: Int) = PendingIntent.getActivity(this, code, MediaControl.reopen ?: Intent(this, BrowserActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun service(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, BrowserKeepAliveService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    override fun onDestroy() {
        running = false
        MediaControl.listener = null
        session?.release()
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set
        private const val CHANNEL = "browser_background"
        private const val NOTIFICATION_ID = 42
        private const val STOP = "app.xvi.web.STOP_BACKGROUND"
        private const val PLAY = "app.xvi.web.MEDIA_PLAY"
        private const val PAUSE = "app.xvi.web.MEDIA_PAUSE"
    }
}
