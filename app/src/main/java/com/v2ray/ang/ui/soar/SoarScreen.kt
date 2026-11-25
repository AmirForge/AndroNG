package com.v2ray.ang.ui.soar

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import com.v2ray.ang.R
import com.v2ray.ang.handler.SoarRepository
import com.v2ray.ang.ui.main.AndroLiquidShortcut
import com.v2ray.ang.core.soar.*
import kotlin.math.sin

@Composable
fun SoarScreen(state: SoarUiState, act: (SoarAction) -> Unit, back: () -> Unit, openFile: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addConnection by rememberSaveable { mutableStateOf(false) }
    var addResolvers by rememberSaveable { mutableStateOf(false) }
    var editingResolver by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = Color.Transparent, contentColor = colors.onBackground, modifier = Modifier.drawWithCache {
        val light = Brush.radialGradient(listOf(colors.primary.copy(alpha = .22f), Color.Transparent),
            center = Offset(size.width * .76f, size.height * .17f), radius = (size.width * .82f).coerceAtLeast(1f))
        val shade = Brush.verticalGradient(listOf(colors.surfaceContainer, colors.surfaceContainerLowest))
        onDrawBehind { drawRect(shade); drawRect(light) }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = back, shape = CircleShape) { Icon(painterResource(R.drawable.ic_arrow_back_24dp), stringResource(R.string.soar_back)) }
                Text(stringResource(R.string.soar_title), Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                AndroLiquidShortcut(
                    label = R.string.control_panel_free_config,
                    enabled = state.loaded && !state.locked,
                ) {
                    act(SoarAction.ImportFree)
                }
            }
            Surface(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer.copy(alpha = .8f),
                border = BorderStroke(.5.dp, colors.primary.copy(alpha = .2f))) {
            Row(Modifier.padding(5.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.soar_connection_tab, R.string.soar_resolvers_tab, R.string.soar_settings_tab).forEachIndexed { index, title ->
                    val selectedColor by androidx.compose.animation.animateColorAsState(
                        if (tab == index) colors.primaryContainer else Color.Transparent,
                        animationSpec = tween(220), label = "DNS tab tone",
                    )
                    Surface(onClick = { tab = index }, modifier = Modifier.weight(1f).semantics { this.selected = tab == index; role = Role.Tab }, shape = CircleShape,
                        color = selectedColor,
                        contentColor = if (tab == index) colors.onPrimaryContainer else colors.onSurfaceVariant) {
                        Text(stringResource(title), Modifier.heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            }
            state.error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(it), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { act(SoarAction.ClearError) }) {
                            Icon(painterResource(R.drawable.ic_close_24dp), stringResource(R.string.soar_dismiss))
                        }
                    }
                }
            }
            if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            AnimatedContent(tab, label = "DNS Connect tabs", modifier = Modifier.weight(1f)) { selected ->
                if (selected == 2) {
                    val profile = state.store.profiles.firstOrNull { it.id == state.store.selectedId }
                    if (profile == null) {
                        Box(Modifier.fillMaxSize().padding(20.dp)) { EmptyCard(R.string.soar_select_profile) }
                    } else key(profile.id, profile.runtime) {
                        LegacyProfileDialog(profile.runtime, { tab = 0 }, embedded = true, enabled = !state.locked) {
                            act(SoarAction.Settings(profile.settings, it))
                        }
                    }
                } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (selected) {
                        0 -> {
                            ConnectionControl(state, act)
                            SectionHeading(R.string.soar_profiles, R.string.soar_add_profile, !state.locked) { act(SoarAction.ClearError); addConnection = true }
                            if (state.store.profiles.isEmpty()) EmptyCard(R.string.soar_profiles_empty)
                            state.store.profiles.forEach { p -> key(p.id) {
                                SelectCard(p.name, if (p.engine == DnsTunnelEngine.SOAR) p.zone else "${p.engine.name} · ${p.zone}", p.id == state.store.selectedId, !state.locked || state.selecting,
                                    { act(SoarAction.Select(p.id)) }, { act(SoarAction.Remove(p.id)) })
                            } }
                            EmptyCard(R.string.soar_tcp_notice)
                        }
                        1 -> {
                            SectionHeading(R.string.soar_resolver_profiles, R.string.soar_add_resolvers, !state.locked) {
                                act(SoarAction.ClearError); editingResolver = null; addResolvers = true
                            }
                            if (state.store.resolverProfiles.isEmpty()) EmptyCard(R.string.soar_resolvers_empty)
                            state.store.resolverProfiles.forEach { p -> key(p.id) {
                                SelectCard(p.name, stringResource(R.string.soar_resolver_count, p.addresses.size), p.id == state.store.resolverId,
                                    !state.locked || state.selecting, { act(SoarAction.SelectResolvers(p.id)) },
                                    if (SoarRepository.isBuiltInResolver(p.id)) null else ({ act(SoarAction.RemoveResolvers(p.id)) }),
                                    if (SoarRepository.isBuiltInResolver(p.id)) null else ({
                                        act(SoarAction.ClearError); editingResolver = p.id; addResolvers = true
                                    }))
                            } }
                            EmptyCard(R.string.soar_discovery_hint)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                }
            }
        }
    }
    if (addConnection) ConnectionDialog(state, act) { addConnection = false }
    if (addResolvers) ResolverDialog(state, state.store.resolverProfiles.firstOrNull { it.id == editingResolver }, act, openFile) {
        addResolvers = false; act(SoarAction.ClearFile)
    }
}

