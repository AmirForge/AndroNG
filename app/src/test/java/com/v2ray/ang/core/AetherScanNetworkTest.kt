package com.v2ray.ang.core

import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherScanNetwork
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AetherScanNetworkTest {
    private fun profile(mode: String? = null, count: Int = 4) = ProfileItem.create(EConfigType.AETHER).apply {
        aetherProtocol = "masque"
        aetherTransport = "h2"
        aetherScanNetwork = mode
        aetherEndpointCount = count
        server = "162.159.197.1"
        serverPort = "443"
    }

    @Test
    fun `default and unknown modes scan both carriers while explicit modes select one`() {
        for (value in listOf(null, "", "future", "tcp+udp")) {
            assertEquals(AetherScanNetwork.BOTH, AetherScanNetwork.fromString(value))
            assertEquals(listOf(AetherTransport.HTTP2, AetherTransport.HTTP3), AetherScanner.scanTransports(profile(value)))
        }
        assertEquals(listOf(AetherTransport.HTTP2), AetherScanner.scanTransports(profile("tcp")))
        assertEquals(listOf(AetherTransport.HTTP3), AetherScanner.scanTransports(profile("udp")))
        assertEquals(listOf(AetherTransport.HTTP3, AetherTransport.HTTP2), AetherScanner.scanTransports(profile().apply { aetherTransport = "h3" }))
        for (protocol in listOf("wg", "gool")) {
            assertEquals(listOf(AetherTransport.HTTP3), AetherScanner.scanTransports(profile("tcp").apply { aetherProtocol = protocol }))
        }
    }

    @Test
    fun `tagged candidates preserve IPv6 hop pairs and old untagged records remain readable`() {
        val old = "[2606:4700::1]:443|162.159.197.2:443"
        assertNull(AetherScanner.decode(old)!!.transport)
        for (transport in AetherTransport.entries) {
            val result = AetherScanner.decode(old)!!.copy(transport = transport)
            assertEquals(result, AetherScanner.decode(AetherScanner.encode(result)))
        }
        listOf("tcp@162.159.197.1:443", "h2@bad", "h3@@162.159.197.1:443", "h2@162.159.197.1:443|bad")
            .forEach { assertNull(AetherScanner.decode(it)) }
    }

    @Test
    fun `links and stored profiles retain the scan mode and each candidates carrier`() {
        for (mode in AetherScanNetwork.entries) {
            val original = profile(mode.type).apply {
                aetherEndpoints = listOf("h2@162.159.197.1:443", "h3@162.159.197.1:443")
            }
            val link = "aether://" + AetherFmt.toUri(original)
            val imported = AetherFmt.parseProfile(link)
            assertEquals(mode.type, imported.aetherScanNetwork)
            assertEquals(original.aetherEndpoints, imported.aetherEndpoints)
            val stored = JsonUtil.fromJsonSafe(JsonUtil.toJson(original), ProfileItem::class.java)!!
            assertEquals(mode.type, stored.aetherScanNetwork)
            val alternatives = AetherCore.of(stored, 1085).alternatives
            assertEquals(2, alternatives.size)
            assertTrue("--h2" in alternatives[0])
            assertFalse("--h2" in alternatives[1])
        }
        val legacy = AetherFmt.parseProfile("aether://162.159.197.1:443?protocol=masque&transport=h2")
        assertNull(legacy.aetherScanNetwork)
        assertEquals(2, AetherScanner.scanTransports(legacy).size)
    }

    @Test
    fun `mixed scan links retain UDP obfuscation even with an HTTP2 primary endpoint`() {
        for (mode in listOf(null, "udp", "tcp+udp")) {
            val original = profile(mode).apply { aetherObfuscation = "aggressive" }
            val imported = AetherFmt.parseProfile("aether://" + AetherFmt.toUri(original))
            assertEquals("aggressive", imported.aetherObfuscation)
        }
        val tcp = profile("tcp").apply { aetherObfuscation = "aggressive" }
        assertEquals("auto", AetherFmt.parseProfile("aether://" + AetherFmt.toUri(tcp)).aetherObfuscation)
    }

    @Test
    fun `combined scan accepts a full list from TCP without waiting for UDP`() = runTest {
        val calls = mutableListOf<String>()
        val batches = mutableListOf<List<String>>()
        val original = profile(count = 4)
        val result = AetherScanner.collectTransportCandidates(original, {}, { batches += it }) { next, output, timeout ->
            calls += next.aetherTransport!!
            assertTrue(timeout in 1..480_000L)
            (1..next.aetherEndpointCount!!).forEach { output("candidate ok 162.159.197.$it:443 rtt=10ms") }
            AetherScanResult(AetherEndpoint("162.159.197.1", 443))
        }!!
        assertTrue(calls.first() == "h2")
        assertEquals((1..4).map { "h2@162.159.197.$it:443" }, result.candidates)
        assertEquals(AetherTransport.HTTP2, result.transport)
        assertEquals(listOf(4), batches.map { it.size })
        assertEquals("h2", original.aetherTransport)
        assertNull(original.aetherEndpoints)
    }

    @Test
    fun `first carrier to fill shared list cancels the other before timeout`() = runTest {
        for (winner in listOf("h2", "h3")) {
            val closed = mutableSetOf<String>()
            val result = AetherScanner.collectTransportCandidates(profile(count = 5), {}, {}) { next, output, _ ->
                try {
                    if (next.aetherTransport != winner) kotlinx.coroutines.awaitCancellation()
                    kotlinx.coroutines.delay(25)
                    (1..5).forEach { output("candidate ok 162.159.197.$it:443 rtt=10ms") }
                    kotlinx.coroutines.awaitCancellation()
                } finally { closed += next.aetherTransport!! }
            }!!
            assertEquals((1..5).map { "$winner@162.159.197.$it:443" }, result.candidates)
            assertEquals(setOf("h2", "h3"), closed)
        }
    }

    @Test
    fun `mixed first arrivals fill one list without carrier quota`() = runTest {
        val closed = mutableSetOf<String>()
        val result = AetherScanner.collectTransportCandidates(profile(count = 5), {}, {}) { next, output, _ ->
            try {
                if (next.aetherTransport == "h2") {
                    output("candidate ok 162.159.197.1:443 rtt=10ms")
                    output("candidate ok 162.159.197.2:443 rtt=10ms")
                } else {
                    kotlinx.coroutines.delay(10)
                    (1..3).forEach { output("candidate ok 162.159.197.$it:443 rtt=10ms") }
                }
                kotlinx.coroutines.awaitCancellation()
            } finally { closed += next.aetherTransport!! }
        }!!
        assertEquals(5, result.candidates.size)
        assertEquals(2, result.candidates.count { it.startsWith("h2@") })
        assertEquals(3, result.candidates.count { it.startsWith("h3@") })
        assertEquals(setOf("h2", "h3"), closed)
    }

    @Test
    fun `blocked first carrier falls back and fills the count from the working carrier`() = runTest {
        val calls = mutableListOf<String>()
        val result = AetherScanner.collectTransportCandidates(profile(count = 3).apply { aetherTransport = "h3" }, {}, {}) { next, output, _ ->
            calls += next.aetherTransport!!
            if (next.aetherTransport == "h3") null else {
                (1..3).forEach { output("candidate ok 162.159.197.$it:443 rtt=10ms") }
                AetherScanResult(AetherEndpoint("162.159.197.1", 443))
            }
        }!!
        assertEquals(3, result.candidates.size)
        assertTrue(result.candidates.all { it.startsWith("h2@") })
        assertEquals(AetherTransport.HTTP2, result.transport)
        assertEquals("h3", calls.first())
    }

    @Test
    fun `explicit modes never probe the other carrier and preserve progress batches`() = runTest {
        for ((mode, transport) in listOf("tcp" to "h2", "udp" to "h3")) {
            val batches = mutableListOf<List<String>>()
            val result = AetherScanner.collectTransportCandidates(profile(mode, 25), {}, { batches += it }) { next, output, _ ->
                assertEquals(transport, next.aetherTransport)
                (1..30).forEach { output("candidate ok 162.159.197.$it:443 rtt=10ms") }
                AetherScanResult(AetherEndpoint("162.159.197.1", 443))
            }!!
            assertEquals(25, result.candidates.size)
            assertEquals(listOf(10, 20, 25), batches.map { it.size })
            assertTrue(result.candidates.all { it.startsWith("$transport@") })
        }
    }

    @Test
    fun `a blocked second carrier refills from new results on the first without counting duplicates`() = runTest {
        val result = AetherScanner.collectTransportCandidates(profile(count = 5), {}, {}) { next, output, _ ->
            if (next.aetherTransport == "h3") null else {
                (1..8).forEach { output("candidate ok 162.159.197.$it:443 rtt=10ms") }
                AetherScanResult(AetherEndpoint("162.159.197.1", 443))
            }
        }!!
        assertEquals((1..5).map { "h2@162.159.197.$it:443" }, result.candidates)
    }

    @Test
    fun `one endpoint falls back to TCP if UDP yields no result`() = runTest {
        val result = AetherScanner.collectTransportCandidates(profile(count = 1).apply { aetherTransport = "h3" }, {}, {}) { next, _, _ ->
            if (next.aetherTransport == "h3") null else AetherScanResult(AetherEndpoint("162.159.197.1", 443))
        }!!
        assertEquals(AetherTransport.HTTP2, result.transport)
        assertEquals(listOf("h2@162.159.197.1:443"), result.candidates)
    }

    @Test
    fun `empty scans return nothing and cancellation propagates instead of returning partial candidates`() = runTest {
        assertNull(AetherScanner.collectTransportCandidates(profile(), {}, {}) { _, _, _ -> null })
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                AetherScanner.collectTransportCandidates(profile(), {}, {}) { _, _, _ -> throw CancellationException() }
            }
        }
    }
}
