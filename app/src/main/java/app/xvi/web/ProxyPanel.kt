package app.xvi.web

import android.app.AlertDialog
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import com.v2ray.ang.api.ProxyPhase
import com.v2ray.ang.api.ProxyState
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
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        addView(enabledSwitch, LinearLayout.LayoutParams(-2, -2))
    }
    private val subscriptionButton = ui.textAction("") { openSubscriptionEditor() }
    private val updateButton = ui.textAction("") {
        operate(R.string.proxy_subscription_updated, subscriptionCommand = true) { host.network.refreshSubscription(it) }
    }
    private val retryButton = ui.textAction(ui.str(R.string.proxy_retry_start)) {
        operate { host.network.retry(it) }
    }
    private val actionRow = ui.row().apply {
        addView(subscriptionButton, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { rightMargin = ui.dp(6) })
        addView(updateButton, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
    }

    private var rendering = false
    private var pending = false
    private var active = false
    private var state: ProxyState? = null
    private var configDialog: AlertDialog? = null
    private var formUrl: EditText? = null
    private var formUpdateInterval: EditText? = null
    private var formTestInterval: EditText? = null
    private var formError: android.widget.TextView? = null

    init {
        body.addView(enabledRow)
        body.addView(runtimeMessage, feedbackParams())
        body.addView(operationError, feedbackParams())
        body.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(4) })
        body.addView(retryButton, LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(2) })
        body.addView(subscriptionMessage, feedbackParams())
        body.addView(connectivityMessage, feedbackParams())
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.onDismiss {
            configDialog?.dismiss()
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
        } finally {
            rendering = false
        }
        enabledStatus.text = ui.str(when (value.phase) {
            ProxyPhase.STARTING -> R.string.proxy_status_starting
            ProxyPhase.RUNNING -> R.string.proxy_status_running
            ProxyPhase.RECONNECTING -> R.string.proxy_status_reconnecting
            ProxyPhase.FAILED -> R.string.proxy_status_failed
            else -> R.string.proxy_status_off
        })
        runtimeMessage.text = ProxyPresentation.message(host, value)
        runtimeMessage.visibility = if (runtimeMessage.text.isBlank()) View.GONE else View.VISIBLE
        renderSubscriptionFeedback()
        renderActions()
    }

    private fun renderSubscriptionFeedback() {
        val subscription = state?.subscription
        if (subscription == null) {
            subscriptionMessage.visibility = View.GONE
            connectivityMessage.visibility = View.GONE
            return
        }
        val error = subscription.getError()
        subscriptionMessage.text = when {
            subscription.isUpdating() -> ui.str(R.string.proxy_subscription_update_status)
            error.isNotBlank() && configDialog == null -> ProxyPresentation.subscriptionError(host, error)
            else -> ""
        }
        subscriptionMessage.visibility = if (subscriptionMessage.text.isBlank()) View.GONE else View.VISIBLE
        subscriptionMessage.setTextColor(if (error.isNotBlank() && !subscription.isUpdating()) palette.danger else palette.muted)
        connectivityMessage.text = when {
            subscription.isTesting() -> ui.str(R.string.proxy_connectivity_testing)
            subscription.getTestError() == "CONNECTIVITY_SLOW" -> ui.str(R.string.proxy_connectivity_slow)
            subscription.getTestError().isNotBlank() -> ui.str(R.string.proxy_connectivity_failed)
            else -> ""
        }
        connectivityMessage.visibility = if (connectivityMessage.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderActions() {
        val value = state
        val subscription = value?.subscription
        val locked = pending || subscription?.isUpdating() == true || subscription?.isTesting() == true
        enabledSwitch.isEnabled = value != null && !pending && subscription?.isUpdating() != true && value.phase != ProxyPhase.STARTING
        subscriptionButton.text = ui.str(if (subscription?.getUrl().orEmpty().isBlank()) R.string.proxy_subscription_add else R.string.proxy_subscription_change)
        subscriptionButton.isEnabled = value != null && !locked
        updateButton.text = ui.str(if (subscription?.isUpdating() == true) R.string.proxy_subscription_updating else R.string.proxy_subscription_update)
        updateButton.isEnabled = value != null && !locked && subscription?.getUrl().orEmpty().isNotBlank()
        retryButton.visibility = if (value?.phase == ProxyPhase.FAILED) View.VISIBLE else View.GONE
        retryButton.isEnabled = value != null && !locked
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
        val updateInterval = ui.edit("").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(subscription.getUpdateIntervalMinutes().toString())
            setSelectAllOnFocus(true)
        }
        val testInterval = ui.edit("").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(subscription.getTestIntervalMinutes().toString())
            setSelectAllOnFocus(true)
        }
        val error = ui.label("", 12f, palette.danger).apply {
            setLineSpacing(0f, 1.25f)
            val message = subscription.getError()
            if (message.isNotBlank()) {
                text = ProxyPresentation.subscriptionError(host, message)
                visibility = View.VISIBLE
            } else visibility = View.GONE
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
        pending = true
        operationError.visibility = View.GONE
        renderActions()
        try {
            host.network.setSubscription(submitted, updateInterval, testInterval, Consumer { value ->
                host.runOnUiThread {
                    if (!active || host.isDestroyed) return@runOnUiThread
                    pending = false
                    render(value)
                    val error = value.subscription.getError()
                    if (error.isBlank()) {
                        dialog.dismiss()
                        host.toast(R.string.proxy_subscription_saved)
                    } else showFormError(ProxyPresentation.subscriptionError(host, error))
                }
            })
        } catch (_: Exception) {
            pending = false
            showFormError(ui.str(R.string.proxy_operation_failed))
            renderActions()
        }
    }

    private fun formField(label: String, input: EditText) = ui.column().apply {
        addView(ui.label(label, 13f, palette.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        addView(input, LinearLayout.LayoutParams(-1, ui.dp(48)))
    }

    private fun feedbackParams() = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = ui.dp(7)
    }

    private fun formHintParams() = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = ui.dp(10)
    }

    private fun showFormError(message: String) {
        formError?.let {
            it.text = message
            it.visibility = View.VISIBLE
        }
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

    private fun operate(
        success: Int? = null,
        subscriptionCommand: Boolean = false,
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
                    val error = if (subscriptionCommand) value.subscription.getError() else ""
                    if (error.isNotBlank()) host.toast(ProxyPresentation.subscriptionError(host, error))
                    else if (success != null) host.toast(success)
                }
            })
        } catch (_: Exception) {
            pending = false
            operationError.text = ui.str(R.string.proxy_operation_failed)
            operationError.visibility = View.VISIBLE
            renderActions()
        }
    }
}
