package com.v2ray.ang.ui.main

import android.app.Application
import android.util.Log
import com.tencent.mmkv.MMKV
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.UseConstructor
import org.mockito.Answers
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class MainViewModelAetherAutoConnectTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scheduler = TestCoroutineScheduler()
    private val source = mock<MainDataSource>()
    private val quick = mock<QuickAetherProfile>()
    private val events = MutableSharedFlow<MainServiceEvent>(extraBufferCapacity = 32)
    private var selected: String? = "old"
    private var profile: ProfileItem? = ProfileItem.create(EConfigType.AETHER).copy(
        aetherProtocol = "masque", server = "192.0.2.1", serverPort = "443",
        aetherEndpointCount = 5, aetherEndpoints = listOf("h2@192.0.2.1:443"),
    )
    private lateinit var viewModel: MainViewModel

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun prepare() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        whenever(source.mainServiceEvent).thenReturn(events)
        whenever(source.getSelectedSubscriptionId()).thenReturn("")
        whenever(source.getSelectServer()).thenAnswer { selected }
        org.mockito.kotlin.doAnswer { selected = it.getArgument(0); null }.whenever(source).setSelectServer(any())
        whenever(source.getSubscriptions()).thenReturn(emptyList())
        whenever(source.getServerGuidList(any())).thenReturn(emptyList())
        whenever(source.decodeServerConfig(eq("a"))).thenAnswer { profile }
        whenever(quick.refresh(eq("a"), any<Boolean>(), any())).thenReturn(null)
        viewModel = makeViewModel()
    }

    private fun makeViewModel(preparer: QuickAetherProfile = quick, timeoutMillis: Long = 5_000L): MainViewModel {
        val loadFreeConfig: suspend () -> String? = { null }
        // Construct the real object as a mock so constructor-launched collectors capture the same
        // instance as the test, rather than an original object copied later by spy().
        return mock<MainViewModel>(
            useConstructor = UseConstructor.withArguments(mock<Application>(), source, preparer, loadFreeConfig, timeoutMillis),
            defaultAnswer = Answers.CALLS_REAL_METHODS,
        ).also {
            doNothing().whenever(it).toastError(any<Int>())
            doNothing().whenever(it).toastError(any<String>())
            doNothing().whenever(it).toastSuccess(any<Int>())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun cleanup() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun prepareConnection() {
        viewModel.onAction(MainAction.RefreshAetherProfile("a", true))
        withTimeout(5_000) { viewModel.uiState.first { it.aetherConnectionRequest == "a" } }
    }

    private suspend fun awaitIdle() {
        withTimeout(5_000) { viewModel.uiState.first { !it.isCreatingAether } }
    }

    @Test
    fun `toolbar batch refresh covers all groups clears stale tests and never auto connects`() = runBlocking {
        whenever(source.getServerGuidList("")).thenReturn(listOf("a", "b"))
        whenever(quick.refreshAll(any(), any())).thenReturn(QuickAetherProfile.RefreshResult(listOf("a", "b")))
        assertFalse(viewModel.uiState.value.refreshingAllEndpoints)
        viewModel.onAction(MainAction.RefreshAllAetherEndpoints)
        awaitIdle()
        verify(quick).refreshAll(eq(listOf("a", "b")), any())
        verify(source).clearAllTestDelayResults(eq(listOf("a", "b")))
        assertFalse(viewModel.uiState.value.refreshingAllEndpoints)
        assertNull(viewModel.uiState.value.aetherConnectionRequest)
        assertEquals("old", selected)
        verify(viewModel).toastSuccess(eq(R.string.toast_success))
    }

    @Test
    fun `toolbar refresh errors and cancellation clear progress without reporting success`() = runBlocking {
        whenever(quick.refreshAll(any(), any())).thenReturn(QuickAetherProfile.RefreshResult(emptyList(), R.string.aether_scan_failed))
        viewModel.onAction(MainAction.RefreshAllAetherEndpoints)
        awaitIdle()
        verify(viewModel).toastError(eq(R.string.aether_scan_failed))
        verify(source, never()).clearAllTestDelayResults(any())
        whenever(quick.refreshAll(any(), any())).thenAnswer { throw kotlinx.coroutines.CancellationException("stopped") }
        viewModel.onAction(MainAction.RefreshAllAetherEndpoints)
        awaitIdle()
        assertFalse(viewModel.uiState.value.refreshingAllEndpoints)
        verify(viewModel, never()).toastSuccess(any<Int>())
    }

    @Test
    fun `bolt selects the prepared profile once and feedback lasts until real service readiness`() = runBlocking {
        assertFalse(viewModel.uiState.value.isCreatingAether)
        prepareConnection()
        assertEquals("a", selected)
        assertEquals("a", viewModel.uiState.value.selectedGuid)
        assertTrue(viewModel.uiState.value.rebuildingAether)
        assertFalse(viewModel.claimPreparedAetherConnection("other"))
        assertFalse(viewModel.beginPreparedAetherConnection())
        assertFalse(viewModel.isPreparedAetherConnectionCurrent())
        assertTrue(viewModel.claimPreparedAetherConnection("a"))
        assertFalse(viewModel.claimPreparedAetherConnection("a"))
        assertNull(viewModel.uiState.value.aetherConnectionRequest)
        assertTrue(viewModel.beginPreparedAetherConnection())
        assertTrue(viewModel.isPreparedAetherConnectionCurrent())
        assertFalse(viewModel.beginPreparedAetherConnection())
        events.emit(MainServiceEvent.StateRunning)
        assertTrue(viewModel.uiState.value.isCreatingAether)
        events.emit(MainServiceEvent.StateStartSuccess)
        awaitIdle()
        assertEquals(MainStatus.Connected, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.rebuildingAether)
        assertNull(viewModel.uiState.value.refreshingAetherGuid)
        assertFalse(viewModel.isPreparedAetherConnectionCurrent())
        verify(quick).refresh(eq("a"), eq(true), any())
        Unit
    }

    @Test
    fun `failed key or scan never requests a connection or changes the selected profile`() = runBlocking {
        whenever(quick.refresh(eq("a"), eq(true), any())).thenReturn(R.string.aether_scan_failed)
        viewModel.onAction(MainAction.RefreshAetherProfile("a", true))
        awaitIdle()
        assertEquals("old", selected)
        assertNull(viewModel.uiState.value.aetherConnectionRequest)
        assertFalse(viewModel.beginPreparedAetherConnection())
    }

    @Test
    fun `removed or unsupported profiles never request a start`() = runBlocking {
        for (value in listOf(null, ProfileItem.create(EConfigType.VLESS))) {
            profile = value
            viewModel.onAction(MainAction.RefreshAetherProfile("a", true))
            awaitIdle()
            assertEquals("old", selected)
            assertNull(viewModel.uiState.value.aetherConnectionRequest)
        }
    }

    @Test
    fun `permission rejection clears feedback and a later callback cannot start`() = runBlocking {
        prepareConnection()
        assertTrue(viewModel.claimPreparedAetherConnection("a"))
        viewModel.onAction(MainAction.CancelPreparedAetherConnection)
        awaitIdle()
        assertFalse(viewModel.beginPreparedAetherConnection())
        verify(source, never()).sendMsg2Service(eq(AppConfig.MSG_STATE_STOP), any())
    }

    @Test
    fun `service failure ends the animation without reporting a successful refresh`() = runBlocking {
        prepareConnection()
        viewModel.claimPreparedAetherConnection("a")
        viewModel.beginPreparedAetherConnection()
        events.emit(MainServiceEvent.StateStartFailure(null))
        awaitIdle()
        assertFalse(viewModel.uiState.value.rebuildingAether)
        verify(viewModel, never()).toastSuccess(eq(R.string.toast_success))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a stalled service is stopped at the connection deadline and feedback ends`() = runBlocking {
        viewModel.viewModelScope.cancel()
        viewModel = makeViewModel(timeoutMillis = 50L)
        prepareConnection()
        viewModel.claimPreparedAetherConnection("a")
        viewModel.beginPreparedAetherConnection()
        // The timeout logs through the normal Android/MMKV logger. Supply its platform boundary
        // while advancing the virtual clock; no native MMKV library is initialized in JVM tests.
        org.mockito.Mockito.mockStatic(MMKV::class.java).use { storage ->
            storage.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(mock<MMKV>())
            org.mockito.Mockito.mockStatic(Log::class.java).use {
                withTimeout(5_000) {
                    while (viewModel.uiState.value.isCreatingAether) {
                        scheduler.advanceTimeBy(51)
                        scheduler.runCurrent()
                        delay(1)
                    }
                }
            }
        }
        verify(source).sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
        verify(viewModel).toastError(R.string.toast_services_failure)
    }

    @Test
    fun `a newer user selection cancels the pending automatic start`() = runBlocking {
        prepareConnection()
        viewModel.claimPreparedAetherConnection("a")
        viewModel.updateSelectedGuid("other")
        awaitIdle()
        assertEquals("other", selected)
        assertFalse(viewModel.beginPreparedAetherConnection())
        assertFalse(viewModel.isPreparedAetherConnectionCurrent())
    }

    @Test
    fun `choosing another card during preparation cancels it before any automatic start`() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        val keys = mock<com.v2ray.ang.ui.server.AetherKeysSource>()
        val editor = mock<com.v2ray.ang.ui.server.AetherEditorSource>()
        val profiles = mock<com.v2ray.ang.ui.server.ProfileEditorSource>()
        whenever(profiles.loadProfile("a")).thenReturn(profile)
        val preparer = QuickAetherProfile(keys, editor, profiles, prepareScanNetwork = { gate.await() })
        viewModel.viewModelScope.cancel()
        viewModel = makeViewModel(preparer)
        viewModel.onAction(MainAction.RefreshAetherProfile("a", true))
        assertTrue(viewModel.uiState.value.isCreatingAether)
        viewModel.updateSelectedGuid("other")
        gate.complete(true)
        awaitIdle()
        assertEquals("other", selected)
        assertNull(viewModel.uiState.value.aetherConnectionRequest)
        assertFalse(viewModel.beginPreparedAetherConnection())
        verifyNoInteractions(keys, editor)
    }

    @Test
    fun `normal refresh keeps its endpoint only behavior without an automatic connection`() = runBlocking {
        viewModel.onAction(MainAction.RefreshAetherProfile("a", false))
        awaitIdle()
        assertEquals("old", selected)
        assertNull(viewModel.uiState.value.aetherConnectionRequest)
        assertFalse(viewModel.beginPreparedAetherConnection())
    }
    @Test
    fun `stop action cancels bolt while waiting for permission without starting VPN`() = runBlocking {
        prepareConnection()
        viewModel.onAction(MainAction.CancelPreparation)
        awaitIdle()
        assertNull(viewModel.uiState.value.aetherConnectionRequest)
        assertFalse(viewModel.beginPreparedAetherConnection())
        verify(source, never()).sendMsg2Service(eq(AppConfig.MSG_STATE_STOP), any())
    }

    @Test
    fun `stop action stops a bolt connection already dispatched to the service`() = runBlocking {
        prepareConnection()
        viewModel.claimPreparedAetherConnection("a")
        viewModel.beginPreparedAetherConnection()
        viewModel.onAction(MainAction.CancelPreparation)
        awaitIdle()
        verify(source).sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
        assertFalse(viewModel.uiState.value.rebuildingAether)
    }

}
