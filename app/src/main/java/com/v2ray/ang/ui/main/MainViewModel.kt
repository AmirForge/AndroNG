package com.v2ray.ang.ui.main

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.fmt.AetherSetupLink
import com.v2ray.ang.handler.ProPayload
import com.v2ray.ang.handler.ProSubscriptionState
import com.v2ray.ang.handler.ProSubscriptionStore
import com.v2ray.ang.handler.ManagedSubscriptionUpdate
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.proGroupNumber
import com.v2ray.ang.handler.ProRequestException
import com.v2ray.ang.handler.ControlPanelRepository
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException
import kotlin.time.TimeSource

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> = servers.map { server ->
    val delayMillis = updates[server.guid]
    if (delayMillis == null || delayMillis == server.testDelayMillis) {
        server
    } else {
        server.copy(testDelayMillis = delayMillis)
    }
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, Long>,
): List<ServerRowUiModel> = rows.map { row ->
    val delayMillis = updates[row.guid]
    if (delayMillis == null || delayMillis == row.testDelayMillis) {
        row
    } else {
        row.copy(testDelayMillis = delayMillis)
    }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource,
    private val quickAether: QuickAetherProfile = QuickAetherProfile(application),
    private val loadFreeConfig: suspend () -> String? = { ControlPanelRepository(application).loadFreeConfigurationCancellable() },
    private val aetherConnectTimeoutMillis: Long = 120_000L,
) : BaseViewModel(application) {

    /** One prepared profile awaiting foreground permission/start and the daemon's real readiness result. */
    private class PreparedAetherConnection(val guid: String) {
        var claimed = false
        var started = false
        val start = CompletableDeferred<Boolean>()
        val result = CompletableDeferred<Boolean>()
    }

    private var preparedAetherConnection: PreparedAetherConnection? = null
    private var aetherRefreshJob: Job? = null
    private var importJob: Job? = null
    private var usageJob: Job? = null
    private val galaxyTapSequence = GalaxyTapSequence()

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val controlPanelRepository = ControlPanelRepository(application)
    private val converterContext = application
    internal var proStore: ProSubscriptionState = ProSubscriptionStore
    internal var loadProConfigs: suspend (String?, String?) -> ProPayload = { code, token -> controlPanelRepository.loadPro(code, token) }
    private var controlPanelJob: Job? = null
    private var dismissedControlPanelVersion = 0

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    /**
     * v2meda: per group, the moves asked for and not stored yet, and the groups one of whose moves the storage refused,
     * see [moveServer]. Concurrent, as a move's job may end on the thread its store ended on, as under an unconfined
     * dispatcher.
     */
    private val unstoredMoves = ConcurrentHashMap<String, Int>()
    private val refusedMoveGroups: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** v2meda: the number the last refusal of a move to be told got, see [MainUiState.moveRefusal]. */
    private val moveRefusals = AtomicInteger()

    /**
     * v2meda: per group, the moves shown, and those settled, stored or refused, see [storeMove]. A reading of a group
     * taken before every move shown was settled is held back, see [updateGroupUi], and the group is shown anew once its
     * moves are, see [moveServer]; [groupsReadEarly] names the groups waiting for that. A move is shown, and a reading
     * shown or held back, under [groupShowLock], one at a time.
     */
    private val shownMoves = ConcurrentHashMap<String, Int>()
    private val settledMoves = ConcurrentHashMap<String, Int>()
    private val groupsReadEarly: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val groupShowLock = Any()

    /**
     * v2meda: per group, the number the last reading got, in the order the readings were taken, which the cached list,
     * written with it, is too, and the number of the reading shown last: a reading older than the one shown is not
     * shown over it, see [updateGroupUi].
     */
    private val readingNumbers = ConcurrentHashMap<String, Int>()
    private val shownReadings = ConcurrentHashMap<String, Int>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()

    private val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null

    private val initialPageReady = CompletableDeferred<Unit>()

    /**
     * v2meda: the names the Aether editor gives the WARP protocols, which the rows of Aether profiles show. Declared
     * before init, whose work off the main thread reads it: a delegate declared after it may not be there yet.
     */
    private val aetherProtocolLabel by lazy {
        aetherProtocolLabels(
            dataSource.getStringArray(R.array.aether_protocol_entries),
            dataSource.getStringArray(R.array.aether_protocol_values),
        )
    }

    // ---------- Service events ----------
    init {
        viewModelScope.launch {
            val active = withContext(ioDispatcher) { runCatching { dataSource.getSubscriptions().any { proGroupNumber(it.guid) > 0 && proStore.tokenFor(it.guid) != null } }.getOrDefault(false) }
            _uiState.update { it.copy(isPro = it.isPro || active) }
        }
        collectServiceEvents()
        setupGroupTab()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                preparedAetherConnection?.takeIf { it.started && uiState.value.selectedGuid == it.guid }?.result?.complete(true)
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                preparedAetherConnection?.takeIf { it.started }?.result?.complete(false)
                // The daemon attaches a reason only when it is a localized resource string, e.g.
                // the Aether core stopping or missing on this ABI; the generic text is the fallback.
                if (!event.message.isNullOrBlank()) {
                    toastError(event.message)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            is MainServiceEvent.StateConnecting -> {
                if (uiState.value.isRunning) {
                    _uiState.update { it.copy(status = MainStatus.Connecting(event.message)) }
                }
            }

            MainServiceEvent.StateStopSuccess -> updateRunningState(false)
            is MainServiceEvent.MeasureDelayResult -> {
                // v2meda: the delay, then the IP lookup, of this request only; a late reply to an older one changes nothing.
                if (!uiState.value.isRunning || !testRequests.answerCurrent(event.requestId)) return
                _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                if (event.requestId == testRequests.bulk?.id) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                // v2meda: a test cancelled before its delay came in ends; a cancelled IP lookup leaves the delay shown.
                if (testRequests.completeCurrent(event.requestId)) resetTestStatus()
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                if (testRequests.completeBulk(event.requestId) != null) {
                    cancelPendingTestResults()
                    resetTestStatus()
                }
            }

            MainServiceEvent.ServersChanged -> setupGroupTab(forceRefresh = true)
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result.delayMillis
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty()) {
                if (!request.healthOnly) delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return

        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            groupDataCache[request.groupId]?.let { cached ->
                groupDataCache[request.groupId] = applyTestDelayResults(cached, drained)
            }
            drained
        }
        if (updates.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return
        mutableServerGroupState(request.groupId).update { current ->
            current.copy(
                servers = applyTestDelayResults(current.servers, updates),
                rows = applyTestDelayResultsToRows(current.rows, updates),
            )
        }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        is MainStatus.Connecting -> status.message
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            // The measured value remains the source of truth for testing and route selection.
            val displayedMillis = kotlin.math.round(result.delayMillis * 0.40).toLong()
            val delay = dataSource.getString(R.string.server_test_delay_value, displayedMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.GalaxyTapped -> {
                if (!uiState.value.andromedaOpen && galaxyTapSequence.tap(System.nanoTime())) {
                    _uiState.update { it.copy(andromedaOpen = true) }
                }
            }
            MainAction.DismissAndromeda -> {
                galaxyTapSequence.reset()
                _uiState.update { it.copy(andromedaOpen = false) }
            }
            is MainAction.StageExternalImport -> {
                if (action.text.isNotBlank() && action.text.length <= com.v2ray.ang.ui.UrlSchemeActivity.MAX_EXTERNAL_IMPORT_CHARS &&
                    !isLoading.value && !uiState.value.isCreatingAether && uiState.value.pendingExternalImport == null) {
                    _uiState.update { it.copy(pendingExternalImport = action.text) }
                }
            }
            MainAction.DismissExternalImport -> _uiState.update { it.copy(pendingExternalImport = null) }
            MainAction.ConfirmExternalImport -> {
                val text = uiState.value.pendingExternalImport
                _uiState.update { it.copy(pendingExternalImport = null) }
                if (text != null) importBatchConfig(text)
            }
            MainAction.Initialize -> initialize()
            MainAction.RefreshServiceState -> dataSource.queryServiceState()
            MainAction.RefreshControlPanel -> refreshControlPanel()
            MainAction.OpenProSubscription -> openProSubscription()
            MainAction.OpenUsageReport -> {
                _uiState.update { it.copy(usageOpen = true) }
                uiState.value.groups.firstOrNull { it.id == uiState.value.selectedGroupId && proGroupNumber(it.id) > 0 }
                    ?.let { loadUsageReport(it.id) }
            }
            MainAction.CloseUsageReport -> {
                usageJob?.cancel()
                _uiState.update { it.copy(usageOpen = false, usageGroup = null, usageLoading = false, usageReport = null) }
            }
            is MainAction.SelectUsageGroup -> loadUsageReport(action.id)
            MainAction.DismissProSubscription -> _uiState.update { it.copy(proDialog = false) }
            is MainAction.SubmitProCode -> importProSubscription(action.code)
            MainAction.CancelPreparation -> cancelPreparation()
            MainAction.ImportFreeConfiguration -> importFreeConfiguration()
            MainAction.BuildDiagnosticConfigs -> _uiState.update { it.copy(converter = ConverterState()) }
            MainAction.StartConverter -> startConverter()
            MainAction.CloseConverter -> {
                converterJob?.cancel()
                _uiState.update { it.copy(converter = null) }
            }
            is MainAction.ConvertWith -> convertWith(action)
            MainAction.DismissDiagnosticConfigs -> {
                diagnosticChoiceJob?.cancel()
                _uiState.update { it.copy(diagnosticDialog = false, diagnosticLoading = false) }
            }
            is MainAction.BuildDiagnosticConfigsWithOutRule -> buildDiagnosticConfigs(action.guid)
            is MainAction.DismissControlPanelUpdate -> dismissControlPanelUpdate(action.versionCode)
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing(showStars = true)
            MainAction.TestRealDelaysWithStars -> testAllRealPing(showStars = true)
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> importConfigViaSub(uiState.value.selectedGroupId)
            is MainAction.UpdateGroupSubscription -> importConfigViaSub(action.groupId)
            MainAction.ExportAll -> exportAllAsync()
            MainAction.RefreshAllAetherEndpoints -> refreshAllAetherEndpoints()
            is MainAction.ScanAllAetherEndpoints -> refreshAllAetherEndpoints(action.mode)
            MainAction.CreateAetherProfile -> createAetherProfile()
            is MainAction.RefreshAetherProfile -> refreshAetherProfile(action.guid, action.rebuild)
            MainAction.CancelPreparedAetherConnection -> cancelPreparedAetherConnection()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.MoveServer -> moveServer(action.groupId, action.fromGuid, action.toGuid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.MoveRefusalShown -> _uiState.update {
                if (it.moveRefusal == action.refusal) it.copy(moveRefusal = null) else it
            }
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.BuyProCode,
            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent,
            is MainAction.OpenSubscriptionLink,
            is MainAction.ConnectPreparedAether,
            is MainAction.OpenContact -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    private fun refreshControlPanel() {
        if (!controlPanelRepository.configured || controlPanelJob?.isActive == true) return
        controlPanelJob = viewModelScope.launch {
            val (content, savedVersion) = withContext(ioDispatcher) {
                runControlPanelRequest {
                    val content = controlPanelRepository.loadContent() ?: return@runControlPanelRequest null
                    content to MmkvManager.decodeSettingsInt("control_panel_dismissed_version", 0)
                }
            } ?: return@launch
            val dismissed = maxOf(savedVersion, dismissedControlPanelVersion)
            _uiState.update { state ->
                val update = content.update?.takeIf { it.versionCode > dismissed }
                state.copy(controlPanel = content.copy(update = update), headerUpdateUrl = content.update?.url)
            }
        }
    }

    private fun dismissControlPanelUpdate(versionCode: Int) {
        if (uiState.value.controlPanel.update?.versionCode != versionCode) return
        dismissedControlPanelVersion = versionCode
        _uiState.update { it.copy(controlPanel = it.controlPanel.copy(update = null)) }
        viewModelScope.launch(ioDispatcher) {
            runControlPanelRequest { MmkvManager.encodeSettings("control_panel_dismissed_version", versionCode) }
        }
    }

    private inline fun <T> runControlPanelRequest(block: () -> T): T? {
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Control panel request failed", error)
            null
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L
            )
        }

    /**
     * v2meda: the servers of a group as read, with how many of its moves were settled then, and the number of the reading,
     * see [updateGroupUi].
     */
    private class GroupReading(val servers: List<ServersCache>, val settledMoves: Int, val number: Int)

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): GroupReading {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            // v2meda: under the group's load lock, which a move is stored under too, see [storeMove], and numbered in turn.
            cacheMutex.withLock {
                groupDataCache[groupId] = servers
                val number = readingNumbers.merge(groupId, 1, Int::plus)!!
                GroupReading(servers, settledMoves.getOrDefault(groupId, 0), number)
            }
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>, filter: String): List<ServersCache> {
        val keyword = filter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    /**
     * Shows [reading] of the group [groupId]. v2meda: as [readingFate] decides: not over a newer reading shown, and not
     * when a move of the group was shown that was not settled when the group was read, which the reading would undo on
     * the screen; the group is then shown anew once its moves are settled, see [moveServer], or at once when they are
     * by now. Nor when the search changed while it was filtered: the group is then read and filtered anew.
     */
    private fun updateGroupUi(groupId: String, reading: GroupReading) {
        val filter = keywordFilter
        val filteredServers = applyKeywordFilter(reading.servers, filter)
        val state = ServerGroupUiState(
            servers = filteredServers,
            rows = buildServerRows(groupId, filteredServers)
        )
        val readAgain = synchronized(groupShowLock) {
            val fate = readingFate(
                number = reading.number,
                lastShown = shownReadings.getOrDefault(groupId, 0),
                settledWhenRead = reading.settledMoves,
                shownMoves = shownMoves.getOrDefault(groupId, 0),
                unsettledMoves = unstoredMoves.getOrDefault(groupId, 0),
                filteredAsSearched = filter == keywordFilter,
            )
            when (fate) {
                ReadingFate.SHOW -> {
                    shownReadings[groupId] = reading.number
                    mutableServerGroupState(groupId).value = state
                }
                ReadingFate.HOLD_UNTIL_SETTLED -> groupsReadEarly += groupId
                ReadingFate.OVERTAKEN, ReadingFate.READ_AGAIN -> Unit
            }
            fate == ReadingFate.READ_AGAIN
        }
        if (readAgain) viewModelScope.launch { showGroupAnew(groupId) }
    }

    /** v2meda: reads the group [groupId] anew and shows it, see [updateGroupUi]. */
    private suspend fun showGroupAnew(groupId: String) {
        val reading = withContext(ioDispatcher) { loadGroup(groupId) }
        withContext(defaultDispatcher) { updateGroupUi(groupId, reading) }
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return servers.map { server ->
            buildServerRowUiModel(
                server = server,
                subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty(),
                aetherProtocolLabel = aetherProtocolLabel,
            )
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(
                        id = it.guid,
                        remarks = it.subscription.remarks,
                        usageInfo = it.subscription.usageInfo?.let { info ->
                            SubscriptionInfoParser.withWebPageFallback(info, it.subscription.url)
                        },
                    )
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        isPro = it.isPro && groups.any { group -> proGroupNumber(group.id) > 0 },
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer(),
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private var diagnosticChoiceJob: Job? = null
    private var converterJob: Job? = null

    private fun startConverter() {
        if (converterJob?.isActive == true || uiState.value.converter == null) return
        cancelAllPing()
        converterJob = viewModelScope.launch {
            if (!prepareDirectOperation()) return@launch
            _uiState.update { it.copy(converter = ConverterState(running = true)) }
            for ((index, template) in aetherReferenceProfiles.withIndex()) {
                _uiState.update { s -> s.copy(converter = s.converter?.copy(current = index)) }
                val prepared = try {
                    withContext(ioDispatcher) {
                        quickAether.prepareConverterProfile(template)?.let { profile ->
                            val delayMillis = com.v2ray.ang.core.AetherDelayTester.measureVia(
                                converterContext, "converter-$index", com.v2ray.ang.core.AetherCore.of(profile),
                                raceEndpoints = true,
                                budgetMs = 25_000L,
                            ) { port, deadline, _ ->
                                com.v2ray.ang.core.AetherDelayTester.cancellableRequestDelay(
                                    port, com.v2ray.ang.handler.SettingsManager.getDelayTestUrl(),
                                    deadline, firstSuccess = true,
                                )
                            }
                            if (delayMillis > 0L) ConverterResult(profile, delayMillis) else null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "Converter probe failed for ${template.remarks}", error)
                    null
                }
                _uiState.update { s -> s.copy(converter = s.converter?.let {
                    it.copy(results = it.results + (index to prepared))
                }) }
            }
            val choices = withContext(ioDispatcher) {
                dataSource.getDiagnosticOutRules().filter {
                    dataSource.decodeServerConfig(it.guid)?.subscriptionId !in
                        setOf(AppConfig.ANDROMEDA_SUBSCRIPTION_ID, AETHER_DIAGNOSTIC_GROUP)
                }
            }
            _uiState.update { s -> s.copy(converter = s.converter?.copy(running = false, choices = choices)) }
        }
    }

    private fun convertWith(action: MainAction.ConvertWith) {
        if (converterJob?.isActive == true) return
        val state = uiState.value.converter ?: return
        val template = state.results[action.variant]?.profile ?: return
        converterJob = viewModelScope.launch {
            val group = withContext(ioDispatcher) { dataSource.converterSubscriptionId() }
            val saved = withContext(ioDispatcher) {
                val ids = if (action.subscription) {
                    if (action.source in setOf(AppConfig.ANDROMEDA_SUBSCRIPTION_ID, AETHER_DIAGNOSTIC_GROUP)) return@withContext false
                    dataSource.getServerGuidList(action.source)
                } else listOf(action.source)
                val allowed = state.choices.map { it.guid }.toSet()
                val converted = ids.filter { it in allowed }.mapNotNull { guid ->
                    val source = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
                    val outRule = dataSource.getDiagnosticOutRule(guid) ?: return@mapNotNull null
                    java.util.UUID.randomUUID().toString() to template.copy(
                        remarks = "${converterProtocolName(template)} · ${source.remarks}", subscriptionId = group,
                        aetherEmbeddedOutrole = outRule.copy(),
                    )
                }.toMap()
                dataSource.saveConverterProfiles(group, converted).also {
                    if (it) dataSource.setSelectedSubscriptionId(group)
                }
            }
            if (saved) {
                _uiState.update { it.copy(converter = null, selectedGroupId = group) }
                setupGroupTab(forceRefresh = true).join()
            } else toastError(R.string.toast_failure)
        }
    }

    private fun openDiagnosticConfigs() {
        if (uiState.value.isCreatingAether || isLoading.value) return
        diagnosticChoiceJob?.cancel()
        _uiState.update { it.copy(diagnosticDialog = true, diagnosticLoading = true, diagnosticOutRules = emptyList()) }
        diagnosticChoiceJob = viewModelScope.launch {
            try {
                val choices = withContext(ioDispatcher) { dataSource.getDiagnosticOutRules() }
                _uiState.update { it.copy(diagnosticOutRules = choices, diagnosticLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load diagnostic OutRule choices", error)
                _uiState.update { it.copy(diagnosticLoading = false) }
                toastError(R.string.toast_failure)
            }
        }
    }

    private fun buildDiagnosticConfigs(outRuleGuid: String) {
        if (uiState.value.isCreatingAether || isLoading.value) return
        if (!uiState.value.diagnosticDialog || uiState.value.diagnosticOutRules.none { it.guid == outRuleGuid }) return
        cancelAllPing()
        _uiState.update { it.copy(diagnosticDialog = false, aetherProgress = newAetherProgress()) }
        aetherRefreshJob = viewModelScope.launch {
            try {
                if (!withContext(ioDispatcher) {
                        val outRule = dataSource.getDiagnosticOutRule(outRuleGuid) ?: return@withContext false
                        val profiles = aetherDiagnosticProfiles(outRule).associateBy { java.util.UUID.randomUUID().toString() }
                        dataSource.saveDiagnosticProfiles(profiles, profiles.keys.toList()).also { saved ->
                            if (saved) dataSource.setSelectedSubscriptionId(AETHER_DIAGNOSTIC_GROUP)
                        }
                    }) {
                    toastError(R.string.toast_failure)
                    return@launch
                }
                _uiState.update { it.copy(selectedGroupId = AETHER_DIAGNOSTIC_GROUP, aetherProgress = null) }
                setupGroupTab(forceRefresh = true).join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to build diagnostic configurations", error)
                toastError(R.string.toast_failure)
            } finally {
                aetherRefreshJob = null
                _uiState.update { it.copy(aetherProgress = null) }
            }
        }
    }

    private fun createAetherProfile() {
        if (uiState.value.isCreatingAether) return
        _uiState.update { it.copy(aetherProgress = newAetherProgress()) }
        aetherRefreshJob = viewModelScope.launch {
            try {
                val error = quickAether.create(::updateAetherProgress)
                if (error != null) {
                    toastError(error)
                    return@launch
                }

                showNewAetherProfiles()
                toastSuccess(R.string.toast_success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to create Aether WARP profile", error)
                toastError(R.string.toast_failure)
            } finally {
                _uiState.update { it.copy(aetherProgress = null) }
            }
        }
    }

    internal suspend fun needsWarpKeys(guid: String): Boolean = quickAether.needsKeys(guid)

    private fun refreshAetherProfile(guid: String, rebuild: Boolean) {
        if (uiState.value.isCreatingAether || isLoading.value) return
        _uiState.update { it.copy(aetherProgress = newAetherProgress(), refreshingAetherGuid = guid, rebuildingAether = rebuild) }
        aetherRefreshJob = viewModelScope.launch {
            try {
                val error = quickAether.refresh(guid, rebuild, ::updateAetherProgress)
                if (error != null) {
                    toastError(if (error == R.string.aether_log_key_renew_failed) R.string.toast_failure else error)
                } else {
                    withContext(ioDispatcher) { dataSource.clearAllTestDelayResults(listOf(guid)) }
                    setupGroupTab(forceRefresh = true)
                    if (rebuild) connectPreparedAether(guid) else toastSuccess(R.string.toast_success)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to prepare/connect Aether profile guid=$guid rebuild=$rebuild", error)
                toastError(R.string.toast_failure)
            } finally {
                aetherRefreshJob = null
                preparedAetherConnection = null
                _uiState.update { it.copy(aetherProgress = null, refreshingAetherGuid = null, rebuildingAether = false, aetherConnectionRequest = null) }
            }
        }
    }

    private fun refreshAllAetherEndpoints(mode: com.v2ray.ang.enums.AetherScanMode? = null) {
        if (uiState.value.isCreatingAether || isLoading.value) return
        val groupId = uiState.value.selectedGroupId.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
        cancelAllPing()
        _uiState.update { it.copy(aetherProgress = newAetherProgress(), refreshingAllEndpoints = true, endpointRefreshResults = emptyMap()) }
        aetherRefreshJob = viewModelScope.launch {
            try {
                if (!prepareDirectOperation()) return@launch
                val guids = withContext(ioDispatcher) { dataSource.getServerGuidList(groupId).toList() }
                val result = if (mode == null) quickAether.refreshAll(guids, ::updateAetherProgress)
                else quickAether.refreshAll(guids, mode, ::updateAetherProgress)
                _uiState.update {
                    it.copy(endpointRefreshResults = result.guids.associateWith { true } +
                        result.failedGuids.associateWith { false })
                }
                if (result.guids.isNotEmpty()) {
                    withContext(ioDispatcher) { dataSource.clearAllTestDelayResults(result.guids) }
                    _uiState.update { state -> state.copy(starredTestGuids = state.starredTestGuids.mapValues { (_, ids) -> ids - result.guids.toSet() }) }
                    setupGroupTab(forceRefresh = true).join()
                    if (result.error == null) toastSuccess(R.string.toast_success)
                }
                result.error?.let { toastError(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to refresh Aether endpoint batch", error)
                toastError(R.string.toast_failure)
            } finally {
                aetherRefreshJob = null
                _uiState.update { it.copy(aetherProgress = null, refreshingAllEndpoints = false) }
            }
        }
    }

    private suspend fun connectPreparedAether(guid: String) {
        val valid = withContext(ioDispatcher) {
            val profile = dataSource.decodeServerConfig(guid)
            profile != null && QuickAetherProfile.canRefresh(profile)
        }
        if (!valid) {
            toastError(R.string.toast_failure)
            return
        }
        // Use the same selection owner as a card tap, after cancellable validation. A cancelled IO
        // read must never write its old selection over a newer tap.
        currentCoroutineContext().ensureActive()
        updateSelectedGuid(guid)
        if (dataSource.getSelectServer() != guid) {
            toastError(R.string.toast_failure)
            return
        }
        val pending = PreparedAetherConnection(guid)
        preparedAetherConnection = pending
        _uiState.update {
            it.copy(selectedGuid = guid, aetherConnectionRequest = guid,
                aetherProgress = it.aetherProgress?.copy(message = R.string.aether_import_progress_working))
        }
        try {
            // Permission may need the user's response. The connection timeout starts only after service dispatch.
            if (pending.start.await()) {
                val connected = withTimeoutOrNull(aetherConnectTimeoutMillis) { pending.result.await() }
                if (connected == null) {
                    LogUtil.w(AppConfig.TAG, "Aether auto-connect timed out guid=$guid")
                    if (uiState.value.selectedGuid == guid) {
                        dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
                    }
                    toastError(R.string.toast_services_failure)
                }
            }
        } finally {
            pending.start.cancel()
            pending.result.cancel()
        }
    }

    /** Consumed by the foreground Activity, once, including across recomposition or rotation. */
    internal fun claimPreparedAetherConnection(guid: String): Boolean {
        val pending = preparedAetherConnection ?: return false
        if (pending.guid != guid || pending.claimed || uiState.value.selectedGuid != guid) return false
        pending.claimed = true
        _uiState.update { it.copy(aetherConnectionRequest = null) }
        return true
    }

    internal fun beginPreparedAetherConnection(): Boolean {
        val pending = preparedAetherConnection ?: return false
        if (!pending.claimed || pending.started) return false
        if (uiState.value.selectedGuid != pending.guid || dataSource.getSelectServer() != pending.guid) {
            cancelPreparedAetherConnection()
            return false
        }
        pending.started = true
        pending.start.complete(true)
        return true
    }

    internal fun isPreparedAetherConnectionCurrent(): Boolean {
        val pending = preparedAetherConnection ?: return false
        return pending.started && !pending.result.isCompleted &&
            uiState.value.selectedGuid == pending.guid && dataSource.getSelectServer() == pending.guid
    }

    private fun cancelPreparedAetherConnection() {
        preparedAetherConnection?.let {
            it.start.complete(false)
            it.result.complete(false)
        }
    }

    private fun newAetherProgress(@StringRes message: Int = R.string.aether_progress_preparing) =
        AetherProgress(message, TimeSource.Monotonic.markNow())

    private fun updateAetherProgress(message: Int) {
        _uiState.update { state ->
            state.copy(aetherProgress = state.aetherProgress?.copy(message = message))
        }
    }

    private fun showNewAetherProfiles() {
        // Keep locally prepared profiles out of groups managed by a subscription.
        val currentGroup = uiState.value.selectedGroupId
        val defaultGroup = AppConfig.DEFAULT_SUBSCRIPTION_ID
        if (currentGroup.isNotEmpty() && currentGroup != defaultGroup) {
            subscriptionIdChanged(defaultGroup)
        }
        setupGroupTab(forceRefresh = true)
    }

    private fun cancelPreparation() {
        val pending = preparedAetherConnection
        if (pending?.started == true && uiState.value.selectedGuid == pending.guid) {
            dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
        }
        importJob?.cancel()
        aetherRefreshJob?.cancel()
        cancelPreparedAetherConnection()
        cancelAllPing()
    }

    private fun launchImport(block: suspend () -> Unit) {
        if (isLoading.value || uiState.value.isCreatingAether) return
        importJob = viewModelScope.launch {
            _isLoading.value = true
            try { ManagedSubscriptionUpdate.lock.withLock { block() } }
            finally {
                _uiState.update { it.copy(aetherProgress = null, proBusy = false) }
                importJob = null
                _isLoading.value = false
            }
        }
    }

    private fun openProSubscription() {
        viewModelScope.launch {
            try {
                val blocked = withContext(ioDispatcher) { proStore.blockedUntil() }
                _uiState.update { it.copy(proDialog = true, proError = null, proBlockedUntil = blocked) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { LogUtil.e(AppConfig.TAG, "Could not open subscription code dialog", error); toastError(R.string.toast_failure) }
        }
    }

    private fun loadUsageReport(group: String) {
        if (!uiState.value.usageOpen || proGroupNumber(group) == 0 || uiState.value.groups.none { it.id == group }) return
        usageJob?.cancel()
        _uiState.update { it.copy(usageGroup = group, usageLoading = true, usageReport = null) }
        usageJob = viewModelScope.launch {
            val report = try {
                withContext(ioDispatcher) { proStore.tokenFor(group)?.let { controlPanelRepository.loadUsage(it) } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            _uiState.update { it.copy(usageLoading = false, usageReport = report) }
        }
    }

    private fun importProSubscription(code: String? = null, groupId: String = AppConfig.PRO_SUBSCRIPTION_ID) {
        if (isLoading.value || uiState.value.isCreatingAether) return
        launchImport {
            _uiState.update { it.copy(proBusy = true, proError = null) }
            try {
                val clean = code?.trim()
                if (clean != null && !clean.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
                    _uiState.update { it.copy(proError = R.string.pro_invalid_code) }
                    return@launchImport
                }
                if (clean != null && !withContext(ioDispatcher) { proStore.consume() }) {
                    val until = withContext(ioDispatcher) { proStore.blockedUntil() }
                    _uiState.update { it.copy(proBlockedUntil = until, proError = R.string.pro_rate_limited) }
                    return@launchImport
                }
                _uiState.update { it.copy(proDialog = false, aetherProgress = newAetherProgress()) }
                // Download on the current default network (including the user's existing VPN).
                val payload = withContext(ioDispatcher) {
                    loadProConfigs(clean, if (clean == null) proStore.tokenFor(groupId) else null)
                }
                withContext(ioDispatcher) { proStore.blockFor(payload.retryAfter) }
                val groupTarget = withContext(ioDispatcher) {
                    if (clean == null) groupId else proStore.groupFor(payload.token)
                }
                val previous = withContext(ioDispatcher) {
                    (dataSource.getSubscriptionItem(groupTarget) != null) to dataSource.getServerGuidList(groupTarget).toSet()
                }
                var committed = false
                try {
                    val group = withContext(ioDispatcher) { dataSource.ensureProSubscription(groupTarget) }
                        ?: throw IllegalStateException("Could not store subscription")
                    val imported = importConfigText(payload.content, group, localOnly = true)
                    val expected = withContext(defaultDispatcher) {
                        payload.content.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().count()
                    }
                    if (imported > 0 && imported == expected) {
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable + ioDispatcher) {
                            proStore.activateGroup(groupTarget, payload.token)
                            if (!dataSource.commitProImport(groupTarget, previous.second)) {
                                throw IllegalStateException("Could not replace managed subscription")
                            }
                            committed = true
                            runCatching { SubscriptionUpdater.updateLastUpdatedAndReschedule(getApplication<Application>(), groupTarget) }
                            _uiState.update { it.copy(isPro = true) }
                        }
                    } else _uiState.update { it.copy(proDialog = true, proError = R.string.pro_unavailable) }
                } finally {
                    if (!committed) withContext(NonCancellable + ioDispatcher) {
                        if (!dataSource.rollbackProImport(groupTarget, previous.second, removeGroup = !previous.first)) {
                            LogUtil.w(AppConfig.TAG, "Could not roll back managed subscription import")
                        }
                        setupGroupTab(forceRefresh = true)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error !is ProRequestException) LogUtil.e(AppConfig.TAG, "Could not import managed subscription",
                    java.io.IOException(error.javaClass.simpleName))
                if (error is ProRequestException) withContext(ioDispatcher) { proStore.blockFor(error.retryAfter) }
                val until = withContext(ioDispatcher) { proStore.blockedUntil() }
                val message = when {
                    until > System.currentTimeMillis() -> R.string.pro_rate_limited
                    error is ProRequestException && error.reason == "invalid_code" -> R.string.pro_invalid_code
                    error is ProRequestException -> R.string.pro_unavailable
                    error is java.io.IOException -> R.string.pro_network_error
                    else -> R.string.pro_unavailable
                }
                _uiState.update { it.copy(proDialog = true, proBlockedUntil = until, proError = message) }
            }
        }
    }

    private fun importFreeConfiguration() {
        if (isLoading.value || uiState.value.isCreatingAether) return
        launchImport {
            _uiState.update { it.copy(aetherProgress = newAetherProgress()) }
            try {
                val configText = withContext(ioDispatcher) { loadFreeConfig() }
                if (configText == null) toast(R.string.control_panel_free_unavailable)
                else {
                    val group = withContext(ioDispatcher) { dataSource.ensureFreeSubscription() }
                    if (group == null) toastError(R.string.toast_failure)
                    else {
                        val previous = withContext(ioDispatcher) { dataSource.getServerGuidList(group).toSet() }
                        var committed = false
                        try {
                            val count = importConfigText(configText, group, localOnly = true)
                            currentCoroutineContext().ensureActive()
                            if (count > 0) withContext(NonCancellable + ioDispatcher) {
                                // Publish the commit before cancellation can resume the caller; never roll back
                                // a successful replacement after the old payloads have already been retired.
                                committed = dataSource.finalizeFreeImport(previous, commit = true)
                                if (committed) runCatching { SubscriptionUpdater.updateLastUpdatedAndReschedule(getApplication<Application>(), group) }
                            }
                            if (!committed && count > 0) toastError(R.string.toast_failure)
                        } finally {
                            if (!committed) withContext(NonCancellable + ioDispatcher) {
                                if (!dataSource.finalizeFreeImport(previous, commit = false))
                                    LogUtil.e(AppConfig.TAG, "Could not roll back free config download")
                            }
                            setupGroupTab(forceRefresh = true)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Could not load free configurations", error)
                toastError(R.string.toast_failure)
            }
        }
    }

    internal suspend fun refreshHeaderSubscriptionStatus(group: String) {
        try {
            val status = withContext(ioDispatcher) {
                val token = proStore.tokenFor(group) ?: return@withContext null
                controlPanelRepository.loadSubscriptionStatus(token)
            } ?: return
            _uiState.update { it.copy(liveSubscriptionStatus = it.liveSubscriptionStatus + (group to status)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the most recent status if the panel is temporarily unreachable.
        }
    }

    private fun importBatchConfig(configText: String) = launchImport { importConfigText(configText) }

    private suspend fun importConfigText(configText: String, targetGroupId: String? = null, localOnly: Boolean = false): Int {
        var imported = 0
        try {
            val (setupLinks, ordinaryText) = withContext(defaultDispatcher) {
                AetherSetupLink.splitImport(configText)
            }
            if (ordinaryText.isNotBlank()) {
                val (count, countSub) = withContext(ioDispatcher) {
                    val group = targetGroupId ?: uiState.value.selectedGroupId
                    if (localOnly) dataSource.importFreeProfiles(ordinaryText, group) to 0
                    else dataSource.importBatchConfig(ordinaryText, group, true)
                }
                imported += count
                if (count > 0 && targetGroupId != null) {
                    setupGroupTab(forceRefresh = true).join()
                    subscriptionIdChanged(targetGroupId)
                } else if (count + countSub > 0) setupGroupTab(forceRefresh = true)
                else if (setupLinks.isEmpty()) toastError(R.string.toast_failure)
                if (count > 0) toast(dataSource.getString(R.string.title_import_config_count, count))
            }
            if (setupLinks.isNotEmpty()) imported += importAetherSetupLinks(setupLinks, targetGroupId)
            else if (ordinaryText.isBlank()) toastError(R.string.toast_failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import batch config", error)
            toastError(R.string.toast_failure)
        }
        return imported
    }

    private suspend fun importAetherSetupLinks(links: List<String>, targetGroupId: String? = null): Int {
        if (aetherRefreshJob?.isActive == true) {
            toast(R.string.aether_import_progress_working)
            return 0
        }
        _uiState.update {
            it.copy(aetherProgress = newAetherProgress(R.string.aether_import_progress_preparing))
        }
        try {
            val result = quickAether.importLinks(links, targetGroupId, ::updateAetherProgress)
            if (result.count > 0) {
                if (targetGroupId == null) showNewAetherProfiles()
                else {
                    setupGroupTab(forceRefresh = true).join()
                    subscriptionIdChanged(targetGroupId)
                }
                toast(dataSource.getString(R.string.title_import_config_count, result.count))
            }
            when (result.error) {
                null -> Unit
                R.string.aether_scan_disconnect_vpn -> toastError(R.string.aether_scan_disconnect_vpn)
                else -> toastError(R.string.aether_import_failed)
            }
            return result.count
        } finally {
            _uiState.update { it.copy(aetherProgress = null) }
        }
    }

    private fun importConfigViaSub(subId: String) {
        if (proGroupNumber(subId) > 0) { importProSubscription(groupId = subId); return }
        if (subId == AppConfig.ANDROMEDA_SUBSCRIPTION_ID) { importFreeConfiguration(); return }
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(
                                getQuantityString(
                                    R.plurals.title_update_config_count,
                                    result.configCount,
                                    result.configCount,
                                )
                            )

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            // v2meda: what the storage removed, a profile it refused to remove staying.
                            currentServers().count { dataSource.removeServer(it.guid) }
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    // v2meda: what the storage removed, a profile it refused to remove staying.
                    val removed = duplicates.count { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, removed))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            // v2meda: with how many of each group's moves were settled when it was cached, and the number of the reading it
            // is, see [updateGroupUi].
            val snapshot = cacheMutex.withLock {
                groupDataCache.mapValues { (groupId, servers) ->
                    GroupReading(servers, settledMoves.getOrDefault(groupId, 0), readingNumbers.getOrDefault(groupId, 0))
                }
            }
            ensureActive()
            snapshot.forEach { (groupId, reading) ->
                ensureActive()
                updateGroupUi(groupId, reading)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        if (uiState.value.rebuildingAether && uiState.value.refreshingAetherGuid != guid) aetherRefreshJob?.cancel()
        if (preparedAetherConnection?.guid != guid) cancelPreparedAetherConnection()
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            // v2meda: a removal the storage refused is told; the list, shown anew, keeps the profile.
            if (!dataSource.removeServer(guid)) toastError(R.string.toast_failure)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    /**
     * Shows the profile [fromGuid] names where the one [toGuid] names stands, and stores it there; the job storing it,
     * null when nothing moved. v2meda: by the two guids, see [storeMove], even when the screen closes right after. A
     * move the storage refused is told, see [MainUiState.moveRefusal], once, when the group's last move asked for is
     * stored, and the groups are shown anew, as stored, so that the reading comes after every move queued behind the
     * refused one. Not in the list of every group, [groupId] empty, which is no stored list.
     */
    fun moveServer(groupId: String, fromGuid: String, toGuid: String): Job? {
        if (groupId.isEmpty()) return null
        synchronized(groupShowLock) {
            val groupState = mutableServerGroupState(groupId).value
            val servers = groupState.servers.toMutableList()
            val fromPosition = servers.indexOfFirst { it.guid == fromGuid }
            val toPosition = servers.indexOfFirst { it.guid == toGuid }
            if (!servers.moveItem(fromPosition, toPosition)) return null
            val rows = groupState.rows.toMutableList()
            rows.moveItem(fromPosition, toPosition)
            mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
            shownMoves.merge(groupId, 1, Int::plus)
            unstoredMoves.merge(groupId, 1, Int::plus)
        }
        // A drag emits several moves; each is stored once the one before it is, in the order they were made.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        // v2meda: begun at once, on the main thread the move comes on, and the store kept from cancellation, so that a
        // screen closing right after neither stops it nor keeps it from starting.
        return viewModelScope.launch {
            val moved = withContext(NonCancellable + ioDispatcher) {
                previousPersistenceJob?.join()
                storeMove(groupId, fromGuid, toGuid)
            }
            if (!moved) refusedMoveGroups += groupId
            // v2meda: once the group's last move asked for is settled: a refusal among them told, and the groups read anew;
            // or else a reading held back for them taken anew, see [updateGroupUi].
            val (refused, readEarly) = synchronized(groupShowLock) {
                if (unstoredMoves.merge(groupId, -1, Int::plus) != 0) {
                    false to false
                } else {
                    refusedMoveGroups.remove(groupId) to groupsReadEarly.remove(groupId)
                }
            }
            if (refused) {
                val refusal = moveRefusals.incrementAndGet()
                _uiState.update { it.copy(moveRefusal = refusal) }
                setupGroupTab(forceRefresh = true)
            } else if (readEarly) {
                showGroupAnew(groupId)
            }
        }.also { serverOrderPersistenceJobs[groupId] = it }
    }

    /**
     * v2meda: stores the move of the profile [fromGuid] names to where the one [toGuid] names stands, in the list of
     * [groupId] as stored then, see [MainDataSource.moveServer], so that a profile an update stored, or a removal took
     * away, meanwhile is not undone, and makes it in the group's cached list too, under the group's load lock, see
     * [loadGroup]: a reading of the group comes before the move is stored or after it is cached, so the cached list stays
     * the stored one; and counts it settled, see [settledMoves]. Whether the storage took it.
     */
    private suspend fun storeMove(groupId: String, fromGuid: String, toGuid: String): Boolean =
        groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }.withLock {
            val moved = dataSource.moveServer(groupId, fromGuid, toGuid)
            cacheMutex.withLock {
                if (moved) {
                    groupDataCache[groupId]?.toMutableList()?.let { cached ->
                        if (cached.moveItem(cached.indexOfFirst { it.guid == fromGuid }, cached.indexOfFirst { it.guid == toGuid })) {
                            groupDataCache[groupId] = cached
                        }
                    }
                }
                // v2meda: settled, stored or refused: a reading from now on has it as stored, see [updateGroupUi].
                settledMoves.merge(groupId, 1, Int::plus)
            }
            moved
        }

    // ---------- Testing ----------
    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        resetTestStatus()
        dataSource.cancelAllPing()
    }

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                testProgress = if (testRequests.isTesting) it.testProgress else null,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false, showStars: Boolean = false) {
        _uiState.update { it.copy(endpointRefreshResults = emptyMap()) }
        cancelAllPing()
        val groupId = uiState.value.selectedGroupId
        val servers = currentServers()
        if (servers.isEmpty()) {
            return
        }
        val serverGuids = servers.map { it.guid }
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.testDelayMillis == 0L) server
                    else server.copy(testDelayMillis = 0L)
                },
                rows = current.rows.map { row ->
                    if (row.testDelayMillis == 0L) row
                    else row.copy(testDelayMillis = 0L)
                }
            )
        }
        val request = testRequests.beginBulk(groupId, healthOnly = showStars)
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = serverGuids,
            onlyTcp = onlyTcp,
            healthOnly = showStars,
        )
        _uiState.update {
            it.copy(
                isTesting = true,
                testProgress = newAetherProgress(),
                status = MainStatus.Testing,
                starredTestGuids = if (!onlyTcp) it.starredTestGuids + (groupId to serverGuids.toSet())
                    else it.starredTestGuids - groupId,
            )
        }
        bulkTestJob = viewModelScope.launch {
            if (!prepareDirectOperation()) {
                onTestsFinished(request.id)
                return@launch
            }
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(testDelayMillis = 0L)
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        val requestId = testRequests.beginCurrent()
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing(requestId)
    }

    private suspend fun prepareDirectOperation(): Boolean {
        val stopped = com.v2ray.ang.core.AetherScanner.prepareNetwork(
            converterContext, ownServiceRunning = uiState.value.isRunning,
        ) && kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            while (uiState.value.isRunning) kotlinx.coroutines.delay(100)
            true
        } == true
        if (!stopped) toastError(R.string.aether_scan_disconnect_vpn)
        return stopped
    }

    private fun onTestsFinished(requestId: String) {
        if (testRequests.completeBulk(requestId) == null) return
        resetTestStatus()
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.clear() }
            reloadAllGroups(_uiState.value.groups.map { it.id })
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        if (!running || clearTestingText) testRequests.invalidateCurrent()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isTesting = testRequests.isTesting,
                status = runningStatus(state.status, state.isRunning, running, clearTestingText)
            )
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    /** v2meda: what becomes of a reading of a group, see [readingFate]. */
    internal enum class ReadingFate { SHOW, OVERTAKEN, HOLD_UNTIL_SETTLED, READ_AGAIN }

    companion object {
        private const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L

        /**
         * v2meda: what becomes of a reading of a group numbered [number] when the one shown last is [lastShown], taken
         * when [settledWhenRead] of the group's moves were settled, now that [shownMoves] were shown and [unsettledMoves]
         * of them are not settled yet, and filtered as the search stands now or not, [filteredAsSearched]: one older than
         * the one shown is not shown over it; one that a move shown since it was taken overtook waits until the group's
         * moves are settled, or, when they are by now, is taken anew, as one filtered for a search changed since is;
         * else it is shown.
         */
        internal fun readingFate(
            number: Int,
            lastShown: Int,
            settledWhenRead: Int,
            shownMoves: Int,
            unsettledMoves: Int,
            filteredAsSearched: Boolean,
        ): ReadingFate =
            when {
                number < lastShown -> ReadingFate.OVERTAKEN
                settledWhenRead < shownMoves && unsettledMoves > 0 -> ReadingFate.HOLD_UNTIL_SETTLED
                settledWhenRead < shownMoves || !filteredAsSearched -> ReadingFate.READ_AGAIN
                else -> ReadingFate.SHOW
            }

        /**
         * The status after a running or stopped signal. A test text survives a repeated signal
         * that changes nothing, but a connecting status does not: the daemon repeats the
         * connecting signal right after a running one whenever the tunnel is still on its way.
         */
        internal fun runningStatus(
            current: MainStatus,
            wasRunning: Boolean,
            running: Boolean,
            clearTestingText: Boolean,
        ): MainStatus =
            if (!clearTestingText && wasRunning == running && current !is MainStatus.Connecting) current
            else if (running) MainStatus.Connected else MainStatus.Disconnected
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
