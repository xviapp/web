package app.xvi.web

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera, microphone and location prompts for websites. An answer applies once unless the user asks to
 * remember it for the site; private tabs never remember and never use remembered answers.
 */
internal class SitePermissions(private val host: BrowserActivity) {
    private var pending: PermissionRequest? = null
    private var decision: (() -> Unit)? = null
    private val runtime = host.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val done = decision; decision = null; done?.invoke()
    }
    private val notifications = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun media(request: PermissionRequest, source: BrowserTab) {
        pending?.deny(); pending = request
        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMicrophone = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val supported = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
        if (supported.isEmpty() || request.origin.scheme != "https") { request.deny(); pending = null; return }
        val kinds = buildList { if (wantsCamera) add(CAMERA); if (wantsMicrophone) add(MICROPHONE) }
        val origin = BrowserStore.origin(request.origin.toString())
        val grant = {
            val permissions = buildList {
                if (wantsCamera) add(Manifest.permission.CAMERA)
                if (wantsMicrophone) add(Manifest.permission.RECORD_AUDIO)
            }.toTypedArray()
            askRuntime(permissions) {
                if (pending === request) {
                    val allowed = supported.filter { resource ->
                        granted(if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO)
                    }
                    if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
                    pending = null
                }
            }
        }
        recall(source, origin, kinds) { remembered ->
            if (pending !== request) return@recall
            when (remembered) {
                true -> grant()
                false -> { request.deny(); pending = null }
                null -> {
                    val requested = host.str(when {
                        wantsCamera && wantsMicrophone -> R.string.permission_camera_microphone
                        wantsCamera -> R.string.permission_camera
                        else -> R.string.permission_microphone
                    })
                    val site = request.origin.host ?: request.origin.toString()
                    ask(source, origin, kinds, host.str(R.string.permission_media_title, site, requested),
                        allow = grant, deny = { if (pending === request) { request.deny(); pending = null } })
                }
            }
        }
    }

    fun cancel(request: PermissionRequest) {
        if (pending === request) { pending = null; decision = null }
    }

    fun location(origin: String, callback: GeolocationPermissions.Callback, source: BrowserTab) {
        val site = runCatching { origin.toUri().host }.getOrNull() ?: origin
        val key = BrowserStore.origin(origin)
        val grant = {
            askRuntime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                callback.invoke(origin, granted(Manifest.permission.ACCESS_COARSE_LOCATION), false)
            }
        }
        recall(source, key, listOf(LOCATION)) { remembered ->
            when (remembered) {
                true -> grant()
                false -> callback.invoke(origin, false, false)
                null -> ask(source, key, listOf(LOCATION), host.str(R.string.permission_location_title, site),
                    allow = grant, deny = { callback.invoke(origin, false, false) })
            }
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Asks for the camera before a page's photo or video capture; [done] receives whether it may be used. */
    fun camera(done: (Boolean) -> Unit) = askRuntime(arrayOf(Manifest.permission.CAMERA)) { done(granted(Manifest.permission.CAMERA)) }

    /** Denies whatever is still waiting; called when the browser closes. */
    fun release() { pending?.deny(); pending = null; decision = null }

    /**
     * Remembered answer covering every kind: true when all are allowed, false when any is denied, null
     * when the user has to be asked.
     */
    private fun recall(source: BrowserTab, origin: String?, kinds: List<String>, done: (Boolean?) -> Unit) {
        if (source.isPrivate || origin == null) { done(null); return }
        host.lifecycleScope.launch {
            val answers = withContext(host.store.io) { kinds.map { host.store.permission(origin, it) } }
            if (source !in host.tabs) return@launch
            done(when {
                answers.any { it == false } -> false
                answers.all { it == true } -> true
                else -> null
            })
        }
    }

    private fun ask(source: BrowserTab, origin: String?, kinds: List<String>, title: String, allow: () -> Unit, deny: () -> Unit) {
        val remember = CheckBox(host).apply {
            text = host.str(R.string.permission_remember)
            setTextColor(host.ui.palette.ink)
        }
        val canRemember = !source.isPrivate && origin != null
        val content = host.ui.column().apply {
            setPadding(host.ui.dp(20), host.ui.dp(4), host.ui.dp(20), 0)
            addView(remember, host.ui.wide())
        }
        fun store(allowed: Boolean) {
            if (canRemember && remember.isChecked) host.store.writeAsync { kinds.forEach { setPermission(origin, it, allowed) } }
        }
        val builder = host.browserDialog(source).setTitle(title)
            .setPositiveButton(R.string.permission_allow) { _, _ -> store(true); allow() }
            .setNegativeButton(R.string.permission_deny) { _, _ -> store(false); deny() }
            .setOnCancelListener { deny() }
        if (canRemember) builder.setView(content)
        builder.show()
    }

    private fun askRuntime(permissions: Array<String>, done: () -> Unit) {
        if (permissions.all(::granted)) done()
        else { decision = done; runtime.launch(permissions) }
    }

    private fun granted(permission: String) = host.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CAMERA = "camera"
        const val MICROPHONE = "microphone"
        const val LOCATION = "location"
        const val POPUP = "popup"
    }
}
