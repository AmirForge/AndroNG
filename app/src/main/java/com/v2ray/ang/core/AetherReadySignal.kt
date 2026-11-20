package com.v2ray.ang.core

/** Pure gate for delayed daemon readiness callbacks; monitor ownership applies to reloads only. */
internal object AetherReadySignal {
    fun accepts(generation: Long, currentGeneration: Long, reload: Boolean, sameMonitor: Boolean): Boolean =
        generation == currentGeneration && (!reload || sameMonitor)
}
