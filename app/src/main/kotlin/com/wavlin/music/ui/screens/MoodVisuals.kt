/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils

private val DefaultMoodGradient = listOf(Color(0xFF0B1E36), Color(0xFF1E3C72))

/**
 * Builds the two-stop dark gradient used by mood and genre cards from the stripe color
 * that YouTube Music attaches to each mood.
 */
fun moodGradientFor(stripeColor: Long): List<Color> {
    val alpha = ((stripeColor shr 24) and 0xFF).toInt().let { if (it == 0) 0xFF else it }
    val red = ((stripeColor shr 16) and 0xFF).toInt()
    val green = ((stripeColor shr 8) and 0xFF).toInt()
    val blue = (stripeColor and 0xFF).toInt()

    if (red < 26 && green < 26 && blue < 26) return DefaultMoodGradient

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(android.graphics.Color.argb(alpha, red, green, blue), hsl)

    val start =
        floatArrayOf(
            hsl[0],
            hsl[1].coerceIn(0.35f, 0.60f),
            hsl[2].coerceIn(0.15f, 0.32f),
        )
    val end =
        floatArrayOf(
            (hsl[0] + 25f) % 360f,
            hsl[1].coerceIn(0.30f, 0.55f),
            hsl[2].coerceIn(0.10f, 0.24f),
        )

    return listOf(Color(ColorUtils.HSLToColor(start)), Color(ColorUtils.HSLToColor(end)))
}