@Composable
private fun ConnectionControl(state: SoarUiState, act: (SoarAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val profile = state.store.profiles.firstOrNull { it.id == state.store.selectedId }
    val resolver = state.store.resolverProfiles.firstOrNull { it.id == state.store.resolverId }
    val canStop = state.busy || state.connected
    val status = when {
        state.connected -> R.string.soar_connected
        state.busy -> R.string.soar_checking
        else -> R.string.soar_disconnected
    }
    val isError = state.phase == "failed"
    val accent by androidx.compose.animation.animateColorAsState(when {
        isError -> colors.error
        state.connected -> colors.tertiary
        else -> colors.primary
    }, label = "DNS connection color")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = colors.surfaceContainerLowest,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = .24f)),
    ) {
        Column(
            Modifier.background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = .66f), Color.Transparent)))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(104.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ConnectionSignal(accent, state.busy, state.connected)
                    Icon(painterResource(if (state.connected) R.drawable.ic_fab_check else R.drawable.ic_soar),
                        contentDescription = null, tint = accent,
                        modifier = Modifier.size(64.dp)
                            .background(colors.surfaceContainerLowest, CircleShape)
                            .padding(17.dp))
                }
                AnimatedContent(status, label = "DNS status") { label ->
                    Text(stringResource(label), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            val profileName = profile?.name ?: stringResource(R.string.soar_no_profile)
            val resolverName = resolver?.name ?: stringResource(
                if (profile?.settings?.discovery != false) R.string.soar_transport_auto
                else R.string.soar_no_profile
            )
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 320.dp && fontScale <= 1.3f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConnectionDetail(R.string.soar_profiles, profileName, Modifier.weight(1f))
                        ConnectionDetail(R.string.soar_resolvers_tab, resolverName, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConnectionDetail(R.string.soar_profiles, profileName, Modifier.fillMaxWidth())
                        ConnectionDetail(R.string.soar_resolvers_tab, resolverName, Modifier.fillMaxWidth())
                    }
                }
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = accent)
                val progress = when {
                    state.phase == "discovery" -> stringResource(R.string.soar_discovering)
                    state.total > 0 -> stringResource(R.string.soar_check_progress, state.attempt, state.total)
                    else -> stringResource(R.string.soar_starting)
                }
                Text(progress, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { if (!state.saving) act(if (canStop) SoarAction.Stop else SoarAction.Connect) },
                enabled = state.loaded && (!state.saving || state.selecting),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = if (canStop) ButtonDefaults.buttonColors(
                    containerColor = colors.surfaceContainerHighest, contentColor = colors.onSurface
                ) else ButtonDefaults.buttonColors(),
            ) {
                Icon(painterResource(if (canStop) R.drawable.ic_stop_24dp else R.drawable.ic_soar),
                    contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (canStop) R.string.soar_stop else R.string.soar_connect))
            }
        }
    }
}

