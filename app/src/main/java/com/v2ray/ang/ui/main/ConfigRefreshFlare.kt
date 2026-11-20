package com.v2ray.ang.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.TimeMark

private data class ConfigSpark(
    val delay: Float,
    val life: Float,
    val x: Float,
    val rise: Float,
    val drift: Float,
    val radius: Float,
    val spin: Float,
    val light: Float,
)

/** Drawn above the list so sparks can rise from the card into the header. */
@Composable
internal fun ConfigRefreshFlare(originInRoot: Rect, startedAt: TimeMark, modifier: Modifier) {
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    val elapsed = produceState(0f, startedAt) {
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = now
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (scale > 0f) value += seconds / scale
        }
    }
    val sparks = remember(startedAt) {
        val random = Random.Default
        List(64) {
            ConfigSpark(
                delay = random.nextFloat() * 0.7f,
                life = 1.7f + random.nextFloat() * 1.4f,
                x = random.nextFloat(),
                rise = 125f + random.nextFloat() * 155f,
                drift = random.nextFloat() * 48f - 24f,
                radius = 1.1f + random.nextFloat() * 2f,
                spin = random.nextFloat() * 100f - 50f,
                light = 0.2f + random.nextFloat() * 0.65f,
            )
        }
    }
    val star = remember {
        Path().apply {
            for (index in 0 until 10) {
                val angle = (index - 2.5) * PI / 5
                val radius = if (index % 2 == 0) 1f else 0.42f
                val x = cos(angle).toFloat() * radius
                val y = sin(angle).toFloat() * radius
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.onGloballyPositioned { canvasOrigin = it.positionInRoot() }) {
        val left = originInRoot.left - canvasOrigin.x
        val top = originInRoot.top - canvasOrigin.y
        for (spark in sparks) {
            val sinceBirth = elapsed.value - spark.delay
            if (sinceBirth < 0f) continue
            val age = (sinceBirth % spark.life) / spark.life
            val x = left + originInRoot.width * spark.x + spark.drift.dp.toPx() * sin(age * 2.7f) * age
            val y = top + 8.dp.toPx() - spark.rise.dp.toPx() * age * (1.15f - 0.15f * age)
            val radius = spark.radius.dp.toPx() * (1f - age * 0.55f)
            val opacity = (age * 10f).coerceAtMost(1f) * (1f - age) * (1f - age)
            val tint = lerp(colors.primary, colors.onSurface, spark.light)
            drawCircle(tint, radius * 2.4f, Offset(x, y), alpha = opacity * 0.12f)
            withTransform({
                translate(x, y)
                rotate(spark.spin * age, pivot = Offset.Zero)
                scale(radius, radius, pivot = Offset.Zero)
            }) {
                drawPath(star, tint.copy(alpha = opacity))
            }
        }
    }
}
