package com.v2ray.ang.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionUsageInfo
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.ReorderableGridItem
import com.v2ray.ang.ui.compose.ReorderableListItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

@Composable
private fun AetherRefreshIcon(spinning: Boolean, label: String) {
    val rotation = if (spinning) {
        rememberInfiniteTransition(label = "Profile refresh").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "Refresh rotation",
        )
    } else null
    Icon(
        painterResource(R.drawable.ic_refresh_24dp),
        contentDescription = label,
        modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation?.value ?: 0f },
    )
}

@Composable
fun GroupPagerPage(
    groupId: String,
    starredTestGuids: Set<String>,
    mainViewModel: MainViewModel,
    selectedGuid: String?,
    isPreparingAether: Boolean,
    refreshingAetherGuid: String?,
    rebuildingAether: Boolean,
    locateTarget: LocateTarget?,
    doubleColumnDisplay: Boolean,
    searchQuery: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onRefreshAether: (String, Boolean, Rect) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String, String) -> Unit,
    usageInfo: SubscriptionUsageInfo?,
    groupTitle: String,
    onOpenLink: (String) -> Unit,
    contentPadding: PaddingValues
) {
    val groupStateFlow = remember(groupId) {
        mainViewModel.serverGroupState(groupId)
    }
    val groupState by groupStateFlow.collectAsStateWithLifecycle()
    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
    val actions = remember(
        onSelectServer,
        onEditServer,
        onRefreshAether,
        isPreparingAether,
        refreshingAetherGuid,
        rebuildingAether,
        onShareServer,
        onMoreServer,
        onRemoveServer,
    ) {
        ServerRowActions(
            select = onSelectServer,
            edit = onEditServer,
            refreshAether = onRefreshAether,
            isPreparingAether = isPreparingAether,
            refreshingAetherGuid = refreshingAetherGuid,
            rebuildingAether = rebuildingAether,
            share = onShareServer,
            more = onMoreServer,
            remove = onRemoveServer,
        )
    }
    // The box floats over the list (the list scrolls underneath it), so the list is padded by the box height.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var infoHeightPx by remember { mutableIntStateOf(0) }
    val listPadding = if (usageInfo == null) {
        contentPadding
    } else {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding() + with(density) { infoHeightPx.toDp() },
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding()
        )
    }

    Box(Modifier.fillMaxSize()) {
        ServerListPage(
            rows = groupState.rows,
            starredTestGuids = starredTestGuids,
            selectedGuid = selectedGuid,
            locateTarget = locateTarget?.takeIf { it.groupId == groupId },
            canReorder = canReorder,
            doubleColumnDisplay = doubleColumnDisplay,
            groupId = groupId,
            lazyListStates = lazyListStates,
            lazyGridStates = lazyGridStates,
            actions = actions,
            onLocateHandled = { mainViewModel.onAction(MainAction.LocateHandled) },
            onMoveServer = { fromGuid, toGuid ->
                mainViewModel.onAction(MainAction.MoveServer(groupId, fromGuid, toGuid))
            },
            contentPadding = listPadding
        )
        if (usageInfo != null) {
            SubscriptionInfoCard(
                info = usageInfo,
                fallbackTitle = groupTitle,
                onOpenLink = onOpenLink,
                onRefresh = { mainViewModel.onAction(MainAction.UpdateGroupSubscription(groupId)) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { infoHeightPx = it.height }
            )
        }
    }
}

private class ServerRowActions(
    val select: (String) -> Unit,
    val edit: (String, ProfileItem) -> Unit,
    val refreshAether: (String, Boolean, Rect) -> Unit,
    val isPreparingAether: Boolean,
    val refreshingAetherGuid: String?,
    val rebuildingAether: Boolean,
    val share: (String, ProfileItem) -> Unit,
    val more: (String, ProfileItem) -> Unit,
    val remove: (String, String) -> Unit,
)

