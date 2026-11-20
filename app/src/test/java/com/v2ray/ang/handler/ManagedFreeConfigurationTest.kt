package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64

class ManagedFreeConfigurationTest {
    private val profile = "vless://00000000-0000-4000-8000-000000000001@example.org:443#free"
    private fun decode(text: String): String = runCatching {
        Base64.getDecoder().decode(text).decodeToString()
    }.getOrDefault(text)

    @Test
    fun `inline and base64 subscriptions produce the same profile text`() {
        for (content in listOf(profile, Base64.getEncoder().encodeToString(profile.toByteArray()))) {
            assertEquals(profile, ControlPanelRepository.resolveFreeConfiguration(content, { error("No URL expected") }, ::decode))
        }
    }

    @Test
    fun `sources are expanded in memory without preserving private subscription URLs`() {
        val source = "https://example.org/private-subscription"
        var downloads = 0
        val result = ControlPanelRepository.resolveFreeConfiguration("$source\n$source", {
            downloads++
            Base64.getEncoder().encodeToString(profile.toByteArray())
        }, ::decode)
        assertEquals(1, downloads)
        assertEquals(profile, result)
        assertFalse(result.orEmpty().contains(source))
    }

    @Test
    fun `cyclic sources stop and empty contents remain unavailable`() {
        val source = "https://example.org/cycle"
        assertNull(ControlPanelRepository.resolveFreeConfiguration(source, { source }, ::decode))
        assertNull(ControlPanelRepository.resolveFreeConfiguration("  ", { error("No download") }, ::decode))
    }

    @Test
    fun `failed source is reported without exposing its URL`() {
        val source = "https://example.org/private-subscription"
        val error = assertThrows(IOException::class.java) {
            ControlPanelRepository.resolveFreeConfiguration(source, { null }, ::decode)
        }
        assertFalse(error.message.orEmpty().contains(source))
    }
}
