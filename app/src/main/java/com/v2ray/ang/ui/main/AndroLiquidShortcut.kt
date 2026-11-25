package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import kotlin.math.sin

@Composable
internal fun AndroLiquidShortcut(
    label: Int, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val deformation = remember { Animatable(1f) }
    var previousLabel by remember { mutableIntStateOf(label) }
    LaunchedEffect(label) {
        if (label != previousLabel) {
            previousLabel = label
            deformation.snapTo(0f)
            deformation.animateTo(1f, spring(dampingRatio = .72f, stiffness = 115f))
        }
    }
    val face = Brush.verticalGradient(
        listOf(
            lerp(colors.surfaceContainerHighest, colors.primary, if (enabled) .23f else .10f),
            colors.surfaceContainerHigh,
            colors.surfaceContainerLow,
        )
    )
    val rim = Brush.verticalGradient(
        listOf(colors.primary.copy(alpha = if (enabled) .60f else .30f), colors.outlineVariant)
    )
    Surface(
        modifier = modifier.graphicsLayer {
            val stretch = sin(deformation.value * Math.PI.toFloat())
            scaleX = 1f + stretch * .11f
            scaleY = 1f - stretch * .13f
            translationY = (-stretch * 5f).dp.toPx()
        },
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        shadowElevation = if (enabled) 8.dp else 3.dp,
        border = BorderStroke(1.dp, rim),
    ) {
        Box(Modifier.background(face)) {
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    ((fadeIn(tween(210, delayMillis = 75)) +
                        slideInHorizontally(spring(dampingRatio = .78f, stiffness = 250f)) { it / 7 })
                        togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(170)) { -it / 8 }))
                        .using(SizeTransform { _, _ -> spring(dampingRatio = .78f, stiffness = 195f) })
                },
                label = "Andro shortcut label",
            ) { current ->
                val icon = when (current) {
                    R.string.control_panel_free_config -> R.drawable.ic_cloud_download_24dp
                    R.string.subscription_expired_header -> R.drawable.ic_stop_24dp
                    else -> R.drawable.ic_refresh_24dp
                }
                Row(
                    Modifier.heightIn(min = 50.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(painterResource(icon), null, Modifier.size(18.dp), tint = colors.primary)
                    Text(stringResource(current), color = colors.onSurface,
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
