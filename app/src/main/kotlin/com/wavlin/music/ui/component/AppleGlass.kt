/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Liquid-glass approach adapted from SimpMusic (GPL-3.0, maxrave-dev/SimpMusic):
 * Kyant backdrop effect stack (vibrancy, colorControls, blur, lens).
 */

package com.wavlin.music.ui.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.wavlin.music.constants.ThumbnailCornerRadius
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import dev.chrisbanes.haze.HazeStyle

/** True when the user picked the Apple Music interface style. */
val LocalInterfaceAppleMusic = staticCompositionLocalOf { false }

/** Source layer that sibling glass surfaces refract. Null unless Apple Music style is on and supported. */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Source layer for glass drawn on top of the expanded player (its blurred artwork background). Null unless Apple style is on. */
val LocalPlayerGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Lens refraction runs on a RuntimeShader (AGSL), which needs Android 13+. Older devices fall back to Haze. */
fun appleGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Liquid glass for the few key surfaces (tab bar, mini player). Uses Kyant backdrop where
 * supported, and falls back to the existing Haze [glassPanel] otherwise.
 * The surface MUST be a sibling of the backdrop source, never a child of it.
 */
@Composable
fun Modifier.appleGlass(
    shape: Shape,
    fallbackStyle: HazeStyle? = null,
    /** Dark/light tint laid over the glass. Big surfaces (dialogs) pass a higher value so text stays readable. */
    tintAlpha: Float? = null,
    backdrop: LayerBackdrop? = LocalGlassBackdrop.current,
): Modifier {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = tintAlpha ?: if (isDark) 0.22f else 0.28f
    if (backdrop == null || !appleGlassSupported()) {
        // No sampleable backdrop (inside screen content, or pre-Android 13): Haze blur with the same tint.
        return this
            .legacyGlassPanel(shape = shape, style = fallbackStyle)
            .background((if (isDark) Color.Black else Color.White).copy(alpha = tint), shape)
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            colorControls(
                brightness = 0.05f,
                contrast = 1f,
                saturation = 1.5f,
            )
            blur(10f.dp.toPx())
            lens(minOf(size.minDimension / 4f, 24.dp.toPx()), minOf(size.minDimension / 2f, 32.dp.toPx()), false)
        },
        onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
        onDrawSurface = {
            drawRect((if (isDark) Color.Black else Color.White).copy(alpha = tint))
        },
    )
}

/** Artwork corner shape: Apple Music uses noticeably rounder cover art than the Material default. */
@Composable
fun artworkShape(): RoundedCornerShape =
    RoundedCornerShape(if (LocalInterfaceAppleMusic.current) 8.dp else ThumbnailCornerRadius)
