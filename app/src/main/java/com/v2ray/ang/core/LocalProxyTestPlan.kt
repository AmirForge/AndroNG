package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import java.net.InetSocketAddress
import java.net.ServerSocket

internal data class LocalProxyTestEntry(
    val guid: String,
    val proxyPort: Int,
    val aether: AetherCore?,
    val exitPort: Int?,
)

/** Reserve distinct free ports while the batch is assembled. Release just before Xray binds them. */
internal class LocalProxyTestPorts : AutoCloseable {
    private val sockets = mutableListOf<ServerSocket>()
    private var nextProxy = PROXY_PORTS.first
    private var nextAuxiliary = PROXY_PORTS.last + 1

    companion object {
        val PROXY_PORTS = 2020..6060
    }

    fun allocateProxy(): Int {
        check(nextProxy in PROXY_PORTS) { "Proxy test port range exhausted" }
        return reserve(nextProxy++)
    }

    fun allocateAuxiliary(): Int {
        while (nextAuxiliary <= 65535) {
            val port = nextAuxiliary++
            try {
                return reserve(port)
            } catch (_: java.net.BindException) {
                // Another listener owns this auxiliary port.
            }
        }
        error("No auxiliary proxy test port available")
    }

    private fun reserve(port: Int): Int {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(AppConfig.LOOPBACK, port), 1)
            sockets += socket
            return port
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }
    override fun close() = sockets.forEach { it.close() }
}

internal class LocalProxyTestPlan {
    private val inbounds = JsonArray()
    private val outbounds = JsonArray()
    private val rules = JsonArray()
    val entries = mutableListOf<LocalProxyTestEntry>()

    fun add(guid: String, content: String, originalCore: AetherCore?, reservedProxyPort: Int? = null, allocate: () -> Int) {
        val config = JsonParser.parseString(content).asJsonObject
        val candidateOutbounds = config.getAsJsonArray("outbounds")
        require(candidateOutbounds != null && candidateOutbounds.size() > 0)
        val ordinal = entries.size
        val prefix = "probe-$ordinal/"
        val proxyPort = reservedProxyPort ?: allocate()
        val exitPort = originalCore?.let { allocate() }
        val core = originalCore?.let {
            val port = allocate()
            require(AetherDependency.moveSocksOutbounds(config, it.port, port))
            it.copy(
                arguments = AetherCoreManager.withListener(it.arguments, AetherCoreManager.listenerFlagOf(it.arguments), port),
                alternatives = it.alternatives.map { command ->
                    AetherCoreManager.withListener(command, AetherCoreManager.listenerFlagOf(command), port)
                },
            )
        }
        if (core != null && candidateOutbounds.none { it.asJsonObject.get("tag")?.asString == AppConfig.TAG_EXIT_NODE }) {
            val exit = requireNotNull(AetherCoreManager.exitConfiguration(core.exit, content, "warning"))
            JsonParser.parseString(exit).asJsonObject.getAsJsonArray("outbounds").forEach { candidateOutbounds.add(it.deepCopy()) }
        }
        val names = linkedMapOf<String, String>()
        candidateOutbounds.forEachIndexed { index, element ->
            val outbound = element.asJsonObject
            outbound.get("tag")?.asString?.let { names.putIfAbsent(it, "$prefix$index") }
            outbound.addProperty("tag", "$prefix$index")
        }
        relink(candidateOutbounds, names)
        // Commit only after every transformation succeeded, so a rejected profile leaves no routes.
        val exitTag = core?.let { requireNotNull(names[AppConfig.TAG_EXIT_NODE]) }
        candidateOutbounds.forEach { outbounds.add(it) }
        addInbound("$prefix/in", proxyPort, "$prefix" + "0")
        if (core != null) addInbound("$prefix/exit", requireNotNull(exitPort), requireNotNull(exitTag))
        entries += LocalProxyTestEntry(guid, proxyPort, core, exitPort)
    }

    private fun addInbound(tag: String, port: Int, outboundTag: String) {
        inbounds.add(JsonObject().apply {
            addProperty("tag", tag)
            addProperty("listen", AppConfig.LOOPBACK)
            addProperty("port", port)
            addProperty("protocol", "mixed")
            add("settings", JsonObject().apply { addProperty("udp", true) })
        })
        rules.add(JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { add(tag) })
            addProperty("outboundTag", outboundTag)
        })
    }

    fun content(selected: List<LocalProxyTestEntry> = entries): String = JsonObject().apply {
        val prefixes = selected.map { "probe-${entries.indexOf(it)}/" }
        fun include(tag: String) = prefixes.any(tag::startsWith)
        add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        add("stats", JsonObject())
        add("inbounds", JsonArray().apply { inbounds.filter { include(it.asJsonObject["tag"].asString) }.forEach(::add) })
        add("outbounds", JsonArray().apply { outbounds.filter { include(it.asJsonObject["tag"].asString) }.forEach(::add) })
        add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray().apply { rules.filter { include(it.asJsonObject["inboundTag"].asJsonArray[0].asString) }.forEach(::add) })
        })
    }.toString()

    private fun relink(value: JsonElement, names: Map<String, String>, parent: String = "") {
        if (value.isJsonArray) value.asJsonArray.forEach { relink(it, names, parent) }
        if (!value.isJsonObject) return
        value.asJsonObject.entrySet().toList().forEach { (key, element) ->
            if ((key == "dialerProxy" || (parent == "proxySettings" && key == "tag")) &&
                element.isJsonPrimitive && element.asJsonPrimitive.isString
            ) {
                names[element.asString]?.let { value.asJsonObject.addProperty(key, it) }
            } else relink(element, names, key)
        }
    }
}
