package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.embedded.EmbeddedProxy
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.util.LogUtil

object LauncherManager {
    fun startService(context: Context, guid: String? = null) {
        if (guid != null) MmkvManager.setSelectServer(guid)
        startEmbedded(context)
    }
    @JvmStatic fun startEmbedded(context: Context): Boolean {
        EmbeddedProxy.setEnabled(true)
        return try {
            check(EmbeddedProxy.hasProfile()) { "No profile selected" }
            MessageHelper.sendMsg2UI(context, com.v2ray.ang.embedded.EmbeddedProxy.STATE_RELOADING, "")
            // The daemon allocates the port once; repeated starts must not invalidate an active listener.
            // Keep the embedded proxy out of the status bar. Android may reclaim this normal
            // background service while the app is hidden; the host probes and restarts it when
            // the browser returns to the foreground.
            context.startService(Intent(context, CoreProxyOnlyService::class.java))
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Embedded proxy start failed", e)
            MessageHelper.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE, "")
            false
        }
    }
    fun stopService(context: Context) {
        EmbeddedProxy.setEnabled(false)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
        MessageHelper.sendMsg2UI(context, AppConfig.MSG_STATE_NOT_RUNNING, "")
    }
    fun restartService(context: Context) { MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "") }

}
