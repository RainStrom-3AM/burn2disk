package com.burnto.disk.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.burnto.disk.ui.theme.Amber
import com.burnto.disk.ui.theme.MonoText
import com.burnto.disk.ui.theme.OutlineDark
import com.burnto.disk.ui.theme.SuccessGreen

/**
 * A large circular progress ring with the percentage in its center using the
 * monospace technical font. The stroke sweeps amber 0 -> 360 and turns green with
 * a checkmark at 100% / success.
 */
@Composable
fun CircularBurnProgress(
    percent: Int,
    modifier: Modifier = Modifier,
    isSuccess: Boolean = false,
    isIndeterminate: Boolean = false
) {
    val animatedSweep by animateFloatAsState(
        targetValue = (percent.coerceIn(0, 100) / 100f) * 360f,
        animationSpec = tween(durationMillis = 400),
        label = "sweep"
    )
    val ringColor = if (isSuccess || percent >= 100) SuccessGreen else Amber

    Box(modifier = modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)

            // Track.
            drawArc(
                color = OutlineDark,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Progress sweep (12 o'clock origin).
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = if (isIndeterminate) 90f else animatedSweep,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }

        if (isSuccess) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Complete",
                tint = SuccessGreen,
                modifier = Modifier.size(96.dp)
            )
        } else {
            Text(
                text = "${percent.coerceIn(0, 100)}%",
                style = MonoText.large,
                color = ringColor
            )
        }
    }
}
