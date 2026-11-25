package com.v2ray.ang.ui.soar

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import com.v2ray.ang.R
import com.v2ray.ang.core.soar.SoarRuntimeSettings

private data class RuntimeNumber(@StringRes val label: Int, val value: Int, val update: (Int) -> SoarRuntimeSettings)

@Composable
internal fun RuntimeForm(value: SoarRuntimeSettings, enabled: Boolean, update: (SoarRuntimeSettings) -> Unit, valid: (Boolean) -> Unit, soar: Boolean) {
    val invalid = remember { mutableStateMapOf<Int, Boolean>() }
    val inputs = remember { mutableStateMapOf<Int, String>() }
    fun number(@StringRes label: Int, n: Int, change: (Int) -> SoarRuntimeSettings) = RuntimeNumber(label, n, change)
    @Composable fun fields(items: List<RuntimeNumber>) {
        val columns = if (LocalDensity.current.fontScale > 1.35f) 1 else 2
        items.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { item -> key(item.label) {
            val text = inputs[item.label] ?: item.value.toString()
            val label = stringResource(item.label)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(text, {
                inputs[item.label] = it
                val n = it.toIntOrNull()
                invalid[item.label] = n == null
                valid(invalid.values.none { error -> error })
                if (n != null) update(item.update(n))
            }, enabled = enabled,
                singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = text.toIntOrNull() == null)
            }
        } }
        if (row.size < columns) Spacer(Modifier.weight(1f))
        }
        }
    }
    @Composable fun toggle(@StringRes label: Int, checked: Boolean, change: (Boolean) -> SoarRuntimeSettings) {
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch,
            onValueChange = { update(change(it)) }).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked, null, enabled = enabled)
        }
    }
    SettingsSection(R.string.soar_runtime_mtu, R.string.soar_runtime_mtu_hint) {
        OutlinedTextField(value.name, { update(value.copy(name = it)) }, enabled = enabled,
            label = { Text(stringResource(R.string.soar_name)) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        fields(listOf(number(R.string.soar_vpn_mtu, value.vpnMtu) { value.copy(vpnMtu = it) }))
    }
    if (soar) SettingsSection(R.string.soar_tuning, R.string.soar_tuning_hint) {
        fields(listOf(
            number(R.string.soar_resolver_workers, value.resolverWorkers) { value.copy(resolverWorkers = it) },
            number(R.string.soar_resolver_retries, value.resolverRetries) { value.copy(resolverRetries = it) },
            number(R.string.soar_resolver_timeout, value.resolverTimeoutMs) { value.copy(resolverTimeoutMs = it) },
            number(R.string.soar_retry_delay, value.retryDelayMs) { value.copy(retryDelayMs = it) },
            number(R.string.soar_https_retries, value.httpsRetries) { value.copy(httpsRetries = it) },
            number(R.string.soar_https_timeout, value.httpsTimeoutMs) { value.copy(httpsTimeoutMs = it) },
            number(R.string.soar_process_workers, value.processWorkers) { value.copy(processWorkers = it) },
            number(R.string.soar_local_connections, value.localConnections) { value.copy(localConnections = it) },
            number(R.string.soar_copy_buffer, value.copyBufferKiB) { value.copy(copyBufferKiB = it) },
            number(R.string.soar_tcp_timeout, value.tcpTimeoutSeconds) { value.copy(tcpTimeoutSeconds = it) },
            number(R.string.soar_udp_timeout, value.udpTimeoutSeconds) { value.copy(udpTimeoutSeconds = it) },
        ))
    }
    SettingsSection(R.string.soar_local_proxy, R.string.soar_local_proxy_hint) {
        OutlinedTextField(value.listenIp, { update(value.copy(listenIp = it.trim())) }, enabled = enabled,
            label = { Text(stringResource(R.string.soar_listen_ip)) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        fields(listOf(number(R.string.soar_listen_port, value.listenPort) { value.copy(listenPort = it) }))
        toggle(R.string.soar_http_proxy, value.httpEnabled) { value.copy(httpEnabled = it) }
        if (value.httpEnabled) fields(listOf(number(R.string.soar_http_port, value.httpPort) { value.copy(httpPort = it) }))
        toggle(R.string.soar_authentication, value.authentication) { value.copy(authentication = it) }
        if (value.authentication) {
            OutlinedTextField(value.username, { update(value.copy(username = it)) }, enabled = enabled,
                label = { Text(stringResource(R.string.soar_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value.password, { update(value.copy(password = it)) }, enabled = enabled,
                label = { Text(stringResource(R.string.soar_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation())
        }
    }
    SettingsSection(R.string.soar_reliability, R.string.soar_reliability_hint) {
        if (soar) {
            fields(listOf(number(R.string.soar_watchdog, value.watchdogSeconds) { value.copy(watchdogSeconds = it) }))
            toggle(R.string.soar_warmup, value.warmup) { value.copy(warmup = it) }
            if (value.warmup) fields(listOf(number(R.string.soar_warmup_count, value.warmupProbes) { value.copy(warmupProbes = it) }))
        }
        Text(stringResource(R.string.soar_log_level), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("ERROR", "WARN", "INFO", "DEBUG").forEach { level ->
                FilterChip(value.logLevel == level, { update(value.copy(logLevel = level)) }, enabled = enabled,
                    label = { Text(level, style = MaterialTheme.typography.labelSmall) })
            }
        }
    }
    SettingsSection(R.string.soar_other_engines, R.string.soar_other_engines_hint) {
        Text(stringResource(R.string.soar_other_engines_detail), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
