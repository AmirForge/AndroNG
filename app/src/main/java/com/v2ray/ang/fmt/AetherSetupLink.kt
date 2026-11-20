package com.v2ray.ang.fmt

import com.v2ray.ang.core.AetherExit
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A copied Aether profile that needs local WARP keys and an endpoint on the receiving device. */
internal data class AetherSetupLink(val profile: ProfileItem, val outrole: ProfileItem?) {
    companion object {
        private const val MAX_OUTROLE_PAYLOAD = 128 * 1024
        private const val OUT_RULE_PARAM = "outRule"
        private const val LEGACY_OUT_ROLE_PARAM = "outrole"
        private const val ANDRO_PREFIX = "andro://"
        private const val ANDRO_VERSION: Byte = 1
        private const val KEY_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val HEADER_BYTES = 1 + KEY_BYTES + NONCE_BYTES
        private const val MAX_ANDRO_PAYLOAD = 256 * 1024
        private val setupParams = setOf("setup", OUT_RULE_PARAM, LEGACY_OUT_ROLE_PARAM)
        private val scheme = EConfigType.AETHER.protocolScheme
        private val random = SecureRandom()

        fun isSetupLink(line: String): Boolean =
            line.startsWith(ANDRO_PREFIX) || isAetherSetupLink(line)

        private fun isAetherSetupLink(line: String): Boolean =
            line.startsWith(scheme) && line.substringBefore('#').substringAfter('?', "")
                .split('&').any { it.substringBefore('=') in setupParams }

        fun containsSetupLinks(text: String): Boolean =
            text.lineSequence().any { isSetupLink(it.trim()) }

        fun splitImport(text: String): Pair<List<String>, String> {
            // A literal profile URI or JSON cannot be a Base64 envelope.
            val plain = "://" in text || text.trimStart().startsWith('{') || text.trimStart().startsWith('[')
            val source = if (plain) text else Utils.decode(text)
            val (setup, ordinary) = source.lines().partition { isSetupLink(it.trim()) }
            return setup.map(String::trim).distinct() to
                if (setup.isEmpty()) text else ordinary.joinToString("\n")
        }

        fun requiresWarp(profile: ProfileItem): Boolean =
            AetherPsiphon.fromString(profile.aetherPsiphon) != AetherPsiphon.ONLY &&
                AetherTor.fromString(profile.aetherTor) != AetherTor.ONLY

        fun encode(profile: ProfileItem, outrole: ProfileItem?): String {
            val body = AetherFmt.toUri(profile)
            if (outrole == null && !requiresWarp(profile)) return scheme + body
            val outroleQuery = outrole?.let {
                require(isOutrole(it))
                val snapshot = it.copy(remarks = it.remarks.trim(), subscriptionId = "", addedTime = 0L, description = null)
                val payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(JsonUtil.toJson(snapshot).toByteArray(Charsets.UTF_8))
                require(payload.length <= MAX_OUTROLE_PAYLOAD)
                "&$OUT_RULE_PARAM=$payload"
            }.orEmpty()
            val link = "$scheme${body.substringBefore('#')}&setup=1$outroleQuery#${body.substringAfter('#')}"
            return if (outrole == null) link else encodeAndro(link)
        }

        fun parse(line: String): AetherSetupLink? {
            val wrapped = line.startsWith(ANDRO_PREFIX)
            return try {
                val link = if (wrapped) decodeAndro(line) ?: return null else line
                if (!isAetherSetupLink(link)) return null
                val uri = URI(Utils.fixIllegalUrl(link))
                val query = AetherFmt.getQueryParam(uri)
                if (query["setup"] != "1") return null
                val outrole = (query[OUT_RULE_PARAM] ?: query[LEGACY_OUT_ROLE_PARAM])?.let { payload ->
                    if (payload.isEmpty() || payload.length > MAX_OUTROLE_PAYLOAD) return null
                    val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
                    val profile = JsonUtil.fromJson(json, ProfileItem::class.java) ?: return null
                    if (!isOutrole(profile)) return null
                    profile
                }
                if (wrapped && outrole == null) return null
                AetherSetupLink(AetherFmt.parseProfile(link), outrole)
            } catch (_: Exception) {
                null
            }
        }

        /** The key travels with the link so the recipient can import it without entering a password. */
        private fun encodeAndro(link: String): String {
            val key = ByteArray(KEY_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val encrypted = androCipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(link.toByteArray(Charsets.UTF_8))
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(ANDRO_VERSION) + key + nonce + encrypted)
            require(payload.length <= MAX_ANDRO_PAYLOAD)
            return ANDRO_PREFIX + payload
        }

        private fun decodeAndro(link: String): String? {
            val payload = link.substring(ANDRO_PREFIX.length)
            if (payload.length !in 1..MAX_ANDRO_PAYLOAD) return null
            val bytes = Base64.getUrlDecoder().decode(payload)
            if (bytes.size <= HEADER_BYTES + TAG_BITS / 8 || bytes[0] != ANDRO_VERSION) return null
            val key = bytes.copyOfRange(1, 1 + KEY_BYTES)
            val nonce = bytes.copyOfRange(1 + KEY_BYTES, HEADER_BYTES)
            return androCipher(Cipher.DECRYPT_MODE, key, nonce)
                .doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
                .toString(Charsets.UTF_8)
        }

        private fun androCipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            }

        private fun isOutrole(profile: ProfileItem): Boolean =
            AetherExit.takesAsNode(profile) && profile.aetherEmbeddedOutrole == null &&
                !profile.server.isNullOrBlank() && profile.remarks.isNotBlank()
    }
}
