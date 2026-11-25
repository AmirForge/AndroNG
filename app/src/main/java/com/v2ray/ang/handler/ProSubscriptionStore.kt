package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.json.JSONObject
import java.io.IOException

/** A single private settings record; subscription rows intentionally carry no URL or credential. */
internal interface ProSubscriptionState {
    fun token(): String?
    fun activate(token: String)
    fun tokenFor(groupId: String): String? = token().takeIf { groupId == AppConfig.PRO_SUBSCRIPTION_ID }
    fun groupFor(token: String): String = AppConfig.PRO_SUBSCRIPTION_ID
    fun activateGroup(groupId: String, token: String) = activate(token)
    fun blockedUntil(): Long
    fun consume(now: Long = System.currentTimeMillis()): Boolean
    fun blockFor(seconds: Long)
}

internal object ProSubscriptionStore : ProSubscriptionState {
    private const val KEY = "andromeda_pro_private_v1"
    private fun read() = JSONObject(MmkvManager.decodeSettingsString(KEY) ?: "{}")
    private fun save(value: JSONObject) { if (!MmkvManager.encodeSettings(KEY, value.toString())) throw IOException("Could not save subscription state") }
    private fun groups(state: JSONObject): JSONObject = state.optJSONObject("groups") ?: JSONObject().apply {
        state.optString("token").takeIf(String::isNotBlank)?.let { put(AppConfig.PRO_SUBSCRIPTION_ID, it) }
    }

    @Synchronized override fun token(): String? = tokenFor(AppConfig.PRO_SUBSCRIPTION_ID)
    @Synchronized override fun activate(token: String) = activateGroup(AppConfig.PRO_SUBSCRIPTION_ID, token)
    @Synchronized override fun tokenFor(groupId: String): String? =
        groups(read()).optString(groupId).takeIf(String::isNotBlank)

    @Synchronized override fun groupFor(token: String): String {
        val saved = groups(read())
        val code = token.substringBefore('.')
        val ids = saved.keys().asSequence().toList()
        return ids.firstOrNull { saved.optString(it).substringBefore('.') == code }
            ?: proGroupId((ids.maxOfOrNull(::proGroupNumber) ?: 0) + 1)
    }

    @Synchronized override fun activateGroup(groupId: String, token: String) {
        val state = read()
        val entries = groups(state).put(groupId, token)
        state.remove("token")
        save(state.put("groups", entries))
    }
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

internal fun proGroupNumber(id: String): Int = when {
    id == AppConfig.PRO_SUBSCRIPTION_ID -> 1
    id.startsWith("${AppConfig.PRO_SUBSCRIPTION_ID}_") ->
        id.substringAfterLast('_').toIntOrNull()?.takeIf { it > 1 } ?: 0
    else -> 0
}

private fun proGroupId(number: Int): String =
    if (number == 1) AppConfig.PRO_SUBSCRIPTION_ID else "${AppConfig.PRO_SUBSCRIPTION_ID}_$number"

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
