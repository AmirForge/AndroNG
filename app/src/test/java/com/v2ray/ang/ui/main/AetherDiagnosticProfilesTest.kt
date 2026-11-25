package com.v2ray.ang.ui.main

import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ProfileEntry
import com.v2ray.ang.dto.ByName
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AetherDiagnosticProfilesTest {
    @Test
    fun masqueProvidesItsEmbeddedOutRuleToAllGeneratedProfiles() {
        val outbound = ProfileItem.create(EConfigType.VLESS).apply { remarks = "Exit"; server = "example.com" }
        val masque = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = "masque"
            aetherEmbeddedOutrole = outbound
        }
        val resolved = diagnosticOutRule(ProfileEntry("masque", masque)) { _, _ -> error("Embedded rule must be used") }
        assertSame(outbound, resolved)
        assertTrue(aetherDiagnosticProfiles(requireNotNull(resolved)).all { it.aetherEmbeddedOutrole == outbound })
    }

    @Test
    fun namedOutRuleIsResolvedAndMissingOrAmbiguousRulesAreRejected() {
        val outbound = ProfileItem.create(EConfigType.TROJAN).apply { remarks = "Exit"; server = "example.com" }
        val aether = ProfileItem.create(EConfigType.AETHER).apply { aetherOutrole = "Exit" }
        val entry = ProfileEntry("aether", aether)
        assertSame(outbound, diagnosticOutRule(entry) { name, accepts ->
            assertEquals("Exit", name)
            assertTrue(accepts(outbound))
            ByName.One(ProfileEntry("outbound", outbound))
        })
        assertNull(diagnosticOutRule(entry) { _, _ -> ByName.None })
        assertNull(diagnosticOutRule(entry) { _, _ -> ByName.Several })
        assertNull(diagnosticOutRule(ProfileEntry("plain", ProfileItem.create(EConfigType.AETHER))) { _, _ -> ByName.None })
        assertSame(outbound, diagnosticOutRule(ProfileEntry("direct", outbound)) { _, _ -> error("No lookup needed") })
    }

    @Test
    fun eachOfTheEightCombinationsHasItsOwnMatchingScanCarrier() {
        val outRule = ProfileItem.create(EConfigType.VLESS).apply { server = "example.com"; remarks = "Outbound" }
        val profiles = aetherDiagnosticProfiles(outRule)
        assertEquals(8, profiles.size)
        assertEquals(8, profiles.map { it.aetherProtocol to it.aetherTransport }.distinct().size)
        profiles.forEach {
            val expected = if (AetherProtocol.fromString(it.aetherProtocol).overMasque && it.aetherTransport == "h2") "tcp" else "udp"
            assertEquals(expected, it.aetherScanNetwork)
            assertEquals(AETHER_DIAGNOSTIC_GROUP, it.subscriptionId)
            assertEquals(1, it.aetherEndpointCount)
            assertEquals(outRule, it.aetherEmbeddedOutrole)
            assertNotSame(outRule, it.aetherEmbeddedOutrole)
            assertNull(it.server)
        }
    }

    @Test
    fun generatedOutRulesAreIndependentCopies() {
        val outRule = ProfileItem.create(EConfigType.VLESS).apply { remarks = "Original" }
        val profiles = aetherDiagnosticProfiles(outRule)
        profiles.first().aetherEmbeddedOutrole!!.remarks = "Changed"
        assertEquals("Original", outRule.remarks)
        assertEquals("Original", profiles.last().aetherEmbeddedOutrole!!.remarks)
    }

}
