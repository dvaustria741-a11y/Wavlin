/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * Scale that pushes the top-left corner of YouTube Music cover art out of view.
 * Generated playlist covers carry the service's round logo badge in that corner.
 */
private const val BADGE_CROP_SCALE = 1.2f

/** Cover art that can hide the YouTube Music badge baked into the top-left of generated covers. */
@Composable
fun HubArt(
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    cropBadge: Boolean = true,
) {
    Box(modifier = modifier.clip(shape)) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (cropBadge) {
                            scaleX = BADGE_CROP_SCALE
                            scaleY = BADGE_CROP_SCALE
                            transformOrigin = TransformOrigin(1f, 1f)
                        }
                    },
        )
    }
}
