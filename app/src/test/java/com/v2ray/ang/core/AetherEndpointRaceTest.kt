package com.v2ray.ang.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AetherEndpointRaceTest {
    @Test
    fun anOccupiedListenerNeverStartsCompetitorsAndCanBeClosedRepeatedly() {
        ServerSocket(0).use { busy ->
            var starts = 0
            val race = AetherEndpointRace(busy.localPort, listOf(listOf("a"), listOf("b")),
                { _, _ -> starts++; error("Must not start") }, {}, {}, {}, {})
            assertThrows(IOException::class.java) { race.start() }
            race.close()
            race.close()
            assertEquals(0, starts)
        }
    }
    private class NativeServer(port: Int, private val ready: CountDownLatch, private val delay: Long) : Process() {
        private val ended = CountDownLatch(1)
        private val listener = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        private val clients = ConcurrentHashMap.newKeySet<Socket>()

        init {
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    while (isAlive) {
                        val client = listener.accept()
                        clients += client
                        kotlin.concurrent.thread(isDaemon = true) {
                            try {
                                client.use {
                                    val input = it.getInputStream()
                                    repeat(3) { input.read() }
                                    ready.await()
                                    Thread.sleep(delay)
                                    val output = it.getOutputStream()
                                    output.write(byteArrayOf(5, 0))
                                    input.copyTo(output)
                                    it.shutdownOutput()
                                }
                            } catch (_: Exception) {
                            } finally {
                                clients -= client
                            }
                        }
                    }
                } catch (_: IOException) {
                }
            }
        }

        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun exitValue(): Int = if (ended.count == 0L) 0 else throw IllegalThreadStateException()
        override fun destroy() {
            ended.countDown()
            listener.close()
            clients.forEach { runCatching { it.close() } }
        }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test
    fun `fifty competitors leave exactly one winner and forward through the existing winning socket server`() {
        val processes = ConcurrentHashMap<Int, NativeServer>()
        val allStarted = CountDownLatch(50)
        val won = CountDownLatch(1)
        val wins = AtomicInteger()
        val port = freePort()
        val race = AetherEndpointRace(port, (0..49).map { listOf(it.toString()) }, { arguments, candidatePort ->
            val index = arguments.single().toInt()
            NativeServer(candidatePort, allStarted, if (index == 23) 0 else 1_000).also {
                processes[index] = it
                allStarted.countDown()
            }
        }, {}, { throw AssertionError(it) }, { wins.incrementAndGet(); won.countDown() }, { fail("winner exited") })
        try {
            race.start()
            assertTrue(won.await(10, TimeUnit.SECONDS))
            assertEquals(1, wins.get())
            assertEquals(setOf(23), processes.filterValues { it.isAlive }.keys)
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 5_000
                val output = client.getOutputStream()
                val input = client.getInputStream()
                output.write(byteArrayOf(5, 1, 0))
                assertEquals(5, input.read())
                assertEquals(0, input.read())
                output.write("payload".toByteArray())
                client.shutdownOutput()
                assertEquals("payload", input.readBytes().decodeToString())
            }
        } finally {
            race.close()
        }
        assertTrue(processes.values.none { it.isAlive })
        ServerSocket(port).close()
    }

    @Test
    fun `all failed launches report one failure and release the front port`() {
        val exited = CountDownLatch(1)
        val exits = AtomicInteger()
        val failures = AtomicInteger()
        val port = freePort()
        val race = AetherEndpointRace(port, List(3) { emptyList() }, { _, _ -> throw IOException("start failed") }, {},
            { failures.incrementAndGet() }, { fail("no winner expected") }, { exits.incrementAndGet(); exited.countDown() })
        race.start()
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(3, failures.get())
        assertEquals(1, exits.get())
        race.close()
        ServerSocket(port).close()
    }

    @Test
    fun `stop during validation kills every process and repeated stop stays closed`() {
        val started = CountDownLatch(3)
        val neverReady = CountDownLatch(1)
        val processes = ConcurrentHashMap.newKeySet<NativeServer>()
        val wins = AtomicInteger()
        val port = freePort()
        val race = AetherEndpointRace(port, List(3) { emptyList() }, { _, candidatePort ->
            NativeServer(candidatePort, neverReady, 0).also { processes += it; started.countDown() }
        }, {}, { throw AssertionError(it) }, { wins.incrementAndGet() }, { fail("explicit stop should not report an exit") })
        race.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        race.close()
        race.close()
        neverReady.countDown()
        assertEquals(0, wins.get())
        assertTrue(processes.none { it.isAlive })
        ServerSocket(port).close()
    }
}