@Composable
private fun ServerListPage(
    rows: List<ServerRowUiModel>,
    starredTestGuids: Set<String>,
    selectedGuid: String?,
    locateTarget: LocateTarget?,
    canReorder: Boolean,
    doubleColumnDisplay: Boolean,
    groupId: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    actions: ServerRowActions,
    onLocateHandled: () -> Unit,
    onMoveServer: (fromGuid: String, toGuid: String) -> Unit,
    contentPadding: PaddingValues
) {
    if (doubleColumnDisplay) {
        val gridState = remember(groupId) {
            lazyGridStates.getOrPut(groupId) { LazyGridState() }
        }
        val reorderableGridState = if (canReorder) {
            rememberReorderableLazyGridState(gridState) { from, to ->
                // v2meda: by the keys of the rows, their guids, not by their positions.
                onMoveServer(from.key as? String ?: return@rememberReorderableLazyGridState, to.key as? String ?: return@rememberReorderableLazyGridState)
            }
        } else null

        LocateTargetEffect(locateTarget, rows, gridState, onLocateHandled)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(gridState),
            contentPadding = contentPadding
        ) {
            itemsIndexed(items = rows, key = { _, item -> item.guid }) { _, row ->
                val content: @Composable () -> Unit = {
                    ServerItemColumn(
                        row = row,
                        isSelected = row.guid == selectedGuid,
                        doubleColumnDisplay = true,
                        showStarredResult = row.guid in starredTestGuids,
                        actions = actions
                    )
                }
                if (canReorder && reorderableGridState != null) {
                    ReorderableItem(
                        reorderableGridState,
                        key = row.guid
                    ) { isDragging ->
                        ReorderableGridItem(
                            scope = this,
                            isDragging = isDragging
                        ) { content() }
                    }
                } else {
                    content()
                }
            }
        }
    } else {
        val listState = remember(groupId) {
            lazyListStates.getOrPut(groupId) { LazyListState() }
        }
        val reorderableState = if (canReorder) {
            rememberReorderableLazyListState(listState) { from, to ->
                // v2meda: by the keys of the rows, their guids, not by their positions.
                onMoveServer(from.key as? String ?: return@rememberReorderableLazyListState, to.key as? String ?: return@rememberReorderableLazyListState)
            }
        } else null

        LocateTargetEffect(locateTarget, rows, listState, onLocateHandled)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(listState),
            contentPadding = contentPadding
        ) {
            itemsIndexed(items = rows, key = { _, item -> item.guid }) { _, row ->
                if (canReorder && reorderableState != null) {
                    ReorderableItem(
                        reorderableState,
                        key = row.guid
                    ) { isDragging ->
                        ReorderableListItem(
                            scope = this,
                            isDragging = isDragging,
                            containerColor = Color.Transparent,
                        ) {
                            ServerItemRow(
                                row = row,
                                isSelected = row.guid == selectedGuid,
                                showStarredResult = row.guid in starredTestGuids,
                                actions = actions
                            )
                        }
                    }
                } else {
                    ServerItemRow(
                        row = row,
                        isSelected = row.guid == selectedGuid,
                        showStarredResult = row.guid in starredTestGuids,
                        actions = actions
                    )
                }
            }
        }
    }
}

