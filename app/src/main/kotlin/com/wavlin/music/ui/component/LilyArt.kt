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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * Draws [img] in three slices so it can stretch to any width without distorting the
 * rounded ends: a fixed left cap, a stretchy middle and a fixed right cap. Caps keep the
 * image's aspect ratio at the available height.
 */
fun DrawScope.drawLilySliced(
    img: ImageBitmap,
    leftCapPx: Int,
    rightCapPx: Int,
    alpha: Float = 1f,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
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
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
    drawImage(
        image = img,
        srcOffset = IntOffset(0, 0),
        srcSize = IntSize(leftCapPx, img.height),
        dstOffset = IntOffset(0, 0),
        dstSize = IntSize(lwi, hi),
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
    drawImage(
        image = img,
        srcOffset = IntOffset(img.width - rightCapPx, 0),
        srcSize = IntSize(rightCapPx, img.height),
        dstOffset = IntOffset(wi - rwi, 0),
        dstSize = IntSize(rwi, hi),
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
}

/** A three-slice stretchy background, e.g. the pill behind the mini player. */
@Composable
fun LilySlicedBackground(
    @DrawableRes res: Int,
    leftCapPx: Int,
    rightCapPx: Int,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val img = ImageBitmap.imageResource(res)
    Canvas(modifier = modifier) {
        drawLilySliced(img, leftCapPx, rightCapPx, alpha)
    }
}

/** Modifier version of [LilySlicedBackground]: draws the sliced art behind the content. */
@Composable
fun Modifier.lilySliced(
    @DrawableRes res: Int,
    leftCapPx: Int,
    rightCapPx: Int,
    alpha: Float = 1f,
): Modifier {
    val img = ImageBitmap.imageResource(res)
    return this.drawBehind { drawLilySliced(img, leftCapPx, rightCapPx, alpha) }
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

/** In the Secret Pink theme, plain surface fills turn transparent so the gingham wallpaper shows through. */
@Composable
fun pinkAwareFill(default: Color): Color = if (LocalPinkLilyTheme.current) Color.Transparent else default

/**
 * Top bar colors for the Secret Pink theme: transparent so the gingham wallpaper shows through, with a soft
 * translucent pink once content scrolls underneath. Other themes keep the stock Material colors.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun pinkTopBarColors(): androidx.compose.material3.TopAppBarColors =
    if (LocalPinkLilyTheme.current) {
        androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color(0xFFFBE4EC).copy(alpha = 0.92f),
        )
    } else {
        androidx.compose.material3.TopAppBarDefaults.topAppBarColors()
    }

/** Nine-patch draw: fixed-size corners (cut at [srcCap] source px) and stretchy edges and center. */
fun DrawScope.drawLilyNinePatch(img: ImageBitmap, srcCap: Int, dstCapPx: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val cap = minOf(dstCapPx, w / 2f, h / 2f)
    val sx = intArrayOf(0, srcCap, img.width - srcCap, img.width)
    val sy = intArrayOf(0, srcCap, img.height - srcCap, img.height)
    val dx = intArrayOf(0, cap.roundToInt(), (w - cap).roundToInt(), w.roundToInt())
    val dy = intArrayOf(0, cap.roundToInt(), (h - cap).roundToInt(), h.roundToInt())
    for (r in 0..2) {
        for (c in 0..2) {
            drawImage(
                image = img,
                srcOffset = IntOffset(sx[c], sy[r]),
                srcSize = IntSize(sx[c + 1] - sx[c], sy[r + 1] - sy[r]),
                dstOffset = IntOffset(dx[c], dy[r]),
                dstSize = IntSize(dx[c + 1] - dx[c], dy[r + 1] - dy[r]),
                filterQuality = FilterQuality.High,
            )
        }
    }
}

/** Draws a nine-patch art behind the content, e.g. the frosted panel behind a dialog. */
@Composable
fun Modifier.lilyNinePatch(
    @DrawableRes res: Int,
    srcCap: Int,
    dstCap: androidx.compose.ui.unit.Dp,
): Modifier {
    val img = ImageBitmap.imageResource(res)
    return this.drawBehind { drawLilyNinePatch(img, srcCap, dstCap.toPx()) }
}
