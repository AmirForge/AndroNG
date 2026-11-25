package com.v2ray.ang.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

data class ConverterState(
    val running: Boolean = false,
    val current: Int = 0,
    val results: Map<Int, ConverterResult?> = emptyMap(),
    val choices: List<DiagnosticOutRuleChoice> = emptyList(),
)

data class ConverterResult(val profile: ProfileItem, val delayMillis: Long)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ConfigConverterScreen(state: ConverterState, ui: MainUiState, onAction: (MainAction) -> Unit) {
    BackHandler { onAction(MainAction.CloseConverter) }
    val colors = MaterialTheme.colorScheme
    val variants = aetherReferenceProfiles
    var selected by remember { mutableStateOf<Int?>(null) }
    var subscriptions by remember { mutableStateOf(false) }
    val finished = !state.running && state.results.size == variants.size

    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.config_converter), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { onAction(MainAction.CloseConverter) }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                }
            }
            item {
                ConverterObservatory(state, variants)
            }
            item {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.converter_description), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.converter_duration), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Button(
                    modifier = Modifier.widthIn(max = 600.dp).padding(horizontal = 24.dp).fillMaxWidth().heightIn(min = 52.dp),
                    enabled = !state.running, onClick = { onAction(MainAction.StartConverter) },
                ) { Text(stringResource(if (state.running) R.string.converter_testing else R.string.converter_start)) }
            }
            item {
                AnimatedVisibility(visible = finished, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically()) {
                    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.converter_results), style = MaterialTheme.typography.titleLarge)
                        val successful = state.results.mapNotNull { (index, result) ->
                            result?.let { index to it.delayMillis }
                        }.sortedBy { it.second }
                        if (successful.isEmpty()) Text(stringResource(R.string.converter_no_results))
                        else Text(stringResource(R.string.converter_score_explanation), color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        val fastest = successful.firstOrNull()?.second ?: 1L
                        successful.forEach { (index, delayMillis) ->
                            val score = (100.0 * fastest / delayMillis).roundToInt().coerceIn(1, 100)
                            Surface(onClick = { selected = index }, shape = MaterialTheme.shapes.large,
                                color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(converterProtocolName(variants[index]), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.converter_score, score), color = colors.primary, style = MaterialTheme.typography.titleMedium)
                                    }
                                    LinearProgressIndicator(progress = { score / 100f }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { variant ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surfaceContainer,
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.converter_choose_source), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text(converterProtocolName(variants[variant]), color = colors.primary, style = MaterialTheme.typography.labelLarge)
                }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerLowest) {
                    Row(Modifier.fillMaxWidth().padding(4.dp)) {
                        listOf(false to R.string.converter_profile, true to R.string.converter_subscription).forEach { (value, label) ->
                            val tone by animateColorAsState(
                                if (subscriptions == value) colors.secondaryContainer else colors.surfaceContainerLowest,
                                label = "Source selector",
                            )
                            Surface(onClick = { subscriptions = value }, modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.extraLarge, color = tone) {
                                Text(stringResource(label), Modifier.padding(vertical = 14.dp), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                AnimatedContent(subscriptions, label = "Converter source list") { showSubscriptions ->
                    val choices = if (showSubscriptions) ui.groups.filter {
                        it.id.isNotBlank() && it.id !in setOf(AppConfig.ANDROMEDA_SUBSCRIPTION_ID, AETHER_DIAGNOSTIC_GROUP)
                    }.map { it.id to it.remarks } else state.choices.map { it.guid to it.remarks }
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (choices.isEmpty()) item { Text(stringResource(R.string.converter_no_sources), color = colors.onSurfaceVariant) }
                        items(choices, key = { it.first }) { (id, label) ->
                            Surface(onClick = {
                                onAction(MainAction.ConvertWith(variant, id, showSubscriptions))
                                selected = null
                            }, shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                                Text(label, Modifier.padding(horizontal = 16.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConverterObservatory(state: ConverterState, variants: List<ProfileItem>) {
    val colors = MaterialTheme.colorScheme
    val active = state.running
    val finished = state.results.size == variants.size && !active
    val transition = rememberInfiniteTransition(label = "Observatory orbit")
    val rotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing)),
        label = "Orbital sweep",
    )
    val stars = remember {
        val random = kotlin.random.Random(17)
        List(82) { Triple(random.nextFloat(), random.nextFloat(), random.nextFloat()) }
    }
    Surface(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerLowest,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.fillMaxWidth().height(226.dp)) {
                val origin = Offset(size.width / 2f, size.height * .51f)
                val radius = minOf(size.width * .33f, size.height * .49f)
                val phase = rotation.value
                val wave = ((1.0 + cos(Math.toRadians(phase.toDouble() * 2))) / 2).toFloat()
                val primary = colors.primary
                val secondary = colors.tertiary
                drawCircle(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = .20f + .15f * wave), secondary.copy(alpha = .10f), primary.copy(alpha = 0f)),
                        center = origin, radius = radius * 1.38f,
                    ),
                    radius * 1.38f, origin,
                )
                stars.forEachIndexed { index, (x, y, magnitude) ->
                    val shimmer = ((1.0 + sin(Math.toRadians(phase.toDouble() * 3 + index * 37))) / 2).toFloat()
                    drawCircle(
                        colors.onSurface.copy(alpha = .08f + magnitude * (.18f + .22f * shimmer)),
                        .55.dp.toPx() + magnitude * .7.dp.toPx(),
                        Offset(size.width * x, size.height * y),
                    )
                }
                for (orbit in 1..3) {
                    val width = radius * (.64f + orbit * .125f)
                    val height = width * .42f
                    rotate(-28f, origin) {
                        val topLeft = origin - Offset(width, height)
                        val bounds = Size(width * 2f, height * 2f)
                        drawOval(primary.copy(alpha = .10f + wave * .06f), topLeft, bounds, style = Stroke(1.dp.toPx()))
                        for (trail in 0..11) {
                            val angle = phase + orbit * 42f - trail * 5f
                            val opacity = (1f - trail / 12f) * if (active) .78f else .53f
                            drawArc(primary.copy(alpha = opacity * .22f), angle, 6f, false, topLeft, bounds,
                                style = Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                            drawArc(primary.copy(alpha = opacity), angle, 6f, false, topLeft, bounds,
                                style = Stroke(1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                    }
                }
                variants.indices.forEach { index ->
                    val angle = Math.toRadians(-90.0 + index * 360.0 / variants.size)
                    val point = origin + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius * .83f)
                    val current = active && index == state.current
                    val passed = state.results[index] != null
                    val tone = when {
                        current || passed -> primary
                        state.results.containsKey(index) -> colors.outlineVariant
                        else -> colors.onSurfaceVariant
                    }
                    if (current || passed) drawCircle(tone.copy(alpha = .10f + wave * .10f), (10f + wave * 4f).dp.toPx(), point)
                    if (current) drawCircle(tone.copy(alpha = .72f), 8.dp.toPx(), point, style = Stroke(1.dp.toPx()))
                    drawCircle(tone.copy(alpha = if (current || passed) 1f else .55f), 3.dp.toPx(), point)
                }
                drawCircle(
                    Brush.radialGradient(listOf(colors.onPrimary, primary, primary.copy(alpha = 0f)),
                        center = origin, radius = 18.dp.toPx()),
                    18.dp.toPx(), origin,
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (active) {
                    AnimatedContent(targetState = converterProtocolName(variants[state.current]), label = "Active protocol") { name ->
                        Text(name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    }
                    Text(stringResource(R.string.converter_progress, state.current + 1, variants.size),
                        style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    LinearProgressIndicator(
                        progress = { state.results.size.toFloat() / variants.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        if (finished) stringResource(R.string.converter_progress_summary, state.results.values.count { it != null }, variants.size)
                        else stringResource(R.string.converter_queued),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
