package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ProfileEntry
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherScanNetwork
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType

internal const val AETHER_DIAGNOSTIC_GROUP = "__aether_diagnostics__"

internal fun converterProtocolName(profile: ProfileItem): String {
    val protocol = AetherProtocol.fromString(profile.aetherProtocol)
    return when (protocol) {
        AetherProtocol.WIREGUARD -> "wg"
        AetherProtocol.GOOL -> "wiw"
        AetherProtocol.MASQUE -> "mq"
        AetherProtocol.MIM -> "mim"
        AetherProtocol.WG_OVER_MASQUE -> "wom"
    } + if (protocol.overMasque) {
        if (AetherTransport.fromString(profile.aetherTransport) == AetherTransport.HTTP2) "-2" else "-3"
    } else ""
}

data class DiagnosticOutRuleChoice(val guid: String, val remarks: String)

internal fun diagnosticOutRule(
    entry: ProfileEntry,
    find: (String, (ProfileItem) -> Boolean) -> ByName<ProfileEntry>,
): ProfileItem? = if (entry.profile.configType == EConfigType.AETHER) {
    CoreConfigContextBuilder.resolveAetherOutrole(entry, find).first?.profile
} else {
    entry.profile
}

internal fun aetherDiagnosticProfiles(outRule: ProfileItem): List<ProfileItem> =
    aetherReferenceProfiles.map { it.copy(subscriptionId = AETHER_DIAGNOSTIC_GROUP, aetherEmbeddedOutrole = outRule.copy()) }

internal val aetherReferenceProfiles: List<ProfileItem> =
    AetherProtocol.entries.flatMap { protocol ->
        val transports = if (protocol.overMasque) AetherTransport.entries else listOf(AetherTransport.HTTP3)
        transports.map { transport ->
            ProfileItem.create(EConfigType.AETHER).apply {
                remarks = "${protocol.name} / ${if (protocol.overMasque) transport.name else "UDP"}"
                aetherProtocol = protocol.type
                aetherTransport = transport.type
                aetherScanNetwork = if (protocol.overMasque && transport == AetherTransport.HTTP2)
                    AetherScanNetwork.TCP.type else AetherScanNetwork.UDP.type
                aetherIpVersion = AetherIpVersion.DUAL.type
                aetherScanMode = AetherScanMode.TURBO.type
                aetherEndpointCount = 1
                aetherObfuscation = "auto"
            }
        }
    }
