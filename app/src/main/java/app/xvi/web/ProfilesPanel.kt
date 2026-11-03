package app.xvi.web

import android.text.InputFilter
import android.text.InputType
import android.widget.ScrollView

/** Settings → Profile: switch between profiles, choose where outside links open, and manage them. */
internal class ProfilesPanel(private val host: BrowserActivity, private val back: (() -> Unit)? = null) {
    private val ui = host.ui
    private val sheet = host.sheet(R.string.profile_title).apply { back?.let(::backTo) }

    fun show() {
        val body = ui.column()
        val profiles = BrowserProfiles.all(host)
        val preferred = BrowserProfiles.preferred(host)
        body.addView(ui.menuRow(R.drawable.ic_open_in_new_24, ui.str(R.string.profile_preferred), BrowserProfiles.label(host, preferred)) {
            val labels = profiles.map { BrowserProfiles.label(host, it) }.toTypedArray()
            host.browserDialog().setTitle(R.string.profile_preferred)
                .setSingleChoiceItems(labels, profiles.indexOfFirst { it.id == preferred.id }) { dialog, index ->
                    dialog.dismiss()
                    BrowserProfiles.setPreferred(host, profiles[index].id)
                    reopen()
                }.show()
        }.view)
        body.addView(ui.sectionTitle(ui.str(R.string.profile_section_list)))
        profiles.forEach { profile ->
            val current = profile.id == host.profile.id
            val label = BrowserProfiles.label(host, profile)
            val row = ui.menuRow(if (current) R.drawable.ic_check_24 else R.drawable.ic_person_24, label,
                ui.str(if (current) R.string.profile_current else R.string.profile_tap_to_switch),
                click = if (current) null else ({ sheet.leave(); host.switchProfile(profile) }))
            row.view.addView(ui.iconAction(R.drawable.ic_more_24, ui.str(R.string.profile_manage, label)) { actions(profile, current) })
            body.addView(row.view)
        }
        body.addView(ui.textAction(ui.str(R.string.profile_add)) { edit(R.string.profile_add, "") { name ->
            val created = BrowserProfiles.create(host, name) ?: return@edit
            sheet.leave()
            host.switchProfile(created)
        } }, ui.wide().apply { topMargin = ui.dp(12) })
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        sheet.show()
    }

    /** The list reads the saved profiles when it opens, so a change shows by opening it again. */
    private fun reopen() { sheet.leave(); ProfilesPanel(host, back).show() }

    private fun actions(profile: BrowserProfile, current: Boolean) {
        val label = BrowserProfiles.label(host, profile)
        host.actionsDialog(label, listOfNotNull(
            ui.str(R.string.profile_rename) to { edit(R.string.profile_rename, label) { name -> if (BrowserProfiles.rename(host, profile.id, name)) reopen() } },
            // The main profile holds WebView's default data, which cannot be deleted. The one showing is in
            // use, but offering it says what to do first, instead of leaving people to look for the action.
            if (profile.isMain) null else ui.str(R.string.profile_delete) to {
                if (current) host.toast(ui.str(R.string.profile_delete_switch_first, label))
                else host.confirm(ui.str(R.string.profile_delete_confirm, label), ui.str(R.string.profile_delete), destructive = true) {
                    BrowserProfiles.remove(host, profile.id)
                    reopen()
                }
            },
        ))
    }

    private fun edit(title: Int, text: String, save: (String) -> Unit) {
        val field = ui.edit(ui.str(R.string.profile_name_hint)).apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(BrowserProfile.MAX_NAME))
        }
        host.browserDialog().setTitle(title).setView(ui.dialogForm(field))
            .setPositiveButton(R.string.action_save) { _, _ -> save(field.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null).show()
    }
}
