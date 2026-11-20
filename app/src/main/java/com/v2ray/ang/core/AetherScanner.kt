package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanNetwork
import com.v2ray.ang.enums.AetherTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AetherScanResult(
    val endpoint: AetherEndpoint,
    val innerHop: AetherEndpoint? = null,
    val candidates: List<String> = emptyList(),
    val transport: AetherTransport? = null,
)

object AetherScanner {

    private const val SCAN_TIMEOUT_MS = 8 * 60_000L

    private val masqueGateway = Regex("""selected MASQUE gateway (\S+)""")
    private val wireguardEndpoint = Regex("""selected WireGuard endpoint (\S+)""")
    private val goolHops = Regex("""using cloudflare edge (\S+) \(outer\) and (\S+) \(inner\)""")
    private val mimHops = Regex("""masque-in-masque ready: (\S+) \(outer\) and (\S+) \(inner\)""")
    private val goolOverMasqueReady = Regex("""gool ready: masque (\S+) carries wireguard (\S+)""")
    private val exitAccepted = Regex("""exit location \S+ accepted""")
    private val exitRejected = Regex("""exit location \S+ rejected""")

    internal suspend fun prepareNetwork(context: Context): Boolean = withContext(Dispatchers.IO) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return@withContext true
        fun usesVpn(): Boolean = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        if (!usesVpn()) return@withContext true
        if (VpnService.prepare(context) != null) return@withContext false

