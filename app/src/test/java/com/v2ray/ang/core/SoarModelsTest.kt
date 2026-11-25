package com.v2ray.ang.core

import com.v2ray.ang.core.soar.*
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class SoarModelsTest {
    @Test fun runtimeDefaultsAndInvalidControls() {
        val d = SoarRuntimeSettings()
        assertTrue(d.valid())
        assertFalse(d.copy(vpnMtu = 300).valid())
        assertFalse(d.copy(listenIp = "0.0.0.0").valid())
        assertFalse(d.copy(httpPort = d.listenPort).valid())
        assertFalse(d.copy(authentication = true).valid())
        assertFalse(d.copy(authentication = true, username = "a\nb", password = "pass").valid())
        assertTrue(d.copy(authentication = true, username = "user", password = "pass").valid())
        assertTrue(d.copy(watchdogSeconds = 0).valid())
    }

    @Test fun precedingProfileFormatGetsRuntimeDefaults() {
        val preceding = """{"id":"old","name":"old","zone":"dns.example.com","publicKey":"$key","settings":{"transport":"auto"}}"""
        val restored = JsonUtil.fromJson(preceding, SoarProfile::class.java)!!
        assertEquals(SoarRuntimeSettings(), restored.runtime)
        assertTrue(restored.valid())
    }
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private fun link(profile: String) = "soar://" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"schema":"whitedns.profile","version":1,"profile":$profile}""".toByteArray())

    @Test fun defaultsAndBoundaries() {
        val d = SoarSettings()
        assertTrue(d.valid())
        assertEquals("auto", d.transport)
        assertEquals(90, d.dialTimeoutSeconds)
        assertEquals(2, d.discoveryConcurrency)
        assertFalse(d.copy(safeQName = 254).valid())
        assertFalse(d.copy(maxInflight = 0).valid())
        assertFalse(d.copy(transport = "tls").valid())
        assertFalse(d.copy(country = "Iran").valid())
        assertEquals(512, SoarSettings.preset("conservative").maxAnswer)
        assertEquals(256, SoarSettings.preset("fast").maxInflight)
        assertEquals(d, SoarSettings.preset("balanced"))
    }

    @Test fun coldStartDuplicateStartAndSystemRestartDecisions() {
        assertEquals(SoarStartDecision.Start, soarStartDecision("connect", false, false))
        assertEquals(SoarStartDecision.Reuse, soarStartDecision("connect", true, false))
        assertEquals(SoarStartDecision.Reuse, soarStartDecision("connect", false, true))
        assertEquals(SoarStartDecision.Stop, soarStartDecision("stop", true, true))
        assertEquals(SoarStartDecision.Stop, soarStartDecision(null, false, false))
    }

    @Test fun importsCompatibleProfileAndResolverSettings() {
        val (p, dns) = SoarImport.profile(link("""{"name":"IR","server":{"domain":"dns.example.com","public_key":"$key"},"soarSettings":{"transport":"only","maxStreams":16},"resolvers":["1.1.1.1","[2606:4700:4700::1111]:53"]}"""))
        assertEquals("dns.example.com", p.zone)
        assertTrue(p.valid())
        assertEquals("only", p.settings.transport)
        assertEquals(16, p.settings.maxStreams)
        assertEquals(101, p.settings.safeQName)
        assertEquals(listOf("1.1.1.1:53", "[2606:4700:4700::1111]:53"), dns)
    }

    @Test fun rejectsInvalidProfileWithoutAcceptingPartialData() {
        listOf("", "vless://anything", "soar://%%%%", link("""{"name":"bad","server":{"domain":"a..b","public_key":"$key"}}"""),
            link("""{"name":"bad","server":{"domain":"dns.example.com","public_key":"AA=="}}""")).forEach {
            assertTrue(runCatching { SoarImport.profile(it) }.isFailure)
        }
    }

    @Test fun importsMasterAndCottonLinksWithTheirOwnEngines() {
        val encoded = link("""{"name":"Tunnel","server":{"domain":"dns.example.com","encryption_key":"sample","encryption_method":2}}""")
            .substringAfter("://")
        for (scheme in listOf("master", "masterdns", "stormdns")) {
            assertEquals(com.v2ray.ang.core.soar.DnsTunnelEngine.STORM, SoarImport.profile("$scheme://$encoded").first.engine)
        }
        for (scheme in listOf("cotton", "cotten", "cottondns", "cottendns")) {
            assertEquals(com.v2ray.ang.core.soar.DnsTunnelEngine.COTTEN, SoarImport.profile("$scheme://$encoded").first.engine)
        }
    }

    @Test fun importsCottonWithLongZoneAndOptionalLinkMetadata() {
        val domain = "server-through-a-long-dns-zone.testing.example.com"
        val payload = """{"schema":"whitedns.profile","profile":{"name":" Cotton ","server":{"domain":"$domain","encryption_key":"sample","encryption_method":2}}}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())
        val (profile, resolvers) = SoarImport.profile("cottendns://$encoded?source=share#Cotton")
        assertEquals(domain, profile.zone)
        assertEquals("Cotton", profile.name)
        assertTrue(resolvers.isEmpty())
    }

    @Test fun legacySettingsRenderReferenceDefaultsAndRejectInvalidOverrides() {
        val defaults = com.v2ray.ang.core.soar.legacyDnsToml(emptyMap())
        assertTrue("MIN_UPLOAD_MTU = 40" in defaults)
        assertTrue("MAX_DOWNLOAD_MTU = 3000" in defaults)
        assertTrue("DISPATCHER_IDLE_POLL_INTERVAL_SECONDS = 0.020" in defaults)
        assertTrue("TX_CHANNEL_SIZE = 4096" in com.v2ray.ang.core.soar.legacyDnsToml(mapOf("TX_CHANNEL_SIZE" to "4096")))
        assertFalse(com.v2ray.ang.core.soar.legacyDnsSettingsValid(mapOf("MIN_UPLOAD_MTU" to "141")))
        assertFalse(com.v2ray.ang.core.soar.legacyDnsSettingsValid(mapOf("TX_CHANNEL_SIZE" to "1\nKEY = 2")))
    }

    @Test fun resolverParserIsStrictAndDeduplicates() {
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:5353"), SoarImport.resolvers("# list\n1.1.1.1\n1.1.1.1:53\n8.8.8.8:5353"))
        assertTrue(SoarImport.resolvers("").isEmpty())
        listOf("example.com", "1.1.1.999", "1.1.1.1:0", "1.1.1.0/24", "[::1]bad", "https://dns.example").forEach {
            assertTrue(runCatching { SoarImport.resolvers(it) }.isFailure, it)
        }
    }

    @Test fun storageRoundTripKeepsSettingsAndIdentity() {
        val p = SoarProfile(name = "saved", zone = "dns.example.com", publicKey = key)
        val dns = SoarResolverProfile(addresses = listOf("1.1.1.1:53"))
        val store = SoarStore(listOf(p), p.id, listOf(dns), dns.id)
        assertEquals(store, JsonUtil.fromJson(JsonUtil.toJson(store), SoarStore::class.java))
    }
}
