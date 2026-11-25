package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.Answers
import org.mockito.kotlin.*

class MainViewModelProSubscriptionTest {
    private val source = mock<MainDataSource>()
    private val quick = mock<QuickAetherProfile>()
    private lateinit var vm: MainViewModel
    private val state = MemoryState()
    private var group = false
    private var fetched = 0
    private var imported = 0
    private val content = "vless://fake@example.org:443#test"
    private class MemoryState : ProSubscriptionState {
        var credential: String? = null
        var attempts = 0
        var until = 0L
        var saveFails = false
        override fun token() = credential
        override fun activate(token: String) { if (saveFails) throw java.io.IOException("test storage failure"); credential = token }
        override fun blockedUntil() = until
        override fun consume(now: Long): Boolean {
            if (until > now) return false
            attempts++
            if (attempts == 5) until = now + 3_600_000
            return true
        }
        override fun blockFor(seconds: Long) { if (seconds > 0) until = System.currentTimeMillis() + seconds * 1000 }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Configure the existing logger through a fake native settings boundary before IO callbacks run.
        org.mockito.Mockito.mockStatic(com.tencent.mmkv.MMKV::class.java).use { native ->
            val settings = mock<com.tencent.mmkv.MMKV>()
            whenever(settings.decodeString(eq(AppConfig.PREF_LOGLEVEL), anyOrNull())).thenReturn("none")
            native.`when`<com.tencent.mmkv.MMKV> { com.tencent.mmkv.MMKV.mmkvWithID("SETTING", com.tencent.mmkv.MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
            com.v2ray.ang.util.LogUtil.refreshLogLevel()
        }
        whenever(source.getString(any())).thenReturn("test")
        whenever(source.getString(any(), any())).thenReturn("test")
        whenever(source.mainServiceEvent).thenReturn(MutableSharedFlow())
        whenever(source.getSelectedSubscriptionId()).thenReturn("")
        whenever(source.getSubscriptions()).thenAnswer { if (group) listOf(SubscriptionCache(AppConfig.PRO_SUBSCRIPTION_ID, SubscriptionItem())) else emptyList() }
        whenever(source.getServerGuidList(any())).thenReturn(emptyList())
        whenever(source.getSubscriptionItem(eq(AppConfig.PRO_SUBSCRIPTION_ID))).thenAnswer { if (group) SubscriptionItem() else null }
        whenever(source.ensureProSubscription(AppConfig.PRO_SUBSCRIPTION_ID)).thenAnswer { group = true; AppConfig.PRO_SUBSCRIPTION_ID }
        whenever(source.importFreeProfiles(any(), eq(AppConfig.PRO_SUBSCRIPTION_ID))).thenAnswer { imported++; 1 }
        whenever(source.rollbackProImport(eq(AppConfig.PRO_SUBSCRIPTION_ID), any(), any())).thenAnswer { group = false; true }
        whenever(source.commitProImport(eq(AppConfig.PRO_SUBSCRIPTION_ID), any())).thenReturn(true)
        vm = mock(useConstructor = UseConstructor.withArguments(mock<Application>(), source, quick, suspend { null }, 120000L), defaultAnswer = Answers.CALLS_REAL_METHODS)
        doNothing().whenever(vm).toast(any<String>())
        doNothing().whenever(vm).toastError(any<Int>())
        vm.proStore = state
        vm.loadProConfigs = { _, _ -> fetched++; ProPayload(content, "test.token", 0) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach fun cleanup() { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    private suspend fun idle() { withTimeout(5000) { vm.isLoading.first { !it } } }
    private suspend fun submit(code: String = "tg-test") { vm.onAction(MainAction.SubmitProCode(code)); idle() }

    @Test fun `invalid and blank codes do not fetch or activate`() = runBlocking {
        for (code in listOf("", "../test", "with spaces")) {
            submit(code)
            assertEquals(R.string.pro_invalid_code, vm.uiState.value.proError)
        }
        assertEquals(0, fetched); assertEquals(0, state.attempts); assertFalse(vm.uiState.value.isPro)
    }
    @Test fun `download finishes before managed import and success activates Pro only after import`() = runBlocking {
        vm.loadProConfigs = { code, token ->
            assertEquals("tg-test", code); assertNull(token); assertFalse(group); assertEquals(0, imported)
            verify(source, never()).sendMsg2Service(eq(AppConfig.MSG_STATE_STOP), any())
            ProPayload(content, "test.token", 0)
        }
        submit()
        assertEquals(1, imported); assertEquals("test.token", state.credential); assertTrue(vm.uiState.value.isPro)
        assertEquals(AppConfig.PRO_SUBSCRIPTION_ID, vm.uiState.value.selectedGroupId)
    }
    @Test fun `fifth failed fetch is allowed then next attempt is locally blocked`() = runBlocking {
        vm.loadProConfigs = { _, _ -> fetched++; throw ProRequestException("subscription_unavailable", 0) }
        repeat(6) { submit() }
        assertEquals(5, fetched); assertEquals(5, state.attempts)
        assertFalse(vm.uiState.value.isPro); assertTrue(vm.uiState.value.proBlockedUntil > System.currentTimeMillis())
        assertEquals(R.string.pro_rate_limited, vm.uiState.value.proError)
        assertFalse(group)
    }
    @Test fun `server cooldown is persisted and shown`() = runBlocking {
        vm.loadProConfigs = { _, _ -> throw ProRequestException("rate_limited", 1800) }
        submit()
        assertTrue(state.until > System.currentTimeMillis())
        assertEquals(R.string.pro_rate_limited, vm.uiState.value.proError)
    }
    @Test fun `empty import leaves no Pro credential and rolls back group`() = runBlocking {
        whenever(source.importFreeProfiles(any(), any())).thenReturn(0)
        submit()
        assertNull(state.credential); assertFalse(vm.uiState.value.isPro); assertFalse(group)
        verify(source).rollbackProImport(AppConfig.PRO_SUBSCRIPTION_ID, emptySet(), true)
        Unit
    }
    @Test fun `credential storage failure rolls back imported profiles`() = runBlocking {
        state.saveFails = true
        submit()
        assertNull(state.credential); assertFalse(vm.uiState.value.isPro); assertFalse(group)
        verify(source).rollbackProImport(AppConfig.PRO_SUBSCRIPTION_ID, emptySet(), true)
        Unit
    }
    @Test fun `stop cancels download and clears feedback without creating subscription`() = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        vm.loadProConfigs = { _, _ -> try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) } }
        vm.onAction(MainAction.SubmitProCode("tg-test"))
        withTimeout(5000) { started.await() }
        assertNotNull(vm.uiState.value.aetherProgress)
        vm.onAction(MainAction.CancelPreparation)
        withTimeout(5000) { stopped.await() }; idle()
        assertNull(vm.uiState.value.aetherProgress); assertFalse(vm.uiState.value.proBusy)
        assertFalse(group); assertNull(state.credential)
    }
    @Test fun `managed refresh uses saved credential rather than consuming code budget`() = runBlocking {
        state.credential = "saved.token"
        vm.loadProConfigs = { code, token -> assertNull(code); assertEquals("saved.token", token); ProPayload(content, "saved.token", 0) }
        vm.onAction(MainAction.UpdateGroupSubscription(AppConfig.PRO_SUBSCRIPTION_ID)); idle()
        assertEquals(0, state.attempts); assertTrue(vm.uiState.value.isPro)
    }
    @Test fun `external import has no side effects until confirmed and another intent cannot replace it`() = runBlocking {
        assertNull(vm.uiState.value.pendingExternalImport)
        vm.onAction(MainAction.StageExternalImport(content))
        assertEquals(content, vm.uiState.value.pendingExternalImport)
        vm.onAction(MainAction.StageExternalImport("andro://another-request"))
        assertEquals(content, vm.uiState.value.pendingExternalImport)
        verify(source, never()).importBatchConfig(anyOrNull(), any(), any())
        verify(source, never()).sendMsg2Service(eq(AppConfig.MSG_STATE_STOP), any())
        vm.onAction(MainAction.DismissExternalImport)
        vm.onAction(MainAction.ConfirmExternalImport)
        assertNull(vm.uiState.value.pendingExternalImport)
        verify(source, never()).importBatchConfig(anyOrNull(), any(), any())
        assertFalse(vm.isLoading.value)
    }

    @Test fun `external import confirms once and rejects oversized or blank input`() = runBlocking {
        for (text in listOf("", " ", "a".repeat(com.v2ray.ang.ui.UrlSchemeActivity.MAX_EXTERNAL_IMPORT_CHARS + 1))) {
            vm.onAction(MainAction.StageExternalImport(text))
            assertNull(vm.uiState.value.pendingExternalImport)
        }
        whenever(source.importBatchConfig(eq(content), eq(""), eq(true))).thenReturn(1 to 0)
        vm.onAction(MainAction.StageExternalImport(content))
        vm.onAction(MainAction.ConfirmExternalImport)
        idle()
        vm.onAction(MainAction.ConfirmExternalImport)
        verify(source, times(1)).importBatchConfig(content, "", true)
        assertNull(vm.uiState.value.pendingExternalImport)
    }

    @Test fun `seven galaxy taps only open presentation and do not change VPN or subscription state`() {
        repeat(6) { vm.onAction(MainAction.GalaxyTapped); assertFalse(vm.uiState.value.andromedaOpen) }
        vm.onAction(MainAction.GalaxyTapped); assertTrue(vm.uiState.value.andromedaOpen)
        repeat(7) { vm.onAction(MainAction.GalaxyTapped) }
        vm.onAction(MainAction.DismissAndromeda); assertFalse(vm.uiState.value.andromedaOpen)
        vm.onAction(MainAction.GalaxyTapped); assertFalse(vm.uiState.value.andromedaOpen)
        verify(source, never()).sendMsg2Service(any(), any())
        assertNull(state.credential); assertEquals(0, state.attempts)
    }

}
