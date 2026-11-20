package com.v2ray.ang.handler

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.Utils
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ControlPanelContent(
    val banner: ControlPanelBanner? = null,
    val update: ControlPanelUpdate? = null,
)

data class ControlPanelBanner(val title: String, val text: String, val url: String?, val image: Bitmap)

data class ControlPanelUpdate(val versionCode: Int, val title: String, val text: String, val url: String)

internal class ControlPanelRepository(context: Context) {
    private val origin = BuildConfig.CONTROL_PANEL_URL.toHttpUrlOrNull()
    private val registration by lazy { AtomicFile(File(context.noBackupFilesDir, "control-panel-install")) }

    val configured: Boolean get() = origin != null

    fun registerInstallation() = synchronized(registrationLock) {
        val server = origin ?: return@synchronized
        val url = server.resolve("/api/install") ?: return@synchronized
        val stored = try {
            registration.readFully().decodeToString().lineSequence().toList()
        } catch (_: FileNotFoundException) {
            emptyList()
        }
        val id = stored.firstOrNull()?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
            ?: UUID.randomUUID().toString().also { writeRegistration(it, null) }
        if (stored.getOrNull(1) == server.toString()) return@synchronized

        val body = JSONObject().put("id", id).toString().toRequestBody(jsonType)
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            if (response.code == 204) writeRegistration(id, server.toString())
        }
    }

    /** Obtaining an ID does not register an installation; registration remains first-connect only. */
    private fun installationID(): String = synchronized(registrationLock) {
        val stored = try { registration.readFully().decodeToString().lineSequence().toList() }
        catch (_: FileNotFoundException) { emptyList() }
        stored.firstOrNull()?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
            ?: UUID.randomUUID().toString().also { writeRegistration(it, null) }
    }

    suspend fun loadPro(code: String? = null, token: String? = null): ProPayload {
        val server = origin ?: throw IOException("Control panel unavailable")
        val body = JSONObject().put("id", installationID())
        if (code != null) body.put("code", code) else body.put("token", token)
        val url = server.resolve(if (code != null) "/api/pro/redeem" else "/api/pro/content")!!
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)).build()
        val response = requestPayload(request, 600_000, proClient)
        val data = JSONObject(response.second)
        if (response.first !in 200..299) throw ProRequestException(data.optString("error"), data.optLong("retryAfter"))
        val content = data.optString("content").takeIf { it.isNotBlank() && it.length <= MAX_FREE_CONTENT }
            ?: throw IOException("Subscription unavailable")
        val credential = data.optString("token").takeIf { it.isNotBlank() && it.length <= 512 }
            ?: throw IOException("Subscription unavailable")
        return ProPayload(content, credential, data.optLong("retryAfter"))
    }

    internal suspend fun requestPayload(request: Request, limit: Long, requestClient: OkHttpClient = client): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = requestClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body?.source() ?: throw IOException("Empty response")
                        if (source.request(limit + 1)) throw IOException("Response too large")
                        it.code to source.readUtf8()
                    }
                    continuation.resume(result)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }

    private fun writeRegistration(id: String, registeredAt: String?) {
        val file = registration.startWrite()
        try {
            file.write((id + "\n" + registeredAt.orEmpty()).encodeToByteArray())
            registration.finishWrite(file)
        } catch (error: IOException) {
            registration.failWrite(file)
            throw error
        }
    }

    fun loadContent(): ControlPanelContent? {
        val server = origin ?: return null
        val payload = loadPayload(server)
        val banner = payload.optJSONObject("banner")?.let { data ->
            val path = data.optString("imagePath")
            val imageUrl = server.resolve(path)?.takeIf { it.host == server.host && it.port == server.port &&
                it.scheme == server.scheme && it.encodedPath == "/api/banner-image" }
            val image = imageUrl?.let { runCatching { fetch(it, 384 * 1024)?.let(::decodeImage) }.getOrNull() }
            image?.let {
                ControlPanelBanner(data.optString("title"), data.optString("text"), safeLink(data.optStringOrNull("url")), it)
            }
        }
        val update = payload.optJSONObject("update")?.let { data ->
            val versionCode = data.optInt("versionCode")
            val link = safeLink(data.optStringOrNull("url"))
            if (versionCode > BuildConfig.VERSION_CODE && link != null) {
                ControlPanelUpdate(versionCode, data.optString("title"), data.optString("text"), link)
            } else null
        }
        return ControlPanelContent(banner, update)
    }

    suspend fun loadFreeConfigurationCancellable(): String? {
        val server = origin ?: return null
        val payload = requestPayload(Request.Builder().url(server.resolve("/api/content")!!).build(), 1_600_000)
        if (payload.first !in 200..299) throw IOException("Control panel unavailable")
        val content = JSONObject(payload.second).optStringOrNull("freeConfig")?.takeIf { it.isNotBlank() } ?: return null
        val pending = ArrayDeque<String>().apply { add(content) }
        val visited = mutableSetOf<String>()
        val profiles = mutableListOf<String>()
        var size = 0
        while (pending.isNotEmpty()) {
            val text = pending.removeFirst().trim()
            if (text.length > MAX_FREE_CONTENT) throw IOException("Content too large")
            val decoded = if ("://" !in text && !text.startsWith('{') && !text.startsWith('[')) Utils.decode(text).ifBlank { text } else text
            for (line in decoded.lineSequence().map(String::trim).filter(String::isNotEmpty)) {
                val url = line.toHttpUrlOrNull()
                if (url != null) {
                    if (visited.add(url.toString())) {
                        if (visited.size > 16) throw IOException("Too many sources")
                        val result = requestPayload(Request.Builder().url(url).build(), MAX_FREE_CONTENT.toLong(), freeClient)
                        if (result.first !in 200..299) throw IOException("Source unavailable")
                        pending.add(result.second)
                    }
                } else {
                    size += line.length + 1
                    if (size > MAX_FREE_CONTENT) throw IOException("Content too large")
                    profiles += line
                }
            }
        }
        return profiles.joinToString("\n").takeIf(String::isNotBlank)
    }

    fun loadFreeConfiguration(): String? {
        val server = origin ?: return null
        val content = loadPayload(server).optStringOrNull("freeConfig")
            ?.takeIf { it.length <= MAX_FREE_CONTENT && it.isNotBlank() } ?: return null
        return resolveFreeConfiguration(content, { url ->
            try {
                fetch(url, MAX_FREE_CONTENT.toLong(), freeClient)?.decodeToString()
            } catch (_: IOException) {
                throw IOException("Could not download managed free configurations")
            }
        }, Utils::decode)
    }

    private fun loadPayload(server: HttpUrl): JSONObject {
        val url = server.newBuilder().encodedPath("/api/content").build()
        val data = fetch(url, 1_600_000) ?: throw IOException("Could not read control panel content")
        return JSONObject(data.decodeToString())
    }

    private fun fetch(url: HttpUrl, limit: Long, requestClient: OkHttpClient = client): ByteArray? =
        requestClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body ?: return null
            if (!response.isSuccessful || body.contentLength() > limit) return null
            val source = body.source()
            if (source.request(limit + 1)) return null
            source.readByteArray()
        }

    private fun decodeImage(data: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        val largest = maxOf(options.outWidth, options.outHeight)
        if (options.outWidth <= 0 || options.outHeight <= 0 || largest > 12_000) return null
        options.inSampleSize = 1
        while (largest / options.inSampleSize > 1280) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)

    private fun safeLink(value: String?): String? =
        value?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()

    internal companion object {
        private val registrationLock = Any()
        private const val MAX_FREE_CONTENT = 262_144

        /** Resolves subscription sources in memory, keeping their URLs out of stored subscription records. */
        internal fun resolveFreeConfiguration(
            content: String,
            download: (HttpUrl) -> String?,
            decode: (String) -> String,
        ): String? {
            val pending = ArrayDeque<String>()
            val visited = mutableSetOf<String>()
            val profiles = mutableListOf<String>()
            var size = 0
            pending.add(content)
            while (pending.isNotEmpty()) {
                val text = pending.removeFirst().trim()
                if (text.length > MAX_FREE_CONTENT) throw IOException("Managed configuration content is too large")
                val decoded = if ("://" !in text && !text.startsWith('{') && !text.startsWith('[')) {
                    decode(text).ifBlank { text }
                } else text
                for (line in decoded.lineSequence().map(String::trim).filter(String::isNotEmpty)) {
                    val url = line.toHttpUrlOrNull()
                    if (url != null) {
                        if (visited.add(url.toString())) {
                            pending.add(download(url) ?: throw IOException("Managed configuration source is unavailable"))
                        }
                    } else {
                        size += line.length + 1
                        if (size > MAX_FREE_CONTENT) throw IOException("Managed configuration content is too large")
                        profiles += line
                    }
                }
            }
            return profiles.joinToString("\n").takeIf(String::isNotBlank)
        }
        val jsonType = "application/json; charset=utf-8".toMediaType()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        private val proClient = client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
        internal fun managedFreeClient(base: OkHttpClient = client): OkHttpClient =
            base.newBuilder().followRedirects(true).followSslRedirects(false).build()
        private val freeClient = managedFreeClient()
    }
}

internal data class ProPayload(val content: String, val token: String, val retryAfter: Long)
internal class ProRequestException(val reason: String, val retryAfter: Long) : IOException("Subscription request failed")
