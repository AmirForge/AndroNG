package com.v2ray.ang

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.ControlPanelRepository
import com.v2ray.ang.util.HttpUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*
import kotlin.concurrent.thread

class SubscriptionTransportSecurityTest {
    private data class Incoming(val target: String, val headers: Map<String, String>)
    private data class Reply(val status: Int = 200, val headers: Map<String, String> = emptyMap(), val body: String = "ok")
    /** Test-only loopback HTTP/1.1 server, using APIs also present on the Android compile classpath. */
    private class LoopbackServer(
        private val listener: ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")),
        private val respond: (Incoming) -> Reply,
    ) : Closeable {
        val port: Int get() = listener.localPort
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true, name = "security-loopback") {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        active.set(socket)
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val first = input.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val reply = respond(Incoming(first.split(' ')[1], headers))
                        val body = reply.body.toByteArray(Charsets.UTF_8)
                        val message = buildString {
                            append("HTTP/1.1 ${reply.status} Test\r\n")
                            append("Content-Length: ${body.size}\r\nConnection: close\r\n")
                            reply.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                            append("\r\n")
                        }.toByteArray(Charsets.US_ASCII)
                        socket.getOutputStream().apply { write(message); write(body); flush() }
                    }
                    active.set(null)
                } catch (error: Exception) {
                    if (!listener.isClosed) failure.compareAndSet(null, error)
                }
            }
        }
        override fun close() {
            listener.close(); active.get()?.close(); worker.join(5000)
            check(!worker.isAlive) { "Loopback server failed to close" }
            failure.get()?.let { throw AssertionError("Loopback server failure", it) }
        }
    }

    @Test fun `cross origin redirect strips every custom header but same origin retains them`() {
        val authorization = AtomicReference<String?>()
        val custom = AtomicReference<String?>()
        LoopbackServer { incoming ->
            authorization.set(incoming.headers["authorization"])
            custom.set(incoming.headers["x-private-token"])
            Reply()
        }.use { target ->
            LoopbackServer { incoming ->
                when (URI(incoming.target).path) {
                    "/cross" -> Reply(302, mapOf("Location" to "http://127.0.0.1:${target.port}/target"), "")
                    "/same" -> Reply(302, mapOf("Location" to "/ok"), "")
                    else -> {
                        authorization.set(incoming.headers["authorization"])
                        custom.set(incoming.headers["x-private-token"])
                        Reply()
                    }
                }
            }.use { source ->
                val base = "http://127.0.0.1:${source.port}"
                val headers = """{"Authorization":"Bearer test-canary","X-Private-Token":"private-canary"}"""
                assertEquals("ok", HttpUtil.getUrlContentWithUserAgent(UrlContentRequest("$base/cross", requestHeaders = headers, timeout = 3000)))
                assertNull(authorization.get()); assertNull(custom.get())
                assertEquals("ok", HttpUtil.getUrlContentWithUserAgent(UrlContentRequest("$base/same", requestHeaders = headers, timeout = 3000)))
                assertEquals("Bearer test-canary", authorization.get()); assertEquals("private-canary", custom.get())
            }
        }
    }

    @Test fun `authenticated HTTP proxy still works via its challenge`() {
        val challenges = AtomicInteger(); val authenticated = AtomicInteger()
        LoopbackServer { incoming ->
            if (incoming.headers["proxy-authorization"] == okhttp3.Credentials.basic("test-user", "test-password")) {
                authenticated.incrementAndGet(); Reply()
            } else {
                challenges.incrementAndGet(); Reply(407, mapOf("Proxy-Authenticate" to "Basic realm=\"test\""), "")
            }
        }.use { proxy ->
            assertEquals("ok", HttpUtil.getUrlContentWithUserAgent(UrlContentRequest("http://unused.example.test/config",
                httpPort = proxy.port, proxyUsername = "test-user", proxyPassword = "test-password", timeout = 3000)))
            assertEquals(1, challenges.get()); assertEquals(1, authenticated.get())
        }
    }

    @Test fun `HTTPS to HTTP is blocked while safe upgrades and equivalent origins are recognized`() {
        assertFalse(HttpUtil.subscriptionRedirectAllowed("https://example.org/sub", "http://example.org/sub"))
        assertFalse(HttpUtil.subscriptionRedirectAllowed("https://example.org/sub", "file:///private"))
        assertTrue(HttpUtil.subscriptionRedirectAllowed("http://example.org/sub", "https://example.org/sub"))
        assertTrue(HttpUtil.sameOrigin("https://EXAMPLE.org:443/first", "https://example.org/second"))
        assertFalse(HttpUtil.sameOrigin("https://example.org/sub", "https://example.org:8443/sub"))
        assertFalse(HttpUtil.sameOrigin("https://example.org/sub", "https://example.org.evil.test/sub"))
    }

    @Test fun `real HTTPS redirect never reaches cleartext target and secure same origin redirect still works`() {
        // Public, test-only self-signed certificate. Never packaged in the application or used for signing.
        val keys = KeyStore.getInstance("PKCS12").apply {
            SubscriptionTransportSecurityTest::class.java.getResourceAsStream("/security/loopback-test.p12").use { load(it, "test-only".toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-only".toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
        val cleartextHits = AtomicInteger()
        LoopbackServer { cleartextHits.incrementAndGet(); Reply() }.use { plain ->
            val socket = tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            LoopbackServer(socket) { incoming ->
                when (URI(incoming.target).path) {
                    "/downgrade" -> Reply(302, mapOf("Location" to "http://127.0.0.1:${plain.port}/"), "")
                    "/safe" -> Reply(302, mapOf("Location" to "/ok"), "")
                    else -> Reply()
                }
            }.use { secure ->
                val base = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, tmf.trustManagers.single() as X509TrustManager)
                    .callTimeout(3, TimeUnit.SECONDS).build()
                val client = ControlPanelRepository.managedFreeClient(base)
                try {
                    val origin = "https://127.0.0.1:${secure.port}"
                    client.newCall(Request.Builder().url("$origin/downgrade").build()).execute().use { assertEquals(302, it.code) }
                    assertEquals(0, cleartextHits.get())
                    client.newCall(Request.Builder().url("$origin/safe").build()).execute().use { assertEquals(200, it.code) }
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }

    @Test fun `download error logging drops URL credentials headers and nested causes`() {
        val original = java.io.IOException("https://user:secret@example.org/sub/token?key=private", IllegalStateException("Authorization: Bearer sensitive"))
        val safe = HttpUtil.privateDownloadError(original)
        val logged = safe.stackTraceToString()
        for (secret in listOf("example.org", "user:secret", "sub/token?key=private", "Bearer sensitive")) assertFalse(logged.contains(secret), secret)
        assertNull(safe.cause)
        assertTrue(safe.message!!.contains("IOException"))
    }
}
