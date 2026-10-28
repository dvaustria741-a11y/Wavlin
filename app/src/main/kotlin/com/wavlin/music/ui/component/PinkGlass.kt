/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Rose tint laid over glass surfaces in the Secret Pink theme. */
val PinkGlassTint = Color(0xFFFF9EC1)

/**
 * Secret Pink glass, drawn instead of the old raster button art. It follows the chosen interface style:
 * Apple Music gets the real liquid glass (lens refraction) tinted pink, Material 3 gets a pink Haze blur.
 *
 * Floating surfaces (top bar, mini player, FABs) sample the content behind them. Surfaces that live inside
 * the scrolling content ([floating] = false) cannot sample their own parent, so they use a static frosted
 * gradient with the same glass edge instead.
 */
@Composable
fun Modifier.pinkGlass(
    shape: Shape,
    floating: Boolean = true,
): Modifier {
    val apple = LocalInterfaceAppleMusic.current
    val surface =
        when {
            floating && apple -> this.appleGlass(shape, pinkLilyHazeStyle())
            floating -> this.legacyGlassPanel(shape, pinkLilyHazeStyle(), drawStroke = false)
            else ->
                this
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (apple) 0.50f else 0.62f),
                                PinkGlassTint.copy(alpha = 0.30f),
                            ),
                        ),
                    )
        }
    return surface.pinkGlassEdge(shape, strong = apple)
}

/** The light-catching rim of a Secret Pink glass surface. */
fun Modifier.pinkGlassEdge(
    shape: Shape,
    strong: Boolean = false,
): Modifier =
    this.border(
        width = if (strong) 1.5.dp else 1.dp,
        brush =
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.95f),
                    PinkGlassTint.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.70f),
                ),
            ),
        shape = shape,
    )
