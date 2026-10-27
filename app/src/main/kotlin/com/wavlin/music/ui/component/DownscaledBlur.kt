/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * Renders [content] blurred by [radius], but blurs a copy that is [factor] times smaller and scales it back up.
 * For very large radii the result looks the same while the GPU shades far fewer pixels every frame.
 */
@Composable
fun DownscaledBlurBox(
    radius: Dp,
    modifier: Modifier = Modifier,
    factor: Float = 8f,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().blur(radius / factor)) { content() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val smallW = (width / factor).roundToInt().coerceAtLeast(1)
        val smallH = (height / factor).roundToInt().coerceAtLeast(1)
        val placeable = measurables.first().measure(Constraints.fixed(smallW, smallH))
        val sx = width.toFloat() / smallW
        val sy = height.toFloat() / smallH
        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = sx
                scaleY = sy
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
