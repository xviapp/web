package app.xvi.web

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * What the pages are playing, for the background notification and the lock screen.
 *
 * Pages are asked one at a time because a page can only answer for itself; the first page that
 * reports playing media is the one the notification shows and controls. Media paused from the
 * notification still counts, so it can be resumed from there.
 *
 * The scripts are the only part of the browser that runs in a page on the browser's own initiative,
 * and they only read the media element's public state. Nothing is sent anywhere.
 */
internal class MediaProbe(private val host: BrowserActivity) {
    private val main = Handler(Looper.getMainLooper())

    /** Tabs whose media was paused from the notification. */
    private val paused = mutableListOf<BrowserTab>()
    /** The tab the media notification controls. */
    private var tab: BrowserTab? = null
    /** Cover picture last fetched or being fetched for the notification, by address. */
    private var art: Pair<String, Bitmap?>? = null
    /** Set once the cover for [art]'s address has been asked for, so a failure cannot loop. */
    private var artProbed: String? = null
    /**
     * True once any page has reported a media element, playing or paused. A page that only ever holds
     * paused media still sets it, so the notification can control what a page paused for itself.
     */
    internal var everSawMedia = false
        private set

    /** One load at a time; a new cover replaces the old request. */
    private var artJob: Job? = null

    /** True while a page is playing, or while one is only paused from the notification. */
    var playing = false
        private set

