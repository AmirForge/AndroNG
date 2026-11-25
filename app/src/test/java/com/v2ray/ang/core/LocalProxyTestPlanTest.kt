package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

class LocalProxyTestPlanTest {
    @Test
    fun eachProfileHasADifferentListenerAndItsOwnOutboundChain() {
        val plan = LocalProxyTestPlan()
        var port = 9090
        val config = """{"outbounds":[
            {"tag":"proxy","protocol":"vless","settings":{"address":"example.com","port":443},"streamSettings":{"sockopt":{"dialerProxy":"hop"}}},
            {"tag":"hop","protocol":"freedom"}]}"""
        plan.add("first", config, null) { port++ }
        plan.add("second", config, null) { port++ }
        assertEquals(listOf(9090, 9091), plan.entries.map { it.proxyPort })
        val json = JsonParser.parseString(plan.content()).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds")
        assertEquals("probe-0/1", outbounds[0].asJsonObject["streamSettings"].asJsonObject["sockopt"].asJsonObject["dialerProxy"].asString)
        assertEquals("probe-1/1", outbounds[2].asJsonObject["streamSettings"].asJsonObject["sockopt"].asJsonObject["dialerProxy"].asString)
        val routes = json["routing"].asJsonObject["rules"].asJsonArray
        assertEquals("probe-0/0", routes[0].asJsonObject["outboundTag"].asString)
        assertEquals("probe-1/0", routes[1].asJsonObject["outboundTag"].asString)
        assertEquals(1, JsonParser.parseString(plan.content(listOf(plan.entries[1]))).asJsonObject["inbounds"].asJsonArray.size())
    }

    @Test
    fun aetherProfilesHaveSeparateProxyTunnelAndExitPortsAndRebasedOutRuleReferences() {
        val plan = LocalProxyTestPlan()
        val primary = listOf("--bind", "127.0.0.1:10808", "--protocol", "masque", "--h2")
        val core = AetherCore(primary, alternatives = listOf(primary, primary.dropLast(1)))
        var port = 9090
        val config = """{"outbounds":[
            {"tag":"proxy","protocol":"socks","settings":{"address":"127.0.0.1","port":10808}},
            {"tag":"exit-node","protocol":"freedom"}]}"""
        plan.add("aether", config, core) { port++ }
        val entry = plan.entries.single()
        assertEquals(9090, entry.proxyPort)
        assertEquals(9091, entry.exitPort)
        assertEquals(9092, entry.aether!!.port)
        val alternatives = requireNotNull(entry.aether).alternatives
        assertEquals(listOf(true, false), alternatives.map { "--h2" in it })
        assertEquals(listOf(9092, 9092), alternatives.map { AetherCoreManager.listenerPortOf(it) })
        val json = JsonParser.parseString(plan.content()).asJsonObject
        assertEquals(9092, json["outbounds"].asJsonArray[0].asJsonObject["settings"].asJsonObject["port"].asInt)
        assertEquals(2, json["inbounds"].asJsonArray.size())
        assertEquals(15_000L, LocalProxyBatchTester.TEST_TIMEOUT_MS)
    }

    @Test
    fun rejectedProfileDoesNotChangeTheBatchAndPortsRemainReservedUntilReleased() {
        val plan = LocalProxyTestPlan()
        assertThrows(Exception::class.java) { plan.add("invalid", "{}", null) { 9090 } }
        assertTrue(plan.entries.isEmpty())
        val ports = LocalProxyTestPorts()
        val first = ports.allocateProxy()
        val auxiliary = ports.allocateAuxiliary()
        val second = ports.allocateProxy()
        assertEquals(2020, first)
        assertEquals(2021, second)
        assertTrue(auxiliary > 6060)
        assertThrows(Exception::class.java) { ServerSocket(first, 1, InetAddress.getByName(AppConfig.LOOPBACK)).close() }
        ports.close()
        ServerSocket(first, 1, InetAddress.getByName(AppConfig.LOOPBACK)).use { assertEquals(first, it.localPort) }
    }
}
