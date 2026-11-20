package com.v2ray.ang.ui.main

/** Hidden gesture recognition; no subscription, connection or persisted setting is changed. */
internal class GalaxyTapSequence {
    private var last: Long? = null
    private var count = 0
    fun tap(now: Long): Boolean {
        val previous = last
        if (previous == null || now < previous || now - previous > 900_000_000L) count = 0
        last = now
        count++
        if (count < 7) return false
        reset()
        return true
    }
    fun reset() { last = null; count = 0 }
}