    /** Media starting or stopping is checked right away instead of at the next periodic check. */
    val audioChanges = object : android.media.AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            main.removeCallbacks(probeSoon)
            main.postDelayed(probeSoon, 300)
        }
    }

    private val probeSoon = Runnable { probe() }

    fun register(am: android.media.AudioManager) = am.registerAudioPlaybackCallback(audioChanges, main)

    fun unregister(am: android.media.AudioManager) = runCatching { am.unregisterAudioPlaybackCallback(audioChanges) }

    /**
     * Asks the live pages whether audio or video is playing; [done] runs after all of them answered.
     *
     * [allPages] is what the periodic check passes: while no page has ever reported media there is
     * nothing behind the current one worth asking, so only the page in front is polled. Muted media in
     * a background page produces no audio event, so once media has been seen anywhere every page is
     * asked again on every check, and a background page that starts playing is never missed for long.
     */
    fun probe(allPages: Boolean = true, done: (() -> Unit)? = null) {
        if (host.isDestroyed) return
        val current = host.tab
        val live = host.tabs.filter { it.view != null && (allPages || it === current) }
        paused.retainAll { it in host.tabs && it.view != null }
        var waiting = live.size
        var playingPage: Pair<BrowserTab, JSONObject>? = null
        var pausedPage: Pair<BrowserTab, JSONObject>? = null
        fun settle() {
            if (host.isDestroyed) return
            playing = playingPage != null || paused.isNotEmpty()
            if (playingPage != null) paused.clear()
            val shown = playingPage ?: pausedPage?.takeIf { it.first in paused }
            tab = shown?.first
            MediaControl.update(shown?.let { (target, info) -> track(target, info) }, playingPage != null)
            done?.invoke()
        }
        if (live.isEmpty()) { settle(); return }
        live.forEach { item ->
            runCatching {
                item.view!!.evaluateJavascript(MEDIA_PROBE) { result ->
                    val info = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
                    if (info != null) {
                        val isPlaying = info.optBoolean("playing")
                        everSawMedia = true
                        if (isPlaying && playingPage == null) playingPage = item to info
                        else if (!isPlaying && pausedPage == null) pausedPage = item to info
                    }
                    if (--waiting == 0) settle()
                }
            }.onFailure { if (--waiting == 0) settle() }
        }
    }

    /** Play / pause from the media notification or a headset button. */
    fun control(play: Boolean) {
        if (host.isDestroyed) return
        if (play) {
            paused.forEach { item -> runCatching { item.view?.evaluateJavascript(MEDIA_RESUME, null) } }
            paused.clear()
            main.postDelayed({ probe() }, 500)
            return
        }
        val live = host.tabs.filter { it.view != null }
        var waiting = live.size
        if (waiting == 0) { probe(); return }
        live.forEach { item ->
            runCatching {
                item.view!!.evaluateJavascript(MEDIA_PAUSE) { result ->
                    if ((result?.toIntOrNull() ?: 0) > 0 && item !in paused) paused.add(item)
                    if (--waiting == 0) probe()
                }
            }.onFailure { if (--waiting == 0) probe() }
        }
    }

    /** Seeks from the notification's progress bar. */
    fun seek(positionMs: Long) {
        val target = tab?.takeIf { it in host.tabs } ?: return
        runCatching { target.view?.evaluateJavascript(MEDIA_SEEK.replace("__POSITION__", (positionMs / 1000.0).toString())) { probe() } }
    }

    /** Notification details from a page's answer; private pages show no title or picture. */
    private fun track(target: BrowserTab, info: JSONObject): MediaControl.Track {
        val duration = info.optDouble("duration", -1.0)
        val title = if (target.isPrivate) host.str(R.string.private_title)
            else info.optString("title").ifBlank { host.displayTitle(target) }
        val artist = if (target.isPrivate) "" else info.optString("artist").ifBlank { host.domain(target.url) }
        val artwork = info.optString("artwork").takeIf { !target.isPrivate && BrowserInput.isWebUrl(it) }
        if (artwork != null && art?.first != artwork) loadArt(artwork)
        val cover = if (target.isPrivate) null else art?.takeIf { it.first == artwork }?.second ?: target.favicon
        return MediaControl.Track(title, artist, if (duration > 0 && duration.isFinite()) (duration * 1000).toLong() else -1L,
            (info.optDouble("position", 0.0) * 1000).toLong().coerceAtLeast(0), info.optDouble("rate", 1.0).toFloat(), cover)
    }

    /** Fetches the page's cover picture through the browser's network settings, then shows it. */
    private fun loadArt(url: String) {
        if (art?.first == url) return
        art = url to null
        artProbed = null
        artJob?.cancel()
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            val proxy = host.network.proxy()
            artJob = host.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        BrowserHttp.through(proxy).newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) return@runCatching null
                            val bytes = response.body.source().apply { request(MAX_ART_BYTES) }.buffer.readByteArray()
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            var sample = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }.getOrNull()
                }
                if (host.isDestroyed || art?.first != url) return@launch
                art = url to bitmap
                probeOnce(url)
            }
        }
    }

    /**
     * Re-probes once for [url] so the notification picks the cover up. Without this the page would have
     * to change its answer before the probe ran again.
     */
    private fun probeOnce(url: String) {
        if (artProbed == url) return
        artProbed = url
        probe()
    }

    /** Stops the cover transfer and the pending checks; called when the browser closes. */
    fun release() {
        artJob?.cancel()
        artJob = null
        art = null
        artProbed = null
        main.removeCallbacks(probeSoon)
    }

    private companion object {
        /** Largest cover accepted; it is decoded into a bitmap afterwards. */
        const val MAX_ART_BYTES = 4L * 1024 * 1024

        /**
         * The playing audio / video element (or the one paused from the notification) with its length,
         * position and speed, plus the title, artist and cover the page gave the Media Session API.
         */
        const val MEDIA_PROBE = "(() => { try { const all = Array.from(document.querySelectorAll('audio,video'));" +
            " let m = all.find(x => !x.paused && !x.ended); const playing = !!m;" +
            " if (!m) m = (window.__xviPausedMedia || [])[0]; if (!m) return null;" +
            " const md = navigator.mediaSession && navigator.mediaSession.metadata;" +
            " const meta = n => { const e = document.querySelector('meta[property=\"' + n + '\"],meta[name=\"' + n + '\"]'); return e ? (e.content || '').trim() : ''; };" +
            " const heading = (document.querySelector('h1') || {}).textContent || '';" +
            " const title = (md && md.title) || meta('og:title') || heading.trim().slice(0, 200) || document.title;" +
            " const art = (md && md.artwork && md.artwork.length ? md.artwork[md.artwork.length - 1].src : '') || meta('og:image') || (m.poster && !m.poster.startsWith('data:') ? m.poster : '');" +
            " return JSON.stringify({ playing, duration: isFinite(m.duration) ? m.duration : -1, position: m.currentTime || 0, rate: m.playbackRate || 1," +
            " title, artist: md && md.artist || '', artwork: art ? new URL(art, location.href).href : '' });" +
            " } catch (_) { return null; } })()"
        const val MEDIA_SEEK = "(() => { try { const m = Array.from(document.querySelectorAll('audio,video')).find(x => !x.paused && !x.ended)" +
            " || (window.__xviPausedMedia || [])[0]; if (m) m.currentTime = __POSITION__; } catch (_) {} })()"
        /** Pauses playing media and remembers it, so the notification can resume exactly that. */
        const val MEDIA_PAUSE = "(() => { try { const playing = Array.from(document.querySelectorAll('audio,video')).filter(m => !m.paused && !m.ended);" +
            " window.__xviPausedMedia = playing; playing.forEach(m => m.pause()); return playing.length; } catch (_) { return 0; } })()"
        const val MEDIA_RESUME = "(() => { try { (window.__xviPausedMedia || []).forEach(m => { const p = m.play(); if (p) p.catch(() => {}); });" +
            " window.__xviPausedMedia = []; } catch (_) {} })()"
    }
}
