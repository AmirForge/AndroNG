package com.v2ray.ang.core.soar

import com.google.gson.JsonParser
import com.v2ray.ang.util.JsonUtil
import java.net.InetAddress
import java.util.Base64
import java.util.UUID

/** Independent DNS-tunnel profiles; deliberately separate from Xray server storage. */
data class SoarSettings(
    val transport: String = "auto",
    val discovery: Boolean = true,
    val country: String = "IR",
    val discoveryWant: Int = 2,
    val discoveryConcurrency: Int = 2,
    val discoveryPaceMs: Int = 1000,
    val safeQName: Int = 101,
    val maxQName: Int = 253,
    val maxAnswer: Int = 1232,
    val holdMs: Int = 500,
    val maxInflight: Int = 128,
    val maxPathWindow: Int = 32,
    val maxStreams: Int = 128,
    val streamWindowKiB: Int = 256,
    val sendBufferKiB: Int = 64,
    val sessionIdleSeconds: Int = 90,
    val dialTimeoutSeconds: Int = 90,
) {
    companion object {
        fun preset(name: String): SoarSettings = when (name) {
            "conservative" -> SoarSettings(maxInflight = 32, maxPathWindow = 8, maxAnswer = 512, maxQName = 101, maxStreams = 16)
            "fast" -> SoarSettings(maxInflight = 256, maxPathWindow = 64)
            else -> SoarSettings()
        }
    }
    fun valid(): Boolean = transport in listOf("auto", "on", "only", "off") &&
        (country.isEmpty() || country.matches(Regex("[A-Z]{2}"))) &&
        discoveryWant in 1..32 && discoveryConcurrency in 1..16 && discoveryPaceMs in 100..5000 &&
        maxQName in 101..253 && safeQName in 101..maxQName && maxAnswer in 512..1232 &&
        holdMs in 100..1500 && maxInflight in 8..1024 && maxPathWindow in 2..128 &&
        maxStreams in 8..256 && streamWindowKiB in 64..1024 && sendBufferKiB in 32..1024 &&
        sessionIdleSeconds in 15..110 && dialTimeoutSeconds in 5..120
}

enum class DnsTunnelEngine { SOAR, STORM, COTTEN }

data class SoarProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Soar",
    val zone: String = "",
    val publicKey: String = "",
    val settings: SoarSettings = SoarSettings(),
    val runtime: SoarRuntimeSettings = SoarRuntimeSettings(),
    val engine: DnsTunnelEngine = DnsTunnelEngine.SOAR,
    val encryptionMethod: Int = 2,
) {
    fun valid(): Boolean = name.isNotBlank() && zone.length in 3..(if (engine == DnsTunnelEngine.SOAR) 45 else 253) && zone.contains('.') &&
        zone.split('.').all { it.length in 1..63 && it.matches(Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?")) } &&
        (if (engine == DnsTunnelEngine.SOAR) {
            runCatching { Base64.getDecoder().decode(publicKey).size == 32 }.getOrDefault(false)
        } else encryptionMethod in 0..5 && publicKey.length in 1..512 && publicKey.none { it.isISOControl() }) &&
        settings.valid() && runtime.valid()
}

data class SoarResolverProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "DNS",
    val addresses: List<String> = emptyList(),
)

data class SoarSettingsProfile(
    val id: String,
    val name: String,
    val settings: SoarSettings,
    val runtime: SoarRuntimeSettings,
)

data class SoarStore(
    val profiles: List<SoarProfile> = emptyList(),
    val selectedId: String = "",
    val resolverProfiles: List<SoarResolverProfile> = emptyList(),
    val resolverId: String = "",
    val settingsProfiles: List<SoarSettingsProfile> = emptyList(),
)

enum class SoarStartDecision { Start, Reuse, Stop }

/** Daemon-side dispatch only; UI-process state is never used as proof of a live engine. */
fun soarStartDecision(action: String?, setupActive: Boolean, connected: Boolean): SoarStartDecision = when {
    action != "connect" -> SoarStartDecision.Stop
    setupActive || connected -> SoarStartDecision.Reuse
    else -> SoarStartDecision.Start
}

object SoarImport {
    /** DNS profile links carry a base64url-encoded JSON envelope. */
    fun profile(link: String): Pair<SoarProfile, List<String>> {
        val engine = when (link.trim().substringBefore("://").lowercase()) {
            "soar" -> DnsTunnelEngine.SOAR
            "stormdns", "masterdns", "master" -> DnsTunnelEngine.STORM
            "cottendns", "cottondns", "cotten", "cotton" -> DnsTunnelEngine.COTTEN
            else -> error("Unsupported DNS tunnel")
        }
        require("://" in link)
        val payload = link.trim().substringAfter("://").substringBefore('#').substringBefore('?').trim()
        require(payload.length <= 1_000_000)
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload) }
            .getOrElse { Base64.getDecoder().decode(payload) }
        val root = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        require(root["schema"].asString == "whitedns.profile" && (root["version"]?.asInt ?: 1) == 1)
        val p = root.getAsJsonObject("profile")
        val s = p.getAsJsonObject("server")
        val settings = p.getAsJsonObject("soarSettings")?.let {
            JsonUtil.fromJson(it.toString(), SoarSettings::class.java)
        } ?: SoarSettings()
        val profile = SoarProfile(name = p["name"].asString.trim(), zone = s["domain"].asString.trim().trimEnd('.'),
            publicKey = s[if (engine == DnsTunnelEngine.SOAR) "public_key" else "encryption_key"].asString.trim(),
            settings = settings, engine = engine, encryptionMethod = s["encryption_method"]?.asInt ?: 2)
        require(profile.valid())
        val resolvers = if (engine == DnsTunnelEngine.SOAR) {
            p.getAsJsonArray("resolvers")?.joinToString("\n") { it.asString }?.let(::resolvers).orEmpty()
        } else emptyList()
        return profile to resolvers
    }

    /** Numeric addresses only. No hostname resolution, URL fetching or CIDR expansion on import. */
    fun resolvers(text: String): List<String> {
        val lines = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size <= 8192)
        return lines.map { line ->
            val host: String
            val port: Int
            if (line.startsWith("[")) {
                require(']' in line)
                host = line.substringAfter('[').substringBefore(']')
                val suffix = line.substringAfter(']')
                require(suffix.isEmpty() || suffix.startsWith(':'))
                port = if (suffix.isEmpty()) 53 else suffix.drop(1).toInt()
            } else if (line.count { it == ':' } == 1) {
                host = line.substringBefore(':')
                port = line.substringAfter(':').toInt()
            } else {
                host = line
                port = 53
            }
            require(port in 1..65535)
            if (':' in host) {
                require(host.matches(Regex("[0-9a-fA-F:]+")) && InetAddress.getByName(host).address.size == 16)
                "[$host]:$port"
            } else {
                require(host.split('.').size == 4 && host.split('.').all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
                "$host:$port"
            }
        }.distinct()
    }
}
