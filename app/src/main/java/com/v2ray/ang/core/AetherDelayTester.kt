package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.blackholeSink
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

object AetherDelayTester {

    /**
     * The whole test, tunnel start included. The native probe gives every other profile a 12-second
     * HTTP client and the current-server test a 12-second context; an Aether profile gets the same
     * budget, so a tunnel that cannot come up fails within it instead of after a minute-long scan.
     */
    internal const val TEST_BUDGET_MS = 12_000L
    private const val HEALTH_BUDGET_MS = 6_000L
    private const val POLL_INTERVAL_MS = 250L
    private const val ATTEMPTS = 2

    /** No further attempt starts with less than this left; it could not tell anything. */
    private const val MIN_REQUEST_MS = 1_000L

    /** Port every Cloudflare edge answers on TCP, whatever port the tunnel itself uses. */
    private const val EDGE_TCP_PORT = 443
    private const val REACH_TIMEOUT_MS = 1000

    /** Stored for "no result": the server row shows nothing instead of a stale or a failed number. */
    const val UNTESTED = 0L

    private val tunnels = Mutex()

    suspend fun measure(
        context: Context, guid: String, profile: ProfileItem, url: String,
        firstSuccess: Boolean = false, onHealthy: (Long) -> Unit = {},
    ): Long = measureVia(context, guid, AetherCore.of(profile), firstSuccess = firstSuccess, onHealthy = onHealthy) { port, deadline, _ ->
        cancellableRequestDelay(port, url, deadline, firstSuccess)
    }

    /** Give each test tunnel its own listener and point any chained Xray configuration at it. */
    suspend fun measureVia(
        context: Context,
        guid: String,
        core: AetherCore,
        configuration: String? = null,
        firstSuccess: Boolean = false,
        onHealthy: (Long) -> Unit = {},
        raceEndpoints: Boolean = false,
        budgetMs: Long = if (firstSuccess) HEALTH_BUDGET_MS else TEST_BUDGET_MS,
        probe: suspend (port: Int, deadline: Long, configuration: String?) -> Long,
    ): Long = tunnels.withLock {
        throughNewTunnel(context, guid, core, configuration, budgetMs, raceEndpoints) { port, deadline, testConfiguration ->
            publishHealthy(firstSuccess, onHealthy) { probe(port, deadline, testConfiguration) }
        }
    }

    /** Publish a real response before teardown. */
    internal suspend fun publishHealthy(firstSuccess: Boolean, onHealthy: (Long) -> Unit, probe: suspend () -> Long): Long {
        val result = probe()
        // Successful sub-millisecond responses round to zero; zero is reserved for untested rows.
        val value = if (firstSuccess && result == 0L) 1L else result
        if (firstSuccess && value > 0) onHealthy(value)
        return value
    }

    /**
     * The cheap probe behind "TCP ping": a TCP connect to the pinned edge address instead of a
     * full tunnel. It tells whether that edge is reachable, not whether the tunnel works, and a
     * profile left to the scanner has nothing to probe.
     */
    fun reachability(
        profile: ProfileItem,
        connect: (host: String, port: Int) -> Long = { host, port -> SpeedtestManager.socketConnectTime(host, port, REACH_TIMEOUT_MS) },
    ): Long {
        val host = probeHost(profile) ?: return UNTESTED
        return connect(host, EDGE_TCP_PORT)
    }

    internal fun probeHost(profile: ProfileItem): String? =
        if (AetherProtocol.fromString(profile.aetherProtocol).twoHops) {
            AetherEndpoint.parse(profile.aetherWiwOuter)?.host
        } else {
            AetherEndpoint.of(profile.server, profile.serverPort)?.host
        }

    private suspend fun throughNewTunnel(
        context: Context,
        guid: String,
        core: AetherCore,
        configuration: String?,
        budgetMs: Long,
        raceEndpoints: Boolean,
        probe: suspend (port: Int, deadline: Long, configuration: String?) -> Long,
    ): Long {
        // The clock starts before the spawn: the budget covers the whole test, as it does for every other profile.
        val deadline = deadlineAfter(budgetMs)
        val port = withContext(Dispatchers.IO) { allocateTestPort(core.port) }
        val arguments = AetherCoreManager.withListener(core.arguments, AetherCoreManager.listenerFlagOf(core.arguments), port)
        val testConfiguration = configuration?.let { content ->
            val json = JsonUtil.parseString(content) ?: return -1L
            if (!AetherDependency.moveSocksOutbounds(json, core.port, port)) return -1L
            json.toString()
        }
        return AetherCoreManager.withProcess(
            context = context,
            arguments = arguments,
            exit = core.exit,
            source = "aether-test",
            onOutput = {},
            configuration = testConfiguration,
            // A health test uses the same single endpoint as menu Real delay; racing the pool
            // here spawns extra cores and delays every subsequent test behind the tunnel mutex.
            alternatives = if (raceEndpoints) core.alternatives else emptyList(),
        ) { output ->
            val needsWord = AetherCoreManager.readyNeedsWord(arguments) && AetherCoreManager.showsInfo(arguments)
            if (!awaitListening(port, output, deadline, needsWord)) {
                LogUtil.w(AppConfig.TAG, "AetherTest: the tunnel did not come up, guid=$guid")
                return@withProcess -1L
            }
            val delay = probe(port, deadline, testConfiguration)
            if (delay < 0) LogUtil.w(AppConfig.TAG, "AetherTest: no answer through the tunnel, guid=$guid")
            delay
        } ?: -1L
    }

