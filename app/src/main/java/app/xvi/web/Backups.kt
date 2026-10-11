package app.xvi.web

import android.net.Uri
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Saves everything worth moving to a new phone into one file, and merges such a file back in. */
internal class Backups(private val host: BrowserActivity) {
    private val ui get() = host.ui
    private var includeSecrets = false
    private val exporter = host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(::write) }
    private val importer = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::read) }

    /** Asks whether secrets go in, then where to save. */
    fun export() {
        val secrets = CheckBox(host).apply { text = ui.str(R.string.backup_include_secrets); setTextColor(ui.palette.ink) }
        host.browserDialog().setTitle(R.string.backup_export).setView(ui.dialogForm(secrets))
            .setPositiveButton(R.string.backup_choose_location) { _, _ ->
                includeSecrets = secrets.isChecked
                val name = "xvi-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".json"
                runCatching { exporter.launch(name) }.onFailure { host.toast(R.string.backup_no_picker) }
            }
            .setNegativeButton(R.string.action_cancel, null).show()
    }

    fun import() {
        runCatching { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }.onFailure { host.toast(R.string.backup_no_picker) }
    }

    private fun write(uri: Uri) {
        val secrets = includeSecrets
        includeSecrets = false
        host.lifecycleScope.launch {
            val tabs = host.tabManager.ordinarySnapshot().first.filter { BrowserInput.isWebUrl(it.url) }.map { SavedTab(it.url, it.title, it.desktop) }
            val subscription = host.proxyState?.subscription
            val backupSecrets = if (!secrets) null else BrowserBackup.Secrets(
                subscription?.url?.takeIf(BrowserInput::isWebUrl), subscription?.updateIntervalMinutes ?: 1440,
                subscription?.testIntervalMinutes ?: 5, host.aiConfig.apiKey)
            val settings = host.prefs.exportSettings()
            val ai = BrowserBackup.Ai(host.aiConfig.enabled, host.aiConfig.model)
            val version = runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }.getOrNull().orEmpty()
            val rows = withContext(host.store.io) { host.store.backupRows() }
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val text = BrowserBackup.encode(BrowserBackup.Backup(System.currentTimeMillis(), version, settings, ai, tabs, rows, backupSecrets))
                    requireNotNull(host.contentResolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            host.toast(if (saved) R.string.backup_saved else R.string.backup_failed)
        }
    }

    private fun read(uri: Uri) {
        host.lifecycleScope.launch {
            val backup = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(host.contentResolver.openInputStream(uri)).use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            require(out.size() <= BrowserBackup.MAX_BYTES) { "too large" }
                        }
                        BrowserBackup.decode(out.toString(Charsets.UTF_8.name()))
                    }
                }.getOrNull()
            }
            if (backup == null) { host.toast(R.string.backup_invalid); return@launch }
            confirm(backup)
        }
    }

    /** Says what the file holds and how it merges before anything changes. */
    private fun confirm(backup: BrowserBackup.Backup) {
        val rows = backup.rows
        val created = if (backup.created > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.created)) else "—"
        val secrets = backup.secrets?.let {
            CheckBox(host).apply { text = ui.str(R.string.backup_restore_secrets); setTextColor(ui.palette.ink); isChecked = true }
        }
        val dialog = host.browserDialog().setTitle(R.string.backup_restore_title)
            .setMessage(ui.str(R.string.backup_restore_message, created, rows.bookmarks.size, rows.history.size, backup.tabs.size,
                rows.siteSettings.size, rows.permissions.size))
            .setPositiveButton(R.string.backup_restore) { _, _ -> restore(backup, secrets?.isChecked == true) }
            .setNegativeButton(R.string.action_cancel, null)
        secrets?.let { dialog.setView(ui.dialogForm(it)) }
        dialog.show()
    }

    private fun restore(backup: BrowserBackup.Backup, withSecrets: Boolean) {
        host.lifecycleScope.launch {
            val themeBefore = host.prefs.theme
            host.store.write { restoreRows(backup.rows) }
            host.prefs.importSettings(backup.settings)
            backup.ai?.let { ai ->
                host.aiConfig.enabled = ai.enabled
                if (ai.model in DeepSeek.models) host.aiConfig.model = ai.model
            }
            if (withSecrets) backup.secrets?.let { secrets ->
                secrets.aiKey?.let { host.aiConfig.apiKey = it }
                // Saving the address does not fetch nodes; refreshing right after does.
                secrets.proxyUrl?.let { url ->
                    host.network.setSubscription(url, secrets.proxyUpdateMinutes, secrets.proxyTestMinutes) { host.network.refreshSubscription { } }
                }
            }
            // Replace, not add: a restored denial must also end an allowance any window still holds.
            suspendCoroutine { done -> host.siteChoices.reload { done.resume(Unit) } }
            val added = host.restoreTabs(backup.tabs)
            AdBlock.reload(host)
            // Lists switched on in the backup but not on this phone are fetched now.
            host.network.prepare { _ -> if (!host.isDestroyed) AdBlock.update(host, host.network.proxy(), force = false) }
            host.settingsRestored(themeChanged = host.prefs.theme != themeBefore)
            host.toast(if (added > 0) ui.str(R.string.backup_restored_tabs, added) else ui.str(R.string.backup_restored))
        }
    }
}
