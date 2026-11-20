package com.v2ray.ang.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val AETHER_MESSAGE_INTERVAL_MILLIS = 3_000L

internal val progressMessages = listOf(
    R.string.aether_wait_stars,
    R.string.aether_wait_galaxy,
    R.string.aether_wait_sparkle,
    R.string.aether_wait_orbit,
    R.string.aether_wait_cosmos_05,
    R.string.aether_wait_cosmos_06,
    R.string.aether_wait_cosmos_07,
    R.string.aether_wait_cosmos_08,
    R.string.aether_wait_cosmos_09,
    R.string.aether_wait_cosmos_10,
    R.string.aether_wait_cosmos_11,
    R.string.aether_wait_cosmos_12,
    R.string.aether_wait_cosmos_13,
    R.string.aether_wait_cosmos_14,
    R.string.aether_wait_cosmos_15,
    R.string.aether_wait_cosmos_16,
    R.string.aether_wait_cosmos_17,
    R.string.aether_wait_cosmos_18,
    R.string.aether_wait_cosmos_19,
    R.string.aether_wait_cosmos_20,
    R.string.aether_wait_cosmos_21,
    R.string.aether_wait_cosmos_22,
    R.string.aether_wait_cosmos_23,
    R.string.aether_wait_cosmos_24,
    R.string.aether_wait_cosmos_25,
    R.string.aether_wait_cosmos_26,
    R.string.aether_wait_cosmos_27,
    R.string.aether_wait_cosmos_28,
    R.string.aether_wait_cosmos_29,
    R.string.aether_wait_cosmos_30,
    R.string.aether_wait_cosmos_31,
    R.string.aether_wait_cosmos_32,
)

internal fun aetherProgressMessage(elapsedMillis: Long): Int =
    progressMessages[((elapsedMillis.coerceAtLeast(0) / AETHER_MESSAGE_INTERVAL_MILLIS) % progressMessages.size).toInt()]

@Composable
internal fun AetherProgressPanel(progress: AetherProgress, modifier: Modifier = Modifier, onStop: () -> Unit = {}) {
    val message by produceState(aetherProgressMessage(progress.startedAt.elapsedNow().inWholeMilliseconds), progress.startedAt) {
        while (isActive) {
            val elapsed = progress.startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
            value = aetherProgressMessage(elapsed)
            delay(AETHER_MESSAGE_INTERVAL_MILLIS - elapsed % AETHER_MESSAGE_INTERVAL_MILLIS)
        }
    }
    val accessibleStatus = stringResource(
        if (progress.message == R.string.aether_progress_saving ||
            progress.message == R.string.aether_import_progress_finishing
        ) R.string.aether_progress_saving else R.string.aether_import_progress_working
    )
    Surface(
        modifier = modifier.widthIn(max = 460.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Crossfade(
                targetState = message,
                modifier = Modifier.weight(1f).clearAndSetSemantics {},
                animationSpec = tween(450),
                label = "Space message transition",
            ) { messageId ->
                Text(
                    text = stringResource(messageId),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleSmall,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onStop) {
                Icon(painterResource(R.drawable.ic_stop_24dp), contentDescription = stringResource(R.string.stop_preparation), tint = MaterialTheme.colorScheme.primary)
            }
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibleStatus },
            )
        }
    }
}