@Composable
private fun LocateTargetEffect(
    target: LocateTarget?,
    rows: List<ServerRowUiModel>,
    state: LazyListState,
    onHandled: () -> Unit,
) {
    if (target == null) return
    LaunchedEffect(target, rows) {
        val index = rows.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

@Composable
private fun LocateTargetEffect(
    target: LocateTarget?,
    rows: List<ServerRowUiModel>,
    state: LazyGridState,
    onHandled: () -> Unit,
) {
    if (target == null) return
    LaunchedEffect(target, rows) {
        val index = rows.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

@Composable
private fun ServerItemRow(
    row: ServerRowUiModel,
    isSelected: Boolean,
    showStarredResult: Boolean,
    actions: ServerRowActions
) {
    ServerItemColumn(row, isSelected, doubleColumnDisplay = false, showStarredResult = showStarredResult, actions = actions)
}

@Composable
private fun ServerItemColumn(
    row: ServerRowUiModel,
    isSelected: Boolean,
    doubleColumnDisplay: Boolean,
    showStarredResult: Boolean,
    actions: ServerRowActions
) {
    ServerListItem(row, isSelected, doubleColumnDisplay, showStarredResult, actions)
}

@Composable
private fun ServerListItem(
    row: ServerRowUiModel,
    isSelected: Boolean,
    doubleColumnDisplay: Boolean,
    showStarredResult: Boolean,
    actions: ServerRowActions
) {
    val testResult = if (row.testDelayMillis == 0L) {
        ""
    } else {
        stringResource(R.string.server_test_delay_value, row.testDelayMillis)
    }
    val selectedStateDescription = if (isSelected) {
        stringResource(R.string.acc_selected_server)
    } else {
        null
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val cardColor = if (isSelected) colors.primaryContainer else colors.surfaceContainerLow
    val outline = colors.primary.copy(alpha = if (isSelected) 0.82f else 0.38f)
    val canRefreshAether = QuickAetherProfile.canRefresh(row.profile)
    val hapticFeedback = LocalHapticFeedback.current
    var cardBounds by remember(row.guid) { mutableStateOf(Rect.Zero) }
    val rebuilding = actions.rebuildingAether && actions.refreshingAetherGuid == row.guid
    val density = LocalDensity.current
    val shake = if (rebuilding) {
        rememberInfiniteTransition(label = "Profile rebuilding").animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(keyframes {
                durationMillis = 720
                0f at 0
                1.8f at 90
                -1.8f at 180
                1.6f at 270
                -1.6f at 360
                1.8f at 450
                -1.8f at 540
                0f at 720
            }),
            label = "Continuous card shake",
        )
    } else null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (doubleColumnDisplay) 3.dp else 0.dp, vertical = 3.dp)
            .height(IntrinsicSize.Min)
            .onGloballyPositioned { cardBounds = it.boundsInRoot() }
            .graphicsLayer {
                val displacement = shake?.value ?: 0f
                translationX = with(density) { displacement.dp.toPx() }
                rotationZ = displacement * 0.14f
            }
            .clip(shape)
            .background(cardColor)
            .border(0.75.dp, outline, shape)
            .semantics {
                if (selectedStateDescription != null) {
                    stateDescription = selectedStateDescription
                }
            }
            .clickable { actions.select(row.guid) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.remarks, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val isAether = row.profile.configType == EConfigType.AETHER
                if (canRefreshAether) {
                    val refreshLabel = stringResource(R.string.aether_action_refresh_profile)
                    IconButton(
                        onClick = { actions.refreshAether(row.guid, false, cardBounds) },
                        enabled = !actions.isPreparingAether,
                        modifier = Modifier.size(width = 48.dp, height = 36.dp),
                    ) {
                        AetherRefreshIcon(actions.refreshingAetherGuid == row.guid, refreshLabel)
                    }
                }
                if (doubleColumnDisplay || isAether) {
                    IconButton(onClick = { actions.more(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert_24dp),
                            stringResource(R.string.acc_more),
                            Modifier.size(24.dp)
                        )
                    }
                } else {
                    IconButton(onClick = { actions.share(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_share_24dp),
                            stringResource(R.string.title_configuration_share),
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { actions.edit(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_edit_24dp),
                            stringResource(R.string.acc_edit),
                            Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { actions.remove(row.guid, row.remarks) }, Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_delete_24dp),
                            stringResource(R.string.acc_delete),
                            Modifier.size(24.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                Modifier.fillMaxWidth().padding(end = if (canRefreshAether) 84.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.subscriptionBadge.isNotBlank()) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), Alignment.Center
                    ) {
                        Text(row.subscriptionBadge.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Box(Modifier.weight(1f).height(20.dp), contentAlignment = Alignment.CenterStart) {
                    if (row.hasEndpoint) HiddenEndpoint()
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                Modifier.fillMaxWidth().padding(end = if (canRefreshAether) 84.dp else 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.typeDescription,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (showStarredResult) {
                    if (row.testDelayMillis > 0L) StarredDelayResult()
                } else {
                    Text(
                        testResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.testDelayMillis < 0L) colors.error else colors.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (canRefreshAether) {
            IconButton(
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    actions.refreshAether(row.guid, true, cardBounds)
                },
                enabled = !actions.isPreparingAether,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 46.dp, bottom = 4.dp)
                    .size(width = 48.dp, height = 36.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_real_delay_bolt),
                    contentDescription = stringResource(R.string.aether_action_rebuild_profile),
                    modifier = Modifier.size(23.dp),
                    tint = colors.primary.copy(alpha = if (actions.isPreparingAether && !rebuilding) 0.38f else 1f),
                )
            }
        }
    }
}

/** A fixed blur silhouette. No address or port is passed into the renderer or semantics tree. */
@Composable
private fun HiddenEndpoint() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.width(96.dp).height(16.dp)) {
        val edge = 2.dp.toPx().coerceAtMost(size.width / 4)
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(tint.copy(alpha = 0.07f), cornerRadius = radius)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(tint.copy(alpha = 0.02f), tint.copy(alpha = 0.35f), tint.copy(alpha = 0.02f))),
            topLeft = Offset(edge, 0f),
            size = Size(size.width - edge * 2, size.height),
            cornerRadius = radius,
        )
    }
}

internal suspend fun PagerState.navigateToPageOptimized(
    targetPage: Int,
    animateAdjacentPage: Boolean = true
) {
    if (pageCount <= 0) return
    val target = targetPage.coerceIn(0, pageCount - 1)
    val current = settledPage.coerceIn(0, pageCount - 1)
    if (target == current) return

    if (abs(target - current) == 1 && animateAdjacentPage) {
        animateScrollToPage(target)
    } else {
        scrollToPage(target)
    }
}
