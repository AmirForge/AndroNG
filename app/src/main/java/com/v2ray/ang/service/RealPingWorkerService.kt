package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.AetherDelayTester
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.LocalProxyBatchTester
import com.v2ray.ang.core.LocalProxyTestPorts
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val healthOnly: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency().let { if (healthOnly) it.coerceIn(1, 4) else it }
    private val dispatcher = Executors.newFixedThreadPool(if (onlyTcp) concurrency * 2 else concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        if (!onlyTcp) {
            scope.launch {
                val delivered = ConcurrentHashMap.newKeySet<String>()
                try {
                    com.v2ray.ang.core.LocalProxyBatchTester(context).run(guids) { event ->
                        if (scope.isActive) {
                            if (event !is RealPingEvent.Result || delivered.add(event.guid)) onEvent(event)
                        }
                    }
                    if (isActive) onEvent(RealPingEvent.Finish("0"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "Local test batch failed mode=test", error)
                    delay(com.v2ray.ang.core.LocalProxyBatchTester.TEST_TIMEOUT_MS)
                    if (isActive) {
                        guids.distinct().filterNot(delivered::contains).forEach { onEvent(RealPingEvent.Result(it, -1L)) }
                        onEvent(RealPingEvent.Finish("0"))
                    }
                } finally { close() }
            }
            return
        }
        scope.launch {
            try {
                AetherCoreManager.withLocalTestBatch {
                    runTcpBatch()
                }
                if (isActive) onEvent(RealPingEvent.Finish("0"))
            } finally {
                close()
            }
        }
    }

    private suspend fun runTcpBatch() = coroutineScope {
        val profiles = guids.distinct()
        totalCount.set(profiles.size)
        profiles.forEachIndexed { index, guid ->
            val ports = LocalProxyTestPorts.PROXY_PORTS
            val localPort = ports.first + index
            launch {
                runningCount.incrementAndGet()
                var reported = false
                fun publish(result: Long) {
                    if (!reported && scope.isActive) {
                        reported = true
                        onEvent(RealPingEvent.Result(guid, result))
                    }
                }
                try {
                    val result = if (localPort in ports) startTcping(guid, localPort) else -1L
                    publish(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "RealPing: probe failed mode=test healthOnly=$healthOnly guid=$guid", error)
                    publish(-1L)
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Progress("$left / $count"))
                    }
                }
            }
        }

    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startTcping(guid: String, localPort: Int): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.AETHER) {
            return AetherDelayTester.reachability(config) { host, port ->
                SpeedtestManager.socketConnectTime(host, port, LocalProxyBatchTester.TEST_TIMEOUT_MS.toInt(), localPort)
            }
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, LocalProxyBatchTester.TEST_TIMEOUT_MS.toInt(), localPort)

            return tcpTime
        }

        return retFailure
    }
}
