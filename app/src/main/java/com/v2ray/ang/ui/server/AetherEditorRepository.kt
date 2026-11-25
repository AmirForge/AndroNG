package com.v2ray.ang.ui.server

import android.content.Context
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherExit
import com.v2ray.ang.core.AetherExitNode
import com.v2ray.ang.core.AetherIdentityManager
import com.v2ray.ang.core.AetherIdentityStatus
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.core.AetherScanner
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.core.ExitNodeOutbound
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The daemon's live Aether session; [protocol] is null when only its listener could be seen. */
data class AetherSession(val protocol: AetherProtocol?) {

    /** A scan opens a second tunnel on the scanned protocol's key, which disturbs a session using that key. */
    fun disturbedByScanOf(protocol: AetherProtocol): Boolean =
        this.protocol == null || AetherIdentityManager.sharesIdentity(protocol, this.protocol)

    /**
     * Whether the session uses a key that new keys of [kind] would replace; one whose protocol is unknown
     * may use any. The keys of the other protocols can change under it.
     */
    fun usesKeysOf(kind: AetherKeyKind): Boolean =
        protocol == null || AetherIdentityManager.filesOf(protocol).any { it in AetherIdentityManager.filesOf(kind) }
}

interface AetherEditorSource {
    suspend fun isCoreAvailable(): Boolean

    /** The daemon's live Aether session, scanning or connected, or null; every Aether profile shares its key files. */
    suspend fun activeSession(): AetherSession?
    suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult?
    suspend fun scanMany(profile: ProfileItem, onOutput: (String) -> Unit, onCandidates: (List<String>) -> Unit): AetherScanResult? =
        scan(profile, onOutput)
    suspend fun scanForConverter(profile: ProfileItem): AetherScanResult? = scan(profile) {}
    suspend fun scanReferences(profiles: List<ProfileItem>, onResult: suspend (Int, AetherScanResult?) -> Unit) {
        kotlinx.coroutines.coroutineScope {
            profiles.forEachIndexed { index, profile ->
                launch { onResult(index, kotlinx.coroutines.withTimeoutOrNull(10_000L) { scanMany(profile, {}, {}) }) }
            }
        }
    }
    suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus

    /** Which of [files], key files of the identity folder, are not there or not readable as keys. */
    suspend fun missingKeys(files: List<String>): List<String>

    /** The Aether listen port of the settings, which every core of a profile listens on. */
    suspend fun listenPort(): Int

    /**
     * The loopback ports the core of a profile cannot listen on, see [AetherFmt.normalize]: the local proxy's, and the
     * port of the inbound the core dials out through.
     */
    suspend fun takenPorts(): Set<Int>

    /** The names of the profiles a core can dial out through in place of freedom, see [AetherExit.nodes]. */
    suspend fun exitNodes(): List<AetherExitNode>

    /** What the profile named [name] gives as the exit-node now, see [CoreOutboundBuilder.toOutboundOfNode]. */
    suspend fun findExitNode(name: String): ExitNodeOutbound

    /** What the profile named [name] gives as the Outrole now, see [CoreOutboundBuilder.toOutboundOfOutrole]. */
    suspend fun findOutrole(name: String): ExitNodeOutbound
}

class AetherEditorRepository(private val context: Context) : AetherEditorSource {

    override suspend fun isCoreAvailable(): Boolean =
        withContext(Dispatchers.IO) { AetherCoreManager.isSupported(context) }

    // The daemon is the only authority on its state, so this looks for its core process and its
    // listener instead of a UI-side flag; see [sessionOf].
    override suspend fun activeSession(): AetherSession? = withContext(Dispatchers.IO) {
        sessionOf(AetherCoreManager.sessionProtocol(context), AetherCoreManager.canListProcesses()) {
            AetherCoreManager.answersSocks(AetherCoreManager.socksPort)
        }
    }

    override suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult? =
        AetherScanner.scan(context, profile, onOutput)

    override suspend fun scanMany(profile: ProfileItem, onOutput: (String) -> Unit, onCandidates: (List<String>) -> Unit): AetherScanResult? =
        AetherScanner.scan(context, profile, onOutput, onCandidates)

    override suspend fun scanForConverter(profile: ProfileItem): AetherScanResult? =
        AetherScanner.scan(context, profile, timeoutMs = 35_000L)

    override suspend fun scanReferences(profiles: List<ProfileItem>, onResult: suspend (Int, AetherScanResult?) -> Unit) =
        AetherScanner.scanReferences(context, profiles, onResult)

    override suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus =
        AetherIdentityManager.status(context, protocol)

    override suspend fun missingKeys(files: List<String>): List<String> = AetherIdentityManager.missing(context, files)

    companion object {
        /**
         * The live session told by the [protocol] its core process names, which covers the scanning
         * phase, before the listener exists. Only where the processes cannot be listed does a listener
         * on the Aether port, which [listenerAnswers] tells, stand in for it, and then the protocol stays
         * unknown: elsewhere that listener is the core of a latency test, which listens on the same port.
         */
        internal fun sessionOf(protocol: AetherProtocol?, processesListed: Boolean, listenerAnswers: () -> Boolean): AetherSession? =
            protocol?.let(::AetherSession) ?: AetherSession(protocol = null).takeIf { !processesListed && listenerAnswers() }
    }

    override suspend fun listenPort(): Int = withContext(Dispatchers.IO) { AetherCoreManager.socksPort }

    override suspend fun takenPorts(): Set<Int> =
        withContext(Dispatchers.IO) { SettingsManager.getLocalProxyPorts() + AetherCoreManager.secondarySocksPort }

    override suspend fun exitNodes(): List<AetherExitNode> = withContext(Dispatchers.IO) { AetherExit.nodes() }

    override suspend fun findExitNode(name: String): ExitNodeOutbound =
        withContext(Dispatchers.IO) { CoreOutboundBuilder.toOutboundOfNode(name) }

    override suspend fun findOutrole(name: String): ExitNodeOutbound =
        withContext(Dispatchers.IO) { CoreOutboundBuilder.toOutboundOfOutrole(name) }
}
