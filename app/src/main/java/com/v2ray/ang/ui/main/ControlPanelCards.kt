package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.ControlPanelBanner
import com.v2ray.ang.handler.ControlPanelUpdate

@Composable
internal fun ControlPanelBannerCard(
    banner: ControlPanelBanner,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val image = remember(banner.image) { banner.image.asImageBitmap() }
    val imageOnly = banner.title.isBlank() && banner.text.isBlank()
    val action = banner.url?.let { url ->
        Modifier.clickable(role = Role.Button) { onAction(MainAction.OpenSubscriptionLink(url)) }
    } ?: Modifier
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(0.75.dp, colors.primary.copy(alpha = 0.58f)),
    ) {
        Row(
            modifier = action.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = image,
                contentDescription = if (imageOnly) stringResource(R.string.control_panel_sponsored) else null,
                modifier = (if (imageOnly) Modifier.weight(1f) else Modifier.width(108.dp))
                    .height(68.dp).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Fit,
            )
            if (!imageOnly) {
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(stringResource(R.string.control_panel_sponsored), color = colors.primary,
                        style = MaterialTheme.typography.labelSmall)
                    if (banner.title.isNotBlank()) {
                        Text(banner.title, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (banner.text.isNotBlank()) {
                        Text(banner.text, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ControlPanelUpdateDialog(update: ControlPanelUpdate, onAction: (MainAction) -> Unit) {
    val dismiss = { onAction(MainAction.DismissControlPanelUpdate(update.versionCode)) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(update.title.ifBlank { stringResource(R.string.update_new_version_found, update.versionCode) })
        },
        text = if (update.text.isBlank()) null else ({ Text(update.text) }),
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                onAction(MainAction.OpenSubscriptionLink(update.url))
            }) { Text(stringResource(R.string.update_now)) }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
