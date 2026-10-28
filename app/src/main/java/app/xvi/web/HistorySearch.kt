package app.xvi.web

import android.content.res.ColorStateList
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filters and bulk removal for the history list; deletion always uses its last completed preview. */
internal class HistorySearch(
    private val host: BrowserActivity,
    private val input: EditText,
    private val notify: (String, String, () -> Unit) -> Unit,
    private val refresh: () -> Unit
) {
    private val ui = host.ui
    private var mode = HistoryFilter.Mode.KEYWORD
    /** Null until a person picks a range, including an explicit choice to delete all history. */
    private var range: HistoryFilter.Range? = null
    private var includeSubdomains = true
    private var busy = false
    private data class Preview(val filter: HistoryFilter, val range: HistoryFilter.Range?, val entries: List<PageEntry>)
    private var preview: Preview? = null
    private val match = ui.textAction("") { chooseMode() }.apply { textSize = 13f }
    private val time = ui.textAction("") { chooseRange() }.apply { textSize = 13f }
    private val subdomains = CheckBox(host).apply {
        text = ui.str(R.string.history_include_subdomains)
        textSize = 13f
        setTextColor(ui.palette.ink)
        buttonTintList = ColorStateList.valueOf(ui.palette.accent)
        minimumHeight = ui.dp(48)
        isChecked = includeSubdomains
        setOnCheckedChangeListener { _, value -> includeSubdomains = value; refresh() }
    }
    private val note = ui.label("", 12f, ui.palette.muted)
    private val summary = ui.label("", 12f, ui.palette.muted).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
    }
    val deleteButton = ui.textAction(ui.str(R.string.history_delete_matches, 0)) { deleteMatches() }.apply {
        setTextColor(ui.palette.danger)
    }
    val view = ui.column().apply {
        addView(ui.row().apply {
            addView(match, ui.weighted())
            addView(time, ui.weighted().apply { leftMargin = ui.dp(8) })
        }, ui.wide())
        addView(subdomains, ui.wide())
        addView(note, ui.wide().apply { topMargin = ui.dp(6) })
        addView(summary, ui.wide())
    }

    fun filter(now: Long) = HistoryFilter(input.text.toString(), mode, range?.since(now) ?: 0, includeSubdomains)

    /** Called immediately on an edit, before debouncing, so a stale list can never be deleted. */
    fun loading() {
        preview = null
        match.text = ui.str(R.string.history_match_choice, modeLabel(mode))
        time.text = range?.let { ui.str(R.string.history_time_choice, rangeLabel(it)) } ?: ui.str(R.string.history_time_range)
        input.hint = ui.str(when (mode) {
            HistoryFilter.Mode.KEYWORD -> R.string.library_search
            HistoryFilter.Mode.URL_CONTAINS -> R.string.history_url_contains_hint
            HistoryFilter.Mode.URL_PREFIX -> R.string.history_url_prefix_hint
            HistoryFilter.Mode.PATH_SUFFIX -> R.string.history_path_suffix_hint
            HistoryFilter.Mode.DOMAIN -> R.string.history_domain_hint
        })
        subdomains.isVisible = mode == HistoryFilter.Mode.DOMAIN
        note.text = ui.str(when {
            mode == HistoryFilter.Mode.PATH_SUFFIX -> R.string.history_path_suffix_note
            mode == HistoryFilter.Mode.DOMAIN -> R.string.history_domain_note
            input.text.isBlank() && range == null -> R.string.history_filter_start
            else -> R.string.history_text_match_note
        })
        summary.text = ui.str(R.string.history_filter_loading)
        updateButton()
    }

    fun loaded(filter: HistoryFilter, entries: List<PageEntry>) {
        preview = Preview(filter, range, entries)
        summary.text = when {
            !filter.isValid && filter.query.isBlank() -> ui.str(R.string.history_filter_input_required)
            !filter.isValid -> ui.str(R.string.history_domain_invalid)
            else -> ui.str(R.string.history_match_count, entries.size)
        }
        updateButton()
    }

    private fun updateButton() {
        val shown = preview
        deleteButton.text = ui.str(R.string.history_delete_matches, shown?.entries?.size ?: 0)
        deleteButton.isEnabled = !busy && shown != null && shown.filter.isValid && shown.entries.isNotEmpty() &&
            (shown.filter.query.isNotBlank() || shown.range != null)
        deleteButton.alpha = if (deleteButton.isEnabled) 1f else 0.4f
    }

    private fun chooseMode() {
        host.browserDialog().setTitle(R.string.history_match_mode)
            .setSingleChoiceItems(HistoryFilter.Mode.entries.map(::modeLabel).toTypedArray(), mode.ordinal) { dialog, which ->
                mode = HistoryFilter.Mode.entries[which]
                dialog.dismiss()
                refresh()
            }.show()
    }

    private fun chooseRange() {
        host.browserDialog().setTitle(R.string.history_time_range)
            .setSingleChoiceItems(HistoryFilter.Range.entries.map(::rangeLabel).toTypedArray(), range?.ordinal ?: -1) { dialog, which ->
                range = HistoryFilter.Range.entries[which]
                dialog.dismiss()
                refresh()
            }.show()
    }

    private fun modeLabel(mode: HistoryFilter.Mode) = ui.str(when (mode) {
        HistoryFilter.Mode.KEYWORD -> R.string.history_match_keyword
        HistoryFilter.Mode.URL_CONTAINS -> R.string.history_match_url_contains
        HistoryFilter.Mode.URL_PREFIX -> R.string.history_match_url_prefix
        HistoryFilter.Mode.PATH_SUFFIX -> R.string.history_match_path_suffix
        HistoryFilter.Mode.DOMAIN -> R.string.history_match_domain
    })

    private fun rangeLabel(range: HistoryFilter.Range) = ui.str(when (range) {
        HistoryFilter.Range.HOUR -> R.string.history_range_hour
        HistoryFilter.Range.TODAY -> R.string.history_range_today
        HistoryFilter.Range.WEEK -> R.string.history_range_week
        HistoryFilter.Range.ALL -> R.string.history_range_all
    })

    private fun deleteMatches() {
        val shown = preview?.takeIf { deleteButton.isEnabled } ?: return
        val criteria = buildList {
            add(ui.str(R.string.history_time_choice, rangeLabel(shown.range ?: HistoryFilter.Range.ALL)))
            if (shown.filter.query.isNotBlank()) {
                add(ui.str(R.string.history_match_condition, modeLabel(shown.filter.mode), shown.filter.query.trim()))
                if (shown.filter.mode == HistoryFilter.Mode.DOMAIN && shown.filter.includeSubdomains)
                    add(ui.str(R.string.history_include_subdomains))
            }
        }.joinToString("\n")
        host.confirm(ui.str(R.string.history_delete_matches_confirm, shown.entries.size, criteria),
            ui.str(R.string.action_delete), destructive = true) {
            busy = true
            updateButton()
            host.lifecycleScope.launch {
                try {
                    val removed = withContext(host.store.io) { host.store.removeHistory(shown.entries) }
                    refresh()
                    host.refreshHome()
                    if (removed.isEmpty) host.toast(R.string.history_preview_changed)
                    else notify(ui.str(R.string.history_bulk_removed, removed.rows.size), ui.str(R.string.action_undo)) {
                        host.lifecycleScope.launch {
                            withContext(host.store.io) { host.store.restore(removed) }
                            refresh()
                            host.refreshHome()
                        }
                    }
                } finally {
                    busy = false
                    updateButton()
                }
            }
        }
    }
}
