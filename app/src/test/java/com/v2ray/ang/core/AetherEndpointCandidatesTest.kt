package com.v2ray.ang.core

import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AetherEndpointCandidatesTest {
    private fun profile(count: Int? = null) = ProfileItem.create(EConfigType.AETHER).apply {
        aetherProtocol = "masque"
        aetherTransport = "h2"
        server = "162.159.197.1"
        serverPort = "443"
        aetherEndpointCount = count
    }

    @Test
    fun `old stored profiles keep a single endpoint and ordinary command`() {
        val old = JsonUtil.fromJsonSafe("""{"configType":"AETHER","server":"162.159.197.1","serverPort":"443"}""", ProfileItem::class.java)!!
        assertEquals(1, AetherScanner.endpointCount(old))
        assertNull(old.aetherSmartSelection)
        assertFalse(AetherCore.of(old, 1085).smartSelection)
        assertTrue(AetherCore.of(old, 1085).alternatives.isEmpty())
        assertEquals(1, AetherScanner.endpointCount(profile(-1)))
        assertEquals(50, AetherScanner.endpointCount(profile(100)))
    }

    @Test
    fun `candidate records preserve IPv6 and two hop pairs and reject malformed input`() {
        val result = AetherScanResult(AetherEndpoint("2606:4700::1", 443), AetherEndpoint("162.159.197.2", 2408))
        assertEquals(result, AetherScanner.decode(AetherScanner.encode(result)))
        listOf("", "example.org:443", "162.159.197.1:0", "162.159.197.1:443|bad", "162.159.197.1:443|162.159.197.2:443|extra")
            .forEach { assertNull(AetherScanner.decode(it)) }
    }

    @Test
    fun `profile links and storage carry the count and unique candidates`() {
        val original = profile(50).apply { aetherEndpoints = (1..50).map { "162.159.197.$it:443" } }
        val imported = AetherFmt.parseProfile("aether://" + AetherFmt.toUri(original))
        assertEquals(50, imported.aetherEndpointCount)
        assertEquals(original.aetherEndpoints, imported.aetherEndpoints)
        val restored = JsonUtil.fromJsonSafe(JsonUtil.toJson(original), ProfileItem::class.java)!!
        val core = AetherCore.of(restored, 1085).through(1086)
        assertEquals(50, core.alternatives.size)
        assertTrue(core.alternatives.all { "socks5://127.0.0.1:1086" in it })
        assertTrue(core.runsAs(core.alternatives.last()))
        assertTrue(AetherCore.of(original.copy(aetherCommand = "aether --protocol masque --peer 162.159.197.1:443"), 1085).alternatives.isEmpty())
        assertEquals(1, AetherScanner.endpointCount(AetherFmt.parseProfile("aether://162.159.197.1:443?endpoint_count=51")))
    }

    @Test
    fun `scan publishes unique candidates in batches of ten and a final partial batch`() = runTest {
        val batches = mutableListOf<List<String>>()
        val result = AetherScanner.collectCandidates(profile(25), {}, { batches += it }) { output, _ ->
            (1..30).forEach {
                output("[+] candidate ok 162.159.197.$it:443 rtt=10ms")
                output("[+] candidate ok 162.159.197.$it:443 rtt=10ms")
            }
            AetherScanResult(AetherEndpoint("162.159.197.1", 443))
        }
        assertEquals(25, result!!.candidates.size)
        assertEquals(listOf(10, 20, 25), batches.map { it.size })
    }

    @Test
    fun `empty scans finish and cancellation propagates without a partial result`() = runTest {
        var scans = 0
        assertNull(AetherScanner.collectCandidates(profile(10), {}, {}) { _, _ -> scans++; null })
        assertEquals(3, scans)
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                AetherScanner.collectCandidates(profile(10), {}, {}) { _, _ -> throw CancellationException() }
            }
        }
    }
}
