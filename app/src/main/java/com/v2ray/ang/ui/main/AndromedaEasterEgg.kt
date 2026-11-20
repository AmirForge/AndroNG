package com.v2ray.ang.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.v2ray.ang.R
import kotlinx.coroutines.awaitCancellation
import kotlin.math.sin
import kotlin.random.Random

private data class Meteor(val x: Float, val y: Float, val start: Float, val duration: Float, val length: Float, val width: Float)

/** A temporary presentation overlay; it owns no core, network call, keys or service state. */
@Composable
internal fun AndromedaEasterEgg(onDismiss: () -> Unit) {
    val elapsed by produceState(0f) {
        var previous = withFrameNanos { it }
        while (value < 9f) {
            val now = withFrameNanos { it }
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (scale <= 0f) { value = -1f; awaitCancellation() }
            value += ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f) / scale
            previous = now
        }
    }
    val meteors = remember {
        val random = Random(709)
        List(42) {
            Meteor(random.nextFloat() * 1.7f - .35f, random.nextFloat() * .8f - .35f,
                .8f + random.nextFloat() * 5.4f, .7f + random.nextFloat() * 1.1f,
                .08f + random.nextFloat() * .18f, .6f + random.nextFloat() * 1.2f)
        }
    }
    val pink = MaterialTheme.colorScheme.primary
    val closeLabel = stringResource(R.string.andromeda_close)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f))
            .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) }) {
            AsyncImage(
                model = R.drawable.andromeda_easter_egg,
                contentDescription = stringResource(R.string.andromeda_art_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 24.dp)
                    .graphicsLayer {
                        alpha = if (elapsed < 0f) 1f else (elapsed / .8f).coerceIn(0f, 1f)
                        scaleX = if (elapsed < 0f) 1f else .96f + .04f * (elapsed / 1.2f).coerceIn(0f, 1f)
                        scaleY = scaleX
                    },
            )
            Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
                if (elapsed >= 0f) for (meteor in meteors) {
                    val t = (elapsed - meteor.start) / meteor.duration
                    if (t !in 0f..1f) continue
                    val visibility = sin(t * Math.PI).toFloat().coerceAtLeast(0f)
                    val tip = Offset((meteor.x + t * .75f) * size.width, (meteor.y + t * .85f) * size.height)
                    val tail = tip - Offset(size.width * meteor.length, size.height * meteor.length)
                    drawLine(Brush.linearGradient(listOf(Color.Transparent, pink.copy(alpha = visibility * .6f), Color.White.copy(alpha = visibility)), tail, tip),
                        tail, tip, strokeWidth = meteor.width.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = visibility), meteor.width.dp.toPx(), tip)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
                .background(Color.Black.copy(alpha = .35f), CircleShape).semantics { contentDescription = closeLabel }) {
                Canvas(Modifier.size(18.dp).clearAndSetSemantics {}) {
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), 1.7.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), 1.7.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
