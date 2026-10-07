package com.v2ray.ang.service

import android.app.Service
import kotlinx.coroutines.sync.Mutex
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.embedded.EmbeddedProxy
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.*
import java.lang.ref.SoftReference

/** The host's only run mode. Setup and teardown are owned by this instance. */
class CoreProxyOnlyService : Service(), ServiceControl {
    companion object { private val ownership = Mutex() }
    @Volatile private var ownsCore = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var setup: Job? = null
    @Volatile private var stopping = false
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping || setup?.isActive == true) return START_NOT_STICKY
        setup = scope.launch {
            try {
                if (!EmbeddedProxy.isEnabled()) { stopSelf(); return@launch }
                if (ownsCore) {
                    if (CoreServiceManager.isRunning()) MessageHelper.sendMsg2UI(this@CoreProxyOnlyService, AppConfig.MSG_STATE_RUNNING, "")
                    return@launch
                }
                ownership.lock(this@CoreProxyOnlyService)
                ownsCore = true
                ensureActive()
                CoreServiceManager.serviceControl = SoftReference(this@CoreProxyOnlyService)
                com.v2ray.ang.handler.SettingsManager.initAssets(this@CoreProxyOnlyService, assets)
                com.v2ray.ang.core.CoreNativeManager.initCoreEnv(this@CoreProxyOnlyService)
                if (!CoreServiceManager.isRunning()) {
                    EmbeddedProxy.allocatePort()
                    if (!CoreServiceManager.startCoreLoop()) { stopSelf(); return@launch }
                } else {
                    MessageHelper.sendMsg2UI(this@CoreProxyOnlyService, AppConfig.MSG_STATE_RUNNING, "")
                }
                ensureActive()
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "Embedded proxy setup failed", e)
                MessageHelper.sendMsg2UI(this@CoreProxyOnlyService, AppConfig.MSG_STATE_START_FAILURE, "")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        stopping = true
        setup?.cancel()
        scope.launch {
            try {
                setup?.join()
                if (ownsCore) {
                    try { CoreServiceManager.stopCoreLoop() }
                    finally {
                        CoreServiceManager.serviceControl = null
                        ownsCore = false
                        ownership.unlock(this@CoreProxyOnlyService)
                    }
                }
            } finally { scope.cancel() }
        }
        super.onDestroy()
    }
    fun requestReload(done: () -> Unit) {
        val job = scope.launch {
            if (!stopping && CoreServiceManager.reloadCore()) {
                MessageHelper.sendMsg2UI(this@CoreProxyOnlyService, AppConfig.MSG_STATE_RUNNING, "")
            }
        }
        job.invokeOnCompletion { done() }
    }
    override fun getService(): Service = this
    override fun stopService() { stopping = true; setup?.cancel(); stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
}
