package app.xvi.web

/**
 * Installing a user script: from a link to a `.user.js` file, an address typed in settings, or code
 * written in the app. Nothing is installed before the user has seen what the script is and where it
 * runs. Scripts are read through the browser's own network settings.
 */
internal class UserScriptInstall(private val host: PageHost) {
    /** Script addresses that turned out not to be scripts, so they load as pages instead. */
    private val passThrough = mutableSetOf<String>()

    /** A main-frame navigation of [target] to [url]; true when it was taken over to install a script. */
    fun intercept(target: BrowserTab, url: String): Boolean {
        if (!UserScripts.available || !UserScriptMeta.isScriptUrl(url)) return false
        if (passThrough.remove(url)) return false
        fromUrl(url, failed = { failure ->
            if (failure == UserScripts.Failure.INVALID) { passThrough.add(url); host.load(target, url) } else host.toast(message(failure))
        }) { name ->
            host.showSnackbar(host.str(R.string.userscript_installed, name), host.str(R.string.page_refresh)) { if (target in host.tabs) host.retryPage(target) }
        }
        return true
    }

    fun fromUrl(url: String, failed: (UserScripts.Failure) -> Unit = { host.toast(message(it)) }, installed: (name: String) -> Unit) {
        host.toast(R.string.userscript_reading)
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            UserScripts.fetch(host, host.network.proxy(), url) { result -> if (!host.isDestroyed) handle(result, failed, installed) }
        }
    }

    /** Code written or changed in the app; [replaces] is the script being edited, and [failed] gets the code back. */
    fun fromCode(code: String, replaces: UserScript?, failed: () -> Unit, installed: (name: String) -> Unit) {
        host.network.prepare { _ ->
            if (host.isDestroyed) return@prepare
            UserScripts.fromCode(host, host.network.proxy(), code, replaces) { result ->
                if (host.isDestroyed) return@fromCode
                val problem = { error: UserScripts.Failure -> host.toast(message(error)); failed() }
                // Edited code was already looked at; only new code is confirmed.
                if (replaces != null) result.onSuccess { UserScripts.install(it); installed(it.script.meta.name) }.onFailure { problem(failure(it)) }
                else handle(result, problem, installed)
            }
        }
    }

    private fun handle(result: Result<UserScripts.Pending>, failed: (UserScripts.Failure) -> Unit, installed: (String) -> Unit) {
        result.onSuccess { confirm(it, installed) }.onFailure { failed(failure(it)) }
    }

    private fun failure(error: Throwable) = (error as? UserScripts.Problem)?.failure ?: UserScripts.Failure.NETWORK

    /** Says what the script is, where it runs and what it cannot do here, before installing it. */
    private fun confirm(pending: UserScripts.Pending, installed: (String) -> Unit) {
        val meta = pending.script.meta
        val existing = pending.replaces ?: UserScripts.find(meta.id)
        val lines = mutableListOf<String>()
        lines.add(if (meta.version.isEmpty()) meta.name else "${meta.name} · ${host.str(R.string.userscript_version, meta.version)}")
        if (meta.description.isNotEmpty()) lines.add(meta.description)
        if (meta.author.isNotEmpty()) lines.add(host.str(R.string.userscript_by, meta.author))
        existing?.let { lines.add(host.str(R.string.userscript_replaces, it.meta.version.ifEmpty { "—" })) }
        lines.add("")
        val sites = meta.matches + meta.includes
        if (sites.isEmpty()) lines.add(host.str(R.string.userscript_runs_nowhere))
        else {
            lines.add(host.str(R.string.userscript_runs_on))
            sites.take(SHOWN_SITES).forEach { lines.add("  $it") }
            if (sites.size > SHOWN_SITES) lines.add("  " + host.str(R.string.userscript_more_sites, sites.size - SHOWN_SITES))
        }
        if (meta.connects.isNotEmpty()) lines.add(if ("*" in meta.connects) host.str(R.string.userscript_connects_any)
            else host.str(R.string.userscript_connects, meta.connects.take(SHOWN_SITES).joinToString("、") +
                if (meta.connects.size > SHOWN_SITES) host.str(R.string.userscript_more_sites, meta.connects.size - SHOWN_SITES) else ""))
        val external = meta.requires.size + meta.resources.size
        if (external > 0) lines.add(host.str(R.string.userscript_requires, external))
        val unsupported = meta.unsupportedGrants
        if (unsupported.isNotEmpty()) lines.add(host.str(R.string.userscript_unsupported, unsupported.joinToString("、")))
        lines.add("")
        lines.add(host.str(R.string.userscript_warning))
        host.browserDialog().setTitle(if (existing == null) R.string.userscript_install_title else R.string.userscript_update_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(if (existing == null) R.string.userscript_install else R.string.userscript_update) { _, _ ->
                UserScripts.install(UserScripts.Pending(pending.script, existing))
                installed(meta.name)
            }
            .setNegativeButton(R.string.action_cancel, null).show()
    }

    fun message(failure: UserScripts.Failure) = host.str(when (failure) {
        UserScripts.Failure.NETWORK -> R.string.userscript_failed_network
        UserScripts.Failure.INVALID -> R.string.userscript_failed_invalid
        UserScripts.Failure.TOO_LARGE -> R.string.userscript_failed_large
        UserScripts.Failure.REQUIRE -> R.string.userscript_failed_require
    })

    private companion object { const val SHOWN_SITES = 5 }
}
