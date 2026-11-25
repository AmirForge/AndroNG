package com.v2ray.ang.core.soar

/** Android VPN and process-adapter tuning, separate from Soar's wire-protocol options. */
data class SoarRuntimeSettings(
    val name: String = "Default",
    val vpnMtu: Int = 1280,
    val resolverWorkers: Int = 4,
    val resolverRetries: Int = 3,
    val resolverTimeoutMs: Int = 2500,
    val retryDelayMs: Int = 1000,
    val httpsRetries: Int = 2,
    val httpsTimeoutMs: Int = 8000,
    val processWorkers: Int = 4,
    val localConnections: Int = 128,
    val copyBufferKiB: Int = 32,
    val tcpTimeoutSeconds: Int = 300,
    val udpTimeoutSeconds: Int = 30,
    val listenIp: String = "127.0.0.1",
    val listenPort: Int = 10886,
    val httpEnabled: Boolean = true,
    val httpPort: Int = 10887,
    val authentication: Boolean = false,
    val username: String = "",
    val password: String = "",
    val watchdogSeconds: Int = 300,
    val warmup: Boolean = false,
    val warmupProbes: Int = 4,
    val logLevel: String = "WARN",
    val legacyOverrides: Map<String, String>? = null,
) {
    fun valid(): Boolean = name.isNotBlank() && resolverWorkers in 1..16 && vpnMtu in 1280..9000 && resolverRetries in 1..10 &&
        resolverTimeoutMs in 500..30000 && retryDelayMs in 0..10000 && httpsRetries in 1..10 &&
        httpsTimeoutMs in 1000..30000 && processWorkers in 1..16 && localConnections in 8..256 &&
        copyBufferKiB in 4..256 && tcpTimeoutSeconds in 15..3600 && udpTimeoutSeconds in 5..600 &&
        listenIp in listOf("127.0.0.1", "::1") && listenPort in 1024..65535 && httpPort in 1024..65535 &&
        (!httpEnabled || httpPort != listenPort) && (!authentication ||
            (username.toByteArray().size in 1..255 && password.toByteArray().size in 1..255 &&
                username.none { it == '\n' || it == '\r' } && password.none { it == '\n' || it == '\r' })) &&
        (watchdogSeconds == 0 || watchdogSeconds in 30..3600) && warmupProbes in 1..16 &&
        logLevel in listOf("ERROR", "WARN", "INFO", "DEBUG") &&
        legacyDnsSettingsValid(legacyOverrides.orEmpty())
}
