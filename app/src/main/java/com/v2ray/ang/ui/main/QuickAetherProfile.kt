package com.v2ray.ang.ui.main

import android.content.Context
import androidx.annotation.StringRes
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherKeys
import com.v2ray.ang.core.AetherExit
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.core.AetherKeysSettings
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherIdentityManager
import com.v2ray.ang.core.AetherScanner
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.core.ExitNodeOutbound
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanNetwork
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.fmt.AetherSetupLink
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ProfileStorageException
import com.v2ray.ang.ui.server.AetherEditorRepository
import com.v2ray.ang.ui.server.AetherEditorSource
import com.v2ray.ang.ui.server.AetherKeysRepository
import com.v2ray.ang.ui.server.AetherKeysSource
import com.v2ray.ang.ui.server.ProfileEditorRepository
import com.v2ray.ang.ui.server.ProfileEditorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class QuickAetherProfile internal constructor(
    private val keys: AetherKeysSource,
    private val editor: AetherEditorSource,
    private val profiles: ProfileEditorSource,
    private val storeImported: suspend (ProfileItem) -> Boolean = {
        !profiles.storeProfile("", it).isNullOrEmpty()
    },
    private val validateOutrole: (ProfileItem) -> Boolean = {
        CoreOutboundBuilder.outroleOutboundOf(CoreOutboundBuilder.convert(it)) is ExitNodeOutbound.Built
    },
    private val storeRefreshed: (String, ProfileItem, ProfileItem) -> Boolean = MmkvManager::updateAetherEndpoint,
    private val prepareScanNetwork: suspend () -> Boolean = { true },
    private val storeBatchRefreshed: (Map<String, Pair<ProfileItem, ProfileItem>>) -> Boolean = MmkvManager::updateAetherEndpoints,
) {
    constructor(context: Context, allowVpnDisconnect: Boolean = true) : this(
        AetherKeysRepository(context),
        AetherEditorRepository(context),
        ProfileEditorRepository(),
        prepareScanNetwork = { AetherScanner.prepareNetwork(context, allowVpnDisconnect) },
    )

    companion object {
        internal const val GLOBAL_SCAN_TIMEOUT_MS = AetherScanner.SCAN_TIMEOUT_MS
        internal fun canRefresh(profile: ProfileItem): Boolean =
            profile.configType == EConfigType.AETHER &&
                profile.aetherCommand.isNullOrBlank() &&
                !AetherFmt.usesRemovedCarrier(profile)
    }

    /** Returns the error to show, or null after the scanned profile has been saved. */
    @StringRes
    suspend fun create(onProgress: (Int) -> Unit = {}): Int? {
        if (!prepareScanNetwork()) return R.string.aether_scan_disconnect_vpn
        renewKeys(AetherKeyKind.ALL, onProgress)?.let { return it }
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "Aether WARP"
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTransport = AetherTransport.HTTP2.type
            aetherIpVersion = AetherIpVersion.DUAL.type
            aetherScanMode = AetherScanMode.BALANCED.type
        }
        onProgress(R.string.aether_action_scanning)
        scan(profile)?.let { return it }
        onProgress(R.string.aether_progress_saving)
        return if (profiles.storeProfile("", profile).isNullOrEmpty()) R.string.toast_failure else null
    }

    @StringRes
    internal suspend fun refresh(guid: String, onProgress: (Int) -> Unit): Int? = refresh(guid, false, onProgress)

    internal suspend fun refresh(guid: String, rebuild: Boolean, onProgress: (Int) -> Unit): Int? {
        val original = withContext(Dispatchers.IO) { profiles.loadProfile(guid) } ?: return R.string.toast_failure
        if (!canRefresh(original)) return R.string.toast_failure
        if (!prepareScanNetwork()) return R.string.aether_scan_disconnect_vpn
        val exit = original.aetherExitNode?.trim().orEmpty()
        if (exit.isNotEmpty() && editor.findExitNode(exit) !is ExitNodeOutbound.Built) {
            return R.string.aether_scan_failed
        }

        onProgress(R.string.aether_import_progress_working)
        val updated = original.copy()
        if (rebuild) {
            val keyKind = keyKindFor(original) ?: return R.string.toast_failure
            renewKeys(keyKind)?.let { return it }
            updated.aetherEndpointCount = 5
            updated.aetherScanMode = AetherScanMode.IRONCLAD.type
        } else {
            updated.aetherScanMode = AetherScanMode.BALANCED.type
            val needed = AetherIdentityManager.filesNeededBy(AetherCoreManager.buildArguments(original, 0, scan = true))
            if (editor.missingKeys(needed).isNotEmpty()) return R.string.aether_scan_failed
        }
        scan(updated)?.let { return it }
        // A working partial batch is enough to connect; five remains the scan target.
        onProgress(R.string.aether_progress_saving)
        val saved = withContext(Dispatchers.IO) { storeRefreshed(guid, original, updated) }
        return R.string.toast_failure.takeUnless { saved }
    }

    internal data class ImportResult(val count: Int, @StringRes val error: Int? = null)

    internal suspend fun prepareConverterProfile(template: ProfileItem): ProfileItem? {
        if (!prepareScanNetwork()) return null
        val kind = keyKindFor(template) ?: return null
        if (renewKeys(kind) != null) {
            LogUtil.w(AppConfig.TAG, "Converter key renewal failed for ${template.remarks}")
            return null
        }
        val profile = template.copy(aetherEndpointCount = 5, aetherScanMode = AetherScanMode.IRONCLAD.type)
        val result = editor.scanForConverter(profile)
        if (result == null) {
            LogUtil.w(AppConfig.TAG, "Converter endpoint scan found no result for ${template.remarks}")
            return null
        }
        applyScan(profile, result)
        return profile
    }

    internal suspend fun importLinks(lines: List<String>, onProgress: (Int) -> Unit): ImportResult =
        importLinks(lines, null, onProgress)

    internal suspend fun importLinks(lines: List<String>, subscriptionId: String?, onProgress: (Int) -> Unit): ImportResult {
        val links = withContext(Dispatchers.IO) {
            lines.mapNotNull { line ->
                val link = AetherSetupLink.parse(line) ?: return@mapNotNull null
                if (AetherFmt.usesRemovedCarrier(link.profile)) return@mapNotNull null
                val outrole = link.outrole
                link.takeIf {
                    outrole == null || runCatching {
                        validateOutrole(outrole)
                    }.getOrDefault(false)
                }
            }
        }
        if (links.isEmpty()) return ImportResult(0, R.string.toast_failure)
        onProgress(R.string.aether_import_progress_working)
        if (!prepareScanNetwork()) return ImportResult(0, R.string.aether_scan_disconnect_vpn)
        // Identity files belong to the device/protocol, not to each imported card.
        val keyErrors = linkedMapOf<AetherKeyKind, Int?>()
        for (link in links) {
            val kind = keyKindFor(link.profile) ?: continue
            if (kind !in keyErrors) {
                keyErrors[kind] = if (editor.missingKeys(requiredKeys(link.profile)).isEmpty()) null else renewKeys(kind)
            }
        }
        val ready = links.filter { keyErrors[keyKindFor(it.profile)] == null }
        val scans = linkedMapOf<ScanGroup, Pair<AetherScanResult?, Int?>>()
        for ((group, members) in ready.groupBy { scanGroup(it.profile) }) {
            currentCoroutineContext().ensureActive()
            val template = members.maxBy { AetherScanner.endpointCount(it.profile) }.profile
            onProgress(R.string.aether_import_progress_working)
            scans[group] = boundedScan(template)
        }
        var count = 0
        var firstError = keyErrors.values.firstOrNull { it != null }
            ?: R.string.toast_failure.takeIf { links.size != lines.size }
        for ((profile, outrole) in links) {
            currentCoroutineContext().ensureActive()
            if (keyErrors[keyKindFor(profile)] != null) continue
            val (result, scanError) = scans[scanGroup(profile)] ?: continue
            if (scanError != null || result == null) {
                if (firstError == null) firstError = scanError ?: R.string.aether_scan_failed
                continue
            }
            applyScan(profile, result)

            onProgress(R.string.aether_import_progress_finishing)
            val prepared = profile.copy(
                aetherOutrole = null,
                aetherEmbeddedOutrole = outrole,
                subscriptionId = subscriptionId ?: profile.subscriptionId,
            )
            val saved = try {
                storeImported(prepared)
            } catch (_: ProfileStorageException) {
                false
            }
            if (saved) count++ else if (firstError == null) firstError = R.string.toast_failure
        }
        return ImportResult(count, firstError)
    }

    private fun keyKindFor(profile: ProfileItem): AetherKeyKind? {
        if (!AetherSetupLink.requiresWarp(profile)) return null
        return when (AetherProtocol.fromString(profile.aetherProtocol)) {
            AetherProtocol.MASQUE -> AetherKeyKind.MASQUE
            AetherProtocol.WIREGUARD -> AetherKeyKind.WIREGUARD
            AetherProtocol.GOOL -> AetherKeyKind.GOOL
            AetherProtocol.MIM -> AetherKeyKind.MIM
            AetherProtocol.WG_OVER_MASQUE -> AetherKeyKind.WG_OVER_MASQUE
        }
    }

    @StringRes
    private suspend fun renewKeys(kind: AetherKeyKind, onProgress: (Int) -> Unit = {}): Int? {
        val settings = AetherKeysSettings(kind = kind)
        if (!keys.isCoreAvailable()) return R.string.aether_unsupported_abi
        if (keys.activeSession()?.usesKeysOf(settings.kind) == true) return R.string.aether_renew_blocked

        onProgress(R.string.aether_action_renewing_key)
        val arguments = AetherKeys.runArguments(settings)
        val renewed = keys.renew(settings.kind, arguments, settings.exit) {}
        if (renewed.isNullOrEmpty() || renewed.any { it.identity == null }) {
            return R.string.aether_log_key_renew_failed
        }
        return null
    }

    internal data class RefreshResult(val guids: List<String>, @StringRes val error: Int? = null, val failedGuids: List<String> = emptyList())

    internal suspend fun needsKeys(guid: String): Boolean {
        val profile = withContext(Dispatchers.IO) { profiles.loadProfile(guid) } ?: return false
        return canRefresh(profile) && editor.missingKeys(requiredKeys(profile)).isNotEmpty()
    }

    internal suspend fun refreshAll(guids: List<String>, onProgress: (Int) -> Unit): RefreshResult =
        refreshAll(guids, AetherScanMode.BALANCED, onProgress)

    /** Each protocol shares one scan pool and the scanner's deadline. */
    internal suspend fun refreshAll(guids: List<String>, mode: AetherScanMode, onProgress: (Int) -> Unit): RefreshResult = coroutineScope {
        val originals = withContext(Dispatchers.IO) {
            guids.distinct().mapNotNull { guid -> profiles.loadProfile(guid)?.takeIf(::canRefresh)?.let { guid to it } }.toMap()
        }
        if (originals.isEmpty()) return@coroutineScope RefreshResult(emptyList(), R.string.aether_refresh_none)
        if (!prepareScanNetwork()) return@coroutineScope RefreshResult(emptyList(), R.string.aether_scan_disconnect_vpn)
        onProgress(R.string.aether_import_progress_working)
        val groups = originals.entries.groupBy { referenceKey(it.value) }
        val session = editor.activeSession()
        val references = aetherReferenceProfiles.mapNotNull { reference ->
            val members = groups[referenceKey(reference)] ?: return@mapNotNull null
            val diagnostic = members.all { it.value.subscriptionId == AETHER_DIAGNOSTIC_GROUP }
            val template = reference.copy(
                subscriptionId = if (diagnostic) AETHER_DIAGNOSTIC_GROUP else reference.subscriptionId,
                aetherScanMode = mode.type,
                aetherEndpointCount = when {
                    diagnostic -> 1
                    mode == AetherScanMode.TURBO -> 5
                    else -> members.maxOf { AetherScanner.endpointCount(it.value) }
                },
            )
            val protocol = AetherProtocol.fromString(template.aetherProtocol)
            if (session?.disturbedByScanOf(protocol) == true ||
                editor.missingKeys(requiredKeys(template)).isNotEmpty()) return@mapNotNull null
            template to members
        }
        val saved = linkedSetOf<String>()
        val writeLock = kotlinx.coroutines.sync.Mutex()
        var writeFailed = false
        editor.scanReferences(references.map { it.first }) { index, result ->
            if (result != null) writeLock.withLock {
                val members = references[index].second
                val updates = members.associate { (guid, original) ->
                    val updated = original.copy(
                        aetherEndpointCount = when {
                            original.subscriptionId == AETHER_DIAGNOSTIC_GROUP -> 1
                            mode == AetherScanMode.TURBO -> 5
                            else -> original.aetherEndpointCount
                        },
                    )
                    applyScan(updated, result)
                    guid to (original to updated)
                }
                if (withContext(Dispatchers.IO) { storeBatchRefreshed(updates) }) saved.addAll(updates.keys)
                else writeFailed = true
            }
        }
        val failed = originals.keys - saved
        RefreshResult(saved.toList(), when {
            writeFailed -> R.string.toast_failure
            failed.isNotEmpty() -> R.string.aether_scan_failed
            else -> null
        }, failed.toList())
    }

    private fun referenceKey(profile: ProfileItem): Pair<AetherProtocol, AetherTransport> {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        val transport = if (protocol.overMasque) AetherTransport.fromString(profile.aetherTransport)
            else AetherTransport.HTTP3
        return protocol to transport
    }

    internal fun commonScanProfile(protocol: AetherProtocol, count: Int, mode: AetherScanMode = AetherScanMode.TURBO): ProfileItem =
        ProfileItem.create(EConfigType.AETHER).copy(
            aetherProtocol = protocol.type, aetherScanMode = mode.type,
            aetherIpVersion = AetherIpVersion.DUAL.type, aetherEndpointCount = count,
            aetherScanNetwork = if (protocol.overMasque) AetherScanNetwork.BOTH.type else AetherScanNetwork.UDP.type,
            aetherTransport = if (protocol.overMasque) AetherTransport.HTTP2.type else AetherTransport.HTTP3.type,
        )

    private fun requiredKeys(profile: ProfileItem): List<String> =
        AetherIdentityManager.filesNeededBy(AetherCoreManager.buildArguments(profile, 0, scan = true))

    // OutRule, names, group IDs and old endpoints do not affect the WARP scan. Native arguments,
    // carrier selection and the outbound path do; never share a result across those boundaries.
    private data class ScanGroup(val arguments: Set<List<String>>, val exit: AetherExit)

    private fun scanGroup(profile: ProfileItem): ScanGroup = ScanGroup(
        AetherScanner.scanTransports(profile).map { transport ->
            AetherCoreManager.buildArguments(profile.copy(aetherTransport = transport.type), 0, scan = true)
        }.toSet(),
        AetherExit.of(profile),
    )

    @StringRes
    private suspend fun scan(profile: ProfileItem): Int? {
        val (result, error) = boundedScan(profile)
        if (result == null) return error ?: R.string.aether_scan_failed
        applyScan(profile, result)
        return null
    }

    private suspend fun boundedScan(profile: ProfileItem, networkPrepared: Boolean = false): Pair<AetherScanResult?, Int?> {
        val partial = AtomicReference<List<String>>(emptyList())
        var error: Int? = R.string.aether_scan_failed
        val result = withTimeoutOrNull(GLOBAL_SCAN_TIMEOUT_MS) {
            val (value, problem) = scanResult(profile, onCandidates = { partial.set(it.toList()) }, networkPrepared = networkPrepared)
            error = problem ?: R.string.aether_scan_failed
            value
        } ?: partial.get().mapNotNull(AetherScanner::decode).firstOrNull()?.copy(candidates = partial.get())
        return if (result == null) null to error else result to null
    }

    private suspend fun scanResult(profile: ProfileItem, onCandidates: ((List<String>) -> Unit)? = null,
        networkPrepared: Boolean = false): Pair<AetherScanResult?, Int?> {
        if (!networkPrepared && !prepareScanNetwork()) return null to R.string.aether_scan_disconnect_vpn
        val exit = profile.aetherExitNode?.trim().orEmpty()
        if (exit.isNotEmpty() && editor.findExitNode(exit) !is ExitNodeOutbound.Built) return null to R.string.aether_scan_failed
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        if (editor.activeSession()?.disturbedByScanOf(protocol) == true) return null to R.string.aether_scan_blocked
        val result = (if (onCandidates == null) editor.scan(profile) {}
            else editor.scanMany(profile, {}, onCandidates)) ?: return null to R.string.aether_scan_failed
        return result to null
    }

    private fun applyScan(profile: ProfileItem, result: AetherScanResult) {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        val candidates = result.candidates.ifEmpty { listOf(AetherScanner.encode(result)) }
            .take(AetherScanner.endpointCount(profile))
        result.transport?.let { profile.aetherTransport = it.type }
        profile.aetherEndpoints = candidates.takeIf { AetherScanner.endpointCount(profile) > 1 }
        if (protocol.twoHops) {
            profile.aetherWiwOuter = result.endpoint.toString()
            result.innerHop?.let { profile.aetherWiwInner = it.toString() }
        } else {
            profile.server = result.endpoint.host
            profile.serverPort = result.endpoint.port.toString()
        }
        profile.description = AngConfigManager.generateDescription(profile)
    }
}
