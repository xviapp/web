package app.xvi.web

/**
 * Shared between the browser and its background notification: which page is playing media, and how
 * to pause or resume it. Updated on the main thread.
 */
internal object MediaControl {
    /** Name shown for the playing (or paused) page; null when no page has media. */
    var title: String? = null
        private set
    var playing = false
        private set
    /** Set by the browser while it is open. */
    var handler: ((play: Boolean) -> Unit)? = null
    /** Set by the background service to refresh its notification. */
    var listener: (() -> Unit)? = null

    fun update(title: String?, playing: Boolean) {
        if (title == this.title && playing == this.playing) return
        this.title = title
        this.playing = playing && title != null
        listener?.invoke()
    }
}