        LauncherManager.stopService(context)
        withTimeoutOrNull(5_000L) {
            while (usesVpn()) delay(150)
            true
        } ?: false
    }

    suspend fun scan(
        context: Context,
        profile: ProfileItem,
        onOutput: (String) -> Unit = {},
        onCandidates: (List<String>) -> Unit = {},
    ): AetherScanResult? {
        return AetherCoreManager.withProbeExit(context, AetherExit.of(profile), "aether-scan") { exitPort ->
            collectTransportCandidates(profile, onOutput, onCandidates) { next, output, timeout ->
                scanOnce(context, next, output, timeout, exitPort)
            }
        }
    }

    internal fun scanTransports(profile: ProfileItem): List<AetherTransport> {
        if (!AetherProtocol.fromString(profile.aetherProtocol).overMasque) return listOf(AetherTransport.HTTP3)
        return when (AetherScanNetwork.fromString(profile.aetherScanNetwork)) {
            AetherScanNetwork.TCP -> listOf(AetherTransport.HTTP2)
            AetherScanNetwork.UDP -> listOf(AetherTransport.HTTP3)
            AetherScanNetwork.BOTH -> listOf(AetherTransport.fromString(profile.aetherTransport)).let {
                it + AetherTransport.entries.filterNot(it::contains)
            }
        }
    }

    /** Both carriers compete for one result list; filling it cancels every remaining scan. */
    internal suspend fun collectTransportCandidates(
        profile: ProfileItem,
        onOutput: (String) -> Unit,
        onCandidates: (List<String>) -> Unit,
        scan: suspend (ProfileItem, (String) -> Unit, Long) -> AetherScanResult?,
    ): AetherScanResult? = coroutineScope {
        val count = endpointCount(profile)
        val transports = scanTransports(profile)
        val overMasque = AetherProtocol.fromString(profile.aetherProtocol).overMasque
        val found = linkedSetOf<String>()
        val lock = Any()
        val done = CompletableDeferred<AetherScanResult?>()
        val remaining = AtomicInteger(transports.size)
        var published = 0
        fun collect(result: AetherScanResult, transport: AetherTransport) = synchronized(lock) {
            val tagged = encode(result.copy(candidates = emptyList(), transport = transport.takeIf { overMasque }))
            if (found.size < count && found.add(tagged)) {
                if (found.size % 10 == 0 || found.size == count) {
                    onCandidates(found.toList())
                    published = found.size
                }
                if (found.size == count) done.complete(decode(found.first())!!.copy(candidates = found.toList()))
            }
        }
        val jobs = transports.map { transport -> launch {
            try {
                val next = profile.copy(aetherTransport = transport.type, aetherEndpointCount = count)
                val result = collectCandidates(next, onOutput, {}, onCandidate = { collect(it, transport) }) { output, timeout ->
                    scan(next, output, timeout)
                }
                result?.let { value ->
                    value.candidates.ifEmpty { listOf(encode(value)) }.mapNotNull(::decode).forEach { collect(it, transport) }
                }
            } catch (cancelled: CancellationException) {
                done.completeExceptionally(cancelled)
                throw cancelled
            } finally {
                if (remaining.decrementAndGet() == 0) done.complete(null)
            }
        } }
        try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) { done.await() }
        } finally {
            jobs.forEach { it.cancel() }
            withContext(NonCancellable) { jobs.forEach { it.cancelAndJoin() } }
            done.cancel()
            // Publish useful partial work even when the caller's shorter deadline cancels this scan.
            synchronized(lock) {
                if (found.size != published) { onCandidates(found.toList()); published = found.size }
            }
        }
        synchronized(lock) {
            val values = found.toList()
            if (values.size != published) onCandidates(values)
            values.firstOrNull()?.let(::decode)?.copy(candidates = values)
        }
    }

    internal suspend fun collectCandidates(
        profile: ProfileItem,
        onOutput: (String) -> Unit,
        onCandidates: (List<String>) -> Unit,
        timeoutMs: Long = SCAN_TIMEOUT_MS,
        excluded: Set<String> = emptySet(),
        onCandidate: (AetherScanResult) -> Unit = {},
        scan: suspend ((String) -> Unit, Long) -> AetherScanResult?,
    ): AetherScanResult? {
        val count = endpointCount(profile)
        if (count == 1 && excluded.isEmpty()) return scan(onOutput, timeoutMs)
        val found = linkedSetOf<String>()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var emptyScans = 0
        var publishedCount = 0
        while (found.size < count && System.nanoTime() < deadline && emptyScans < 3) {
            val before = found.size
            fun collect(result: AetherScanResult) {
                val encoded = encode(result)
                if (found.size >= count || encoded in excluded || !found.add(encoded)) return
                onCandidate(result)
                if (found.size % 10 == 0 || found.size == count) {
                    onCandidates(found.toList())
                    publishedCount = found.size
                }
            }
            val result = scan({ line ->
                onOutput(line)
                if (AetherProtocol.fromString(profile.aetherProtocol) == AetherProtocol.MASQUE && profile.aetherExitLoc.isNullOrBlank()) {
                    candidate.find(line)?.groupValues?.get(1)?.let(::endpointOf)
                        ?.let { collect(AetherScanResult(it)) }
                }
            }, (deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000)
            result?.let(::collect)
            emptyScans = if (found.size == before) emptyScans + 1 else 0
        }
        val candidates = found.toList()
        if (candidates.size != publishedCount) onCandidates(candidates)
        return candidates.firstOrNull()?.let(::decode)?.copy(candidates = candidates)
    }

    fun endpointCount(profile: ProfileItem): Int = (profile.aetherEndpointCount ?: 1).coerceIn(1, 50)

    fun encode(result: AetherScanResult): String =
        result.transport?.let { "${it.type}@" }.orEmpty() + listOfNotNull(result.endpoint, result.innerHop).joinToString("|")

    fun decode(value: String): AetherScanResult? {
        val transport = if ('@' in value) AetherTransport.entries.find { it.type == value.substringBefore('@') } ?: return null else null
        val parts = value.substringAfter('@', value).split('|')
        if (parts.size !in 1..2) return null
        val endpoint = AetherEndpoint.parse(parts[0]) ?: return null
        val inner = if (parts.size == 2) AetherEndpoint.parse(parts[1]) ?: return null else null
        return AetherScanResult(endpoint, inner, transport = transport)
    }

    fun candidates(profile: ProfileItem): List<AetherScanResult> = profile.aetherEndpoints.orEmpty()
        .asSequence().filterNotNull().mapNotNull(::decode).distinct().take(endpointCount(profile)).toList()

    private val candidate = Regex("""candidate ok (\S+) rtt=""")

    private suspend fun scanOnce(
        context: Context,
        profile: ProfileItem,
        onOutput: (String) -> Unit,
        timeoutMs: Long = SCAN_TIMEOUT_MS,
        exitPort: Int,
    ): AetherScanResult? {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        val port = withContext(Dispatchers.IO) { AetherCoreManager.scanPort(profile) }
        return AetherCoreManager.runUntilOnExit(
            context = context,
            arguments = AetherCoreManager.buildArguments(profile, port, scan = true),
            exitPort = exitPort,
            timeoutMs = timeoutMs,
            source = "aether-scan",
            onOutput = onOutput,
            match = matcher(protocol, exitRuled = !profile.aetherExitLoc.isNullOrBlank()),
        )
    }

    /**
     * What ends a scan, line by line. Without an exit rule, the line that names the endpoint. With
     * one, the core names its endpoint before it checks the exit behind it and looks again when the
     * rule refuses it, so the endpoint named last counts only once the core has accepted its exit.
     * Masque-in-masque and WireGuard over MASQUE say they are ready after that check, so their line
     * stands on its own either way.
     */
    internal fun matcher(protocol: AetherProtocol, exitRuled: Boolean): (String) -> AetherScanResult? {
        if (!exitRuled || protocol == AetherProtocol.MIM || protocol == AetherProtocol.WG_OVER_MASQUE) return { line -> parse(protocol, line) }
        var named: AetherScanResult? = null
        return { line ->
            val found = parse(protocol, line)
            when {
                found != null -> {
                    named = found
                    null
                }

                exitAccepted.containsMatchIn(line) -> named
                exitRejected.containsMatchIn(line) -> {
                    named = null
                    null
                }

                else -> null
            }
        }
    }

    fun parse(protocol: AetherProtocol, line: String): AetherScanResult? = when (protocol) {
        AetherProtocol.GOOL -> hopsOf(goolHops, line)
        AetherProtocol.MIM -> hopsOf(mimHops, line)
        AetherProtocol.MASQUE -> masqueGateway.find(line)?.let { endpointOf(it.groupValues[1]) }?.let(::AetherScanResult)
        AetherProtocol.WIREGUARD -> wireguardEndpoint.find(line)?.let { endpointOf(it.groupValues[1]) }?.let(::AetherScanResult)
        // Once the whole tunnel carries, the gateway alone: the WireGuard endpoint inside it is the profile's, which the scan kept.
        AetherProtocol.WG_OVER_MASQUE -> goolOverMasqueReady.find(line)?.let { endpointOf(it.groupValues[1]) }?.let(::AetherScanResult)
    }

    private fun hopsOf(hops: Regex, line: String): AetherScanResult? = hops.find(line)?.let { match ->
        val outer = endpointOf(match.groupValues[1])
        val inner = endpointOf(match.groupValues[2])
        if (outer != null && inner != null) AetherScanResult(outer, inner) else null
    }

    private fun endpointOf(text: String): AetherEndpoint? {
        AetherEndpoint.parse(text)?.let { return it }
        val separator = text.lastIndexOf(':')
        if (separator <= 0) return null
        return AetherEndpoint.of(text.substring(0, separator), text.substring(separator + 1))
    }
}
