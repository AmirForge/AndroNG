package com.v2ray.ang.ui.main

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.handler.SubscriptionUsageReport
import com.v2ray.ang.handler.proGroupNumber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

@Composable
internal fun UsageReportDialog(state: MainUiState, onAction: (MainAction) -> Unit) {
    val groups = state.groups.filter { proGroupNumber(it.id) > 0 }.sortedBy { proGroupNumber(it.id) }
    var hourly by rememberSaveable { mutableStateOf(false) }
    var subscriptionsOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { onAction(MainAction.CloseUsageReport) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.usage_report), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        val closeLabel = stringResource(android.R.string.cancel)
                        val closeColor = MaterialTheme.colorScheme.onSurfaceVariant
                        IconButton(
                            onClick = { onAction(MainAction.CloseUsageReport) },
                            modifier = Modifier.semantics { contentDescription = closeLabel },
                        ) {
                            Canvas(Modifier.size(16.dp)) {
                                drawLine(closeColor, Offset.Zero, Offset(size.width, size.height), 1.6.dp.toPx(), StrokeCap.Round)
                                drawLine(closeColor, Offset(size.width, 0f), Offset(0f, size.height), 1.6.dp.toPx(), StrokeCap.Round)
                            }
                        }
                    }
                    if (groups.isNotEmpty()) {
                        val colors = MaterialTheme.colorScheme
                        val density = LocalDensity.current
                        var anchorWidth by remember { mutableIntStateOf(0) }
                        Box(Modifier.fillMaxWidth()) {
                            Surface(
                                onClick = { subscriptionsOpen = true },
                                shape = RoundedCornerShape(16.dp),
                                color = colors.primaryContainer,
                                contentColor = colors.onPrimaryContainer,
                                border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width },
                            ) {
                                Row(
                                    Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(painterResource(R.drawable.ic_usage_report), null, Modifier.size(20.dp), tint = colors.primary)
                                    val selectedGroup = groups.firstOrNull { it.id == state.usageGroup }
                                    Text(
                                        selectedGroup?.let { "Andromeda ${proGroupNumber(it.id)}" }
                                            ?: stringResource(R.string.usage_choose_subscription),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Canvas(Modifier.size(16.dp).graphicsLayer { rotationZ = if (subscriptionsOpen) 180f else 0f }) {
                                        val midpoint = Offset(size.width / 2f, size.height * 0.65f)
                                        drawLine(colors.primary, Offset(size.width * 0.2f, size.height * 0.35f), midpoint, 1.8.dp.toPx(), StrokeCap.Round)
                                        drawLine(colors.primary, midpoint, Offset(size.width * 0.8f, size.height * 0.35f), 1.8.dp.toPx(), StrokeCap.Round)
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = subscriptionsOpen,
                                onDismissRequest = { subscriptionsOpen = false },
                                modifier = Modifier.width(with(density) { anchorWidth.toDp() }),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = colors.surfaceContainerHigh,
                                border = BorderStroke(1.dp, colors.outlineVariant),
                            ) {
                                groups.forEach { group ->
                                    val active = group.id == state.usageGroup
                                    DropdownMenuItem(text = {
                                        Text(
                                            "Andromeda ${proGroupNumber(group.id)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (active) colors.primary else colors.onSurface,
                                        )
                                    }, modifier = Modifier.semantics { selected = active }, onClick = {
                                        subscriptionsOpen = false
                                        onAction(MainAction.SelectUsageGroup(group.id))
                                    })
                                }
                            }
                        }
                    }
                    val report = state.usageReport
                    when {
                        state.usageLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        state.usageGroup == null -> Text(stringResource(R.string.usage_choose_subscription))
                        report == null -> {
                            Text(stringResource(R.string.usage_report_unavailable))
                            TextButton(onClick = { state.usageGroup?.let { onAction(MainAction.SelectUsageGroup(it)) } }) {
                                Text(stringResource(R.string.home_update_list))
                            }
                        }
                        else -> {
                            val context = LocalContext.current
                            val colors = MaterialTheme.colorScheme
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = colors.surfaceContainerHigh,
                                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(stringResource(R.string.usage_period_total), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                            Text(Formatter.formatFileSize(context, report.usedBytes), style = MaterialTheme.typography.titleMedium, color = colors.primary)
                                        }
                                        VerticalDivider(Modifier.height(32.dp), color = colors.outlineVariant)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(stringResource(R.string.usage_remaining), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                            Text(report.remainingBytes?.let { Formatter.formatFileSize(context, it) } ?: stringResource(R.string.usage_no_limit), style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                    report.remainingBytes?.let { remaining ->
                                        val total = report.usedBytes.toDouble() + remaining.toDouble()
                                        if (total > 0) {
                                            LinearProgressIndicator(
                                                progress = { (report.usedBytes / total).toFloat().coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                color = colors.primary,
                                                trackColor = colors.primary.copy(alpha = 0.12f),
                                                gapSize = 0.dp,
                                                drawStopIndicator = {},
                                            )
                                        }
                                    }
                                }
                            }
                            Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(false, true).forEach { isHourly ->
                                        val active = hourly == isHourly
                                        Box(
                                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                                .background(if (active) colors.primaryContainer else colors.surfaceContainerHigh)
                                                .selectable(
                                                    selected = active,
                                                    role = Role.Tab,
                                                    onClick = { hourly = isHourly },
                                                ).heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stringResource(if (isHourly) R.string.usage_hourly else R.string.usage_daily),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (active) colors.primary else colors.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            key(state.usageGroup, hourly) { UsageBars(report, hourly, Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

private const val USAGE_WINDOW_SIZE = 7
private const val SECONDS_PER_HOUR = 3_600L

internal fun usageWindows(values: List<Long?>): List<IntRange> {
    val firstRecorded = values.indexOfFirst { it != null }
    if (firstRecorded < 0) return emptyList()
    return (firstRecorded..values.lastIndex).reversed().chunked(USAGE_WINDOW_SIZE).asReversed()
        .map { it.last()..it.first() }
        .filter { window -> window.any { values[it] != null } }
}

internal fun usageChartCeiling(peakBytes: Long): Long {
    val target = (peakBytes.toDouble() * 2).coerceAtLeast(1.0)
    val unit = 10.0.pow(floor(log10(target)))
    return (ceil(target / unit) * unit).toLong()
}

internal fun usageDaysThrough(start: LocalDate?, end: LocalDate, count: Int): Int =
    start?.let { (ChronoUnit.DAYS.between(it, end) + 1).coerceIn(0L, count.toLong()).toInt() } ?: count

@Composable
private fun UsageBars(report: SubscriptionUsageReport, hourly: Boolean, modifier: Modifier) {
    val tehran = remember { ZoneId.of("Asia/Tehran") }
    val firstDay = remember(report.startDate) { runCatching { LocalDate.parse(report.startDate) }.getOrNull() }
    val lastHour = Instant.ofEpochMilli(report.hourStart).plusSeconds((report.hourly.size - 1) * SECONDS_PER_HOUR)
    val values = if (hourly) report.hourly else report.daily.take(
        usageDaysThrough(firstDay, lastHour.atZone(tehran).toLocalDate(), report.daily.size)
    )
    val context = LocalContext.current
    val missing = stringResource(R.string.usage_no_data)
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]

    if (values.all { it == null }) {
        Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_usage_report), contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.usage_collecting), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (hourly) R.string.usage_wait_hourly else R.string.usage_wait_daily),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        return
    }

    val labels = remember(firstDay, report.hourStart, hourly, values.size, locale) {
        val clock = DateTimeFormatter.ofPattern("HH:mm", locale).withDecimalStyle(java.time.format.DecimalStyle.of(locale))
        val calendarLocale = android.icu.util.ULocale("fa_IR@calendar=persian")
        val reportDate = android.icu.text.SimpleDateFormat("d MMM", calendarLocale).apply {
            timeZone = android.icu.util.TimeZone.getTimeZone(tehran.id)
        }
        List(values.size) { index ->
            if (hourly) clock.format(Instant.ofEpochMilli(report.hourStart).plusSeconds(index * SECONDS_PER_HOUR).atZone(tehran))
            else firstDay?.plusDays(index.toLong())?.atStartOfDay(tehran)?.toInstant()?.let { reportDate.format(java.util.Date.from(it)) }
                ?: (index + 1).toString()
        }
    }
    val windows = remember(values) { usageWindows(values) }

    val ceiling = remember(values) {
        usageChartCeiling(values.filterNotNull().maxOrNull() ?: 0L)
    }

    key(windows.size) {
        val pager = rememberPagerState(initialPage = windows.lastIndex) { windows.size }
        val scope = rememberCoroutineScope()

        Surface(
            color = colors.surfaceContainerLow,
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, colors.primary.copy(alpha = .16f)),
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    stringResource(if (hourly) R.string.usage_last_hours else R.string.usage_cycle_days),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                        val window = windows[page]
                        var selected by rememberSaveable { mutableIntStateOf(window.lastOrNull { values[it] != null } ?: window.last) }
                        val active = selected.coerceIn(window.first, window.last)
                        val amount = values[active]?.let { Formatter.formatFileSize(context, it) } ?: missing
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                          val chartHeight = (maxHeight - 125.dp).coerceAtLeast(1.dp)
                          Column(Modifier.fillMaxWidth()) {
                            AnimatedContent(targetState = labels[active] to amount, label = "Selected usage",
                                modifier = Modifier.fillMaxWidth().height(64.dp)) { (period, usage) ->
                                Surface(shape = RoundedCornerShape(18.dp), color = colors.primaryContainer.copy(alpha = .58f)) {
                                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(period, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(usage, color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Text(
                                Formatter.formatShortFileSize(context, ceiling),
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.Start).padding(start = 4.dp, bottom = 2.dp),
                            )
                            val gridColor = colors.outlineVariant
                            Row(
                                Modifier.fillMaxWidth().drawBehind {
                                    for (line in 0..2) {
                                        val y = (chartHeight.toPx() - 1.dp.toPx()) * line / 2
                                        drawLine(
                                            gridColor.copy(alpha = if (line == 2) 0.8f else 0.45f),
                                            Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                                            pathEffect = if (line == 2) null else PathEffect.dashPathEffect(
                                                floatArrayOf(3.dp.toPx(), 5.dp.toPx())
                                            ),
                                        )
                                    }
                                },
                            ) {
                                val sideSlots = (USAGE_WINDOW_SIZE - window.count()) / 2f
                                if (sideSlots > 0f) Spacer(Modifier.weight(sideSlots))
                                window.forEach { index ->
                                    val value = values[index]
                                    val accessibleAmount = value?.let { Formatter.formatFileSize(context, it) } ?: missing
                                    val fraction = (value?.toFloat()?.div(ceiling) ?: 0f).coerceIn(0f, 1f)
                                    var entered by remember(index, page) { mutableStateOf(false) }
                                    LaunchedEffect(page, pager.currentPage) {
                                        if (page == pager.currentPage) entered = true
                                    }
                                    val heightFraction by animateFloatAsState(
                                        if (entered) fraction else 0f, spring(stiffness = 160f), label = "Usage bar height")
                                    val emphasis by animateFloatAsState(if (active == index) 1f else .54f,
                                        spring(stiffness = 300f), label = "Selected usage bar")
                                    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                    Column(
                                        modifier = Modifier.weight(1f)
                                            .selectable(
                                                selected = active == index,
                                                interactionSource = null,
                                                indication = null,
                                                onClick = { selected = index },
                                            )
                                            .semantics { contentDescription = "${labels[index]}, $accessibleAmount" },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.BottomCenter) {
                                            if (value == null) {
                                                Box(Modifier.width(12.dp).height(2.dp).background(colors.outline))
                                            } else {
                                                Box(
                                                    Modifier.width(22.dp)
                                                        .height((chartHeight * heightFraction.coerceIn(0f, 1f)).coerceIn(minOf(3.dp, chartHeight), chartHeight))
                                                        .clip(shape)
                                                        .border(BorderStroke(1.dp, colors.primary.copy(alpha = .16f)), shape)
                                                        .background(
                                                            Brush.verticalGradient(listOf(colors.primary.copy(alpha = emphasis), colors.tertiary.copy(alpha = emphasis * .52f))),
                                                        )
                                                ) {
                                                    if (active == index && page == pager.currentPage) {
                                                        UsageBarStars(Modifier.matchParentSize())
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            labels[index], style = MaterialTheme.typography.labelSmall,
                                            color = if (active == index) colors.primary else colors.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                                                .padding(horizontal = 2.dp, vertical = 5.dp).clearAndSetSemantics {},
                                        )
                                    }
                                }
                                if (sideSlots > 0f) Spacer(Modifier.weight(sideSlots))
                            }
                          }
                        }
                    }
                }
                if (windows.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            enabled = pager.canScrollBackward,
                            onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                        ) { Text(stringResource(R.string.usage_earlier)) }
                        TextButton(
                            enabled = pager.canScrollForward,
                            onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(windows.lastIndex)) } },
                        ) { Text(stringResource(R.string.usage_later)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageBarStars(modifier: Modifier = Modifier) {
    val progress = rememberInfiniteTransition(label = "Usage stars").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing)),
        label = "Star drift",
    )
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val stars = (size.width * size.height / (28.dp.toPx() * 1.dp.toPx())).toInt().coerceIn(18, 100)
        val time = progress.value * 2f * Math.PI.toFloat()
        for (index in 0 until stars) {
            val seed = index * 2.39996f
            val originX = 0.20f + (index * 0.618034f % 1f) * 0.60f
            val originY = 0.14f + (index * 0.414214f % 1f) * 0.72f
            val center = Offset(
                size.width * (originX + 0.15f * sin(time + seed)),
                size.height * (originY + 0.10f * sin(time + seed * 1.7f)),
            )
            val shimmer = (sin(time * 2f + seed) + 1f) / 2f
            val radius = (0.45f + index % 3 * 0.18f).dp.toPx()
            val light = colors.onPrimaryContainer
            drawCircle(light.copy(alpha = 0.04f + shimmer * 0.04f), radius * 4.5f, center)
            drawCircle(light.copy(alpha = 0.10f + shimmer * 0.08f), radius * 2.5f, center)
            drawCircle(colors.onSurface.copy(alpha = 0.60f + shimmer * 0.35f), radius, center)
        }
    }
}
