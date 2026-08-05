/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * Circular glass icon button built from bare primitives instead of Material3's
 * FloatingActionButton/SmallFloatingActionButton (those compute shadow/elevation from an
 * internal shape independent of any shape param passed in, which showed up as a visible
 * polygon artifact behind the circular clipped content). Uses a flat translucent fill
 * instead of real backdrop blur too - at this small a scale (40-56dp) blur sampling doesn't
 * read as meaningfully "frosted" anyway, and skipping it avoids any further blur-related
 * rendering quirks at the circular clip boundary.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, glassStrokeBrush(), androidx.compose.foundation.shape.CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = size / 2),
                onClick = onClick,
            )
            .let {
                if (contentDescription != null) it.semantics { this.contentDescription = contentDescription } else it
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
