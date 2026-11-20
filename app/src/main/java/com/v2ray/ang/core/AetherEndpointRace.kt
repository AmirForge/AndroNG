package com.v2ray.ang.core

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Owns the competing native processes and the stable loopback listener for one session. */
internal class AetherEndpointRace(
    private val listenPort: Int,
    private val commands: List<List<String>>,
    private val startProcess: (List<String>, Int) -> Process,
    private val onOutput: (String) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val onWinner: (Int) -> Unit,
    private val onExit: () -> Unit,
) : Closeable {
    private data class Candidate(val index: Int, val port: Int, val process: Process)

    private val lock = Any()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "aether-endpoint-race").apply { isDaemon = true }
    }
    private val processes = mutableListOf<Candidate>()
    private val ports = mutableSetOf<Int>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val selected = CountDownLatch(1)
    private val remaining = AtomicInteger(commands.size)
    private var listener: ServerSocket? = null
    @Volatile private var closed = false
    @Volatile private var winner: Candidate? = null

    fun start() {
        require(commands.size in 2..50)
        synchronized(lock) {
            if (closed) return
            listener = ServerSocket().also { server ->
                try {
                    server.reuseAddress = true
                    server.bind(InetSocketAddress("127.0.0.1", listenPort))
                } catch (error: IOException) {
                    server.close()
                    throw error
                }
            }
            executor.execute { serve() }
            commands.forEachIndexed { index, arguments -> executor.execute { compete(index, arguments) } }
        }
    }

    private fun compete(index: Int, arguments: List<String>) {
        var candidate: Candidate? = null
        try {
            candidate = synchronized(lock) {
                if (closed || winner != null) return
                var port: Int
                do {
                    port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                } while (!ports.add(port) || port == listenPort)
                Candidate(index, port, startProcess(arguments, port)).also { processes += it }
            }
            val running = candidate
            executor.execute {
                try {
                    running.process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onOutput) }
                } catch (_: IOException) {
                    // Stopping a competitor closes its output pipe.
                }
            }
            if (awaitSocks(running)) choose(running)
            if (winner === running && !closed) {
                running.process.waitFor()
                fail()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!closed && winner == null) onFailure(e)
        } finally {
            if (candidate != null && winner !== candidate) candidate.process.destroyForcibly()
            if (remaining.decrementAndGet() == 0 && winner == null) fail()
        }
    }

    private fun awaitSocks(candidate: Candidate): Boolean {
        while (!closed && winner == null && candidate.process.isAlive) {
            try {
                Socket().use { socket ->
                    sockets += socket
                    try {
                        socket.connect(InetSocketAddress("127.0.0.1", candidate.port), 250)
                        if (socket.localPort == candidate.port) return@use
                        socket.soTimeout = 250
                        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                        val input = socket.getInputStream()
                        var version = -1
                        while (!closed && winner == null && candidate.process.isAlive) {
                            try {
                                if (version < 0) version = input.read()
                                if (version != 5) return@use
                                return input.read() == 0
                            } catch (_: SocketTimeoutException) {
                                // Keep one greeting pending until the native tunnel is validated.
                            }
                        }
                    } finally {
                        sockets -= socket
                    }
                }
            } catch (_: IOException) {
                // The native listener may still be starting.
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun choose(candidate: Candidate) {
        val losers = synchronized(lock) {
            if (closed || winner != null || !candidate.process.isAlive) return
            winner = candidate
            processes.filter { it !== candidate }
        }
        losers.forEach { it.process.destroyForcibly() }
        onWinner(candidate.index)
        selected.countDown()
    }

    private fun serve() {
        try {
            selected.await()
            while (!closed) {
                val socket = listener?.accept() ?: return
                synchronized(lock) {
                    if (closed) {
                        socket.close()
                        return
                    }
                    sockets += socket
                    executor.execute { relay(socket) }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            if (!closed) {
                onFailure(e)
                fail()
            }
        }
    }

    private fun relay(client: Socket) {
        val upstream = Socket()
        synchronized(lock) {
            if (closed) {
                client.close()
                return
            }
            sockets += upstream
        }
        try {
            val port = winner?.port ?: return
            upstream.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            val sent = executor.submit {
                client.getInputStream().copyTo(upstream.getOutputStream())
                upstream.shutdownOutput()
            }
            upstream.getInputStream().copyTo(client.getOutputStream())
            client.shutdownOutput()
            sent.get()
        } catch (_: Exception) {
            // A client, the winning core, or session teardown closed this connection.
        } finally {
            runCatching { client.close() }
            runCatching { upstream.close() }
            sockets -= client
            sockets -= upstream
        }
    }

    private fun fail() {
        val report = synchronized(lock) {
            if (closed) false else {
                close()
                true
            }
        }
        if (report) onExit()
    }

    override fun close() {
        val running = synchronized(lock) {
            if (closed) return
            closed = true
            selected.countDown()
            runCatching { listener?.close() }
            processes.toList()
        }
        sockets.forEach { runCatching { it.close() } }
        running.forEach { it.process.destroyForcibly() }
        executor.shutdown()
        // Every process was signalled together. Wait for its exit before a replacement session starts.
        val deadline = System.nanoTime() + 2_000_000_000L
        running.forEach {
            val left = (deadline - System.nanoTime()).coerceAtLeast(0)
            it.process.waitFor(left, java.util.concurrent.TimeUnit.NANOSECONDS)
        }
    }
}
