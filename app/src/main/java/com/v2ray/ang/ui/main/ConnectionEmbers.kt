package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Ember(random: Random) {
    private val bearing = random.nextFloat() * 2.4f - 1.2f
    val edge = Offset(sin(bearing), -cos(bearing))
    val drift = random.nextFloat() * 9f - 4.5f
    val rise = 15f + random.nextFloat() * 15f
    val radius = 1.6f + random.nextFloat() * 1.7f
    val spin = random.nextFloat() * 54f - 27f
    val lifetime = random.nextInt(540, 870)
    val tint = EmberTints[random.nextInt(EmberTints.size)]
    val age = Animatable(0f)
}

private val EmberTints = listOf(Color(0xFFF59AC6), Color(0xFFFFBADE), Color(0xFFFFD2B9))

@Composable
internal fun ConnectionEmbers(centerFromRight: Dp, buttonRadius: Dp, modifier: Modifier) {
    val embers = remember { mutableStateListOf<Ember>() }
    val starPath = remember {
        Path().apply {
            for (point in 0 until 10) {
                val angle = (point - 2.5) * PI / 5
                val radius = if (point % 2 == 0) 1f else 0.43f
                val x = cos(angle).toFloat() * radius
                val y = sin(angle).toFloat() * radius
                if (point == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
    LaunchedEffect(Unit) {
        val random = Random.Default
        while (isActive) {
            val ember = Ember(random)
            embers.add(ember)
            launch {
                try {
                    ember.age.animateTo(1f, tween(ember.lifetime, easing = LinearEasing))
                } finally {
                    embers.remove(ember)
                }
            }
            delay(random.nextLong(180, 370))
        }
    }
    Canvas(modifier) {
        val buttonCenter = Offset(size.width - centerFromRight.toPx(), size.height / 2)
        for (ember in embers) {
            val age = ember.age.value
            val emerge = buttonCenter + ember.edge * (buttonRadius.toPx() - 1.dp.toPx())
            val x = emerge.x + (ember.drift * sin(age * 2.1f)).dp.toPx()
            val y = emerge.y - (ember.rise * age * (1.4f - 0.4f * age)).dp.toPx()
            val radius = (ember.radius * (1f - 0.42f * age)).dp.toPx()
            val remaining = 1f - age
            val opacity = (age * 12f).coerceAtMost(1f) * remaining * remaining
            drawCircle(ember.tint, radius * 1.8f, Offset(x, y), alpha = opacity * 0.12f)
            withTransform({
                translate(x, y)
                rotate(ember.spin * age, pivot = Offset.Zero)
                scale(radius, radius, pivot = Offset.Zero)
            }) {
                drawPath(starPath, ember.tint.copy(alpha = opacity))
            }
        }
    }
}
