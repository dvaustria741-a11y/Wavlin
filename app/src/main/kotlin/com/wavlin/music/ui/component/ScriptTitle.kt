package com.wavlin.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavlin.music.R
import com.wavlin.music.ui.theme.PinkInk
import com.wavlin.music.ui.theme.PinkSwoosh
import com.wavlin.music.ui.theme.playball

/**
 * Big handwritten page title for the Secret Pink theme: Playball script, a faint heart
 * and a hand-drawn swoosh underline that tapers off to the right.
 */
@Composable
fun ScriptTitle(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 44.sp,
    showHeart: Boolean = true,
    swooshWidth: androidx.compose.ui.unit.Dp = 118.dp,
) {
    Box(modifier = modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = text,
                fontFamily = playball,
                fontSize = fontSize,
                lineHeight = fontSize * 1.1f,
                color = PinkInk,
                maxLines = 1,
            )
            if (showHeart) Icon(
                painter = painterResource(R.drawable.ic_heart_outline),
                contentDescription = null,
                tint = PinkSwoosh.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 4.dp, bottom = 14.dp).size(16.dp),
            )
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(y = 2.dp)
                .padding(start = 6.dp)
                .size(width = swooshWidth, height = 12.dp),
        ) {
            val path = Path().apply {
                moveTo(0f, size.height * 0.85f)
                cubicTo(
                    size.width * 0.30f, size.height * 0.15f,
                    size.width * 0.70f, size.height * 0.95f,
                    size.width, size.height * 0.20f,
                )
            }
            drawPath(
                path = path,
                color = PinkSwoosh,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