    internal fun allocateTestPort(sessionPort: Int): Int {
        val loopback = InetAddress.getByName(AppConfig.LOOPBACK)
        // Keep the socket open while asking the OS for another port if it chose the configured one.
        return ServerSocket(0, 1, loopback).use { socket ->
            if (socket.localPort == sessionPort) {
                ServerSocket(0, 1, loopback).use { it.localPort }
            } else {
                socket.localPort
            }
        }
    }

    /**
     * Waits until the listener on [port] answers, and, with [needsWord], until the core has also
     * written its word that the listener carries traffic; false when the core ends or [deadline] passes.
     */
    internal suspend fun awaitListening(port: Int, output: ReceiveChannel<String>, deadline: Long, needsWord: Boolean = false): Boolean {
        var wordSeen = !needsWord
        while (System.nanoTime() < deadline) {
            while (true) {
                val line = output.tryReceive().getOrNull() ?: break
                if (!wordSeen && AetherCoreManager.isReadyWord(line)) wordSeen = true
            }
            if (output.isClosedForReceive) return false
            if (wordSeen && withContext(Dispatchers.IO) { AetherCoreManager.answersSocks(port) }) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * [requestDelay], which a cancelled test ends at once. A cancelled coroutine does not interrupt the
     * thread that its requests block, so they are cancelled from here: they would otherwise keep a test
     * tunnel up for the rest of the budget, also beside a session that starts meanwhile.
     */
    internal suspend fun cancellableRequestDelay(port: Int, url: String, deadline: Long, firstSuccess: Boolean = false): Long = coroutineScope {
        val calls = ProbeCalls()
        val probe = async(Dispatchers.IO) { requestDelay(port, url, deadline, calls, firstSuccess) }
        try {
            probe.await()
        } catch (e: CancellationException) {
            calls.cancel()
            throw e
        }
    }

    /** The requests of a probe, which [cancel] ends; a request that starts after it is cancelled as it starts. */
    internal class ProbeCalls {
        private val calls = mutableListOf<Call>()
        private var cancelled = false

        @Synchronized
        fun start(call: Call): Call {
            if (cancelled) call.cancel() else calls += call
            return call
        }

        @Synchronized
        fun cancel() {
            cancelled = true
            calls.forEach(Call::cancel)
        }
    }

    /**
     * The best of up to [ATTEMPTS] requests through the SOCKS port at [port], each given what is
     * left of the budget ending at [deadline]; -1 when none of them was answered in time. Its
     * requests start through [calls].
     */
    internal fun requestDelay(
        port: Int,
        url: String,
        deadline: Long = deadlineAfter(TEST_BUDGET_MS),
        calls: ProbeCalls = ProbeCalls(),
        firstSuccess: Boolean = false,
    ): Long {
        val request = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            return -1L
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port)))
            .build()
        return try {
            var best = -1L
            repeat(ATTEMPTS) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining < MIN_REQUEST_MS) return best
                // Derived clients share the pool, so a second attempt measures a warm connection. The connect
                // timeout is set as well because the SOCKS handshake happens inside the connect.
                val attempt = client.newBuilder()
                    .connectTimeout(remaining, TimeUnit.MILLISECONDS)
                    .callTimeout(remaining, TimeUnit.MILLISECONDS)
                    .build()
                val time = timedRequest(attempt, request, calls, headersOnly = firstSuccess) ?: return@repeat
                if (firstSuccess) return time.coerceAtLeast(1L)
                if (best < 0 || time < best) best = time
            }
            best
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun deadlineAfter(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)

    private fun timedRequest(client: OkHttpClient, request: Request, calls: ProbeCalls, headersOnly: Boolean = false): Long? = try {
        val started = System.nanoTime()
        calls.start(client.newCall(request)).execute().use { response ->
            if (!headersOnly) response.body?.source()?.readAll(blackholeSink())
            if (response.code == 200 || response.code == 204) {
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            } else {
                null
            }
        }
    } catch (_: IOException) {
        null
    }
}
