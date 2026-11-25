package com.v2ray.ang.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import kotlin.math.sin

@Composable
internal fun StarredDelayResult() {
    val progress = rememberInfiniteTransition(label = "Delay result pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1750, easing = LinearEasing)),
        label = "Delay result sparkles",
    )
    val label = stringResource(R.string.real_delay_test_passed)
    val lightCard = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > 0.5f
    val sparkle = remember {
        Path().apply {
            moveTo(0f, -1f)
            lineTo(0.24f, -0.24f)
            lineTo(1f, 0f)
            lineTo(0.24f, 0.24f)
            lineTo(0f, 1f)
            lineTo(-0.24f, 0.24f)
            lineTo(-1f, 0f)
            lineTo(-0.24f, -0.24f)
            close()
        }
    }
    Canvas(Modifier.size(width = 39.dp, height = 16.dp).semantics { contentDescription = label }) {
        val pink = Color(0xFFF8A6CF)
        val middle = Offset(size.width * 0.58f, size.height * 0.52f)
        val motion = progress.value
        val pulse = sin(motion * Math.PI).toFloat()
        val glow = pulse * pulse
        val start = middle + Offset(-4.dp.toPx(), 0.dp.toPx())
        val bend = middle + Offset(-1.dp.toPx(), 3.dp.toPx())
        val end = middle + Offset(5.dp.toPx(), -4.dp.toPx())
        if (lightCard) {
            val outline = Color(0xFF803357)
            drawLine(outline, start, bend, 3.5.dp.toPx(), StrokeCap.Round)
            drawLine(outline, bend, end, 3.5.dp.toPx(), StrokeCap.Round)
        }
        val stroke = (1.8f + 0.28f * glow).dp.toPx()
        drawLine(pink, start, bend, stroke, StrokeCap.Round)
        drawLine(pink, bend, end, stroke, StrokeCap.Round)

        if (glow > 0f) {
            val distance = 0.4f + motion * 0.6f
            for ((direction, radius) in Sparkles) {
                val point = middle + direction * (size.height * distance)
                val arm = radius.dp.toPx() * (0.6f + glow * 0.4f)
                withTransform({
                    translate(point.x, point.y)
                    scale(arm, arm, pivot = Offset.Zero)
                }) {
                    drawPath(sparkle, pink.copy(alpha = glow * 0.85f))
                }
            }
        }
    }
}

private val Sparkles = listOf(
    Offset(-0.91f, -0.23f) to 2.1f,
    Offset(-0.69f, 0.25f) to 1.5f,
    Offset(0.75f, -0.22f) to 1.9f,
    Offset(0.83f, 0.19f) to 1.3f,
)

@Composable
internal fun FailedDelayResult() {
    val progress = rememberInfiniteTransition(label = "Failed delay result").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing)),
        label = "Failure shake",
    )
    val label = stringResource(R.string.toast_failure)
    val light = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > 0.5f
    val color = if (light) Color(0xFF6A2787) else Color(0xFFD085F0)
    Canvas(Modifier.size(width = 39.dp, height = 16.dp).semantics { contentDescription = label }) {
        val phase = progress.value
        val shake = if (phase < 0.35f) sin(phase * 58f) * (1f - phase / 0.35f) else 0f
        val center = Offset(size.width * 0.58f + 2.dp.toPx() * shake, size.height * 0.52f)
        val arm = 4.dp.toPx()
        val stroke = 2.4.dp.toPx()
        drawLine(color, center + Offset(-arm, -arm), center + Offset(arm, arm), stroke, StrokeCap.Round)
        drawLine(color, center + Offset(-arm, arm), center + Offset(arm, -arm), stroke, StrokeCap.Round)
    }
}
