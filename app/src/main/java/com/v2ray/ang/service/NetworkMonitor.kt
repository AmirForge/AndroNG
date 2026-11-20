package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * After the debounce window, on a background thread, a handover is first claimed with [claimHandover]
 * while this monitor is registered, under the gate that [unregister] closes first: it has to be short,
 * and once unregister() has returned no handover is claimed. Only a claimed handover goes on to
 * [onHandover], which may block. Both are passed this monitor. [describe] names the run mode and the
 * profile of the service it watches, for its failure logs.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val claimHandover: (NetworkMonitor) -> Boolean,
    private val onHandover: (NetworkMonitor) -> Unit,
    private val describe: () -> String,
    private val onRecovery: (NetworkMonitor) -> Unit = onHandover,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    // v2meda: the network callbacks run on a thread of ConnectivityManager, register and unregister on the main
    // thread, so the state below is read and written under this lock.
    private val lock = Any()
    private val upstream = UpstreamState<Network>()
    val hasUpstream: Boolean get() = synchronized(lock) { registered && upstream.available }
    private var handoverJob: Job? = null
    private var registered = false

    /** v2meda: lets a handover be claimed from register() until unregister(), see [claimHandover]. */
    private val gate = HandoverGate()

    /** v2meda: owns the handover work, which [unregister] cancels. */
    private val handoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(lock) {
            if (!registered) return@synchronized
            val arrival = upstream.onAvailable(network)
            onUnderlyingNetworksChanged(arrayOf(network))
            if (arrival != UpstreamState.Arrival.NONE) scheduleHandover(network, recovery = arrival == UpstreamState.Arrival.RECOVERY)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = synchronized(lock) {
            if (registered && upstream.available && upstream.network == network) onUnderlyingNetworksChanged(arrayOf(network))
        }

        override fun onLost(network: Network) = synchronized(lock) {
            if (!registered || !upstream.onLost(network)) return@synchronized
            onUnderlyingNetworksChanged(null)
        }
    }

    /** Uses this monitor's cancellation gate; offline recovery waits for the next onAvailable. */
    fun requestRecovery(): Boolean = synchronized(lock) {
        if (!registered) return@synchronized false
        if (upstream.requestRecovery()) scheduleHandover(upstream.network ?: return@synchronized false, recovery = true)
        true
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        synchronized(lock) {
            if (registered) return
            try {
                connectivity.requestNetwork(request, callback)
                registered = true
                gate.open()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: ${describe()}, register: requesting the network failed", e)
            }
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once. No handover is claimed once
     * it has returned: it closes the gate first, waiting for a claim under way, see [claimHandover].
     */
    fun unregister() {
        synchronized(lock) {
            // v2meda: closed under the lock it was opened under; a claim never takes this lock, so the wait is short.
            gate.close()
            handoverJob?.cancel()
            handoverJob = null
            handoverScope.coroutineContext.cancelChildren()
            upstream.clear()
            if (!registered) return
            registered = false
        }
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: ${describe()}, unregister: unregistering the callback failed", e)
        }
    }

    private fun scheduleHandover(network: Network, recovery: Boolean = false) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        synchronized(lock) {
            // v2meda: a network callback that arrives after unregister() schedules nothing.
            if (!registered) return
            handoverJob?.cancel()
            handoverJob = handoverScope.launch {
                try {
                    delay(HANDOVER_DEBOUNCE_MS)
                    // v2meda: cancelling ends the wait above, not a handover that has just come out of it. The handover
                    // goes on only once claimed under the gate, which unregister() closes first: an unregistered monitor
                    // claims nothing and calls nothing.
                    gate.runClaimed({ claimHandover(this@NetworkMonitor) }) {
                        if (recovery) onRecovery(this@NetworkMonitor) else onHandover(this@NetworkMonitor)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "NetworkMonitor: ${describe()}, handover: handling the upstream change failed", e)
                }
            }
        }
    }
}
