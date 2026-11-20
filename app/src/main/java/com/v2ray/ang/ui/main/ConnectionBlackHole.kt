package com.v2ray.ang.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class BlackHoleStar(val angle: Float, val radius: Float, val brightness: Float)

/** Geometry is expressed in units of the shared galaxy core radius. */
internal class ConnectionBlackHole(
    rose: Color,
    violet: Color,
    private val starlight: Color,
    background: Color,
    onBackground: Color,
) {
    private val darkness = if (background.luminance() > 0.5f) {
        lerp(background, onBackground, 0.31f)
    } else {
        lerp(background, Color(0xFF0D0812), 0.78f)
    }
    private val shadow = Brush.radialGradient(
        0f to darkness,
        0.67f to darkness.copy(alpha = 0.83f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = 1.02f,
    )
    private val halo = Brush.radialGradient(
        0f to violet.copy(alpha = 0.05f),
        0.46f to rose.copy(alpha = 0.21f),
        0.69f to violet.copy(alpha = 0.09f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = 1.46f,
    )
    private val plasma = Brush.linearGradient(
        0f to Color.Transparent,
        0.23f to violet.copy(alpha = 0.66f),
        0.51f to rose.copy(alpha = 0.82f),
        0.65f to lerp(rose, starlight, 0.47f),
        0.82f to rose.copy(alpha = 0.44f),
        1f to Color.Transparent,
        start = Offset(-2.65f, 0.46f),
        end = Offset(2.65f, -0.43f),
    )
    private val lens = Path().apply {
        moveTo(-1.36f, 0.36f)
        cubicTo(-1.04f, 0.09f, -0.91f, -0.92f, -0.27f, -1f)
        cubicTo(0.42f, -1.17f, 0.84f, -0.54f, 1.22f, -0.12f)
    }
    private val disk = Path().apply {
        moveTo(-2.65f, 0.46f)
        cubicTo(-1.54f, 0.79f, -0.33f, 0.58f, 0.63f, 0.13f)
        cubicTo(1.31f, -0.19f, 2.03f, -0.47f, 2.65f, -0.43f)
    }
    private val trail = Path().apply {
        moveTo(-2.41f, 0.64f)
        cubicTo(-1.24f, 0.97f, 0.04f, 0.63f, 0.85f, 0.24f)
        cubicTo(1.53f, -0.09f, 2.05f, -0.34f, 2.49f, -0.34f)
    }
    private val dust = Random(83).let { random ->
        List(23) {
            val x = (random.nextFloat() - 0.5f) * 5.4f
            Offset(x, 0.23f - x * 0.17f + (random.nextFloat() - 0.5f) * 0.72f)
        }
    }
    private val glowStroke = Stroke(0.29f, cap = StrokeCap.Round)
    private val plasmaStroke = Stroke(0.075f, cap = StrokeCap.Round)
    private val fineStroke = Stroke(0.047f, cap = StrokeCap.Round)
    private val orbitStars = Random(149).let { random ->
        List(48) { index ->
            BlackHoleStar(
                angle = (index + random.nextFloat() * 0.65f) * (2 * PI).toFloat() / 48,
                radius = 0.013f + random.nextFloat() * 0.023f,
                brightness = 0.13f + random.nextFloat() * 0.19f,
            )
        }
    }

    private fun orbitPoint(angle: Float): Offset {
        val x = cos(angle)
        return Offset(x * 2.15f, sin(angle) * 0.77f - x * 0.32f)
    }

    fun draw(scope: DrawScope, center: Offset, radius: Float, opacity: Float, phase: Float) = with(scope) {
        withTransform({
            translate(center.x + cos(phase) * radius * 0.035f, center.y + sin(phase) * radius * 0.045f)
            rotate(sin(phase) * 1.6f, pivot = Offset.Zero)
            scale(radius, radius, pivot = Offset.Zero)
        }) {
            scale(1.9f, 0.83f, pivot = Offset.Zero) {
                drawCircle(halo, radius = 1.46f, center = Offset.Zero, alpha = opacity)
            }
            for (segment in 0 until 72) {
                val angle = phase + segment * (2 * PI).toFloat() / 72
                val fade = 0.045f + 0.045f * (0.5f + 0.5f * sin(angle - phase))
                drawLine(
                    starlight,
                    orbitPoint(angle),
                    orbitPoint(angle + (2 * PI).toFloat() / 72),
                    strokeWidth = 0.015f,
                    cap = StrokeCap.Round,
                    alpha = opacity * fade,
                )
            }
            for (star in orbitStars) {
                val angle = star.angle + phase
                val position = orbitPoint(angle)
                val shimmer = 0.78f + 0.22f * sin(phase * 2f + star.angle)
                drawCircle(starlight, star.radius, position, alpha = opacity * star.brightness * shimmer)
            }
            drawCircle(shadow, radius = 1.02f, center = Offset.Zero, alpha = opacity * 0.64f)
            drawPath(lens, plasma, alpha = opacity * 0.19f, style = glowStroke)
            drawPath(lens, plasma, alpha = opacity * 0.43f, style = fineStroke)
            drawPath(disk, plasma, alpha = opacity * 0.21f, style = glowStroke)
            drawPath(disk, plasma, alpha = opacity * 0.63f, style = plasmaStroke)
            drawPath(trail, plasma, alpha = opacity * 0.28f, style = fineStroke)
            dust.forEachIndexed { index, point ->
                val fade = (1f - abs(point.x) / 2.9f).coerceAtLeast(0f)
                drawCircle(starlight, 0.019f + (index % 3) * 0.012f, point, alpha = opacity * fade * 0.38f)
            }
        }
    }
}
