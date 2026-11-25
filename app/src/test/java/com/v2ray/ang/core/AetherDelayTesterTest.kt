package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AetherDelayTesterTest {
    @Test
    fun healthCheckStopsAtTheFirstValidResponseWhileNumericTestingKeepsBothAttempts() {
        HttpStub("204 No Content").use { http ->
            SocksStub().use { socks ->
                val url = "http://127.0.0.1:${http.port}/generate_204"
                assertTrue(AetherDelayTester.requestDelay(socks.port, url, firstSuccess = true) > 0)
                assertEquals(1, http.requests.get())
                assertTrue(AetherDelayTester.requestDelay(socks.port, url) >= 0)
                assertEquals(3, http.requests.get())
            }
        }
    }

    @Test
    fun healthCheckDoesNotWaitForAResponseBodyAndRejectsFailureStatus() {
        HttpStub("200 OK", bodyLength = 1000).use { http ->
            SocksStub().use { socks ->
                val url = "http://127.0.0.1:${http.port}/"
                assertTrue(AetherDelayTester.requestDelay(socks.port, url, firstSuccess = true) > 0)
                assertEquals(-1L, AetherDelayTester.requestDelay(socks.port, url))
            }
        }
        HttpStub("503 Unavailable").use { http ->
            SocksStub().use { socks ->
                assertEquals(-1L, AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:${http.port}/", firstSuccess = true))
            }
        }
    }

    @Test
    fun healthyResultIsPublishedBeforeTeardownAndNeverOnFailureOrCancellation() = runBlocking {
        val order = mutableListOf<String>()
        try {
            assertEquals(42L, AetherDelayTester.publishHealthy(true, { order += "tick" }) { order += "probe"; 42L })
        } finally { order += "cleanup" }
        assertEquals(listOf("probe", "tick", "cleanup"), order)
        for (result in listOf(-1L)) {
            AetherDelayTester.publishHealthy(true, { error("Not a valid healthy result") }) { result }
        }
        var rounded: Long? = null
        assertEquals(1L, AetherDelayTester.publishHealthy(true, { rounded = it }) { 0L })
        assertEquals(1L, rounded)
        AetherDelayTester.publishHealthy(false, { error("Numeric mode must not publish early") }) { 42L }
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                AetherDelayTester.publishHealthy(true, { error("Cancelled") }) { throw kotlinx.coroutines.CancellationException() }
            }
        }
        Unit
    }

    private fun aether(protocol: AetherProtocol) =
        ProfileItem.create(EConfigType.AETHER).apply { aetherProtocol = protocol.type }

    @Test
    fun testListenerUsesAnAvailableLoopbackPortAwayFromTheSession() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { session ->
            val firstPort = AetherDelayTester.allocateTestPort(session.localPort)
            assertTrue(firstPort != session.localPort)
            ServerSocket(firstPort, 1, loopback).use {
                val secondPort = AetherDelayTester.allocateTestPort(session.localPort)
                assertTrue(secondPort != firstPort)
                assertTrue(secondPort != session.localPort)
            }
        }
    }

    @Test
    fun chainedTestMovesOnlyTheOutboundsThatDialItsAetherListener() {
        val config = JsonParser.parseString(
            """
            {"outbounds":[
                {"tag":"aether","protocol":"socks","settings":{"address":"127.0.0.1","port":10808}},
                {"tag":"aether-fallback","protocol":"socks","settings":{"address":"127.0.0.1","port":10808}},
                {"tag":"another-local","protocol":"socks","settings":{"address":"127.0.0.1","port":10809}},
                {"tag":"remote","protocol":"socks","settings":{"address":"example.com","port":10808}},
                {"tag":"http","protocol":"http","settings":{"address":"127.0.0.1","port":10808}}
            ]}
            """.trimIndent()
        ).asJsonObject

        assertTrue(AetherDependency.moveSocksOutbounds(config, fromPort = 10808, toPort = 49000))
        val ports = config.getAsJsonArray("outbounds").map { it.asJsonObject.getAsJsonObject("settings").get("port").asInt }
        assertEquals(listOf(49000, 49000, 10809, 10808, 10808), ports)
        assertFalse(AetherDependency.moveSocksOutbounds(config, fromPort = 10808, toPort = 49001))
    }

    @Test
    fun aTestCoreThatDialsPsiphonIsReadyOnlyOnTheCoresWord() = runBlocking {
        SocksStub().use { socks ->
            val output = Channel<String>(Channel.UNLIMITED)
            // The listener answers, but nothing said yet: not ready within a short budget.
            assertFalse(AetherDelayTester.awaitListening(socks.port, output, deadlineAfterMs(600), needsWord = true))
            // Without the need, the answering listener is enough.
            assertTrue(AetherDelayTester.awaitListening(socks.port, output, deadlineAfterMs(2_000), needsWord = false))

            output.trySend("[2026-09-24T10:00:00.000Z INFO  aether] [*] starting psiphon through the tunnel at 127.0.0.1:10820")
            output.trySend("[2026-09-24T10:00:00.000Z INFO  aether] [+] psiphon is ready; 127.0.0.1:${socks.port} leaves through psiphon, carried by the tunnel")
            assertTrue(AetherDelayTester.awaitListening(socks.port, output, deadlineAfterMs(2_000), needsWord = true))

            // A core that ends before its word is a failed test, not a wait.
            val ended = Channel<String>(Channel.UNLIMITED)
            ended.close()
            assertFalse(AetherDelayTester.awaitListening(socks.port, ended, deadlineAfterMs(2_000), needsWord = true))
        }
    }

    private fun deadlineAfterMs(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)

    @Test
    fun aTcpPingProbesThePinnedEdgeInsteadOfOpeningATunnel() {
        val probed = mutableListOf<Pair<String, Int>>()
        val connect = { host: String, port: Int -> probed.add(host to port); 42L }

        val pinned = aether(AetherProtocol.MASQUE).apply { server = "162.159.198.1"; serverPort = "2408" }
        assertEquals(42L, AetherDelayTester.reachability(pinned, connect))
        assertEquals(listOf("162.159.198.1" to 443), probed)

        probed.clear()
        val hops = aether(AetherProtocol.GOOL).apply { aetherWiwOuter = "162.159.192.1:2408"; aetherWiwInner = "188.114.96.1:894" }
        assertEquals(42L, AetherDelayTester.reachability(hops, connect))
        assertEquals(listOf("162.159.192.1" to 443), probed)

        probed.clear()
        val masqueHops = aether(AetherProtocol.MIM).apply { aetherWiwOuter = "162.159.197.3:443"; aetherWiwInner = "188.114.96.1:443" }
        assertEquals(42L, AetherDelayTester.reachability(masqueHops, connect))
        assertEquals(listOf("162.159.197.3" to 443), probed)

        probed.clear()
        val unreachable = { _: String, _: Int -> -1L }
        assertEquals(-1L, AetherDelayTester.reachability(pinned, unreachable))
    }

    @Test
    fun aProfileLeftToTheScannerHasNothingToProbe() {
        val probes = { _: String, _: Int -> throw AssertionError("must not probe") }

        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(aether(AetherProtocol.MASQUE), probes))
        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(aether(AetherProtocol.GOOL), probes))
        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(aether(AetherProtocol.MIM), probes))
        val halfPinned = aether(AetherProtocol.WIREGUARD).apply { server = "162.159.198.1" }
        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(halfPinned, probes))
        val hostName = aether(AetherProtocol.WIREGUARD).apply { server = "engage.cloudflareclient.com"; serverPort = "2408" }
        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(hostName, probes))
        assertEquals(0L, AetherDelayTester.UNTESTED)
    }

    @Test
    fun aDelayIsMeasuredThroughTheSocksPort() {
        HttpStub("204 No Content").use { http ->
            SocksStub().use { socks ->
                val delay = AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:${http.port}/generate_204")
                assertTrue(delay >= 0, "delay was $delay")
            }
        }
    }

    @Test
    fun aPlainOkAlsoCountsAsAnAnswer() {
        HttpStub("200 OK").use { http ->
            SocksStub().use { socks ->
                assertTrue(AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:${http.port}/") >= 0)
            }
        }
    }

    @Test
    fun anErrorStatusIsNotADelay() {
        HttpStub("500 Internal Server Error").use { http ->
            SocksStub().use { socks ->
                assertEquals(-1L, AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:${http.port}/"))
            }
        }
    }

    @Test
    fun nothingListeningOrABrokenUrlIsNotADelay() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertEquals(-1L, AetherDelayTester.requestDelay(closedPort, "http://127.0.0.1:1/generate_204"))
        assertEquals(-1L, AetherDelayTester.requestDelay(closedPort, "not a url"))
    }

    @Test
    fun aProbeThatGetsNoAnswerGivesUpWithinItsBudget() {
        StallingSocksStub().use { socks ->
            val started = System.nanoTime()
            val deadline = started + TimeUnit.MILLISECONDS.toNanos(1_500)
            val delay = AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:1/generate_204", deadline)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(-1L, delay)
            // One attempt within the budget, no second one, and nothing left to OkHttp's own 10-second connect timeout.
            assertTrue(elapsedMs < 6_000, "gave up after $elapsedMs ms")
        }
    }

    @Test
    fun aCancelledTestEndsItsProbeAtOnce() {
        StallingSocksStub().use { socks ->
            runBlocking {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AetherDelayTester.TEST_BUDGET_MS)
                val probe = launch(Dispatchers.Default) {
                    AetherDelayTester.cancellableRequestDelay(socks.port, "http://127.0.0.1:1/generate_204", deadline)
                }
                assertTrue(socks.awaitClient(5_000), "the probe did not connect")
                val started = System.nanoTime()
                probe.cancelAndJoin()
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                // Its request would otherwise wait for the rest of the 12-second budget, and keep the tunnel up.
                assertTrue(elapsedMs < 2_000, "ended after $elapsedMs ms")
            }
        }
    }

    @Test
    fun aProbeRequestThatStartsAfterItsTestEndedIsCancelled() {
        val client = OkHttpClient()
        val request = Request.Builder().url("http://127.0.0.1:1/").build()
        val calls = AetherDelayTester.ProbeCalls()

        val running = calls.start(client.newCall(request))
        calls.cancel()
        val late = calls.start(client.newCall(request))

        assertTrue(running.isCanceled())
        assertTrue(late.isCanceled())
    }

    /** Accepts connections and never answers, like a tunnel whose far end is gone. */
    private class StallingSocksStub : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val clients = mutableListOf<Socket>()
        private val connected = CountDownLatch(1)
        val port: Int get() = server.localPort

        /** Whether a client connected within [timeoutMs]. */
        fun awaitClient(timeoutMs: Long): Boolean = connected.await(timeoutMs, TimeUnit.MILLISECONDS)

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    synchronized(clients) { clients.add(client) }
                    connected.countDown()
                }
            }
        }

        override fun close() {
            server.close()
            synchronized(clients) { clients.forEach { runCatching { it.close() } } }
        }
    }

    private class HttpStub(private val status: String, private val bodyLength: Int = 0) : AutoCloseable {
        val requests = AtomicInteger()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) { answer(client) }
                }
            }
        }

        private fun answer(client: Socket) = client.use { socket ->
            val reader = socket.getInputStream().bufferedReader()
            while (reader.readLine()?.isNotEmpty() == true) Unit
            requests.incrementAndGet()
            val response = "HTTP/1.1 $status\r\nContent-Length: $bodyLength\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(response.toByteArray())
        }

        override fun close() = server.close()
    }

    private class SocksStub : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) { runCatching { relay(client) } }
                }
            }
        }

        private fun relay(client: Socket) = client.use {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            input.readByte()
            repeat(input.readUnsignedByte()) { input.readByte() }
            output.write(byteArrayOf(5, 0))
            repeat(3) { input.readByte() }
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                else -> return@use
            }
            val targetPort = input.readUnsignedShort()
            Socket(host, targetPort).use { target ->
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                thread(isDaemon = true) {
                    runCatching { client.getInputStream().copyTo(target.getOutputStream()) }
                }
                runCatching { target.getInputStream().copyTo(output) }
            }
        }

        override fun close() = server.close()
    }
}
