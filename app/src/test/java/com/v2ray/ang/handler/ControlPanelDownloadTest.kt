package com.v2ray.ang.handler

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.io.IOException

class ControlPanelDownloadTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancellation cancels HTTP call and late failure cannot revive it`() = runTest {
        val call = mock<Call>()
        val client = mock<OkHttpClient>()
        whenever(client.newCall(any())).thenReturn(call)
        val repository = ControlPanelRepository(mock<Context>())
        val task = async { repository.requestPayload(Request.Builder().url("https://worker.example/api/content").build(), 1024, client) }
        runCurrent()
        val callback = argumentCaptor<Callback>()
        verify(call).enqueue(callback.capture())
        task.cancelAndJoin()
        verify(call).cancel()
        callback.firstValue.onFailure(call, IOException("Cancelled"))
        assertTrue(task.isCancelled)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `bounded response returns status and content`() = runTest {
        val call = mock<Call>(); val client = mock<OkHttpClient>()
        whenever(client.newCall(any())).thenReturn(call)
        val request = Request.Builder().url("https://worker.example/api/content").build()
        val task = async { ControlPanelRepository(mock<Context>()).requestPayload(request, 1024, client) }
        runCurrent()
        val callback = argumentCaptor<Callback>(); verify(call).enqueue(callback.capture())
        callback.firstValue.onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("test".toResponseBody()).build())
        assertEquals(200 to "test", task.await())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `oversized response fails without delivering partial content`() = runTest {
        supervisorScope {
            val call = mock<Call>(); val client = mock<OkHttpClient>()
            whenever(client.newCall(any())).thenReturn(call)
            val request = Request.Builder().url("https://worker.example/api/content").build()
            val task = async { ControlPanelRepository(mock<Context>()).requestPayload(request, 3, client) }
            runCurrent()
            val callback = argumentCaptor<Callback>(); verify(call).enqueue(callback.capture())
            callback.firstValue.onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("too large".toResponseBody()).build())
            try { task.await(); fail<Unit>("Oversized response must fail") } catch (_: IOException) { }
        }
    }
}
