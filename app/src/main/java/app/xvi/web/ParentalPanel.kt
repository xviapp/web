package app.xvi.web

import android.text.InputType
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat

/**
 * Settings → Parental control: the kinds of site blocked, the user's own additions and exceptions,
 * strict search, and whether opening this page asks for the phone's unlock first.
 */
internal class ParentalPanel(private val host: BrowserActivity, back: (() -> Unit)? = null) {
    private val ui = host.ui
    private val prefs = host.prefs
    private val sheet = host.sheet(R.string.parental_title).apply { back?.let(::backTo) }
    private val rows = HashMap<SiteBlock.Category, MenuRow>()
    /** Categories whose list is being downloaded, and those whose last download failed. */
    private val downloading = HashSet<SiteBlock.Category>()
    private val failed = HashSet<SiteBlock.Category>()
    private lateinit var custom: MenuRow
    private lateinit var allowed: MenuRow
    private lateinit var updateButton: TextView

    fun show() {
        val body = ui.column()
        SiteBlock.LISTS.forEach { category ->
            val row = ui.switchRow(R.drawable.ic_shield_24, ui.str(title(category)), category in SiteBlock.enabled(prefs)) { on -> setEnabled(category, on) }
            rows[category] = row
            body.addView(row.view)
        }
        custom = ui.menuRow(R.drawable.ic_close_24, ui.str(R.string.parental_custom), sitesSummary(prefs.siteBlockCustom)) {
            editSites(R.string.parental_custom, prefs.siteBlockCustom) { prefs.siteBlockCustom = it; custom.setSubtitle(sitesSummary(it)) }
        }
        body.addView(custom.view)
        allowed = ui.menuRow(R.drawable.ic_check_24, ui.str(R.string.parental_allowed), sitesSummary(prefs.siteBlockAllowed)) {
            editSites(R.string.parental_allowed, prefs.siteBlockAllowed) { prefs.siteBlockAllowed = it; allowed.setSubtitle(sitesSummary(it)) }
        }
        body.addView(allowed.view)
        body.addView(ui.switchRow(R.drawable.ic_search_24, ui.str(R.string.parental_safe_search), prefs.safeSearch) { prefs.safeSearch = it }
            .apply { setSubtitle(ui.str(R.string.parental_safe_search_note)) }.view)
        lateinit var lock: MenuRow
        lock = ui.switchRow(R.drawable.ic_lock_24, ui.str(R.string.parental_lock), prefs.parentalLock) { on ->
            if (on && !host.unlock.available) { host.toast(R.string.settings_lock_private_unavailable); lock.toggle?.isChecked = false }
            else prefs.parentalLock = on
        }
        lock.setSubtitle(ui.str(R.string.parental_lock_note))
        body.addView(lock.view)
        body.addView(ui.label(ui.str(R.string.parental_note), 13f, ui.palette.muted).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(8)) })
        render()
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        updateButton = ui.textAction(ui.str(R.string.parental_update), true) { update(null) }
        sheet.add(updateButton, ui.wide().apply { topMargin = ui.dp(8) })
        sheet.show()
    }

    private fun render() {
        val enabled = SiteBlock.enabled(prefs)
        rows.forEach { (category, row) -> row.setSubtitle(status(category, category in enabled)) }
    }

    private fun status(category: SiteBlock.Category, enabled: Boolean): String {
        val updated = SiteBlock.updated[category]
        return when {
            !enabled -> ui.str(R.string.parental_list_off)
            category in downloading -> ui.str(R.string.parental_list_downloading)
            category in failed -> ui.str(R.string.parental_list_failed)
            updated == null -> ui.str(R.string.parental_list_missing)
            else -> ui.str(R.string.parental_list_status, SiteBlock.filter.size(category.key), DateFormat.getDateInstance(DateFormat.MEDIUM).format(updated))
        }
    }

    /** A category switched on without its list on the device has it downloaded first. */
    private fun setEnabled(category: SiteBlock.Category, on: Boolean) {
        SiteBlock.setEnabled(prefs, category, on)
        if (on && !SiteBlock.available(host, category)) update(setOf(category)) else SiteBlock.reload(host) { render() }
    }

    /** Downloads [only] those lists, or every one switched on. */
    private fun update(only: Set<SiteBlock.Category>?) {
        val due = only ?: SiteBlock.enabled(prefs).toSet()
        if (due.isEmpty() || downloading.isNotEmpty()) return
        downloading.addAll(due)
        failed.removeAll(due)
        updateButton.isEnabled = false
        updateButton.text = ui.str(R.string.parental_updating)
        render()
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            SiteBlock.update(host, host.network.proxy(), force = only == null, only = only) { broken ->
                if (host.isDestroyed) return@update
                downloading.clear()
                failed.addAll(broken)
                updateButton.isEnabled = true
                updateButton.text = ui.str(R.string.parental_update)
                render()
                if (only == null) sheet.showSnackbar(if (broken.isEmpty()) ui.str(R.string.parental_updated) else ui.str(R.string.parental_update_failed, broken.size))
            }
        }
    }

    /** The user's own list of sites, one per line; saving applies it at once. */
    private fun editSites(title: Int, text: String, save: (String) -> Unit) {
        val field = ui.edit(ui.str(R.string.parental_sites_hint)).apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = false
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
        }
        host.browserDialog().setTitle(title).setMessage(R.string.parental_sites_message).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.action_save) { _, _ ->
                save(field.text.toString().trim())
                SiteBlock.reload(host)
            }
            .setNegativeButton(R.string.action_cancel, null).show()
    }

    private fun sitesSummary(text: String): String {
        val count = SiteBlock.ownSites(text).count()
        return if (count == 0) ui.str(R.string.parental_sites_none) else ui.str(R.string.parental_sites_count, count)
    }

    companion object {
        fun title(category: SiteBlock.Category) = when (category) {
            SiteBlock.Category.ADULT -> R.string.parental_category_adult
            SiteBlock.Category.GAMBLING -> R.string.parental_category_gambling
            SiteBlock.Category.CUSTOM -> R.string.parental_custom
        }
        fun name(category: SiteBlock.Category) = when (category) {
            SiteBlock.Category.ADULT -> R.string.parental_name_adult
            SiteBlock.Category.GAMBLING -> R.string.parental_name_gambling
            SiteBlock.Category.CUSTOM -> R.string.parental_name_custom
        }

        /** What parental control is doing, for the settings row. */
        fun summary(host: BrowserActivity): String {
            val prefs = host.prefs
            val kinds = SiteBlock.enabled(prefs) + listOfNotNull(SiteBlock.Category.CUSTOM.takeIf { SiteBlock.ownSites(prefs.siteBlockCustom).any() })
            return when {
                kinds.isNotEmpty() -> host.str(R.string.parental_summary, kinds.joinToString("、") { host.str(name(it)) })
                prefs.safeSearch -> host.str(R.string.parental_summary_safe_search)
                else -> host.str(R.string.parental_summary_off)
            }
        }
    }
}
