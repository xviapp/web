package app.xvi.web

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.v2ray.ang.api.ProxyPhase
import com.v2ray.ang.api.ProxyState
import java.text.DateFormat
import java.util.Date
import java.util.function.Consumer

/** A single mounted form, fed by the activity's unified proxy observation. */
internal class ProxyPanel(private val host: BrowserActivity) {
    private val ui = host.ui
    private val sheet = host.sheet(R.string.proxy_title)
    private val body = ui.column()
    private val stateLabel = ui.label("", 17f)
    private val message = ui.label("", 13f)
    private val url = ui.edit(ui.str(R.string.proxy_subscription_url_hint)).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
    }
    private val draft = ProxyUrlDraft()
    private var rendering = false
    private var pending = false
    private var active = false
    private var state: ProxyState? = null
    private val detail = ui.label("", 12f)
    private val current = ui.label("", 14f)
    private val enabled = ui.textAction(ui.str(R.string.proxy_enable), true) {
        val value = state ?: return@textAction
        operate { host.network.setEnabled(!value.enabled, it) }
    }
    private val retry = ui.textAction(ui.str(R.string.proxy_reconnect)) {
        operate { host.network.retry(it) }
    }
    private val save = ui.textAction(ui.str(R.string.proxy_subscription_save), true) {
        val submitted = draft.value.trim()
        operate(R.string.proxy_subscription_saved, submitted) { host.network.setSubscription(submitted, it) }
    }
    private val update = ui.textAction(ui.str(R.string.proxy_subscription_update)) {
        operate(R.string.proxy_subscription_updated) { host.network.refreshSubscription(it) }
    }
    private val next = ui.textAction(ui.str(R.string.proxy_node_next)) {
        operate(subscriptionCommand = true) { host.network.selectNextNode(it) }
    }

    init {
        body.addView(stateLabel)
        body.addView(message)
        body.addView(enabled)
        body.addView(retry)
        body.addView(ui.sectionTitle(ui.str(R.string.proxy_section_subscription)))
        body.addView(ui.label(ui.str(R.string.proxy_subscription_url), 12f, ui.palette.muted))
        body.addView(url, LinearLayout.LayoutParams(-1, ui.dp(48)))
        body.addView(ui.label(ui.str(R.string.proxy_subscription_daily), 12f, ui.palette.muted))
        body.addView(ui.row().apply {
            addView(save, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            addView(update, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        })
        body.addView(detail)
        body.addView(ui.sectionTitle(ui.str(R.string.proxy_nodes)))
        body.addView(current)
        body.addView(next)
        body.addView(ui.label(ui.str(R.string.proxy_subscription_direct), 12f, ui.palette.muted))
        sheet.add(ScrollView(host).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ui.onTextChanged(url) {
            if (!rendering) {
                draft.edit(it)
                renderActions()
            }
        }
        sheet.onDismiss {
            active = false
            if (host.proxyPanel === this) host.proxyPanel = null
        }
        renderActions()
    }

    fun show() {
        if (active) return
        host.proxyPanel?.sheet?.dismiss()
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
        val subscription = value.subscription
        draft.sync(subscription.getUrl())
        rendering = true
        try {
            if (url.text.toString() != draft.value) url.setText(draft.value)
        } finally {
            rendering = false
        }
        stateLabel.text = ui.str(when (value.phase) {
            ProxyPhase.RUNNING -> R.string.proxy_running
            ProxyPhase.STARTING -> R.string.proxy_starting
            ProxyPhase.RECONNECTING -> R.string.proxy_reconnecting_label
            ProxyPhase.FAILED -> R.string.proxy_failed
            else -> R.string.proxy_off
        })
        stateLabel.setTextColor(ui.palette.proxy(value.phase))
        message.text = ProxyPresentation.message(host, value)
        message.visibility = if (message.text.isBlank()) View.GONE else View.VISIBLE
        message.setTextColor(ui.palette.proxy(value.phase))
        val error = subscription.getError()
        detail.text = when {
            error.isNotBlank() -> ProxyPresentation.subscriptionError(host, error)
            subscription.isUpdating() -> ui.str(R.string.proxy_subscription_testing)
            subscription.getLastUpdated() > 0 -> ui.str(R.string.proxy_subscription_last_updated,
                DateFormat.getDateTimeInstance().format(Date(subscription.getLastUpdated())))
            else -> ""
        }
        detail.setTextColor(if (error.isNotBlank()) ui.palette.danger else ui.palette.muted)
        val node = subscription.getNodes().firstOrNull { it.isSelected() }
        current.text = if (node == null) ui.str(R.string.proxy_node_needed) else {
            val delay = when {
                node.getDelayMillis() < 0 -> ui.str(R.string.proxy_node_unavailable)
                node.getDelayMillis() == 0L -> ui.str(R.string.proxy_node_untested)
                else -> ui.str(R.string.proxy_node_delay, node.getDelayMillis())
            }
            ui.str(R.string.proxy_node_current, node.getName().ifBlank { node.getType() }) + " · " + delay
        }
        renderActions()
    }

    private fun renderActions() {
        val value = state
        val busy = pending || value?.subscription?.isUpdating() == true
        enabled.text = ui.str(if (value?.enabled == true) R.string.proxy_disable else R.string.proxy_enable)
        enabled.isEnabled = value != null && !pending
        retry.visibility = if (value?.enabled == true || value?.phase == ProxyPhase.FAILED) View.VISIBLE else View.GONE
        retry.isEnabled = !pending
        save.isEnabled = value != null && !busy && draft.dirty
        update.text = ui.str(if (value?.subscription?.isUpdating() == true) R.string.proxy_subscription_updating else R.string.proxy_subscription_update)
        update.isEnabled = value != null && !busy && !draft.dirty && value.subscription.getUrl().isNotBlank()
        next.isEnabled = value != null && !busy && value.subscription.getNodes().size > 1
    }

    private fun operate(success: Int? = null, submitted: String? = null,
                        subscriptionCommand: Boolean = success != null,
                        operation: (Consumer<ProxyState>) -> Unit) {
        if (pending) return
        pending = true
        if (submitted != null) draft.beginSave()
        renderActions()
        operation(Consumer { value -> host.runOnUiThread {
            if (!active || host.isDestroyed) return@runOnUiThread
            pending = false
            val error = if (subscriptionCommand) value.subscription.getError() else ""
            if (submitted != null) {
                if (error.isBlank()) draft.saved(submitted, value.subscription.getUrl())
                else draft.failed()
            }
            render(value)
            if (error.isNotBlank()) host.toast(ProxyPresentation.subscriptionError(host, error))
            else if (success != null) host.toast(success)
        } })
    }
}
