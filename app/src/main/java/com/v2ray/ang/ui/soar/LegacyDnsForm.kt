package com.v2ray.ang.ui.soar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.v2ray.ang.R
import androidx.compose.ui.unit.dp
import com.v2ray.ang.core.soar.SoarRuntimeSettings
import com.v2ray.ang.core.soar.legacyDnsFields
import kotlin.math.roundToInt

@Composable
internal fun LegacyProfileDialog(initial: SoarRuntimeSettings, dismiss: () -> Unit, embedded: Boolean = false, enabled: Boolean = true, save: (SoarRuntimeSettings) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial.copy(name = initial.name.takeUnless { it == "Default" } ?: "Custom Advanced")) }
    var listenPort by remember(initial) { mutableStateOf(initial.listenPort.toString()) }
    var httpPort by remember(initial) { mutableStateOf(initial.httpPort.toString()) }
    val completed = draft.copy(name = draft.name.trim(), listenPort = listenPort.toIntOrNull() ?: 0,
        httpPort = httpPort.toIntOrNull() ?: 0)
    val content: @Composable () -> Unit = {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.soar_create_settings), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, singleLine = true,
                        label = { Text(stringResource(R.string.soar_name)) }, modifier = Modifier.fillMaxWidth())
                    LegacyDnsForm(draft, enabled, update = { draft = it }, profileDialog = true) {
                        Text("Local Proxy", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(draft.listenIp, { draft = draft.copy(listenIp = it) },
                                label = { Text("Listen IP") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(listenPort, { listenPort = it }, label = { Text("Listen Port") },
                                singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        LegacyToggle("HTTP Proxy", draft.httpEnabled) { draft = draft.copy(httpEnabled = it) }
                        if (draft.httpEnabled) OutlinedTextField(httpPort, { httpPort = it }, label = { Text("HTTP Port") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        LegacyToggle("SOCKS5 Authentication", draft.authentication) { draft = draft.copy(authentication = it) }
                        if (draft.authentication) {
                            OutlinedTextField(draft.username, { draft = draft.copy(username = it) }, label = { Text("Username") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.password, { draft = draft.copy(password = it) }, label = { Text("Password") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                        }
                    }
                    var levels by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { levels = true }, modifier = Modifier.fillMaxWidth()) { Text("Log Level · ${draft.logLevel}") }
                        DropdownMenu(levels, { levels = false }) {
                            listOf("DEBUG", "INFO", "WARN", "ERROR").forEach { level ->
                                DropdownMenuItem(text = { Text(level) }, onClick = {
                                    draft = draft.copy(logLevel = level)
                                    levels = false
                                })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text(stringResource(android.R.string.cancel)) }
                    Button(onClick = { save(completed) }, enabled = enabled && completed.valid(), modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.soar_save))
                    }
                }
            }
        }
    }
    if (embedded) Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    else Dialog(onDismissRequest = dismiss) { content() }
}

@Composable
private fun LegacyToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
internal fun LegacyDnsForm(runtime: SoarRuntimeSettings, enabled: Boolean, update: (SoarRuntimeSettings) -> Unit, profileDialog: Boolean = false, localProxy: @Composable () -> Unit = {}) {
    val overrides = runtime.legacyOverrides.orEmpty()
    fun change(key: String, value: String) = update(runtime.copy(legacyOverrides = overrides + (key to value)))
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    val additionalFields = setOf("MAX_ACTIVE_STREAMS", "LOCAL_HANDSHAKE_TIMEOUT_SECONDS", "TRAFFIC_KEEPALIVE_INTERVAL_SECONDS")
    legacyDnsFields.filter {
        !profileDialog || (it.key !in additionalFields &&
            (it.key != "TRAFFIC_WARMUP_PROBE_COUNT" || overrides["TRAFFIC_WARMUP_ENABLED"] == "true"))
    }.groupBy { it.section }.forEach { (title, fields) ->
        if (title == "Network Tuning") localProxy()
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val fullWidth = setOf("MTU_TEST_PARALLELISM_RESOLVERS", "MTU_TEST_PARALLELISM_LOGS", "RESOLVER_BALANCING_STRATEGY", "BASE_ENCODE_DATA", "PING_WATCHDOG_TIMEOUT_SECONDS", "TRAFFIC_WARMUP_ENABLED")
                val rows = buildList {
                    var pending = emptyList<com.v2ray.ang.core.soar.LegacyDnsField>()
                    for (field in fields) {
                        if (field.key in fullWidth) {
                            if (pending.isNotEmpty()) add(pending)
                            pending = emptyList()
                            add(listOf(field))
                        } else {
                            pending = pending + field
                            if (pending.size == columns) {
                                add(pending)
                                pending = emptyList()
                            }
                        }
                    }
                    if (pending.isNotEmpty()) add(pending)
                }
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { field ->
                            val value = overrides[field.key] ?: field.default
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(field.label, style = MaterialTheme.typography.bodySmall)
                                val choices = when (field.key) {
                                    "RESOLVER_BALANCING_STRATEGY" -> listOf("1" to "Random", "2" to "Round Robin", "3" to "Least Loss", "4" to "Lowest Latency")
                                    "UPLOAD_COMPRESSION_TYPE", "DOWNLOAD_COMPRESSION_TYPE" ->
                                        listOf("0" to "Off", "1" to "ZSTD", "2" to "LZ4", "3" to "ZLIB")
                                    else -> emptyList()
                                }
                                if (choices.isNotEmpty()) {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                                            Text(choices.firstOrNull { it.first == value }?.second ?: value)
                                        }
                                        DropdownMenu(expanded, { expanded = false }) {
                                            choices.forEach { (code, label) ->
                                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                                    change(field.key, code)
                                                    expanded = false
                                                })
                                            }
                                        }
                                    }
                                } else if (field.key == "MTU_TEST_PARALLELISM_RESOLVERS") {
                                    Text(value, style = MaterialTheme.typography.labelLarge)
                                    Slider((value.toFloatOrNull() ?: 100f).coerceIn(50f, 1000f),
                                        { change(field.key, it.roundToInt().toString()) }, enabled = enabled,
                                        valueRange = 50f..1000f, steps = 18)
                                    if (profileDialog) Text(stringResource(R.string.legacy_mtu_parallel_hint),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (field.default == "false") {
                                    Switch(value == "true", { change(field.key, it.toString()) },
                                        enabled = enabled, modifier = Modifier.align(Alignment.Start))
                                } else {
                                    OutlinedTextField(value, { change(field.key, it) }, singleLine = true,
                                        enabled = enabled, isError = !field.accepts(value), modifier = Modifier.fillMaxWidth(),
                                        keyboardOptions = KeyboardOptions(keyboardType =
                                            if ('.' in field.default) KeyboardType.Decimal else KeyboardType.Number))
                                }
                            }
                        }
                        if (row.size < columns && row.none { it.key in fullWidth }) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
