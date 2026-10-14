package app.xvi.web

import android.app.AlertDialog
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.v2ray.ang.api.ProxyPhase
import com.v2ray.ang.api.ProxyState
import java.util.Locale
import java.util.function.Consumer

/** A quiet settings surface for the single embedded subscription. */
internal class ProxyPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val palette = ui.palette
    private val sheet = host.sheet(R.string.proxy_title)
    private val body = ui.column()
    private val enabledStatus = ui.label("", 12f, palette.muted)
    private val runtimeMessage = ui.label("", 12f, palette.muted)
    private val operationError = ui.label("", 12f, palette.danger)
    private val subscriptionMessage = ui.label("", 12f, palette.muted)
    private val connectivityMessage = ui.label("", 12f, palette.muted)
    private val nodesTitle = ui.sectionTitle(ui.str(R.string.proxy_nodes))
    private val fallbackMessage = ui.label("", 12f, palette.caution)
    private val nodeSelector = ui.textAction("") { openNodeMenu() }
    private var nodeMenu: ListPopupWindow? = null
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
    private val vpnStatus = ui.label("", 12f, palette.muted)
    private val vpnSwitch = SwitchCompat(host).apply {
        contentDescription = ui.str(R.string.proxy_bypass_vpn)
        showText = false
        setOnCheckedChangeListener { _, checked ->
            if (!rendering) host.network.setBypassOnVpn(checked)
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
        setOnClickListener { vpnSwitch.toggle() }
    }
    private val holdSwitch = SwitchCompat(host).apply {
        contentDescription = ui.str(R.string.proxy_hold_on_failure)
        showText = false
        setOnCheckedChangeListener { _, checked ->
            if (!rendering) host.network.setHoldOnFailure(checked)
        }
    }
    private val holdRow = ui.row().apply {
        minimumHeight = ui.dp(60)
        setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(8))
        val copy = ui.column().apply {
            addView(ui.medium(ui.label(ui.str(R.string.proxy_hold_on_failure), 15f)))
            addView(ui.label(ui.str(R.string.proxy_hold_on_failure_subtitle), 12f, palette.muted))
        }
        addView(copy, ui.weighted())
        addView(holdSwitch, ui.sized(ui.wrap(), ui.wrap()))
        setOnClickListener { holdSwitch.toggle() }
    }
    private val subscriptionButton = ui.textAction("") { openSubscriptionEditor() }
    private val updateButton = ui.textAction("") {
        operate(R.string.proxy_subscription_updated, subscriptionCommand = true) { host.network.refreshSubscription(it) }
    }
    private val retryButton = ui.textAction(ui.str(R.string.proxy_retry_start)) {
        operate { host.network.retry(it) }
    }
    private val actionRow = ui.row().apply {
        addView(subscriptionButton, ui.sized(0, ui.dp(46), 1f).apply { rightMargin = ui.dp(6) })
        addView(updateButton, ui.sized(0, ui.dp(46), 1f))
    }

    private var rendering = false
    private var pending = false
    private var active = false
    private var state: ProxyState? = null
    private var configDialog: AlertDialog? = null
    private var formUrl: EditText? = null
    private var formUpdateInterval: EditText? = null
    private var formTestInterval: EditText? = null
    private var formError: TextView? = null
    private var renderedNodes: List<Any>? = null

    init {
        body.addView(enabledRow)
        body.addView(vpnRow)
        body.addView(holdRow)
        body.addView(runtimeMessage, feedbackParams())
        body.addView(operationError, feedbackParams())
        body.addView(retryButton, ui.bar(46).apply { topMargin = ui.dp(8) })
        body.addView(nodesTitle, ui.wide().apply { topMargin = ui.dp(10) })
        body.addView(fallbackMessage, ui.wide().apply { leftMargin = ui.dp(4); bottomMargin = ui.dp(4) })
        body.addView(nodeSelector, ui.bar(48))
        body.addView(connectivityMessage, feedbackParams())
        body.addView(ui.sectionTitle(ui.str(R.string.proxy_subscription_title)), ui.wide().apply { topMargin = ui.dp(10) })
        body.addView(actionRow, ui.wide().apply { bottomMargin = ui.dp(4) })
        body.addView(subscriptionMessage, feedbackParams())
        sheet.add(ScrollView(host).apply { addView(body) }, ui.stretch())
        sheet.onDismiss {
            configDialog?.dismiss()
            nodeMenu?.dismiss()
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
        host.proxyState?.let(::render)
        sheet.show()
        host.network.status { value -> host.runOnUiThread { if (active) render(value) } }
    }

    fun dismiss() = sheet.dismiss()

    fun render(value: ProxyState) {
        if (!active) return
        state = value
        rendering = true
        try {
            enabledSwitch.isChecked = value.enabled
            vpnSwitch.isChecked = host.network.bypassOnVpn()
            holdSwitch.isChecked = host.network.holdOnFailure()
        } finally {
            rendering = false
        }
        enabledStatus.text = ui.str(when (value.phase) {
            ProxyPhase.STARTING -> R.string.proxy_status_starting
            ProxyPhase.RUNNING -> R.string.proxy_status_running
            ProxyPhase.RECONNECTING -> R.string.proxy_status_reconnecting
            ProxyPhase.FAILED -> if (host.network.holdOnFailure()) R.string.proxy_status_failed_held else R.string.proxy_status_failed
            else -> R.string.proxy_status_off
        })
        val bypassed = value.enabled && host.network.bypassing()
        if (bypassed) enabledStatus.text = ui.str(R.string.proxy_status_vpn_bypassed)
        vpnStatus.text = ui.str(if (bypassed) R.string.proxy_bypass_vpn_active else R.string.proxy_bypass_vpn_subtitle)
        runtimeMessage.showText(if (bypassed) "" else ProxyPresentation.message(host, value))
        renderSubscriptionFeedback()
        renderActions()
    }

    private fun renderNodes(locked: Boolean) {
        val subscription = state?.subscription
        val nodes = subscription?.getNodes().orEmpty()
        nodesTitle.visibility = if (nodes.isEmpty()) View.GONE else View.VISIBLE
        val inUse = nodes.firstOrNull { it.isSelected() }
        fallbackMessage.showText(if (subscription?.isFallback() == true && inUse != null) ui.str(R.string.proxy_node_fallback, inUse.getName()) else "")
        val manualIndex = subscription?.getManualIndex() ?: -1
        val key = listOf(manualIndex, locked) + nodes.map { listOf(it.getId(), it.getName()) }
        if (key != renderedNodes) {
            nodeMenu?.dismiss()
            renderedNodes = key
        }
        nodeSelector.visibility = if (nodes.isEmpty()) View.GONE else View.VISIBLE
        nodeSelector.isEnabled = !locked
        val name = nodes.getOrNull(manualIndex)?.getName() ?: ui.str(R.string.proxy_node_auto)
        nodeSelector.text = ui.str(R.string.proxy_node_choice, name)
        nodeSelector.contentDescription = ui.str(R.string.proxy_node_description, name)
    }

    private fun openNodeMenu() {
        if (!active || !nodeSelector.isEnabled) return
        val subscription = state?.subscription ?: return
        val names = listOf(ui.str(R.string.proxy_node_auto)) + subscription.getNodes().map { it.getName() }
        val current = subscription.getManualIndex() + 1
        nodeMenu = ui.popupList(nodeSelector, names, nodeSelector.width, selected = current) { position ->
            if (!active || !nodeSelector.isEnabled || position == current) return@popupList
            operate(subscriptionCommand = true) {
                if (position == 0) host.network.selectAutomatically(it)
                else host.network.selectNode(position - 1, it)
            }
        }.apply { setOnDismissListener { nodeMenu = null } }
    }

    private fun renderSubscriptionFeedback() {
        val subscription = state?.subscription
        if (subscription == null) {
            subscriptionMessage.visibility = View.GONE
            connectivityMessage.visibility = View.GONE
            return
        }
        val error = subscription.getError()
        subscriptionMessage.showText(when {
            subscription.isUpdating() -> ui.str(R.string.proxy_subscription_update_status)
            error.isNotBlank() && configDialog == null -> ProxyPresentation.subscriptionError(host, error)
            else -> ""
        })
        subscriptionMessage.setTextColor(if (error.isNotBlank() && !subscription.isUpdating()) palette.danger else palette.muted)
        connectivityMessage.showText(when {
            subscription.isTesting() -> ui.str(R.string.proxy_connectivity_testing)
            subscription.getTestError() == "CONNECTIVITY_SLOW" -> ui.str(R.string.proxy_connectivity_slow)
            subscription.getTestError().isNotBlank() -> ui.str(R.string.proxy_connectivity_failed)
            else -> ""
        })
    }

    private fun renderActions() {
        val value = state
        val subscription = value?.subscription
        val locked = isSubscriptionLocked()
        enabledSwitch.isEnabled = value != null && !pending && subscription?.isUpdating() != true && value.phase != ProxyPhase.STARTING
        subscriptionButton.text = ui.str(if (subscription?.getUrl().orEmpty().isBlank()) R.string.proxy_subscription_add else R.string.proxy_subscription_change)
        subscriptionButton.isEnabled = value != null && !locked
        updateButton.text = ui.str(if (subscription?.isUpdating() == true) R.string.proxy_subscription_updating else R.string.proxy_subscription_update)
        updateButton.isEnabled = value != null && !locked && subscription?.getUrl().orEmpty().isNotBlank()
        retryButton.visibility = if (value?.phase == ProxyPhase.FAILED) View.VISIBLE else View.GONE
        retryButton.isEnabled = value != null && !locked
        renderNodes(locked || value?.phase == ProxyPhase.STARTING)
        renderConfigDialog()
    }

    private fun openSubscriptionEditor() {
        val value = state ?: return
        if (isSubscriptionLocked()) return
        operationError.visibility = View.GONE
        val subscription = value.subscription
        val url = ui.edit(ui.str(R.string.proxy_subscription_url_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(subscription.getUrl())
            setSelectAllOnFocus(true)
        }
        val updateInterval = minutesField(subscription.getUpdateIntervalMinutes())
        val testInterval = minutesField(subscription.getTestIntervalMinutes())
        val error = ui.label("", 12f, palette.danger).apply {
            setLineSpacing(0f, 1.25f)
            val message = subscription.getError()
            showText(if (message.isNotBlank()) ProxyPresentation.subscriptionError(host, message) else "")
        }
        val form = ui.column().apply {
            setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(4))
            addView(formField(ui.str(R.string.proxy_subscription_url), url))
            addView(formField(ui.str(R.string.proxy_subscription_update_interval), updateInterval))
            addView(formField(ui.str(R.string.proxy_subscription_test_interval), testInterval))
            addView(error, formHintParams())
        }
        val dialog = host.browserDialog()
            .setTitle(if (subscription.getUrl().isBlank()) R.string.proxy_subscription_add else R.string.proxy_subscription_change)
            .setView(ScrollView(host).apply { addView(form) })
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.proxy_subscription_save, null)
            .create()
        configDialog = dialog
        formUrl = url
        formUpdateInterval = updateInterval
        formTestInterval = testInterval
        formError = error
        ui.onTextChanged(url) { renderConfigDialog() }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && isSubscriptionLocked()
        }
        dialog.setOnDismissListener {
            if (configDialog === dialog) {
                configDialog = null
                formUrl = null
                formUpdateInterval = null
                formTestInterval = null
                formError = null
                renderSubscriptionFeedback()
                renderActions()
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveSubscription(dialog)
            }
            renderConfigDialog()
        }
        dialog.show()
    }

    private fun saveSubscription(dialog: AlertDialog) {
        val url = formUrl ?: return
        if (isSubscriptionLocked()) return
        val submitted = url.text.toString().trim()
        val updateInterval = formUpdateInterval?.text?.toString()?.toIntOrNull()
        val testInterval = formTestInterval?.text?.toString()?.toIntOrNull()
        if (submitted.isBlank()) {
            showFormError(ui.str(R.string.proxy_subscription_url_needed))
            return
        }
        if (updateInterval == null || updateInterval < 15 || testInterval == null || testInterval < 1) {
            showFormError(ui.str(R.string.proxy_subscription_invalid_interval))
            return
        }
        operate(failed = { showFormError(ui.str(R.string.proxy_operation_failed)) }, done = { value ->
            val error = value.subscription.getError()
            if (error.isBlank()) {
                dialog.dismiss()
                host.toast(R.string.proxy_subscription_saved)
            } else showFormError(ProxyPresentation.subscriptionError(host, error))
        }) { host.network.setSubscription(submitted, updateInterval, testInterval, it) }
    }

    private fun minutesField(minutes: Int) = ui.edit("").apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(String.format(Locale.ROOT, "%d", minutes))
        setSelectAllOnFocus(true)
    }

    /** Shows [text], or hides the label while there is none. */
    private fun TextView.showText(text: CharSequence) {
        this.text = text
        isVisible = text.isNotBlank()
    }

    private fun formField(label: String, input: EditText) = ui.column().apply {
        addView(ui.label(label, 13f, palette.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        addView(input, ui.bar(48))
    }

    private fun feedbackParams() = ui.wide().apply {
        topMargin = ui.dp(7)
    }

    private fun formHintParams() = ui.wide().apply {
        topMargin = ui.dp(10)
    }

    private fun showFormError(message: String) {
        formError?.showText(message)
        renderConfigDialog()
    }

    private fun renderConfigDialog() {
        val dialog = configDialog ?: return
        val locked = isSubscriptionLocked()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !locked
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = !locked && formUrl?.text?.toString()?.trim()?.isNotEmpty() == true
            text = ui.str(if (pending) R.string.proxy_subscription_saving else R.string.proxy_subscription_save)
        }
        formUrl?.isEnabled = !locked
        formUpdateInterval?.isEnabled = !locked
        formTestInterval?.isEnabled = !locked
    }

    private fun isSubscriptionLocked() = pending || state?.subscription?.isUpdating() == true || state?.subscription?.isTesting() == true

    /**
     * Runs one proxy command with the controls locked until it answers. By default the answer's
     * subscription error, or [success], is toasted; [done] replaces that, and [failed] replaces the
     * panel's own message when the command cannot even start.
     */
    private fun operate(
        success: Int? = null,
        subscriptionCommand: Boolean = false,
        failed: () -> Unit = { operationError.showText(ui.str(R.string.proxy_operation_failed)) },
        done: ((ProxyState) -> Unit)? = null,
        operation: (Consumer<ProxyState>) -> Unit,
    ) {
        if (pending) return
        pending = true
        operationError.visibility = View.GONE
        renderActions()
        try {
            operation(Consumer { value ->
                host.runOnUiThread {
                    if (!active || host.isDestroyed) return@runOnUiThread
                    pending = false
                    render(value)
                    if (done != null) { done(value); return@runOnUiThread }
                    val error = if (subscriptionCommand) value.subscription.getError() else ""
                    if (error.isNotBlank()) host.toast(ProxyPresentation.subscriptionError(host, error))
                    else if (success != null) host.toast(success)
                }
            })
        } catch (_: Exception) {
            pending = false
            failed()
            renderActions()
        }
    }
}
