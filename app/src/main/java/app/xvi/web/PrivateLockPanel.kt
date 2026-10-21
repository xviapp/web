package app.xvi.web

import android.widget.ScrollView

/**
 * Settings → Privacy and security → Private lock: the lock itself, and what else happens with it. The other two
 * options only apply while the lock is on.
 */
internal class PrivateLockPanel(private val host: BrowserActivity, back: (() -> Unit)? = null) {
    private val ui = host.ui
    private val prefs = host.prefs
    private val sheet = host.sheet(R.string.settings_private_lock).apply { back?.let(::backTo) }

    fun show() {
        val body = ui.column()
        lateinit var lock: MenuRow
        lateinit var pauseMedia: MenuRow
        lateinit var wipe: MenuRow
        fun sync() { pauseMedia.setEnabled(prefs.lockPrivate); wipe.setEnabled(prefs.lockPrivate) }
        lock = ui.switchRow(R.drawable.ic_ghost_24, ui.str(R.string.settings_lock_private), prefs.lockPrivate) { on ->
            if (on == prefs.lockPrivate) return@switchRow
            when {
                on && !host.unlock.available -> { host.toast(R.string.settings_lock_private_unavailable); lock.toggle?.isChecked = false }
                on -> { prefs.lockPrivate = true; sync() }
                // Otherwise anyone holding the phone could switch the lock off and walk in.
                else -> host.unlock.confirm(ui.str(R.string.unlock_lock_private_off), cancelled = { lock.toggle?.isChecked = true }) {
                    prefs.lockPrivate = false
                    sync()
                }
            }
        }
        body.addView(lock.view)
        pauseMedia = ui.switchRow(R.drawable.ic_pause_24, ui.str(R.string.settings_lock_private_pause_media), prefs.lockPrivatePausesMedia) {
            prefs.lockPrivatePausesMedia = it
        }
        body.addView(pauseMedia.view)
        wipe = ui.switchRow(R.drawable.ic_close_24, ui.str(R.string.settings_lock_private_close_on_fail), prefs.lockPrivateCloseOnFail) {
            prefs.lockPrivateCloseOnFail = it
        }
        body.addView(wipe.view)
        sync()
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        sheet.show()
    }
}