@Composable
private fun ConnectionSignal(accent: Color, checking: Boolean, connected: Boolean) {
    val active = checking || connected
    val waveOpacity by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "DNS signal visibility",
    )
    val phase = if (active) {
        rememberInfiniteTransition(label = "DNS signal").animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(if (checking) 1050 else 2400, easing = LinearEasing)),
            label = "DNS signal phase",
        )
    } else null
    val amplitude by animateFloatAsState(
        if (checking) 1f else if (connected) .66f else .24f,
        label = "DNS signal amplitude",
    )
    Canvas(Modifier.fillMaxSize()) {
        val time = phase?.value ?: 0f
        val rim = 35.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = .28f * amplitude), Color.Transparent),
                center = center,
                radius = rim * 1.55f,
            ),
            radius = rim * 1.55f,
        )
        drawCircle(accent.copy(alpha = .46f), rim, style = Stroke(1.dp.toPx()))
        val opacity = waveOpacity
        if (opacity <= 0f) return@Canvas

        for (trace in 0..1) {
            val path = Path()
            for (sample in 0..64) {
                val position = sample / 64f
                val envelope = sin(position * Math.PI.toFloat())
                val y = center.y + sin(position * 19f - time + trace * 1.1f) *
                    envelope * size.height * .24f * amplitude
                val x = size.width * position
                if (sample == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent.copy(alpha = opacity * if (trace == 0) .58f else .22f),
                style = Stroke(width = (if (trace == 0) 2.dp else 1.dp).toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ConnectionDetail(@StringRes label: Int, value: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHighest,
        contentColor = colors.onSurface) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionHeading(@StringRes title: Int, @StringRes add: Int, enabled: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        FilledTonalIconButton(onClick = click, enabled = enabled, shape = CircleShape) {
            Icon(painterResource(R.drawable.ic_add_24dp), stringResource(add))
        }
    }
}

@Composable
private fun EmptyCard(@StringRes text: Int) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .65f)) {
        Text(stringResource(text), Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectCard(title: String, subtitle: String, selected: Boolean, enabled: Boolean, select: () -> Unit,
    delete: (() -> Unit)?, edit: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val cardTone by androidx.compose.animation.animateColorAsState(
        if (selected) colors.primaryContainer.copy(alpha = .75f) else colors.surfaceContainerLow,
        label = "DNS profile highlight",
    )
    Surface(onClick = select, enabled = enabled, modifier = Modifier.semantics(mergeDescendants = true) { this.selected = selected }, shape = RoundedCornerShape(22.dp),
        color = cardTone,
        border = BorderStroke(.6.dp, colors.primary.copy(alpha = if (selected) .6f else .16f))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = colors.primary.copy(alpha = if (selected) .18f else .06f)) {
                Icon(painterResource(if (selected) R.drawable.ic_fab_check else R.drawable.ic_soar), null,
                    Modifier.padding(10.dp).size(20.dp), tint = if (selected) colors.primary else colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (edit != null) IconButton(onClick = edit, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_edit_24dp), stringResource(R.string.soar_edit))
            }
            if (delete != null) IconButton(onClick = delete, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_delete_24dp), stringResource(R.string.soar_delete))
            }
        }
    }
}

@Composable
private fun FormDialog(@StringRes title: Int, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(20.dp).widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 650.dp),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(.7.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f))) {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = dismiss) { Icon(painterResource(R.drawable.ic_close_24dp), stringResource(R.string.soar_dismiss)) }
                }
                MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(14.dp))) { content() }
            }
        }
    }
}

@Composable
private fun ConnectionDialog(state: SoarUiState, act: (SoarAction) -> Unit, dismiss: () -> Unit) {
    val initialRevision = remember { state.saveRevision }
    LaunchedEffect(state.saveRevision) { if (state.saveRevision > initialRevision) dismiss() }
    var text by rememberSaveable { mutableStateOf("") }
    FormDialog(R.string.soar_add_profile, dismiss) {
        Text(stringResource(R.string.dns_connect_link_types), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.soar_link)) }, minLines = 4, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { act(SoarAction.Import(text)) },
            enabled = !state.saving && text.isNotBlank(),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.soar_save))
        }
    }
}

@Composable
private fun ResolverDialog(state: SoarUiState, profile: SoarResolverProfile?, act: (SoarAction) -> Unit, file: () -> Unit, dismiss: () -> Unit) {
    val initialRevision = remember { state.saveRevision }
    LaunchedEffect(state.saveRevision) { if (state.saveRevision > initialRevision) dismiss() }
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var text by rememberSaveable(profile?.id) { mutableStateOf(profile?.addresses?.joinToString("\n").orEmpty()) }
    LaunchedEffect(state.importedText) { state.importedText?.let { text = it; act(SoarAction.ClearFile) } }
    FormDialog(R.string.soar_add_resolvers, dismiss) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.soar_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(file, Modifier.weight(1f)) { Text(stringResource(R.string.soar_import_file)) }
            OutlinedButton({ text = "" }, Modifier.weight(1f)) { Text(stringResource(R.string.soar_clear)) }
        }
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.soar_addresses)) }, minLines = 7,
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(), supportingText = { Text(stringResource(R.string.soar_address_hint)) })
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { act(SoarAction.SaveResolvers(name, text, profile?.id)) },
            enabled = !state.saving && name.isNotBlank() && text.isNotBlank(), shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.soar_save)) }
    }
}

@Composable
internal fun SettingsSection(@StringRes title: Int, @StringRes hint: Int, initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "settings expansion")
    val colors = MaterialTheme.colorScheme
    val expansion = stringResource(if (open) R.string.soar_expanded else R.string.soar_collapsed)
    Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(.5.dp, colors.primary.copy(alpha = .2f))) {
        Column {
            Surface(onClick = { open = !open }, color = Color.Transparent, modifier = Modifier.semantics { stateDescription = expansion }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Icon(painterResource(R.drawable.ic_expand_more_24dp), null, Modifier.padding(start = 8.dp).size(22.dp)
                        .graphicsLayer { rotationZ = rotation }, tint = colors.primary)
                }
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}
