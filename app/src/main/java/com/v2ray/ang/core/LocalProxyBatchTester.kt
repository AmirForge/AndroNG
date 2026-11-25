package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class LocalProxyBatchTester(private val context: Context) {
    companion object { internal const val TEST_TIMEOUT_MS = 15_000L }

    suspend fun run(guids: List<String>, onEvent: (RealPingEvent) -> Unit) =
        AetherCoreManager.withLocalTestBatch {
            withContext(Dispatchers.IO) {
                val plan = LocalProxyTestPlan()
                val rejected = mutableListOf<String>()
                LocalProxyTestPorts().use { ports ->
                    for (guid in guids.distinct()) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val port = ports.allocateProxy()
                            val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                            require(result.status)
                            plan.add(guid, result.content, result.aetherCore, port, ports::allocateAuxiliary)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            rejected += guid
                            LogUtil.e(AppConfig.TAG, "Local proxy test configuration failed mode=test guid=$guid", error)
                        }
                    }
                }
                CoreNativeManager.initCoreEnv(context)
                val controller = CoreNativeManager.newCoreController(object : CoreCallbackHandler {
                    override fun startup() = 0L
                    override fun shutdown() = 0L
                    override fun onEmitStatus(code: Long, message: String?) = 0L
                })
                try {
                    var runnable = plan.entries.toList()
                    if (runnable.isNotEmpty()) {
                        try {
                            controller.startLoop(plan.content(), 0)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            LogUtil.e(AppConfig.TAG, "Local proxy test batch initialization failed mode=test", error)
                            controller.stopLoop()
                            // Find a malformed custom outbound without failing every valid profile beside it.
                            runnable = plan.entries.filter { entry ->
                                try {
                                    controller.startLoop(plan.content(listOf(entry)), 0)
                                    true
                                } catch (failure: Exception) {
                                    rejected += entry.guid
                                    LogUtil.e(AppConfig.TAG, "Local proxy test startup failed mode=test guid=${entry.guid}", failure)
                                    false
                                } finally { controller.stopLoop() }
                            }
                            if (runnable.isNotEmpty()) controller.startLoop(plan.content(runnable), 0)
                        }
                    }
                    val remaining = AtomicInteger(guids.distinct().size)
                    val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    val deadline = testDeadline()
                    suspend fun publish(guid: String, result: Long) {
                        if (result < 0) waitForDeadline(deadline)
                        currentCoroutineContext().ensureActive()
                        if (!delivered.add(guid)) return
                        val port = plan.entries.firstOrNull { it.guid == guid }?.proxyPort
                        LogUtil.i(AppConfig.TAG, "RealDelay result guid=$guid port=$port delayMs=$result remainingMs=${TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())}")
                        onEvent(RealPingEvent.Result(guid, result))
                        onEvent(RealPingEvent.Progress(remaining.decrementAndGet().toString()))
                    }
                    coroutineScope {
                        rejected.forEach { guid ->
                            launch {
                                publish(guid, -1L)
                            }
                        }
                        runnable.forEach { entry ->
                            launch {
                                try {
                                    if (entry.aether == null) {
                                        publish(entry.guid, probe(entry.proxyPort, deadline))
                                    } else {
                                        val core = entry.aether
                                        val result = AetherCoreManager.withLocalTestProcess(context, core, requireNotNull(entry.exitPort), entry.proxyPort) { output ->
                                            val validated = java.util.concurrent.atomic.AtomicBoolean(false)
                                            val monitor = launch {
                                                for (line in output) {
                                                    if ("tunnel validated (end-to-end data confirmed)" in line) {
                                                        validated.set(true)
                                                    }
                                                }
                                            }
                                            val value = try {
                                                val delay = probe(entry.proxyPort, deadline)
                                                if (delay < 0 && validated.get()) 0L else delay
                                            } finally {
                                                monitor.cancel()
                                            }
                                            publish(entry.guid, value)
                                            value
                                        }
                                        if (result == null) {
                                            publish(entry.guid, -1L)
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    LogUtil.e(AppConfig.TAG, "Local proxy test failed mode=test guid=${entry.guid}", error)
                                    publish(entry.guid, -1L)
                                }
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { controller.stopLoop() }
                }
            }
        }

    private fun testDeadline(): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_TIMEOUT_MS)

    private suspend fun probe(port: Int, deadline: Long): Long = coroutineScope {
        val urls = listOf(
            SettingsManager.getDelayTestUrl(),
            SettingsManager.getDelayTestUrl(true),
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        ).distinct()
        val results = kotlinx.coroutines.channels.Channel<Long>(urls.size)
        val probes = urls.mapIndexed { index, url ->
            launch {
                // Keep the first SOCKS handshake alive while the tunnel starts. Stagger
                // fallback destinations so a subscription does not open four per card at once.
                if (index > 0) delay(index * 2_000L)
                var result = -1L
                while (System.nanoTime() < deadline) {
                    result = AetherDelayTester.cancellableRequestDelay(port, url, deadline, firstSuccess = true)
                    if (result > 0) break
                    delay(150)
                }
                results.send(result)
            }
        }
        try {
            repeat(urls.size) {
                val result = results.receive()
                if (result > 0) return@coroutineScope result
            }
            -1L
        } finally {
            probes.forEach { it.cancel() }
        }
    }

    private suspend fun waitForDeadline(deadline: Long) {
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return
            delay(TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1L))
        }
    }
}
