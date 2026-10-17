package app.xvi.web

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Shared between the app's windows and the background notification: what a page is playing, and how to
 * pause, resume or seek it. Each window reports through its own [MediaProbe]; the notification follows
 * the window that reported media, and another window only takes over once that one stops playing.
 * Updated on the main thread.
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
    /** The window whose media the notification shows and controls. */
    private var owner: MediaProbe? = null
    /** Brings back the window the notification belongs to; null when no window has media. */
    val reopen: Intent? get() = owner?.reopen
    /** Set by the background service to refresh its notification. */
    var listener: (() -> Unit)? = null

    fun control(play: Boolean) { owner?.control(play) }
    fun seek(positionMs: Long) { owner?.seek(positionMs) }

    /** What [source]'s window is playing; null when it has no media. */
    fun report(source: MediaProbe, track: Track?, playing: Boolean) {
        if (owner !== source) {
            if (track == null || (owner != null && this.playing)) return
            owner = source
        } else if (track == null) owner = null
        update(track, playing)
    }

    /** [source]'s window closed. */
    fun release(source: MediaProbe) {
        if (owner === source) { owner = null; update(null, false) }
    }

    private fun update(track: Track?, playing: Boolean) {
        val same = track == this.track && playing == this.playing
        this.track = track
        this.playing = playing && track != null
        readAt = SystemClock.elapsedRealtime()
        if (!same) listener?.invoke()
    }
}
