package app.xvi.web

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri

/** Camera, microphone and location prompts for websites. Every grant is for one request only. */
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
        val requested = host.str(when {
            wantsCamera && wantsMicrophone -> R.string.permission_camera_microphone
            wantsCamera -> R.string.permission_camera
            else -> R.string.permission_microphone
        })
        val site = request.origin.host ?: request.origin.toString()
        host.browserDialog(source).setTitle(host.str(R.string.permission_media_title, site, requested))
            .setPositiveButton(R.string.permission_allow_once) { _, _ ->
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
            }.setNegativeButton(R.string.permission_deny) { _, _ -> request.deny(); pending = null }
            .setOnCancelListener { request.deny(); pending = null }.show()
    }

    fun cancel(request: PermissionRequest) {
        if (pending === request) { pending = null; decision = null }
    }

    fun location(origin: String, callback: GeolocationPermissions.Callback, source: BrowserTab) {
        val site = runCatching { origin.toUri().host }.getOrNull() ?: origin
        host.browserDialog(source).setTitle(host.str(R.string.permission_location_title, site))
            .setPositiveButton(R.string.permission_allow_once) { _, _ ->
                askRuntime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, granted(Manifest.permission.ACCESS_COARSE_LOCATION), false)
                }
            }.setNegativeButton(R.string.permission_deny) { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }.show()
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Denies whatever is still waiting; called when the browser closes. */
    fun release() { pending?.deny(); pending = null; decision = null }

    private fun askRuntime(permissions: Array<String>, done: () -> Unit) {
        if (permissions.all(::granted)) done()
        else { decision = done; runtime.launch(permissions) }
    }

    private fun granted(permission: String) = host.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
