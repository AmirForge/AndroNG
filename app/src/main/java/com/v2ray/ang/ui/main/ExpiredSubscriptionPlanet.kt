package com.v2ray.ang.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
internal fun ExpiredSubscriptionPlanet(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val orbit = rememberInfiniteTransition(label = "Expired subscription orbit").animateFloat(
        0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "Planet phase",
    )
    val stars = remember {
        val random = Random(29)
        List(24) { Offset(random.nextFloat(), random.nextFloat()) }
    }
    val shards = remember {
        val random = Random(83)
        List(9) { index ->
            Path().apply {
                val count = 5 + index % 3
                repeat(count) { vertex ->
                    val angle = (vertex * 2.0 * Math.PI / count).toFloat()
                    val distance = .58f + random.nextFloat() * .42f
                    val x = cos(angle) * distance
                    val y = sin(angle) * distance * (.52f + index % 4 * .10f)
                    if (vertex == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }
    val rose = colors.primary
    val dark = colors.surfaceContainerLowest
    val rock = remember(rose, dark) {
        Brush.linearGradient(listOf(lerp(dark, rose, .72f), lerp(dark, rose, .21f), dark),
            start = Offset(-1f, -1f), end = Offset(1f, 1f))
    }
    Canvas(modifier) {
        val center = Offset(size.width * .5f, size.height * .54f)
        val radius = minOf(size.height * .34f, size.width * .16f)
        if (radius <= 0f) return@Canvas
        val phase = orbit.value * (2 * Math.PI).toFloat()
        stars.forEachIndexed { index, star ->
            val wave = sin(phase + index * 2.4f)
            val drift = (if (index % 2 == 0) 1 else -1) * (.09f + index % 5 * .026f)
            val point = Offset(star.x * size.width + wave * radius * drift,
                star.y * size.height + cos(phase + index) * radius * .12f)
            val dot = radius * (.018f + index % 3 * .009f)
            drawCircle(rose.copy(alpha = .08f + wave * .045f), dot * 2.7f, point)
            drawCircle(rose.copy(alpha = .32f + wave * .16f), dot, point)
        }
        val ringSize = Size(radius * 3.85f, radius * 1.37f)
        val ringTop = center - Offset(ringSize.width / 2, ringSize.height / 2)
        fun fragment(index: Int, front: Boolean) {
            val angle = phase + index * .698f + sin(phase + index) * .14f
            val depth = sin(angle)
            if ((depth >= 0f) != front) return
            val point = center + Offset(cos(angle) * radius * (1.54f + index % 4 * .16f),
                depth * radius * (.55f + index % 3 * .09f) + sin(phase * 2f + index) * radius * .08f)
            val edge = radius * ((if (index < 4) .16f + index * .022f else .052f + index % 3 * .019f) * (1f + depth * .17f))
            rotate(-21f, center) {
                translate(point.x, point.y) {
                    rotate(index * 47f + sin(phase + index * .8f) * 26f, Offset.Zero) {
                        scale(edge, edge, Offset.Zero) {
                            drawPath(shards[index], rock, alpha = .82f + depth * .16f)
                            drawLine(rose.copy(alpha = .28f), Offset(-.6f, -.18f), Offset(.02f, -.5f), .07f)
                        }
                    }
                }
            }
        }
        rotate(-21f, center) {
            drawOval(rose.copy(alpha = .24f), ringTop, ringSize, style = Stroke(radius * .035f))
            val gap = radius * .13f
            drawOval(rose.copy(alpha = .12f), ringTop - Offset(gap, gap),
                Size(ringSize.width + 2 * gap, ringSize.height + 2 * gap), style = Stroke(radius * .022f))
        }
        shards.indices.forEach { fragment(it, false) }
        drawCircle(Brush.radialGradient(listOf(rose.copy(alpha = .18f), rose.copy(alpha = 0f)), center, radius * 1.52f),
            radius * 1.52f, center)
        drawCircle(Brush.radialGradient(listOf(lerp(dark, rose, .52f), lerp(dark, rose, .19f), dark),
            center - Offset(radius * .34f, radius * .37f), radius * 1.45f), radius, center)
        val fissures = Path().apply {
            fun move(x: Float, y: Float) = moveTo(center.x + x * radius, center.y + y * radius)
            fun line(x: Float, y: Float) = lineTo(center.x + x * radius, center.y + y * radius)
            move(.28f, -.94f); line(.10f, -.58f); line(.34f, -.30f); line(-.06f, -.06f)
            line(.15f, .25f); line(-.17f, .55f); line(.05f, .98f)
            move(-.06f, -.06f); line(-.39f, -.17f); line(-.60f, -.39f); line(-.84f, -.43f)
            move(.15f, .25f); line(.47f, .31f); line(.80f, .14f); line(.96f, .24f)
            move(-.17f, .55f); line(-.46f, .64f); line(-.48f, .84f)
        }
        val glow = .64f + .23f * sin(phase * 3)
        val disk = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
        clipPath(disk) {
            drawCircle(dark.copy(alpha = .26f), radius * .18f, center + Offset(-radius * .42f, -radius * .28f))
            drawCircle(dark.copy(alpha = .36f), radius * .24f, center + Offset(radius * .45f, radius * .31f))
            drawPath(fissures, rose.copy(alpha = glow * .21f), style = Stroke(radius * .13f))
            drawPath(fissures, rose.copy(alpha = glow), style = Stroke(radius * .029f))
        }
        rotate(-21f, center) {
            drawArc(rose.copy(alpha = .65f), 0f, 180f, false, ringTop, ringSize,
                style = Stroke(radius * .043f))
            val gap = radius * .13f
            drawArc(rose.copy(alpha = .29f), 0f, 180f, false, ringTop - Offset(gap, gap),
                Size(ringSize.width + 2 * gap, ringSize.height + 2 * gap), style = Stroke(radius * .026f))
        }
        shards.indices.forEach { fragment(it, true) }
    }
}
