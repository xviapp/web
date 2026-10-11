package app.xvi.web

import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Download records with live progress while the panel is open. Rows are rebuilt only when the set of
 * downloads or their states change; progress ticks update the existing rows. Private downloads keep no record.
 */
internal class DownloadsPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(R.string.downloads_title)
    private val summary = ui.label("", 12f, palette.muted)
    private val list = ui.column()
    private val actions = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
    private val rows = mutableMapOf<Long, MenuRow>()
    private var layout: List<Pair<Long, DownloadState>>? = null
    private var polling: Job? = null

    fun show() {
        sheet.add(summary)
        sheet.add(ScrollView(host).apply { addView(list) }, ui.stretch())
        sheet.add(actions)
        sheet.onDismiss { polling?.cancel() }
        load()
        sheet.show()
    }

    /** Reads the records, then keeps polling once a second while any download is running. */
    private fun load(wait: Long = 0) {
        polling?.cancel()
        polling = host.lifecycleScope.launch {
            delay(wait)
            while (true) {
                val entries = withContext(host.store.io) { host.store.downloads() }
                render(entries)
                if (entries.none { it.state == DownloadState.RUNNING }) break
                delay(1000)
            }
        }
    }

    private fun render(entries: List<DownloadEntry>) {
        val running = entries.count { it.state == DownloadState.RUNNING }
        summary.text = when {
            entries.isEmpty() -> ""
            running > 0 -> ui.str(R.string.downloads_summary_running, entries.size, running)
            else -> ui.str(R.string.downloads_summary, entries.size)
        }
        val shape = entries.map { it.id to it.state }
        if (shape == layout) {
            // Same rows in the same states: only progress text moved.
            entries.forEach { entry -> rows[entry.id]?.setSubtitle(status(entry)) }
            return
        }
        layout = shape
        rows.clear()
        list.removeAllViews()
        if (entries.isEmpty()) list.addView(ui.emptyState(ui.str(R.string.downloads_empty)), ui.wide())
        entries.forEach { entry ->
            val color = when (entry.state) {
                DownloadState.RUNNING, DownloadState.COMPLETED -> palette.accent
                else -> palette.muted
            }
            // Running rows act only through their cancel button, so a stray tap never stops a download.
            val primary = if (entry.state == DownloadState.RUNNING) null else ({ act(entry) })
            val row = ui.menuRow(R.drawable.ic_download_24, entry.title, status(entry), color, primary)
            if (entry.state.retryable) row.subtitle.apply { setTextColor(palette.warning); maxLines = 2 }
            row.view.addView(action(entry))
            row.view.setOnLongClickListener {
                host.haptic(it)
                if (entry.state == DownloadState.RUNNING) host.toast(R.string.downloads_cancel_first)
                else host.actionsDialog(entry.title, listOf(ui.str(R.string.action_delete) to { remove(entry) }))
                true
            }
            rows[entry.id] = row
            list.addView(row.view, ui.wide().apply { bottomMargin = ui.dp(3) })
        }
        actions.removeAllViews()
        if (running > 0) actions.addView(ui.textAction(ui.str(R.string.downloads_cancel_all)) {
            host.confirm(ui.str(R.string.downloads_cancel_all_confirm, running), ui.str(R.string.downloads_cancel_all), destructive = true) {
                host.startService(BrowserDownloadService.cancelIntent(host))
                load(300)
            }
        }, ui.weighted().apply { rightMargin = ui.dp(6) })
        if (entries.any { it.state == DownloadState.COMPLETED }) actions.addView(ui.textAction(ui.str(R.string.downloads_clear_completed)) {
            host.lifecycleScope.launch { withContext(host.store.io) { host.store.clearCompletedDownloads() }; load() }
        }, ui.weighted().apply { rightMargin = ui.dp(6) })
        actions.addView(ui.textAction(ui.str(R.string.action_done), true) { sheet.dismiss() }, ui.weighted())
    }

    /** Removes the record only; the file stays. Undo puts the record back. */
    private fun remove(entry: DownloadEntry) {
        val store = host.store
        host.lifecycleScope.launch {
            val removed = withContext(store.io) { store.removeDownload(entry.id) }
            // The store work above already happened on its own thread; reloading now sees the removal.
            load()
            sheet.showSnackbar(ui.str(R.string.download_record_removed), ui.str(R.string.action_undo)) {
                host.lifecycleScope.launch { withContext(store.io) { store.restore(removed) }; load() }
            }
        }
    }

    /** The one action that fits the row's state, as its own button. */
    private fun action(entry: DownloadEntry) = when (entry.state) {
        DownloadState.RUNNING -> ui.iconAction(R.drawable.ic_close_24, ui.str(R.string.download_cancel)) { act(entry) }
        DownloadState.COMPLETED -> ui.iconAction(R.drawable.ic_open_in_new_24, ui.str(R.string.download_open), palette.accent) { act(entry) }
        else -> ui.iconAction(R.drawable.ic_refresh_24, ui.str(if (resumable(entry)) R.string.download_resume else R.string.download_retry), palette.accent) { act(entry) }
    }

    private fun resumable(entry: DownloadEntry) = entry.validator.isNotEmpty() && entry.received > 0

    private fun size(bytes: Long) = BrowserDownloadService.size(host, bytes)

    private fun status(entry: DownloadEntry): String = when (entry.state) {
        DownloadState.RUNNING -> BrowserDownloadService.progressText(host, entry.received, entry.total)
        DownloadState.COMPLETED -> if (entry.received > 0) ui.str(R.string.download_status_done_size, size(entry.received)) else ui.str(R.string.download_status_done)
        DownloadState.FAILED, DownloadState.INTERRUPTED -> {
            val state = ui.str(if (entry.state == DownloadState.FAILED) R.string.download_status_failed else R.string.download_status_interrupted)
            val base = DownloadFailure.describe(host, entry.error)?.let { ui.str(R.string.download_status_reason, state, it) } ?: state
            if (resumable(entry)) ui.str(R.string.download_status_resumable, base, size(entry.received)) else base
        }
        DownloadState.CANCELLED -> ui.str(R.string.download_status_cancelled)
    }

    private fun act(entry: DownloadEntry) = when (entry.state) {
        DownloadState.COMPLETED -> try {
            val uri = entry.uri.toUri()
            host.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, host.contentResolver.getType(uri) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { host.toast(R.string.download_open_failed) }
        DownloadState.RUNNING -> host.confirm(ui.str(R.string.download_cancel_confirm, entry.title), ui.str(R.string.download_cancel), destructive = true) {
            host.startService(BrowserDownloadService.cancelIntent(host).putExtra(BrowserDownloadService.EXTRA_RECORD, entry.id))
            load(300)
        }
        else -> { sheet.dismiss(); host.downloads.retry(entry) }
    }
}
