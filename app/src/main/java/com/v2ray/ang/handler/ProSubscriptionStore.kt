package com.v2ray.ang.handler

import org.json.JSONObject
import java.io.IOException

/** A single private settings record; subscription rows intentionally carry no URL or credential. */
internal interface ProSubscriptionState {
    fun token(): String?
    fun activate(token: String)
    fun blockedUntil(): Long
    fun consume(now: Long = System.currentTimeMillis()): Boolean
    fun blockFor(seconds: Long)
}

internal object ProSubscriptionStore : ProSubscriptionState {
    private const val KEY = "andromeda_pro_private_v1"
    private fun read() = JSONObject(MmkvManager.decodeSettingsString(KEY) ?: "{}")
    private fun save(value: JSONObject) { if (!MmkvManager.encodeSettings(KEY, value.toString())) throw IOException("Could not save subscription state") }
    @Synchronized override fun token(): String? = read().optString("token").takeIf(String::isNotBlank)
    @Synchronized override fun activate(token: String) { save(read().put("token", token)) }
    @Synchronized override fun blockedUntil(): Long = read().optLong("blockedUntil")
    @Synchronized override fun consume(now: Long): Boolean {
        val state = read()
        val next = CodeAttemptPolicy.consume(state.optInt("attempts"), state.optLong("window"), state.optLong("blockedUntil"), now) ?: return false
        save(state.put("attempts", next.attempts).put("window", next.window).put("blockedUntil", next.blockedUntil))
        return true
    }
    @Synchronized override fun blockFor(seconds: Long) {
        if (seconds <= 0) return
        val state = read()
        save(state.put("blockedUntil", maxOf(state.optLong("blockedUntil"), System.currentTimeMillis() + seconds.coerceAtMost(3600) * 1000)))
    }
}

internal data class CodeAttempts(val attempts: Int, val window: Long, val blockedUntil: Long)
internal object CodeAttemptPolicy {
    private const val HOUR = 3_600_000L
    fun consume(attempts: Int, window: Long, blockedUntil: Long, now: Long): CodeAttempts? {
        if (blockedUntil > now) return null
        val reset = blockedUntil > 0 || now - window >= HOUR || now < window
        val count = if (reset) 1 else attempts + 1
        return CodeAttempts(count, if (reset || attempts == 0) now else window, if (count >= 5) now + HOUR else 0)
    }
}
