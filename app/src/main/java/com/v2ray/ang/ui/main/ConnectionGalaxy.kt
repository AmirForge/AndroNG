package com.v2ray.ang.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private val TURN = (2 * PI).toFloat()
private const val ARM_COUNT = 3

private data class GalaxyStar(val position: Offset, val radius: Float, val brightness: Float, val phase: Float)

private fun orbit(radius: Float, angle: Float) = Offset(cos(angle) * radius, sin(angle) * radius)

private fun armAngle(arm: Int, radius: Float) = arm * TURN / ARM_COUNT + 4.8f * radius

@Composable
internal fun ConnectionGalaxy(animated: Boolean, modifier: Modifier = Modifier, energised: Boolean = false) {
    val openness = animateFloatAsState(
        targetValue = if (animated) 1f else 0f,
        animationSpec = if (animated) tween(460, easing = LinearOutSlowInEasing)
            else tween(740, easing = FastOutSlowInEasing),
        label = "Galaxy expansion",
    )
    val orbitSpeed = animateFloatAsState(
        targetValue = if (energised) 5f else 1f,
        animationSpec = tween(700, easing = LinearEasing),
        label = "Orbital speed",
    )
    val idleOrbit = produceState(0f) {
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = now
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (scale > 0f) value = (value + elapsed * TURN / 84f * orbitSpeed.value / scale) % TURN
        }
    }
    val dormantArms = remember {
        List(2) { arm ->
            List(48) { index ->
                val radius = 0.22f + index * 0.74f / 47
                orbit(radius, arm * PI.toFloat() + radius * 4.3f)
            }
        }
    }
    val dormantStars = remember {
        val random = Random(171)
        List(142) { index ->
            val radius = 0.24f + random.nextFloat() * 0.72f
            val angle = index % 2 * PI.toFloat() + radius * 4.3f + (random.nextFloat() - 0.5f) * 0.45f
            GalaxyStar(orbit(radius, angle), 0.35f + random.nextFloat() * 0.55f, 0.15f + random.nextFloat() * 0.19f, random.nextFloat() * TURN)
        }
    }
    val arms = remember {
        List(ARM_COUNT) { arm ->
            List(25) { i ->
                val radius = 0.08f + i * 0.88f / 24
                orbit(radius, armAngle(arm, radius))
            }
        }
    }
    val stars = remember {
        val random = Random(42)
        List(238) { i ->
            val radius = 0.08f + random.nextFloat().pow(1.25f) * 0.92f
            val angle = armAngle(i % ARM_COUNT, radius) + (random.nextFloat() - 0.5f) * 0.95f
            val size = 0.24f + random.nextFloat().pow(1.8f) * 1.05f
            val brightness = (0.34f + random.nextFloat() * 0.66f) * (1f - 0.38f * radius)
            GalaxyStar(orbit(radius, angle), size, brightness, random.nextFloat() * TURN)
        }
    }
    val field = remember {
        val random = Random(73)
        List(62) {
            val radius = 0.25f + random.nextFloat() * 0.83f
            GalaxyStar(orbit(radius, random.nextFloat() * TURN), 0.25f + random.nextFloat() * 0.39f, 0.18f + random.nextFloat() * 0.38f, random.nextFloat() * TURN)
        }
    }
    val colors = MaterialTheme.colorScheme
    val rose = colors.primary
    val violet = colors.secondary
    val starlight = lerp(rose, colors.onSurface, 0.72f)

    Box(
        modifier.drawWithCache {
            val nucleus = Offset(size.width * 0.5f, size.height * 0.49f)
            val spreadX = size.width * 0.44f
            val spreadY = size.height * 0.38f
            val glowRadius = (size.minDimension * 0.56f).coerceAtLeast(1f)
            val coreRadius = (glowRadius * 0.32f).coerceAtLeast(1f)
            val nebula = Brush.radialGradient(
                0f to rose.copy(alpha = 0.33f),
                0.36f to violet.copy(alpha = 0.18f),
                1f to Color.Transparent,
                center = nucleus,
                radius = glowRadius,
            )
            val core = Brush.radialGradient(
                0f to starlight,
                0.23f to rose.copy(alpha = 0.74f),
                1f to Color.Transparent,
                center = nucleus,
                radius = coreRadius,
            )
            val outerStroke = 4.dp.toPx()
            val innerStroke = 0.6.dp.toPx()
            val blackHole = ConnectionBlackHole(rose, violet, starlight, colors.background, colors.onBackground)

            fun project(point: Offset, cosine: Float, sine: Float, open: Float): Offset {
                val x = point.x * cosine - point.y * sine
                val y = point.x * sine + point.y * cosine
                return nucleus + Offset(x * spreadX, (y - x * 0.19f) * spreadY) * open
            }

            onDrawBehind {
                val open = openness.value
                val hole = (1f - open / 0.68f).coerceIn(0f, 1f)
                if (hole > 0f) {
                    val orbitAngle = idleOrbit.value * 2f
                    val idleCosine = cos(orbitAngle)
                    val idleSine = sin(orbitAngle)
                    drawRect(nebula, alpha = hole * 0.14f)
                    dormantArms.forEach { points ->
                        var start = project(points.first(), idleCosine, idleSine, 0.88f)
                        for (index in 1..points.lastIndex) {
                            val end = project(points[index], idleCosine, idleSine, 0.88f)
                            val fade = sin(index.toFloat() / points.lastIndex * PI.toFloat()).coerceAtLeast(0f)
                            drawLine(rose, start, end, 2.4.dp.toPx(), StrokeCap.Butt, alpha = hole * fade * 0.055f)
                            drawLine(starlight, start, end, 0.4.dp.toPx(), StrokeCap.Butt, alpha = hole * fade * 0.14f)
                            start = end
                        }
                    }
                    dormantStars.forEachIndexed { index, star ->
                        val point = project(star.position, idleCosine, idleSine, 0.88f)
                        val shimmer = 0.77f + 0.23f * sin(orbitAngle + star.phase)
                        val alpha = hole * star.brightness * shimmer
                        val radius = star.radius.dp.toPx()
                        drawCircle(starlight, radius, point, alpha = alpha)
                        if (index % 29 == 0) {
                            drawLine(rose, point - Offset(radius * 2.6f, 0f), point + Offset(radius * 2.6f, 0f), 0.5.dp.toPx(), alpha = alpha * 0.75f)
                            drawLine(rose, point - Offset(0f, radius * 2.6f), point + Offset(0f, radius * 2.6f), 0.5.dp.toPx(), alpha = alpha * 0.75f)
                        }
                    }
                    blackHole.draw(
                        scope = this,
                        center = nucleus,
                        radius = coreRadius * (0.82f + 0.26f * hole),
                        opacity = hole * hole * (3f - 2f * hole),
                        phase = idleOrbit.value,
                    )
                }
                if (open <= 0f) return@onDrawBehind
                val angle = idleOrbit.value * 3f
                val twist = (1f - open) * 3.1f
                val cosine = cos(angle + twist)
                val sine = sin(angle + twist)
                val driftCosine = cos(-angle + twist)
                val driftSine = sin(-angle + twist)
                val power = open * (2f - open)
                drawRect(nebula, alpha = power)
                for (star in field) {
                    val twinkle = 0.65f + 0.35f * sin(angle * 2 + star.phase)
                    drawCircle(starlight, star.radius.dp.toPx(), project(star.position, driftCosine, driftSine, open), alpha = star.brightness * power * twinkle)
                }
                arms.forEachIndexed { index, points ->
                    val tint = if (index == 1) violet else rose
                    var start = project(points.first(), cosine, sine, open)
                    for (segment in 1..points.lastIndex) {
                        val end = project(points[segment], cosine, sine, open)
                        val t = (segment.toFloat() / points.lastIndex - 0.58f).coerceAtLeast(0f) / 0.42f
                        val fade = 1f - t * t * (3f - 2f * t)
                        drawLine(tint, start, end, outerStroke, StrokeCap.Butt, alpha = power * fade * 0.11f)
                        drawLine(tint, start, end, innerStroke, StrokeCap.Butt, alpha = power * fade * 0.37f)
                        start = end
                    }
                }
                stars.forEachIndexed { index, star ->
                    val twinkle = 0.69f + 0.31f * sin(angle * 3 + star.phase)
                    val opacity = star.brightness * power * twinkle
                    val position = project(star.position, cosine, sine, open)
                    val radius = star.radius.dp.toPx()
                    if (index % 23 == 0) {
                        drawCircle(rose, radius * 2.7f, position, alpha = opacity * 0.23f)
                        drawLine(starlight, position - Offset(radius * 2.6f, 0f), position + Offset(radius * 2.6f, 0f), 0.55.dp.toPx(), alpha = opacity)
                        drawLine(starlight, position - Offset(0f, radius * 2.2f), position + Offset(0f, radius * 2.2f), 0.55.dp.toPx(), alpha = opacity)
                    }
                    drawCircle(starlight, radius, position, alpha = opacity)
                }
                val flare = if (animated) (1f - abs(open - 0.40f) / 0.31f).coerceAtLeast(0f) else 0f
                drawRect(core, alpha = (power * (0.88f + 0.12f * sin(angle * 2)) + flare * 0.7f).coerceAtMost(1f))
            }
        }
    )
}
