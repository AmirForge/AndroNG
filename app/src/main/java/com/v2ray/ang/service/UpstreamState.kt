package com.v2ray.ang.service

/** State owned under NetworkMonitor.lock; late loss of an old network cannot clear the new one. */
internal class UpstreamState<T> {
    var network: T? = null
        private set
    var available = false
        private set
    enum class Arrival { NONE, HANDOVER, RECOVERY }
    private var recoveryPending = false
    fun requestRecovery(): Boolean {
        if (!available) recoveryPending = true
        return available
    }
    fun onAvailable(next: T): Arrival {
        val handover = network != null && (network != next || !available)
        network = next
        available = true
        val arrival = if (handover) Arrival.HANDOVER else if (recoveryPending) Arrival.RECOVERY else Arrival.NONE
        recoveryPending = false
        return arrival
    }
    fun onLost(previous: T): Boolean {
        if (network != previous) return false
        available = false
        return true
    }
    fun clear() { network = null; available = false; recoveryPending = false }
}
