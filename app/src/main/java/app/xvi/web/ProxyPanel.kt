package app.xvi.web

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import java.text.DateFormat
import java.util.Date
import java.util.function.Consumer

/** A quiet settings surface for the external proxy: switch, address, VPN choice and its state. */
internal class ProxyPanel(private val host: BrowserActivity, back: (() -> Unit)? = null) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(R.string.proxy_title).apply { back?.let(::backTo) }
    private val body = ui.column()
    private val enabledStatus = ui.label("", 12f, palette.muted)
    private val reason = ui.label("", 12f, palette.caution)
    private val operationError = ui.label("", 12f, palette.danger)
    private val enabledSwitch = SwitchCompat(host).apply {
        contentDescription = ui.str(R.string.proxy_app)
        showText = false
        setOnCheckedChangeListener { _, checked ->
            if (!rendering) operate { host.network.setEnabled(checked, it) }
        }
    }
    private val enabledRow = ui.row().apply {
        minimumHeight = ui.dp(68)
        setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(8))
        val copy = ui.column().apply {
            addView(ui.medium(ui.label(ui.str(R.string.proxy_app), 15f)))
            addView(enabledStatus)
        }
        addView(copy, ui.weighted())
        addView(enabledSwitch, ui.sized(ui.wrap(), ui.wrap()))
    }
    private val url = ui.edit(ui.str(R.string.proxy_url_hint)).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) save() } }
    }
    private val saveButton = ui.textAction(ui.str(R.string.proxy_url_save)) { save() }
    private val checkButton = ui.textAction(ui.str(R.string.proxy_check)) { operate { host.network.check(true, it) } }
    private val actionRow = ui.row().apply {
        addView(saveButton, ui.sized(0, ui.dp(46), 1f).apply { rightMargin = ui.dp(6) })
        addView(checkButton, ui.sized(0, ui.dp(46), 1f))
    }
    private val vpnStatus = ui.label("", 12f, palette.muted)
    private val vpnSwitch = SwitchCompat(host).apply {
        contentDescription = ui.str(R.string.proxy_bypass_vpn)
        showText = false
        setOnCheckedChangeListener { _, checked ->
            if (!rendering) operate { host.network.setBypassOnVpn(checked, it) }
        }
    }
    private val vpnRow = ui.row().apply {
        minimumHeight = ui.dp(60)
        setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(8))
        val copy = ui.column().apply {
            addView(ui.medium(ui.label(ui.str(R.string.proxy_bypass_vpn), 15f)))
            addView(vpnStatus)
        }
        addView(copy, ui.weighted())
        addView(vpnSwitch, ui.sized(ui.wrap(), ui.wrap()))
        setOnClickListener { if (vpnSwitch.isEnabled) vpnSwitch.toggle() }
    }

    private var rendering = false
    private var pending = false
    private var active = false
    private var state: BrowserProxyNetwork.Status? = null

    init {
        body.addView(enabledRow)
        body.addView(vpnRow)
        body.addView(ui.label(ui.str(R.string.proxy_url), 13f, palette.muted).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(4)) })
        body.addView(url, ui.bar(48))
        body.addView(ui.label(ui.str(R.string.proxy_url_help), 12f, palette.muted).apply { setPadding(ui.dp(4), 0, 0, 0) }, feedbackParams())
        body.addView(actionRow, ui.wide().apply { topMargin = ui.dp(10); bottomMargin = ui.dp(4) })
        body.addView(reason, feedbackParams())
        body.addView(operationError, feedbackParams())
        ui.onTextChanged(url) { renderActions() }
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        sheet.onDismiss {
            active = false
            if (host.proxyPanel === this) host.proxyPanel = null
        }
        renderActions()
    }

    fun show() {
        if (active) return
        host.proxyPanel?.dismiss()
        host.proxyPanel = this
        active = true
        sheet.show()
        host.network.status { value -> if (active) render(value) }
    }

    fun dismiss() = sheet.dismiss()

    fun render(value: BrowserProxyNetwork.Status) {
        if (!active) return
        val saved = state?.url
        state = value
        rendering = true
        try {
            enabledSwitch.isChecked = value.enabled
            vpnSwitch.isChecked = value.bypassOnVpn
            // A saved address comes back normalized; other updates leave what is being typed.
            if (saved == null || saved != value.url) url.setText(value.url)
        } finally {
            rendering = false
        }
        enabledStatus.text = when (value.route) {
            BrowserProxyNetwork.Route.OFF -> ui.str(if (value.enabled) R.string.proxy_status_no_url else R.string.proxy_status_off)
            BrowserProxyNetwork.Route.VPN -> ui.str(R.string.proxy_status_vpn)
            BrowserProxyNetwork.Route.CHECKING -> ui.str(R.string.proxy_status_checking)
            BrowserProxyNetwork.Route.PROXY -> ui.str(R.string.proxy_status_running, value.url.substringAfter("://"))
            BrowserProxyNetwork.Route.DIRECT -> ui.str(R.string.proxy_status_direct)
        }
        vpnStatus.text = ui.str(if (value.route == BrowserProxyNetwork.Route.VPN) R.string.proxy_status_vpn else R.string.proxy_bypass_vpn_subtitle)
        reason.showText(if (value.route != BrowserProxyNetwork.Route.DIRECT) "" else {
            val time = if (value.checkedAt > 0) ui.str(R.string.proxy_checked_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value.checkedAt))) else ""
            probeError(value.error) + time
        })
        renderActions()
    }

    private fun probeError(code: String) = when (code) {
        "refused" -> ui.str(R.string.proxy_error_refused)
        "timeout" -> ui.str(R.string.proxy_error_timeout)
        "dns" -> ui.str(R.string.proxy_error_dns)
        "auth" -> ui.str(R.string.proxy_error_auth)
        "protocol" -> ui.str(R.string.proxy_error_protocol)
        else -> ""
    }

    private fun renderActions() {
        val value = state
        val typed = url.text.toString().trim()
        enabledSwitch.isEnabled = value != null && !pending && (value.enabled || value.url.isNotEmpty())
        url.isEnabled = !pending
        saveButton.isEnabled = value != null && !pending && typed != value.url
        checkButton.isVisible = value != null && value.enabled && value.url.isNotEmpty() && value.route != BrowserProxyNetwork.Route.VPN
        checkButton.isEnabled = !pending && value?.route != BrowserProxyNetwork.Route.CHECKING
        vpnSwitch.isEnabled = value != null && !pending && value.enabled
    }

    private fun save() {
        val value = state ?: return
        val typed = url.text.toString().trim()
        if (pending || typed == value.url) return
        operate(success = R.string.proxy_url_saved) { done ->
            try {
                host.network.setUrl(typed, done)
            } catch (invalid: ProxyAddress.Invalid) {
                operationError.showText(ui.str(when (invalid.message) {
                    "SCHEME" -> R.string.proxy_url_scheme
                    "AUTH" -> R.string.proxy_url_auth
                    "PORT" -> R.string.proxy_url_port
                    else -> R.string.proxy_url_invalid
                }))
                pending = false
                renderActions()
            }
        }
    }

    /** Shows [text], or hides the label while there is none. */
    private fun TextView.showText(text: CharSequence) {
        this.text = text
        isVisible = text.isNotBlank()
    }

    private fun feedbackParams() = ui.wide().apply { topMargin = ui.dp(7) }

    /** Runs one proxy command with the controls locked until it answers; [success] is toasted. */
    private fun operate(success: Int? = null, operation: (Consumer<BrowserProxyNetwork.Status>) -> Unit) {
        if (pending) return
        pending = true
        operationError.visibility = android.view.View.GONE
        renderActions()
        try {
            operation(Consumer { value ->
                if (!active || host.isDestroyed) return@Consumer
                pending = false
                render(value)
                if (success != null) host.toast(success)
            })
        } catch (_: Exception) {
            pending = false
            operationError.showText(ui.str(R.string.proxy_operation_failed))
            renderActions()
        }
    }
}
