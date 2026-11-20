package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminConverterCompatibilityTest {
    @Test
    fun `browser converter links decode as native Aether profiles with embedded OutRules`() {
        val fixture = javaClass.getResourceAsStream("/converter/admin-links.json")!!
            .bufferedReader().use { it.readText() }
        val cases = JsonParser.parseString(fixture).asJsonArray
        assertEquals(14, cases.size())
        for ((index, case) in cases.withIndex()) {
            val sample = case.asJsonObject
            val decoded = AetherSetupLink.parse(sample["link"].asString)
            assertNotNull(decoded, "Converter sample $index must be accepted by the app")
            val parsed = decoded!!
            val expectedOutbound = JsonUtil.fromJson(sample["expectedOutbound"].toString(), ProfileItem::class.java)
            assertEquals(expectedOutbound, parsed.outrole, "OutRule fields, credentials and Unicode must survive: $index")
            assertEquals(sample["expectedName"].asString, parsed.profile.remarks)
            assertTrue(AetherSetupLink.requiresWarp(parsed.profile))
            assertNull(parsed.profile.server, "The receiving device must find its own endpoint")
            assertNull(parsed.profile.aetherOutrole)
            assertNull(parsed.outrole?.aetherEmbeddedOutrole)
            val nativeProfile = JsonParser.parseString(JsonUtil.toJson(parsed.profile)).asJsonObject
            for ((field, value) in sample["expectedAether"].asJsonObject.entrySet()) {
                assertEquals(value, nativeProfile[field], "Aether setting $field in sample $index")
            }
        }
    }
}
