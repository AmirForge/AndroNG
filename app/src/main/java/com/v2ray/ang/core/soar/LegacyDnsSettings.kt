package com.v2ray.ang.core.soar

internal data class LegacyDnsField(val section: String, val key: String, val label: String, val default: String) {
    fun accepts(value: String): Boolean = when {
        default == "false" -> value == "true" || value == "false"
        '.' in default -> value.matches(Regex("[0-9]+(\\.[0-9]+)?")) &&
            value.toDoubleOrNull()?.let { it > 0 && it <= 65535 } == true
        else -> value.matches(Regex("0|[1-9][0-9]*")) && value.toIntOrNull()?.let { it in 0..65535 } == true
    }
}

internal val legacyDnsFields = listOf(
    LegacyDnsField("MTU", "MIN_UPLOAD_MTU", "Min Upload", "40"),
    LegacyDnsField("MTU", "MIN_DOWNLOAD_MTU", "Min Download", "300"),
    LegacyDnsField("MTU", "MAX_UPLOAD_MTU", "Max Upload", "140"),
    LegacyDnsField("MTU", "MAX_DOWNLOAD_MTU", "Max Download", "3000"),
    LegacyDnsField("MTU", "MTU_TEST_RETRIES_RESOLVERS", "Resolver Retries", "3"),
    LegacyDnsField("MTU", "MTU_TEST_TIMEOUT_RESOLVERS", "Resolver Timeout", "2.5"),
    LegacyDnsField("MTU", "MTU_TEST_PARALLELISM_RESOLVERS", "Resolver MTU Parallelism", "100"),
    LegacyDnsField("MTU", "MTU_TEST_RETRIES_LOGS", "Logs Retries", "5"),
    LegacyDnsField("MTU", "MTU_TEST_TIMEOUT_LOGS", "Logs Timeout", "2.5"),
    LegacyDnsField("MTU", "MTU_TEST_PARALLELISM_LOGS", "Logs Parallel", "32"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "RX_TX_WORKERS", "RX/TX Workers", "4"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "TUNNEL_PROCESS_WORKERS", "Process Workers", "4"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "TUNNEL_PACKET_TIMEOUT_SECONDS", "Tunnel Packet Timeout", "10.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "DISPATCHER_IDLE_POLL_INTERVAL_SECONDS", "Idle Poll", "0.020"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "TX_CHANNEL_SIZE", "TX Channel", "2048"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "RX_CHANNEL_SIZE", "RX Channel", "2048"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "RESOLVER_UDP_CONNECTION_POOL_SIZE", "UDP Pool", "64"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "STREAM_QUEUE_INITIAL_CAPACITY", "Stream Queue", "128"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "ORPHAN_QUEUE_INITIAL_CAPACITY", "Orphan Queue", "32"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "DNS_RESPONSE_FRAGMENT_STORE_CAPACITY", "DNS Fragments", "256"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "MAX_ACTIVE_STREAMS", "Max Active Streams", "2048"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "LOCAL_HANDSHAKE_TIMEOUT_SECONDS", "Local Handshake Timeout", "5.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS", "SOCKS UDP Timeout", "30.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "CLIENT_TERMINAL_STREAM_RETENTION_SECONDS", "Terminal Retain", "45.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "CLIENT_CANCELLED_SETUP_RETENTION_SECONDS", "Cancelled Retain", "120.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "SESSION_INIT_RETRY_BASE_SECONDS", "Retry Base", "1.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "SESSION_INIT_RETRY_STEP_SECONDS", "Retry Step", "1.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "SESSION_INIT_RETRY_LINEAR_AFTER", "Retry Linear", "5"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "SESSION_INIT_RETRY_MAX_SECONDS", "Retry Max", "60.0"),
    LegacyDnsField("Runtime Workers, Queues, and Timers", "SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS", "Busy Retry", "60.0"),
    LegacyDnsField("Network Tuning", "RESOLVER_BALANCING_STRATEGY", "Balancing Strategy", "3"),
    LegacyDnsField("Network Tuning", "UPLOAD_PACKET_DUPLICATION_COUNT", "Upload Dup", "3"),
    LegacyDnsField("Network Tuning", "DOWNLOAD_PACKET_DUPLICATION_COUNT", "Download Dup", "7"),
    LegacyDnsField("Network Tuning", "UPLOAD_COMPRESSION_TYPE", "Upload Compress", "2"),
    LegacyDnsField("Network Tuning", "DOWNLOAD_COMPRESSION_TYPE", "Download Compress", "2"),
    LegacyDnsField("Network Tuning", "BASE_ENCODE_DATA", "Base Encode Data", "false"),
    LegacyDnsField("Reliability", "PING_WATCHDOG_TIMEOUT_SECONDS", "Ping Watchdog", "300"),
    LegacyDnsField("Reliability", "TRAFFIC_WARMUP_ENABLED", "Traffic Warmup", "false"),
    LegacyDnsField("Reliability", "TRAFFIC_WARMUP_PROBE_COUNT", "Warmup Probes", "4"),
    LegacyDnsField("Reliability", "TRAFFIC_KEEPALIVE_INTERVAL_SECONDS", "Keepalive Interval", "5"),
)

internal fun legacyDnsSettingsValid(overrides: Map<String, String>): Boolean {
    fun mtu(key: String): Int = (overrides[key] ?: legacyDnsFields.first { it.key == key }.default).toInt()
    return legacyDnsFields.all { it.accepts(overrides[it.key] ?: it.default) } &&
        mtu("MIN_UPLOAD_MTU") in 1..mtu("MAX_UPLOAD_MTU") &&
        mtu("MIN_DOWNLOAD_MTU") in 1..mtu("MAX_DOWNLOAD_MTU")
}

internal fun legacyDnsToml(overrides: Map<String, String>): String {
    require(legacyDnsSettingsValid(overrides))
    return legacyDnsFields.joinToString("\n") { "${it.key} = ${overrides[it.key] ?: it.default}" }
}
