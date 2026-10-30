package com.wavlin.music.ui.screens.equalizer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wavlin.music.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val ThumbRestRadius = 9.dp
private val ThumbActiveRadius = 13.dp
private val TrackWidth = 6.dp

/** Formats a dB value compactly: "+3", "-4.5", "0". */
fun formatGainDb(gain: Float): String = when {
    abs(gain) < 0.05f -> "0"
    gain % 1f == 0f -> String.format(Locale.getDefault(), "%+d", gain.toInt())
    else -> String.format(Locale.getDefault(), "%+.1f", gain)
}

private fun snapGain(raw: Float): Float {
    val stepped = (raw / EqGainStepDb).roundToInt() * EqGainStepDb
    return stepped.coerceIn(-EqMaxGainDb, EqMaxGainDb)
}

/**
 * One vertical band of the custom equalizer.
 *
 * Drag to set the gain, tap anywhere on the track to jump there, double-tap to reset to 0 dB.
 * [gain] is the value to display, so the caller can animate it for preset changes.
 */
@Composable
fun EqBandSlider(
    gain: Float,
    label: String,
    onGainChange: (Float) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 208.dp,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val currentGain by rememberUpdatedState(gain)
    val currentOnGainChange by rememberUpdatedState(onGainChange)
    val currentOnDragState by rememberUpdatedState(onDragStateChange)
    var dragging by remember { mutableStateOf(false) }

    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) ThumbActiveRadius else ThumbRestRadius,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "eqThumbRadius",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.18f else 0f,
        label = "eqThumbHalo",
    )
    val valueColor by animateColorAsState(
        targetValue = if (dragging) colors.primary else colors.onSurfaceVariant,
        label = "eqValueColor",
    )

    val trackColor = colors.surfaceContainerHighest
    val activeColor = colors.primary
    val thumbInnerColor = colors.onPrimary
    val tickColor = colors.outline.copy(alpha = 0.6f)
    val bandDescription = stringResource(R.string.eq_band_description, label)

    // Emits a snapped gain for a touch position and ticks the haptic motor on whole-dB crossings
    fun emitFromTouch(y: Float, heightPx: Float, edgePx: Float) {
        val usable = (heightPx - 2f * edgePx).coerceAtLeast(1f)
        val fraction = ((y - edgePx) / usable).coerceIn(0f, 1f)
        val target = snapGain((1f - 2f * fraction) * EqMaxGainDb)
        if (target != currentGain) {
            if (target.roundToInt() != currentGain.roundToInt()) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            currentOnGainChange(target)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = formatGainDb(gain),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (dragging) FontWeight.Bold else FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = bandDescription
                    stateDescription = "${formatGainDb(gain)} dB"
                    progressBarRangeInfo = ProgressBarRangeInfo(gain, -EqMaxGainDb..EqMaxGainDb)
                    setProgress { value ->
                        currentOnGainChange(snapGain(value))
                        true
                    }
                }
                .pointerInput(Unit) {
                    val edgePx = ThumbActiveRadius.toPx()
                    detectTapGestures(
                        onDoubleTap = { currentOnGainChange(0f) },
                        onTap = { offset -> emitFromTouch(offset.y, size.height.toFloat(), edgePx) },
                    )
                }
                .pointerInput(Unit) {
                    val edgePx = ThumbActiveRadius.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            currentOnDragState(true)
                            emitFromTouch(offset.y, size.height.toFloat(), edgePx)
                        },
                        onDragEnd = {
                            dragging = false
                            currentOnDragState(false)
                        },
                        onDragCancel = {
                            dragging = false
                            currentOnDragState(false)
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            emitFromTouch(change.position.y, size.height.toFloat(), edgePx)
                        },
                    )
                },
        ) {
            val edge = ThumbActiveRadius.toPx()
            val centerX = size.width / 2f
            val usable = size.height - 2f * edge
            val trackW = TrackWidth.toPx()
            val zeroY = edge + usable / 2f
            val thumbY = edge + usable * (0.5f - (gain / (2f * EqMaxGainDb)))

            // Full track
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(centerX - trackW / 2f, edge),
                size = Size(trackW, usable),
                cornerRadius = CornerRadius(trackW / 2f),
            )

            // Active fill grows from the 0 dB line towards the thumb
            val fillTop = minOf(zeroY, thumbY)
            val fillBottom = maxOf(zeroY, thumbY)
            if (fillBottom - fillTop > 0.5f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(centerX - trackW / 2f, fillTop),
                    size = Size(trackW, fillBottom - fillTop),
                    cornerRadius = CornerRadius(trackW / 2f),
                )
            }

            // 0 dB tick
            drawLine(
                color = tickColor,
                start = Offset(centerX - trackW * 1.6f, zeroY),
                end = Offset(centerX + trackW * 1.6f, zeroY),
                strokeWidth = 1.5.dp.toPx(),
            )

            val center = Offset(centerX, thumbY)
            if (haloAlpha > 0.001f) {
                drawCircle(color = activeColor.copy(alpha = haloAlpha), radius = thumbRadius.toPx() * 1.7f, center = center)
            }
            drawCircle(color = activeColor, radius = thumbRadius.toPx(), center = center)
            drawCircle(color = thumbInnerColor, radius = thumbRadius.toPx() * 0.38f, center = center)
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
