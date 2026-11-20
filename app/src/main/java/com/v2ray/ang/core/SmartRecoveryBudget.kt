package com.v2ray.ang.core

/** Per daemon session: tolerate transient probes, and cap retries until real traffic succeeds. */
internal class SmartRecoveryBudget {
    companion object {
        const val RELOAD_WAIT_MS = 100L
        const val MAX_RELOAD_WAITS = 50
        fun boundsWarmUp(smart: Boolean, recovery: Boolean): Boolean = smart && recovery
        fun waitsForReload(smart: Boolean, reloading: Boolean, waits: Int): Boolean =
            smart && reloading && waits < MAX_RELOAD_WAITS
    }
    private var failures = 0
    private var attempts = 0
    @Synchronized fun observe(healthy: Boolean): Boolean {
        if (healthy) { failures = 0; attempts = 0; return false }
        failures++
        return failures >= 2
    }
    @Synchronized fun claimAttempt(): Boolean {
        if (attempts >= 3) return false
        attempts++
        failures = 0
        return true
    }
    @Synchronized fun reset() { failures = 0; attempts = 0 }
}
