/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow)
 * by Shubham Karande — MIT / Apache-2.0.
 */

package com.wavlin.music.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Animated visualizer for the Reverb section in [AudioEffectsSheet].
 *
 * presetIndex: 0 = None, 1 = Small Room, 2 = Concert Hall,
 *              3 = Stadium, 4 = Plate, 5 = Spring
 */
@Composable
fun ReverbRoomVisualizer(
    presetIndex: Int,
    enabled: Boolean,
    speed: Float = 1.0f,
    modifier: Modifier = Modifier,
) {
    val primaryColor   = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor  = MaterialTheme.colorScheme.tertiary
    val onSurfaceVar   = MaterialTheme.colorScheme.onSurfaceVariant

    var rotationPhase by remember { mutableStateOf(0f) }
    LaunchedEffect(enabled, speed) {
        if (enabled) {
            val duration = (6000f / speed).toInt().coerceIn(1500, 15000)
            val startTime = System.currentTimeMillis()
            while (true) {
                withFrameNanos {
                    val elapsed = (System.currentTimeMillis() - startTime) % duration
                    rotationPhase = (elapsed.toFloat() / duration) * (2f * PI.toFloat())
                }
            }
        } else {
            rotationPhase = 0f
        }
    }

    val roomW   by animateFloatAsState(if (enabled && presetIndex == 1) 1f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "room")
    val hallW   by animateFloatAsState(if (enabled && presetIndex == 2) 1f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "hall")
    val stadW   by animateFloatAsState(if (enabled && presetIndex == 3) 1f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "stad")
    val plateW  by animateFloatAsState(if (enabled && presetIndex == 4) 1f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "plate")
    val springW by animateFloatAsState(if (enabled && presetIndex == 5) 1f else 0f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "spring")

    val density = LocalDensity.current
    val sw = with(density) { 1.5.dp.toPx() }

    Canvas(modifier = modifier.fillMaxWidth().height(130.dp)) {
        val w = size.width; val h = size.height
        val cx = w / 2f;   val cy = h / 2f
        val base  = if (enabled) primaryColor else onSurfaceVar.copy(alpha = 0.38f)
        val phase = if (enabled) rotationPhase else 0f
        val total = (roomW + hallW + stadW + plateW + springW).coerceIn(0f, 1f)

        if (total > 0f) drawCircle(
            Brush.radialGradient(listOf(primaryColor.copy(alpha = 0.15f * total), Color.Transparent),
                Offset(cx, cy), h * 0.5f), h * 0.5f, Offset(cx, cy))

        // 1. Small Room — 3-D perspective box
        if (roomW > 0.001f) {
            val s = (0.7f + 0.3f * roomW) * h * 0.35f
            val a = 0.85f * roomW
            val c = arrayOf(
                Offset(cx - s * 1.1f, cy - s * 0.6f), Offset(cx + s * 1.1f, cy - s * 0.6f),
                Offset(cx + s * 1.1f, cy + s * 0.6f), Offset(cx - s * 1.1f, cy + s * 0.6f),
                Offset(cx - s * 0.6f, cy - s * 0.35f), Offset(cx + s * 0.6f, cy - s * 0.35f),
                Offset(cx + s * 0.6f, cy + s * 0.35f), Offset(cx - s * 0.6f, cy + s * 0.35f),
            )
            for (i in 0..3) {
                drawLine(base.copy(alpha = a), c[i], c[(i + 1) % 4], sw * 1.2f)
                drawLine(base.copy(alpha = a * 0.5f), c[i], c[i + 4], sw * 0.8f)
                drawLine(base.copy(alpha = a * 0.4f), c[i + 4], c[((i + 1) % 4) + 4], sw * 0.8f)
                drawCircle(primaryColor.copy(alpha = a), 3.dp.toPx() * roomW, c[i])
            }
        }

        // 2. Concert Hall — parabolic dome arcs
        if (hallW > 0.001f) {
            val a = 0.85f * hallW; val ribCount = 8
            val maxRx = w * 0.35f * hallW; val maxRy = h * 0.42f * hallW
            for (i in 0 until ribCount) {
                val p = i.toFloat() / (ribCount - 1)
                val rx = maxRx * (0.3f + 0.7f * p); val ry = maxRy * (0.3f + 0.7f * p)
                val pulse = sin(phase * 2f + i * 0.5f) * 4f
                val path = Path().apply {
                    for (step in 0..60) {
                        val ang = (step / 60f) * PI.toFloat()
                        val x = cx + (rx + pulse) * cos(ang)
                        val y = (cy + maxRy * 0.3f) - (ry + pulse) * sin(ang)
                        if (step == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(path, secondaryColor.copy(alpha = a * (0.3f + 0.7f * p)),
                    style = Stroke(width = sw * (1f + p * 0.5f)))
            }
            drawLine(secondaryColor.copy(alpha = a * 0.6f),
                Offset(cx - maxRx, cy + maxRy * 0.35f),
                Offset(cx + maxRx, cy + maxRy * 0.35f), sw * 1.5f)
        }

        // 3. Stadium — expanding oval rings
        if (stadW > 0.001f) {
            val a = 0.85f * stadW; val rings = 5
            val rxBase = w * 0.38f * stadW; val ryBase = h * 0.35f * stadW
            for (r in 0 until rings) {
                val wp = (phase * 1.5f + r * 0.8f) % (2f * PI.toFloat())
                val exp2 = 0.3f + 0.7f * (r.toFloat() / (rings - 1))
                val path = Path().apply {
                    for (step in 0..80) {
                        val ang = (step / 80f) * (2 * PI.toFloat())
                        val x = cx + rxBase * exp2 * cos(ang)
                        val y = cy + ryBase * exp2 * sin(ang)
                        if (step == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(path, tertiaryColor.copy(alpha = a * (0.2f + 0.8f * abs(sin(wp)))),
                    style = Stroke(width = sw * (1f + (1f - exp2))))
            }
            for (b in 0 until 12) {
                val ang = (b / 12f) * 2 * PI.toFloat() + phase * 0.5f
                drawCircle(tertiaryColor.copy(alpha = a * 0.7f), 2.5.dp.toPx() * stadW,
                    Offset(cx + rxBase * cos(ang), cy + ryBase * sin(ang)))
            }
        }

        // 4. Plate — vibrating lattice grid
        if (plateW > 0.001f) {
            val a = 0.85f * plateW; val cols = 7; val rows = 5
            val sx = (w * 0.55f * plateW) / (cols - 1)
            val sy = (h * 0.5f * plateW) / (rows - 1)
            val ox = cx - (cols - 1) * sx / 2f; val oy = cy - (rows - 1) * sy / 2f
            val pts = Array(rows) { r ->
                Array(cols) { c ->
                    val wave = sin(phase * 3f + (r + c) * 0.7f) * (4.dp.toPx() * plateW)
                    Offset(ox + c * sx, oy + r * sy + wave)
                }
            }
            for (r in 0 until rows) for (c in 0 until cols) {
                if (c < cols - 1) drawLine(primaryColor.copy(alpha = a * 0.6f), pts[r][c], pts[r][c + 1], sw)
                if (r < rows - 1) drawLine(primaryColor.copy(alpha = a * 0.6f), pts[r][c], pts[r + 1][c], sw)
                drawCircle(secondaryColor.copy(alpha = a), 2.dp.toPx() * plateW, pts[r][c])
            }
        }

        // 5. Spring — dual interlocking coils
        if (springW > 0.001f) {
            val a = 0.85f * springW; val coils = 10
            val maxH = h * 0.72f * springW; val rx = 16.dp.toPx() * springW
            val spacing = 42.dp.toPx() * springW; val steps = 160
            listOf(cx - spacing / 2f, cx + spacing / 2f).forEachIndexed { sIdx, scx ->
                val dir = if (sIdx == 0) 1f else -1f
                val bPhase = phase * 4f * dir
                val dynH = maxH * (0.82f + 0.18f * sin(bPhase))
                val startY = cy - dynH / 2f
                var prev: Offset? = null; var pAlpha = 0f; var pStroke = sw
                for (step in 0..steps) {
                    val prog = step.toFloat() / steps
                    val wc = sin(bPhase + prog * 8f) * 0.08f
                    val adj = (prog + wc).coerceIn(0f, 1f)
                    val py = startY + adj * dynH
                    val ang = prog * coils * (2 * PI).toFloat() + phase * 3f * dir
                    val px = scx + rx * cos(ang); val z = sin(ang)
                    val sAlpha = a * (0.35f + 0.65f * ((z + 1f) / 2f))
                    val sStroke = sw * (0.75f + 0.85f * ((z + 1f) / 2f))
                    val curr = Offset(px, py)
                    if (prev != null) drawLine(
                        (if (sIdx == 0) primaryColor else secondaryColor).copy(alpha = (sAlpha + pAlpha) / 2f),
                        prev!!, curr, (sStroke + pStroke) / 2f)
                    if (z > 0.92f && step % 12 == 0)
                        drawCircle((if (sIdx == 0) primaryColor else tertiaryColor).copy(alpha = a),
                            2.5.dp.toPx() * springW, curr)
                    prev = curr; pAlpha = sAlpha; pStroke = sStroke
                }
                drawLine(base.copy(alpha = a * 0.7f), Offset(scx - rx, startY), Offset(scx + rx, startY), sw * 1.5f)
                drawLine(base.copy(alpha = a * 0.7f), Offset(scx - rx, startY + dynH), Offset(scx + rx, startY + dynH), sw * 1.5f)
            }
        }

        // 0. None / resting — subtle pulse circle
        if (total < 0.1f) {
            val restA = (1f - total * 10f).coerceIn(0f, 1f) * 0.35f
            drawCircle(base.copy(alpha = restA), (w * 0.2f) + sin(phase * 2f) * 5.dp.toPx(),
                Offset(cx, cy), style = Stroke(width = sw))
        }
    }
}
