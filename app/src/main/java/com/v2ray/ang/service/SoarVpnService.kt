package com.v2ray.ang.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.soar.DnsTunnelEngine
import com.v2ray.ang.core.soar.SoarProfile
import com.v2ray.ang.core.soar.SoarRuntimeSettings
import com.v2ray.ang.core.soar.SoarStartDecision
import com.v2ray.ang.core.soar.soarStartDecision
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.SoarRepository
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket

/** Separate native-process lifetime: Soar cannot collide with Xray's HEV singleton. */
class SoarVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transition = Mutex()
    private var setup: Job? = null
    private var process: Process? = null
    private var vpn: ParcelFileDescriptor? = null
    private var tunnel: TProxyService? = null
    @Volatile private var phase = "idle"
    @Volatile private var stopping = false
    private var resolver = ""
    private var attempt = 0
    private var total = 0
    private var profileId = ""
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("command")) {
                "stop" -> finishSession()
                "query" -> publish()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, commands, IntentFilter(MessageHelper.SOAR_COMMAND), Utils.receiverFlags())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = PendingIntent.getService(this, 15, Intent(this, SoarVpnService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationHelper.startForeground(this, NotificationChannelType.SOAR, getString(R.string.soar_title), getString(R.string.soar_checking),
            NotificationCompat.Action(R.drawable.ic_close_24dp, getString(R.string.soar_stop), stop))
        // Always-on starts have no selected, explicitly requested Soar session to restore.
        when (soarStartDecision(intent?.action, setup?.isActive == true, phase == "connected")) {
            SoarStartDecision.Stop -> { finishSession(); return START_NOT_STICKY }
            SoarStartDecision.Reuse -> { publish(); return START_NOT_STICKY }
            SoarStartDecision.Start -> Unit
        }
        stopping = false
        setup = scope.launch {
            transition.withLock {
                try {
                    val data = SoarRepository.load()
                    val profile = data.profiles.firstOrNull { it.id == data.selectedId } ?: error("missing profile")
                    require(profile.valid())
                    profileId = profile.id
                    val resolvers = data.resolverProfiles.firstOrNull { it.id == data.resolverId }?.addresses.orEmpty()
                    require(resolvers.isNotEmpty() || (profile.engine == DnsTunnelEngine.SOAR && profile.settings.discovery))
                    MessageHelper.sendMsg2Service(this@SoarVpnService, AppConfig.MSG_STATE_STOP, "")
                    if (profile.engine == DnsTunnelEngine.SOAR) startEngine(profile, resolvers)
                    else startLegacyEngine(profile, resolvers)
                } catch (e: Exception) {
                    // A startup deadline is a failed attempt; an external stop cancels the owner.
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    LogUtil.e(AppConfig.TAG, "Soar VPN setup failed, profileId=$profileId", IllegalStateException(e.javaClass.simpleName))
                    phase = "failed"
                    cleanup()
                    publish()
                    withContext(Dispatchers.Main) { NotificationHelper.stopForeground(this@SoarVpnService); stopSelf() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startEngine(profile: SoarProfile, addresses: List<String>) {
        val executable = File(applicationInfo.nativeLibraryDir, "libandrong-soar.so")
        check(executable.canExecute())
        val runtime = profile.runtime
        val port = runtime.listenPort
        // Taking VPN ownership first revokes any other VPN before testing the physical DNS path.
        // Traffic stays inside the interface until the authenticated SOCKS bridge is ready.
        establishInterface(runtime.vpnMtu, virtualDns = true)
        phase = "checking"
        resolver = ""
        publish()
        val child = ProcessBuilder(executable.absolutePath).redirectError(File("/dev/null")).start()
        process = child
        child.outputStream.bufferedWriter().use {
            it.write(JsonUtil.toJson(mapOf("zone" to profile.zone, "publicKey" to profile.publicKey,
                "listen" to java.net.InetSocketAddress(runtime.listenIp, port).let { if (':' in runtime.listenIp) "[${runtime.listenIp}]:$port" else "${runtime.listenIp}:$port" },
                "resolvers" to addresses, "settings" to profile.settings, "runtime" to runtime, "caPem" to trustedRoots())))
        }
        val lines = child.inputStream.bufferedReader()
        try {
            withTimeout((profile.settings.dialTimeoutSeconds + 5L) * 1000) {
                val reader = scope.async {
                    while (isActive) {
                        val line = lines.readLine() ?: break
                        val event = JsonParser.parseString(line).asJsonObject
                        when (event["phase"].asString) {
                            "checking", "discovery" -> {
                                phase = event["phase"].asString
                                resolver = event["resolver"].asString
                                attempt = event["attempt"].asInt
                                total = event["total"].asInt
                                publish()
                            }
                            "ready" -> { establishTunnel(runtime, virtualDns = true); return@async }
                            "failed" -> error("no working resolver")
                        }
                    }
                    if (phase != "connected") error("engine ended during setup")
                }
                try { reader.await() } finally {
                    withContext(NonCancellable) {
                        if (reader.isActive) { child.destroy(); reader.cancelAndJoin() }
                    }
                }
            }
            val diagnostics = scope.launch {
                try {
                while (isActive) {
                    val line = lines.readLine() ?: break
                    val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    if (event["phase"]?.asString == "diagnostic") {
                        val message = event["resolver"]?.asString
                        if (message in listOf("send failed", "bad resolver"))
                            LogUtil.d(AppConfig.TAG, "Soar transport diagnostic: $message, profileId=$profileId")
                    }
                }
                } catch (e: java.io.IOException) {
                    if (!stopping) LogUtil.w(AppConfig.TAG, "Soar VPN diagnostic pipe ended, profileId=$profileId", e)
                }
            }
            try {
                while (scope.isActive && !stopping && child.isAlive && tunnel?.isTun2SocksRunning() == true) delay(1000)
                if (!stopping) error("Soar process or tunnel stopped")
            } finally {
                withContext(NonCancellable) { child.destroy(); diagnostics.cancelAndJoin() }
            }
        } finally {
            lines.close()
        }
    }

    private suspend fun startLegacyEngine(profile: SoarProfile, addresses: List<String>) {
        val runtime = profile.runtime
        val binary = when (profile.engine) {
            DnsTunnelEngine.STORM -> "libstormdns_client.so"
            DnsTunnelEngine.COTTEN -> "libcottendns_client.so"
            DnsTunnelEngine.SOAR -> error("Invalid engine selection")
        }
        val executable = File(applicationInfo.nativeLibraryDir, binary)
        check(executable.canExecute())
        val directory = java.nio.file.Files.createTempDirectory(cacheDir.toPath(), "dns-engine-").toFile()
        val config = File(directory, "client.toml")
        val resolvers = File(directory, "resolvers.txt")
        try {
            config.writeText(buildString {
                appendLine("DOMAINS = [${JsonUtil.toJson(profile.zone)}]")
                appendLine("DATA_ENCRYPTION_METHOD = ${profile.encryptionMethod}")
                appendLine("ENCRYPTION_KEY = ${JsonUtil.toJson(profile.publicKey)}")
                appendLine("PROTOCOL_TYPE = \"SOCKS5\"")
                appendLine("LISTEN_IP = ${JsonUtil.toJson(runtime.listenIp)}")
                appendLine("LISTEN_PORT = ${runtime.listenPort}")
                appendLine("SOCKS5_AUTH = ${runtime.authentication}")
                appendLine("SOCKS5_USER = ${JsonUtil.toJson(runtime.username)}")
                appendLine("SOCKS5_PASS = ${JsonUtil.toJson(runtime.password)}")
                appendLine("LOCAL_DNS_ENABLED = false")
                appendLine(com.v2ray.ang.core.soar.legacyDnsToml(runtime.legacyOverrides.orEmpty()))
                appendLine("HTTP_PROXY_ENABLED = ${runtime.httpEnabled}")
                appendLine("HTTP_PROXY_PORT = ${runtime.httpPort}")
                appendLine("LOG_LEVEL = ${JsonUtil.toJson(runtime.logLevel)}")
            })
            resolvers.writeText(addresses.joinToString("\n", postfix = "\n"))
            establishInterface(runtime.vpnMtu)
            phase = "checking"
            publish()
            val child = ProcessBuilder(executable.absolutePath, "-config", config.absolutePath,
                "-resolvers", resolvers.absolutePath).directory(directory)
                .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
            process = child
            withTimeout(profile.settings.dialTimeoutSeconds * 1000L) {
                while (true) {
                    check(child.isAlive)
                    val ready = runCatching {
                        java.net.Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress(runtime.listenIp, runtime.listenPort), 300)
                        }
                    }.isSuccess
                    if (ready) break
                    delay(250)
                }
            }
            establishTunnel(runtime)
            while (!stopping && child.isAlive && tunnel?.isTun2SocksRunning() == true) delay(1000)
            if (!stopping) error("DNS engine or VPN stopped")
        } finally {
            process?.destroy()
            config.delete()
            resolvers.delete()
            directory.delete()
        }
    }

    private fun establishTunnel(runtime: SoarRuntimeSettings, virtualDns: Boolean = false) {
        check(!stopping)
        val descriptor = vpn ?: error("VPN interface missing")
        val config = """
            tunnel:
              mtu: ${runtime.vpnMtu}
              ipv4: 10.77.0.1
              ipv6: 'fd77::1'
            socks5:
              address: '${runtime.listenIp}'
              port: ${runtime.listenPort}
              udp: 'udp'
              ${if (runtime.authentication) "username: '${runtime.username.replace("'", "''")}'\n              password: '${runtime.password.replace("'", "''")}'" else ""}
            misc:
              tcp-read-write-timeout: ${runtime.tcpTimeoutSeconds * 1000}
              udp-read-write-timeout: ${runtime.udpTimeoutSeconds * 1000}
              log-level: ${runtime.logLevel.lowercase()}
        """.trimIndent() + if (virtualDns) """

            mapdns:
              address: 10.77.0.2
              port: 53
              network: 198.18.0.0
              netmask: 255.254.0.0
              cache-size: 10000
        """.trimIndent().let { "\n$it" } else ""
        val bridge = TProxyService(this, descriptor, { phase == "connected" }, {}, config)
        tunnel = bridge
        check(bridge.startTun2Socks())
        phase = "connected"
        publish()
    }

    private fun establishInterface(mtu: Int, virtualDns: Boolean = false) {
        check(!stopping)
        val builder = Builder().setSession(getString(R.string.soar_title)).setMtu(mtu)
            .addAddress("10.77.0.1", 30).addRoute("0.0.0.0", 0)
            .addAddress("fd77::1", 126).addRoute("::", 0)
            .addDnsServer(if (virtualDns) "10.77.0.2" else "1.1.1.1")
        // The native executable shares our UID. Excluding it prevents resolver sockets recursing
        // into its own tunnel; app-owned checks explicitly use Soar's loopback SOCKS listener.
        builder.addDisallowedApplication(packageName)
        val descriptor = builder.establish() ?: error("VPN permission missing")
        vpn = descriptor
    }

    private fun publish() {
        MessageHelper.sendSoarState(this, phase, resolver, attempt, total)
        NotificationHelper.updateNotification(NotificationChannelType.SOAR, this, getString(R.string.soar_title),
            getString(if (phase == "connected") R.string.soar_connected else R.string.soar_checking))
    }

    private fun trustedRoots(): String {
        // Export Android's trust store to the Go child; Android 14+ can store roots in Conscrypt's
        // APEX instead of Go's legacy filesystem search paths. Certificate verification stays on.
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>()
            .flatMap { it.acceptedIssuers.toList() }.distinctBy { it.serialNumber to it.issuerX500Principal }
            .joinToString("\n") { "-----BEGIN CERTIFICATE-----\n" +
                java.util.Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(it.encoded) + "\n-----END CERTIFICATE-----" }
    }

    private fun cleanup() {
        tunnel?.stopTun2Socks()
        tunnel = null
        try { vpn?.close() } catch (e: Exception) { LogUtil.w(AppConfig.TAG, "Soar VPN cleanup failed", e) }
        vpn = null
        process?.destroy()
        process?.let { if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null
    }

    private fun finishSession() {
        if (stopping) return
        stopping = true
        // Close the process pipe before joining the job, since readLine is blocking IO.
        scope.launch {
            process?.destroy()
            setup?.cancelAndJoin()
            transition.withLock { cleanup(); phase = "idle"; publish() }
            withContext(Dispatchers.Main) { NotificationHelper.stopForeground(this@SoarVpnService); stopSelf() }
        }
    }

    override fun onRevoke() { finishSession() }
    override fun onDestroy() {
        unregisterReceiver(commands)
        stopping = true
        scope.cancel()
        // Leak prevention on framework destruction: finish native teardown before returning,
        // bounded by the process wait (2 s), so no detached tunnel survives the service.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            process?.destroy()
            kotlinx.coroutines.withTimeoutOrNull(2500) { setup?.cancelAndJoin(); transition.withLock { cleanup() } }
        }
        phase = "idle"
        MessageHelper.sendSoarState(this, phase)
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }
}
