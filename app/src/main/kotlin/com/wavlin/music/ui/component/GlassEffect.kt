/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * A single shared blur coordinator for the whole screen. The main scrollable content
 * is marked as the "source" (see Modifier.hazeSource in MainActivity), and floating
 * surfaces - the mini-player, bottom nav bar and floating action buttons - read from
 * it via [glassPanel] to render a real iOS-style frosted backdrop blur instead of a
 * flat color.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState> {
    error("No HazeState provided - wrap the root content in Modifier.hazeSource(LocalHazeState.current)")
}

/**
 * A neutral, theme-agnostic "glass edge" highlight: a soft white gradient stroke that
 * catches the light the way real glass (or iOS's frosted panels) does.
 *
 * Deliberately NOT derived from MaterialTheme.colorScheme.primary - that would tint
 * the glow with whatever accent color Material You / dynamic color picked (often
 * purple), which isn't the iOS look being asked for here. White stays correct across
 * every wallpaper and theme.
 */
fun glassStrokeBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.55f),
        Color.White.copy(alpha = 0.06f),
        Color.White.copy(alpha = 0.28f),
    ),
)

/**
 * Applies a real backdrop blur - sampling whatever content is behind this composable,
 * via the shared [LocalHazeState] - plus a subtle glowing glass-edge stroke around the
 * given [shape]. Use on floating surfaces such as the mini-player, nav bar, or FABs.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(32.dp),
    style: HazeStyle? = null,
    strokeWidth: Dp = 1.dp,
    drawStroke: Boolean = true,
): Modifier = composed {
    val hazeState = LocalHazeState.current
    var m = this
        .clip(shape)
        .hazeEffect(state = hazeState, style = style ?: HazeMaterials.thin())
    if (drawStroke) {
        m = m.border(strokeWidth, glassStrokeBrush(), shape)
    }
    m
}

/**
 * A thin glowing hairline along the top edge, for edge-to-edge glass surfaces (like the
 * bottom nav bar) where a full rounded-corner border would look wrong.
 */
fun Modifier.glassTopEdgeGlow(height: Dp = 1.dp): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.35f),
                Color.Transparent,
            ),
        ),
        size = Size(size.width, height.toPx()),
    )
}
