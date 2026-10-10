package app.xvi.web

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Shared between the browser and its background notification: what a page is playing, and how to
 * pause, resume or seek it. Updated on the main thread.
 */
internal object MediaControl {
    /** [durationMs] is -1 for live streams and unknown lengths. */
    data class Track(
        val title: String, val artist: String, val durationMs: Long, val positionMs: Long,
        val rate: Float, val art: Bitmap?
    )

    /** What the playing (or paused-from-the-notification) page plays; null when no page has media. */
    var track: Track? = null
        private set
    var playing = false
        private set
    /** When [Track.positionMs] was read, on the elapsed-realtime clock, so the system can advance it. */
    var readAt = 0L
        private set
    /** Set by the browser while it is open. */
    var handler: ((play: Boolean) -> Unit)? = null
    var seek: ((positionMs: Long) -> Unit)? = null
    /** Set by the background service to refresh its notification. */
    var listener: (() -> Unit)? = null

    fun update(track: Track?, playing: Boolean) {
        val same = track == this.track && playing == this.playing
        this.track = track
        this.playing = playing && track != null
        readAt = SystemClock.elapsedRealtime()
        if (!same) listener?.invoke()
    }
}
