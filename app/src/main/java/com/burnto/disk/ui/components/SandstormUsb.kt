package com.burnto.disk.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.AmberDim
import kotlin.math.sin
import kotlin.random.Random

/**
 * The home-screen hero: a large amber USB icon with sand particles drifting
 * across it, suggesting a sandstorm. Particles loop horizontally with a gentle
 * vertical sine wobble.
 */
@Composable
fun SandstormUsb(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sandstorm")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Stable random seeds for particle lanes.
    val particles = remember {
        List(18) {
            Particle(
                laneY = Random.nextFloat(),
                speed = 0.6f + Random.nextFloat() * 0.8f,
                radius = 1.5f + Random.nextFloat() * 2.5f,
                offsetPhase = Random.nextFloat(),
                wobble = 6f + Random.nextFloat() * 10f
            )
        }
    }

    Box(modifier = modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(200.dp)) {
            val w = size.width
            val h = size.height
            for (p in particles) {
                val t = (phase * p.speed + p.offsetPhase) % 1f
                val x = t * w
                val baseY = p.laneY * h
                val y = baseY + sin((t + p.offsetPhase) * 6.28318f).toFloat() * p.wobble
                drawCircle(
                    color = if (p.radius > 3f) Amber else AmberDim,
                    radius = p.radius,
                    center = Offset(x, y),
                    alpha = (0.5f + 0.5f * (1f - kotlin.math.abs(0.5f - t) * 2f)).coerceIn(0f, 1f)
                )
            }
        }

        Icon(
            imageVector = Icons.Filled.Usb,
            contentDescription = "USB drive",
            tint = Amber,
            modifier = Modifier.size(96.dp)
        )
    }
}

private data class Particle(
    val laneY: Float,
    val speed: Float,
    val radius: Float,
    val offsetPhase: Float,
    val wobble: Float
)
