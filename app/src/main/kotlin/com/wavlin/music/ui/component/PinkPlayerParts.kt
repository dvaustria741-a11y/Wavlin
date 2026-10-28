/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavlin.music.R
import com.wavlin.music.ui.theme.PinkInk
import com.wavlin.music.ui.theme.PinkSwoosh
import com.wavlin.music.ui.theme.playball

/** Secret Pink glass in a capsule shape: a circle for square-ish sizes, a pill for wide ones. */
@Composable
fun Modifier.lilyGlassAuto(): Modifier = this.pinkGlass(RoundedCornerShape(50), floating = false)

/**
 * Drop-in replacement for Material's FilledIconButton. In the Secret Pink theme the button draws glass art
 * (disc, pill or rounded square depending on its shape); other themes get the stock Material button.
 */
@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    if (LocalPinkNowPlaying.current) {
        val round = shape == RoundedCornerShape(50)
        androidx.compose.material3.FilledIconButton(
            onClick = onClick,
            modifier =
                if (round) {
                    modifier.lilyGlassAuto()
                } else {
                    modifier.pinkGlassPanel(RoundedCornerShape(16.dp))
                },
            enabled = enabled,
            shape = if (round) shape else RoundedCornerShape(16.dp),
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = PinkInk,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = PinkInk.copy(alpha = 0.4f),
                ),
            interactionSource = interactionSource,
            content = content,
        )
    } else {
        androidx.compose.material3.FilledIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            interactionSource = interactionSource,
            content = content,
        )
    }
}

/** "Now Playing" script heading with thin rules, a bow, and the queue/playlist name underneath. */
@Composable
fun PinkNowPlayingHeader(
    queueTitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.width(44.dp).height(1.dp).background(PinkSwoosh))
            Text(
                text = "Now Playing",
                fontFamily = playball,
                fontSize = 38.sp,
                color = PinkInk,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Box(Modifier.width(44.dp).height(1.dp).background(PinkSwoosh))
        }
        Image(
            painter = painterResource(R.drawable.lily_bow),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.width(300.dp),
        )
        if (!queueTitle.isNullOrBlank()) {
            Text(
                text = queueTitle,
                color = PinkInk,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

/** Glass track with a pink fill for the Now Playing slider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinkSliderTrack(
    sliderState: SliderState,
    modifier: Modifier = Modifier,
) {
    val range = sliderState.valueRange
    val span = range.endInclusive - range.start
    val fraction = if (span > 0f) ((sliderState.value - range.start) / span).coerceIn(0f, 1f) else 0f
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(20.dp)
                .pinkGlass(RoundedCornerShape(50), floating = false),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Color(0xFFF3B4C8), Color(0xFFD97A9C)))),
        )
    }
}

@Composable
fun PinkSliderThumb() {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xFFC85A86))
            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
    )
}
