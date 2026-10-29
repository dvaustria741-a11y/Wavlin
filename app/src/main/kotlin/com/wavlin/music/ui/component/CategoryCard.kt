/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val CardShape = RoundedCornerShape(22.dp)

/**
 * Search category tile. Everything is derived from one [accent] colour taken from the active
 * colour scheme, so it follows every theme (dynamic, Secret Pink, dark, pure black, light).
 */
@Composable
fun CategoryCard(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "categoryCardScale",
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier =
            modifier
                .height(96.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(CardShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.06f)),
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        ),
                    ),
        ) {
            // Oversized, faded copy of the icon peeking out of the corner for depth.
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = accent.copy(alpha = 0.16f),
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 14.dp, y = 14.dp)
                        .size(84.dp)
                        .rotate(-14f),
            )
            Box(
                modifier =
                    Modifier
                        .padding(14.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.24f))
                        .align(Alignment.TopStart),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}
