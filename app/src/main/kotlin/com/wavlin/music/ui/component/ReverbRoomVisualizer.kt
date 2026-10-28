/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val Tau = 6.2831855f
private const val PiF = 3.1415927f
private const val PulseSeconds = 2.6f

/**
 * Animated visualizer for the Reverb section in [AudioEffectsSheet].
 *
 * presetIndex: 0 = None, 1 = Small Room, 2 = Concert Hall,
 *              3 = Stadium, 4 = Plate, 5 = Spring
 *
 * Every preset shows the same idea, one impulse and what the space does to it:
 * a short ping-pong, a long swelling tail, far-away echoes, a vibrating metal
 * sheet and a travelling coil wave.
 */
@Composable
fun ReverbRoomVisualizer(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float = 1.0f,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, speed) {
        if (!enabled) {
            time = 0f
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time += (now - last) / 1_000_000_000f * speed.coerceIn(0.25f, 3f)
                last = now
            }
        }
    }

    val roomW = presetWeight(enabled && presetIndex == 1, "room")
    val hallW = presetWeight(enabled && presetIndex == 2, "hall")
    val stadiumW = presetWeight(enabled && presetIndex == 3, "stadium")
    val plateW = presetWeight(enabled && presetIndex == 4, "plate")
    val springW = presetWeight(enabled && presetIndex == 5, "spring")

    Canvas(modifier = modifier.fillMaxWidth().height(130.dp)) {
        val t = time
        val total = (roomW + hallW + stadiumW + plateW + springW).coerceIn(0f, 1f)

        if (total > 0f) {
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = 0.16f * total), Color.Transparent),
                        center = center,
                        radius = size.height * 0.55f,
                    ),
                radius = size.height * 0.55f,
                center = center,
            )
        }
        if (total < 1f) drawIdle(t, 1f - total, muted)
        if (roomW > 0.001f) drawPingPong(t, roomW, primary, secondary)
        if (hallW > 0.001f) drawSwell(t, hallW, primary, secondary)
        if (stadiumW > 0.001f) drawEchoes(t, stadiumW, secondary)
        if (plateW > 0.001f) drawPlate(t, plateW, tertiary)
        if (springW > 0.001f) drawCoil(t, springW, primary, tertiary)
    }
}

@Composable
private fun presetWeight(
    active: Boolean,
    label: String,
): Float =
    animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(450),
        label = label,
    ).value

private fun DrawScope.drawIdle(
    t: Float,
    weight: Float,
    color: Color,
) {
    val half = size.width * 0.3f
    drawLine(
        color = color.copy(alpha = 0.3f * weight),
        start = Offset(center.x - half, center.y),
        end = Offset(center.x + half, center.y),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx())),
    )
    val breath = 0.5f + 0.5f * sin(t * 1.5f)
    drawCircle(color.copy(alpha = (0.35f + 0.3f * breath) * weight), 3.dp.toPx(), center)
}

private fun DrawScope.drawPingPong(
    t: Float,
    weight: Float,
    color: Color,
    accent: Color,
) {
    val phase = (t / PulseSeconds) % 1f
    val reach = size.width * 0.17f
    val gap = 12.dp.toPx()
    val wallHalfHeight = size.height * 0.24f

    fun xAt(p: Float): Float = center.x + reach * exp(-3.2f * p) * cos(Tau * 3.5f * p)

    val swing = cos(Tau * 3.5f * phase)
    val fade = exp(-3.2f * phase)
    val rightFlash = max(0f, swing).pow(12) * fade
    val leftFlash = max(0f, -swing).pow(12) * fade

    val wallRight = center.x + reach + gap
    val wallLeft = center.x - reach - gap
    drawLine(
        color = accent.copy(alpha = (0.35f + 0.65f * rightFlash).coerceIn(0f, 1f) * weight),
        start = Offset(wallRight, center.y - wallHalfHeight),
        end = Offset(wallRight, center.y + wallHalfHeight),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = accent.copy(alpha = (0.35f + 0.65f * leftFlash).coerceIn(0f, 1f) * weight),
        start = Offset(wallLeft, center.y - wallHalfHeight),
        end = Offset(wallLeft, center.y + wallHalfHeight),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color.copy(alpha = 0.18f * weight),
        start = Offset(wallLeft, center.y),
        end = Offset(wallRight, center.y),
        strokeWidth = 1.dp.toPx(),
    )

    for (k in 7 downTo 1) {
        val p = phase - k * 0.011f
        if (p < 0f) continue
        val a = (0.5f * (1f - k / 8f)).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = a * weight), (5 - k * 0.4f).coerceAtLeast(1f).dp.toPx(), Offset(xAt(p), center.y))
    }
    val dot = Offset(xAt(phase), center.y)
    drawCircle(color.copy(alpha = 0.22f * weight), 10.dp.toPx(), dot)
    drawCircle(color.copy(alpha = weight), 5.dp.toPx(), dot)
}

