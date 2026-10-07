package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked on a background thread after the debounce window and may block.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onHandover: () -> Unit,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    private var upstream: Network? = null
    private var handoverJob: Job? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = upstream
            upstream = network
            if (previous != null && previous != network) {
                scheduleHandover(network)
            }
        }

    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        if (registered) return
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        handoverJob?.cancel()
        handoverJob = null
        upstream = null
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        handoverJob?.cancel()
        handoverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                onHandover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}
