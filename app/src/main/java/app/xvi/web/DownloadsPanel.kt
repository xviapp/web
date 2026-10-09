package app.xvi.web

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ScrollView

/** Download records with live progress while the panel is open. Private downloads keep no record. */
internal class DownloadsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(R.string.downloads_title)
    private val handler = Handler(Looper.getMainLooper())
    private val summary = ui.label("", 12f, palette.muted)
    private val list = ui.column()
    private val actions = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
    private var shown: List<DownloadEntry>? = null
    private val poll = object : Runnable {
        override fun run() { load() }
    }

    fun show() {
        sheet.add(summary)
        sheet.add(ScrollView(host).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.add(actions)
        sheet.onDismiss { handler.removeCallbacksAndMessages(null) }
        load()
        sheet.show()
    }

    private fun load() {
        handler.removeCallbacks(poll)
        host.io.execute {
            val entries = host.store.downloads()
            host.runOnUiThread {
                if (host.isDestroyed || !sheet.isShowing && shown != null) return@runOnUiThread
                if (entries != shown) render(entries)
                if (entries.any { it.state == DownloadState.RUNNING }) handler.postDelayed(poll, 1000)
            }
        }
    }

    private fun render(entries: List<DownloadEntry>) {
        shown = entries
        val running = entries.count { it.state == DownloadState.RUNNING }
        summary.text = when {
            entries.isEmpty() -> ""
            running > 0 -> ui.str(R.string.downloads_summary_running, entries.size, running)
            else -> ui.str(R.string.downloads_summary, entries.size)
        }
        list.removeAllViews()
        if (entries.isEmpty()) list.addView(ui.emptyState(ui.str(R.string.downloads_empty)), LinearLayout.LayoutParams(-1, -2))
        entries.forEach { entry ->
            val color = when (entry.state) {
                DownloadState.RUNNING, DownloadState.COMPLETED -> palette.accent
                else -> palette.muted
            }
            val row = ui.menuRow(R.drawable.ic_download_24, entry.title, status(entry), color) { open(entry) }
            if (entry.state.retryable) row.subtitle.setTextColor(palette.warning)
            row.view.setOnLongClickListener {
                host.haptic(it)
                if (entry.state == DownloadState.RUNNING) { host.toast(R.string.downloads_cancel_first); return@setOnLongClickListener true }
                host.markDeleteTarget(row.view, true)
                host.confirmDelete(entry.title, {
                    host.io.execute { host.store.removeDownload(entry.id); host.runOnUiThread { load() } }
                }, { host.markDeleteTarget(row.view, false) })
                true
            }
            list.addView(row.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(3) })
        }
        actions.removeAllViews()
        if (running > 0) actions.addView(ui.textAction(ui.str(R.string.downloads_cancel_all)) {
            host.confirm(ui.str(R.string.downloads_cancel_all_confirm, running), ui.str(R.string.downloads_cancel_all), destructive = true) {
                host.startService(Intent(host, BrowserDownloadService::class.java).setAction(BrowserDownloadService.ACTION_CANCEL))
                handler.postDelayed({ load() }, 300)
            }
        }, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { rightMargin = ui.dp(6) })
        if (entries.any { it.state == DownloadState.COMPLETED }) actions.addView(ui.textAction(ui.str(R.string.downloads_clear_completed)) {
            host.io.execute { host.store.clearCompletedDownloads(); host.runOnUiThread { load() } }
        }, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { rightMargin = ui.dp(6) })
        actions.addView(ui.textAction(ui.str(R.string.action_done), true) { sheet.dismiss() }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(host, bytes)

    private fun status(entry: DownloadEntry): String = when (entry.state) {
        DownloadState.RUNNING -> when {
            entry.total > 0 -> ui.str(R.string.download_status_progress, (entry.received * 100 / entry.total).toInt(), size(entry.received), size(entry.total))
            entry.received > 0 -> ui.str(R.string.download_status_received, size(entry.received))
            else -> ui.str(R.string.download_status_waiting)
        }
        DownloadState.COMPLETED -> if (entry.received > 0) ui.str(R.string.download_status_done_size, size(entry.received)) else ui.str(R.string.download_status_done)
        DownloadState.FAILED -> ui.str(R.string.download_status_failed)
        DownloadState.CANCELLED -> ui.str(R.string.download_status_cancelled)
        DownloadState.INTERRUPTED -> ui.str(R.string.download_status_interrupted)
    }

    private fun open(entry: DownloadEntry) = when (entry.state) {
        DownloadState.COMPLETED -> try {
            val uri = Uri.parse(entry.uri)
            host.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, host.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { host.toast(R.string.download_open_failed) }
        DownloadState.RUNNING -> host.confirm(ui.str(R.string.download_cancel_confirm, entry.title), ui.str(R.string.download_cancel), destructive = true) {
            host.startService(Intent(host, BrowserDownloadService::class.java).setAction(BrowserDownloadService.ACTION_CANCEL)
                .putExtra(BrowserDownloadService.EXTRA_RECORD, entry.id))
            handler.postDelayed({ load() }, 300)
        }
        else -> { sheet.dismiss(); host.retryDownload(entry) }
    }
}
