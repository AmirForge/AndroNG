package com.v2ray.ang.ui.main

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    uiState.converter?.let { converter ->
        ConfigConverterScreen(converter, uiState, onAction)
        return
    }
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val isConnecting = uiState.status is MainStatus.Connecting
    var connectTapped by remember { mutableStateOf(false) }
    LaunchedEffect(connectTapped, isRunning, isConnecting) {
        if (connectTapped) {
            if (!isRunning && !isConnecting) delay(2500)
            connectTapped = false
        }
    }
    val showConnectionAnimation = connectTapped || isRunning || isConnecting
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, uiState.selectedGroupId) {
        val group = uiState.selectedGroupId
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                mainViewModel.refreshHeaderSubscriptionStatus(group)
                delay(45_000L)
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(60_000L)
                onAction(MainAction.RefreshControlPanel)
            }
        }
    }
    LaunchedEffect(uiState.aetherConnectionRequest, lifecycleOwner) {
        uiState.aetherConnectionRequest?.let { guid ->
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                onAction(MainAction.ConnectPreparedAether(guid))
            }
        }
    }
    val hapticFeedback = LocalHapticFeedback.current
    val rebuildingGuid = uiState.refreshingAetherGuid.takeIf { uiState.rebuildingAether }
    // Keep feedback owned by the visible screen even when the refreshed card scrolls offscreen.
    LaunchedEffect(rebuildingGuid, lifecycleOwner, hapticFeedback) {
        if (rebuildingGuid != null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pulseAetherRefreshHaptics {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }
    var refreshBurstOrigin by remember { mutableStateOf(Rect.Zero) }
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    uiState.controlPanel.update?.let { ControlPanelUpdateDialog(it, onAction) }

    // v2meda: a move of a profile the storage refused is told once, each refusal by its number, so that one set again
    // right after the last was told is told too; the groups are shown anew, as stored.
    val context = LocalContext.current
    val failureText = stringResource(R.string.toast_failure)
    val moveRefusal = uiState.moveRefusal
    LaunchedEffect(moveRefusal) {
        if (moveRefusal != null) {
            context.toastError(failureText)
            onAction(MainAction.MoveRefusalShown(moveRefusal))
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showScanModes by rememberSaveable { mutableStateOf(false) }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)
    // Height of the floating connection bar, so the server lists can scroll clear of it.
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    var progressHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    if (uiState.andromedaOpen) AndromedaEasterEgg(onDismiss = { onAction(MainAction.DismissAndromeda) })
    if (showScanModes) {
        val labels = androidx.compose.ui.res.stringArrayResource(R.array.aether_scan_entries)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showScanModes = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.aether_lab_scan_mode), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.v2ray.ang.enums.AetherScanMode.entries.forEachIndexed { index, mode ->
                        ConfigChoiceCard(
                            onClick = {
                                showScanModes = false
                                onAction(MainAction.ScanAllAetherEndpoints(mode))
                            },
                            title = labels[index],
                            icon = R.drawable.ic_refresh_24dp,
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showScanModes = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (uiState.pendingExternalImport != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onAction(MainAction.DismissExternalImport) },
            title = { Text(stringResource(R.string.external_import_title)) },
            text = { Text(stringResource(R.string.external_import_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onAction(MainAction.ConfirmExternalImport) }) {
                    Text(stringResource(R.string.external_import_accept))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { onAction(MainAction.DismissExternalImport) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (uiState.proDialog) ProCodeDialog(uiState, onAction)
    if (uiState.usageOpen) UsageReportDialog(uiState, onAction)
    if (uiState.diagnosticDialog) DiagnosticOutRuleDialog(uiState, onAction)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                tutorialsUrl = uiState.controlPanel.tutorialsUrl,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                },
                onAction = { action ->
                    if (action == MainAction.OpenProSubscription || action == MainAction.OpenUsageReport ||
                        action == MainAction.BuildDiagnosticConfigs || action is MainAction.OpenSubscriptionLink
                    ) scope.launch { drawerState.close() }
                    onAction(action)
                },
            )
        }
    ) {
        val colors = MaterialTheme.colorScheme
        Scaffold(
            modifier = Modifier.drawWithCache {
                val radius = maxOf(size.width, size.height) * 0.74f
                val headerLight = Brush.radialGradient(
                    listOf(colors.primaryContainer, Color.Transparent),
                    center = Offset(size.width * 0.08f, 0f),
                    radius = radius.coerceAtLeast(1f),
                )
                val dockLight = Brush.radialGradient(
                    listOf(colors.primaryContainer, Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height),
                    radius = (radius * 0.78f).coerceAtLeast(1f),
                )
                val roseLight = Brush.radialGradient(
                    listOf(colors.primary.copy(alpha = .19f), Color.Transparent),
                    center = Offset(size.width * .38f, size.height * .30f),
                    radius = (size.width * .46f).coerceAtLeast(1f),
                )
                onDrawBehind {
                    drawRect(colors.background)
                    drawRect(headerLight)
                    drawRect(dockLight)
                    drawRect(roseLight)
                }
            },
            containerColor = Color.Transparent,
            contentColor = colors.onBackground,
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                MainTopBar(
                    isLoading = isLoading || uiState.isCreatingAether,
                    isPro = uiState.isPro,
                    isTesting = uiState.isTesting,
                    refreshingEndpoints = uiState.refreshingAllEndpoints,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { query: String ->
                        searchQuery = query
                        onAction(MainAction.Search(query))
                    },
                    onSearchClose = {
                        searchQuery = ""
                        onAction(MainAction.Search(""))
                        showSearch = false
                    },
                    onSearchToggle = { show: Boolean -> showSearch = show },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onAction = { action ->
                        if (action == MainAction.RefreshAllAetherEndpoints) showScanModes = true else onAction(action)
                    },
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                        }
                    }
                )
            },
            floatingActionButton = {},
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val bottomBarHeight = with(LocalDensity.current) { bottomBarHeightPx.toDp() }
            val progress = uiState.aetherProgress
            val progressClearance = if (progress == null) 0.dp else with(LocalDensity.current) {
                progressHeightPx.toDp() + 12.dp
            }

            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // No bottom padding: the lists run behind the floating connection bar.
                        .padding(
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            top = innerPadding.calculateTopPadding(),
                            end = innerPadding.calculateEndPadding(layoutDirection)
                        )
                ) {
                    if (!showSearch) {
                        val usage = uiState.liveSubscriptionStatus[uiState.selectedGroupId]
                            ?: groups.firstOrNull { it.id == uiState.selectedGroupId }?.usageInfo
                        var expired by remember(usage?.expire) {
                            mutableStateOf(usage?.let { buildSubscriptionInfoUiModel(it, System.currentTimeMillis()).expired } == true)
                        }
                        LaunchedEffect(usage?.expire) {
                            val expiry = usage?.expire?.takeIf { it > 0 } ?: return@LaunchedEffect
                            while (!expired) {
                                val remaining = expiry - System.currentTimeMillis() / 1000
                                if (remaining <= 0) expired = true
                                else delay(remaining.coerceAtMost(60) * 1000)
                            }
                        }
                        HomeHeader(
                            expired = expired || (usage != null && usage.total > 0L && usage.used >= usage.total),
                            updateUrl = uiState.headerUpdateUrl,
                            animateGalaxy = showConnectionAnimation,
                            importing = isLoading || uiState.isCreatingAether,
                            energised = uiState.rebuildingAether || (uiState.isRunning && uiState.status == MainStatus.Testing && uiState.testProgress == null),
                            selectedGroupId = uiState.selectedGroupId,
                            onAction = onAction,
                        )
                        uiState.controlPanel.banner?.let { banner ->
                            ControlPanelBannerCard(
                                banner = banner,
                                onAction = onAction,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
                            )
                        }
                    }
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(
                                        targetPage = targetIndex,
                                        animateAdjacentPage = true
                                    )
                                }
                            }
                        )
                    }

                    if (groups.isNotEmpty()) HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = true,
                        beyondViewportPageCount = 1,
                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                    ) { page ->
                        val group = groups.getOrNull(page) ?: return@HorizontalPager

                        GroupPagerPage(
                            groupId = group.id,
                            starredTestGuids = uiState.starredTestGuids[group.id].orEmpty(),
                            endpointRefreshResults = uiState.endpointRefreshResults,
                            mainViewModel = mainViewModel,
                            selectedGuid = selectedGuid,
                            isPreparingAether = uiState.isCreatingAether || isLoading,
                            refreshingAetherGuid = uiState.refreshingAetherGuid,
                            rebuildingAether = uiState.rebuildingAether,
                            locateTarget = uiState.locateTarget,
                            doubleColumnDisplay = doubleColumnDisplay,
                            searchQuery = searchQuery,
                            lazyListStates = lazyListStates,
                            lazyGridStates = lazyGridStates,
                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                            onRefreshAether = { guid, rebuild, bounds ->
                                if (!uiState.isCreatingAether && !isLoading) {
                                    if (rebuild) refreshBurstOrigin = bounds
                                    onAction(MainAction.RefreshAetherProfile(guid, rebuild))
                                }
                            },
                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },
                            onRemoveServer = removeServer,
                            usageInfo = group.usageInfo,
                            groupTitle = group.remarks,
                            onOpenLink = { url -> onAction(MainAction.OpenSubscriptionLink(url)) },
                            contentPadding = PaddingValues(
                                start = 14.dp,
                                top = 4.dp,
                                end = 14.dp,
                                bottom = bottomBarHeight + progressClearance + 8.dp
                            )
                        )
                    }
                }
                if (progress != null) {
                    AetherProgressPanel(
                        progress = progress,
                        onStop = { onAction(MainAction.CancelPreparation) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = bottomBarHeight + 12.dp)
                            .onSizeChanged { progressHeightPx = it.height },
                    )
                }
                MainBottomBar(
                    displayText = displayText,
                    isRunning = isRunning,
                    animateStars = showConnectionAnimation,
                    onAction = { action ->
                        if (action == MainAction.ToggleService && !isRunning && selectedGuid != null) {
                            connectTapped = true
                        }
                        onAction(action)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { bottomBarHeightPx = it.height }
                )
                if (uiState.rebuildingAether && progress != null && refreshBurstOrigin.width > 0f) {
                    ConfigRefreshFlare(
                        originInRoot = refreshBurstOrigin,
                        startedAt = progress.startedAt,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

internal suspend fun pulseAetherRefreshHaptics(onPulse: () -> Unit) {
    while (currentCoroutineContext().isActive) {
        delay(900)
        onPulse()
    }
}

@Composable
private fun HomeHeader(
    expired: Boolean,
    updateUrl: String?,
    animateGalaxy: Boolean,
    importing: Boolean,
    energised: Boolean,
    selectedGroupId: String,
    onAction: (MainAction) -> Unit,
) {
    val compactHeight = LocalConfiguration.current.screenHeightDp < 480
    val colors = MaterialTheme.colorScheme
    val galaxyActionLabel = stringResource(R.string.andromeda_galaxy_action)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = if (compactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground,
                )
                if (!compactHeight) {
                    Text(
                        text = stringResource(R.string.title_server),
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            val artwork = Modifier.weight(1.7f).fillMaxHeight()
                .offset(x = 6.dp, y = if (compactHeight) (-4).dp else (-10).dp)
                .semantics { contentDescription = galaxyActionLabel }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClickLabel = galaxyActionLabel) { onAction(MainAction.GalaxyTapped) }
            var displayedExpired by remember { mutableStateOf(expired) }
            val artworkScale = remember { Animatable(1f) }
            LaunchedEffect(expired) {
                if (displayedExpired != expired) {
                    artworkScale.animateTo(.035f, tween(170, easing = FastOutSlowInEasing))
                    displayedExpired = expired
                }
                artworkScale.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
            }
            Box(artwork) {
                val scaledArtwork = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = artworkScale.value
                    scaleY = artworkScale.value
                }
                if (displayedExpired) ExpiredSubscriptionPlanet(scaledArtwork)
                else ConnectionGalaxy(animated = animateGalaxy, energised = energised, modifier = scaledArtwork)
            }
        }
        val freeConfigurationPage = isFreeConfigurationPage(selectedGroupId)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeShortcut(
                title = stringResource(R.string.menu_item_import_config_clipboard),
                icon = R.drawable.ic_copy,
            ) { onAction(MainAction.ImportClipboard) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                freeConfigurationPage -> R.string.control_panel_free_config
                expired -> R.string.subscription_expired_header
                else -> R.string.home_update_list
            }
            AndroLiquidShortcut(label = label, enabled = !importing && !expired) {
                onAction(if (freeConfigurationPage) MainAction.ImportFreeConfiguration else MainAction.UpdateGroupSubscription(selectedGroupId))
            }
            if (updateUrl != null) {
                val updateDescription = stringResource(R.string.andro_header_update)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onAction(MainAction.OpenSubscriptionLink(updateUrl)) },
                    modifier = Modifier.size(52.dp),
                    contentPadding = PaddingValues(0.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_nova_update),
                        contentDescription = updateDescription,
                        modifier = Modifier.size(36.dp),
                        tint = Color.Unspecified,
                    )
                }
            }
            }
        }
    }
}

internal fun isFreeConfigurationPage(groupId: String): Boolean =
    groupId.isEmpty() || groupId == com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID

@Composable
private fun HomeShortcut(title: String, icon: Int, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
        border = BorderStroke(0.75.dp, colors.primary.copy(alpha = 0.58f)),
        modifier = Modifier.animateContentSize(tween(320)),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = if (enabled) colors.primary else colors.primary.copy(alpha = 0.38f),
            )
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
