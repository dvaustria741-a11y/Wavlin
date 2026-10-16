package com.wavlin.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wavlin.music.R
import kotlin.math.roundToInt

/**
 * Draws a pill-shaped image in three slices so it can stretch to any width without
 * distorting the rounded ends or the lily artwork: a fixed left cap, a stretchy middle
 * and a fixed right cap. Caps keep the image's aspect ratio at the available height.
 */
@Composable
fun LilySlicedBackground(
    @DrawableRes res: Int,
    leftCapPx: Int,
    rightCapPx: Int,
    modifier: Modifier = Modifier,
) {
    val img = ImageBitmap.imageResource(res)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val scale = h / img.height
        var lw = leftCapPx * scale
        var rw = rightCapPx * scale
        if (lw + rw > w) {
            val f = w / (lw + rw)
            lw *= f
            rw *= f
        }
        val hi = h.roundToInt()
        val wi = w.roundToInt()
        val lwi = lw.roundToInt()
        val rwi = rw.roundToInt()
        val midSrcW = (img.width - leftCapPx - rightCapPx).coerceAtLeast(1)
        val midX = (lwi - 1).coerceAtLeast(0)
        val midW = (wi - lwi - rwi + 2).coerceAtLeast(1)
        drawImage(
            image = img,
            srcOffset = IntOffset(leftCapPx, 0),
            srcSize = IntSize(midSrcW, img.height),
            dstOffset = IntOffset(midX, 0),
            dstSize = IntSize(midW, hi),
            filterQuality = FilterQuality.High,
        )
        drawImage(
            image = img,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(leftCapPx, img.height),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(lwi, hi),
            filterQuality = FilterQuality.High,
        )
        drawImage(
            image = img,
            srcOffset = IntOffset(img.width - rightCapPx, 0),
            srcSize = IntSize(rightCapPx, img.height),
            dstOffset = IntOffset(wi - rwi, 0),
            dstSize = IntSize(rwi, hi),
            filterQuality = FilterQuality.High,
        )
    }
}

/** Secret Pink chip: a pale lily pill when idle, a deeper pill with a lily when selected. */
@Composable
fun LilyChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(32.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        LilySlicedBackground(
            res = if (selected) R.drawable.lily_chip_on else R.drawable.lily_chip_off,
            leftCapPx = if (selected) 314 else 316,
            rightCapPx = if (selected) 400 else 316,
            modifier = Modifier.matchParentSize(),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = if (selected) 42.dp else 16.dp),
        )
    }
}

/** Puts the lily disc behind an icon when the Secret Pink theme is active. */
@Composable
fun LilyDiscBox(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        if (enabled) {
            Image(
                painter = painterResource(R.drawable.lily_btn_disc),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        }
        content()
    }
}