private fun DrawScope.drawSwell(
    t: Float,
    weight: Float,
    from: Color,
    to: Color,
) {
    val phase = (t / PulseSeconds) % 1f
    val bars = 29
    val span = size.width * 0.8f
    val left = center.x - span / 2f
    val maxHalf = size.height * 0.44f
    val front = phase * 1.25f

    for (i in 0 until bars) {
        val u = i / (bars - 1f)
        val d = abs(u - 0.5f) * 2f
        val ring = exp(-((d - front) * (d - front)) / 0.025f) * (1f - phase * 0.55f)
        val tail = 0.16f * (1f - d * 0.6f) * (0.7f + 0.3f * sin(t * 2.1f + i * 0.6f))
        val a = (ring + tail).coerceIn(0f, 1f)
        val half = (2.dp.toPx() + maxHalf * a) * weight
        val x = left + span * u
        drawLine(
            color = lerp(from, to, d).copy(alpha = ((0.3f + 0.7f * a) * weight).coerceIn(0f, 1f)),
            start = Offset(x, center.y - half),
            end = Offset(x, center.y + half),
            strokeWidth = (span / bars) * 0.46f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawEchoes(
    t: Float,
    weight: Float,
    color: Color,
) {
    val phase = (t / PulseSeconds) % 1f
    val rx = size.width * 0.46f
    val ry = size.height * 0.42f

    var firstRadius = 0f
    for (k in 0..3) {
        val delay = k * 0.18f
        if (phase < delay) continue
        val r = (phase - delay) / (1f - delay)
        if (k == 0) firstRadius = r
        val alpha = ((1f - r).pow(1.4f) * (1f - k * 0.22f)).coerceIn(0f, 1f) * weight
        drawOval(
            color = color.copy(alpha = alpha),
            topLeft = Offset(center.x - rx * r, center.y - ry * r),
            size = Size(rx * r * 2f, ry * r * 2f),
            style = Stroke(width = (2.4f - k * 0.45f).dp.toPx()),
        )
    }

    val flash = ((firstRadius - 0.85f) / 0.15f).coerceIn(0f, 1f)
    drawOval(
        color = color.copy(alpha = (0.14f + 0.6f * flash) * weight),
        topLeft = Offset(center.x - rx, center.y - ry),
        size = Size(rx * 2f, ry * 2f),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 7.dp.toPx()))),
    )
    val pulse = 1f - phase
    drawCircle(color.copy(alpha = weight), (3.5f + 3f * pulse).dp.toPx(), center)
}

private fun DrawScope.drawPlate(
    t: Float,
    weight: Float,
    color: Color,
) {
    val cols = 19
    val rows = 7
    val areaW = size.width * 0.82f
    val areaH = size.height * 0.64f
    val left = center.x - areaW / 2f
    val top = center.y - areaH / 2f
    val c1 = cos(t * 2.2f)
    val c2 = cos(t * 3.1f + 1f)

    for (j in 0 until rows) {
        for (i in 0 until cols) {
            val x = i / (cols - 1f)
            val y = j / (rows - 1f)
            val v = sin(3f * PiF * x) * sin(2f * PiF * y) * c1 + 0.55f * sin(PiF * x) * sin(4f * PiF * y) * c2
            val m = min(abs(v), 1f)
            drawCircle(
                color = color.copy(alpha = ((0.25f + 0.75f * m) * weight).coerceIn(0f, 1f)),
                radius = (1.3.dp.toPx() + 3.4.dp.toPx() * m) * weight,
                center = Offset(left + areaW * x, top + areaH * y),
            )
        }
    }
}

private fun DrawScope.drawCoil(
    t: Float,
    weight: Float,
    from: Color,
    to: Color,
) {
    val len = size.width * 0.72f
    val left = center.x - len / 2f
    val loops = 8
    val swing = size.width * 0.04f
    val radius = size.height * 0.3f * weight
    val steps = 240
    val path = Path()

    for (i in 0..steps) {
        val s = i / steps.toFloat()
        val wave = sin(Tau * (s * 1.6f - t * 0.75f))
        val u = s + 0.022f * wave * sin(PiF * s)
        val angle = Tau * loops * u
        val x = left + len * u + swing * cos(angle)
        val y = center.y + radius * (1f + 0.12f * wave) * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        brush = Brush.horizontalGradient(listOf(from, to), startX = left, endX = left + len),
        alpha = weight,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
    )
    val tip = Offset(left + len + swing, center.y)
    drawCircle(to.copy(alpha = 0.25f * weight), 9.dp.toPx(), tip)
    drawCircle(to.copy(alpha = weight), 4.5f.dp.toPx(), tip)
}
