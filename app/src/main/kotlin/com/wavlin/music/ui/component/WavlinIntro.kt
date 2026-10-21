/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavlin.music.R
import com.wavlin.music.ui.theme.PinkInk
import com.wavlin.music.ui.theme.PinkSwoosh
import com.wavlin.music.ui.theme.playball
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/** Remembers that the intro already played in this process so rotation or recreation does not replay it. */
object IntroState {
    @Volatile
    var shown = false
}

private fun seg(
    p: Float,
    from: Float,
    to: Float,
): Float = FastOutSlowInEasing.transform(((p - from) / (to - from)).coerceIn(0f, 1f))

/**
 * Animated launch intro with "Welcome to Wavlin". Tap anywhere to skip.
 * [pink] switches to the Secret Pink version (watercolor, lilies, petals and a blooming flower).
 */
@Composable
fun WavlinIntro(
    pink: Boolean,
    onFinished: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(3400, easing = LinearEasing))
        if (!done) {
            done = true
            exit.animateTo(1f, tween(450))
            onFinished()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - exit.value }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (!done) {
                        done = true
                        scope.launch {
                            exit.animateTo(1f, tween(250))
                            onFinished()
                        }
                    }
                },
    ) {
        val p = progress.value
        if (pink) PinkIntro(p) else DefaultIntro(p)
    }
}

@Composable
private fun LetterReveal(
    text: String,
    p: Float,
    start: Float,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) {
    Row {
        text.forEachIndexed { i, c ->
            val a = seg(p, start + i * 0.04f, start + 0.16f + i * 0.04f)
            Text(
                text = c.toString(),
                fontSize = fontSize,
                color = color,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = a
                        translationY = (1f - a) * 48f
                        scaleX = 0.8f + 0.2f * a
                        scaleY = 0.8f + 0.2f * a
                    },
            )
        }
    }
}

@Composable
private fun DefaultIntro(p: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground

    Box(modifier = Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.38f)

            // Soft glow spreading from the centre.
            val glow = seg(p, 0f, 0.6f)
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = 0.28f * (1f - 0.4f * seg(p, 0.7f, 1f))), Color.Transparent),
                        center = center,
                        radius = size.width * 0.95f * glow + 1f,
                    ),
            )

            // Two expanding rings.
            for (k in 0..1) {
                val r = seg(p, 0.08f + k * 0.14f, 0.75f)
                drawCircle(
                    color = primary.copy(alpha = (1f - r) * 0.4f),
                    radius = r * minOf(size.width, size.height) * 0.55f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // Pulsing equalizer bars.
            val appear = seg(p, 0.05f, 0.3f)
            val bars = 7
            val barW = 10.dp.toPx()
            val gap = 9.dp.toPx()
            val total = bars * barW + (bars - 1) * gap
            val maxH = 92.dp.toPx()
            for (i in 0 until bars) {
                val wave = 0.3f + 0.7f * abs(sin(p * 15f + i * 0.85f))
                val h = maxH * appear * wave
                val x = center.x - total / 2f + i * (barW + gap)
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(x, center.y - h / 2f),
                    size = androidx.compose.ui.geometry.Size(barW, h.coerceAtLeast(barW)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(300.dp))
            val a = seg(p, 0.28f, 0.5f)
            Text(
                text = stringResource(R.string.intro_welcome_to),
                fontSize = 22.sp,
                color = ink.copy(alpha = 0.75f),
                modifier = Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 30f },
            )
            LetterReveal(
                text = stringResource(R.string.app_name),
                p = p,
                start = 0.4f,
                fontSize = 56.sp,
                color = ink,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            val line = seg(p, 0.62f, 0.85f)
            Box(
                Modifier
                    .width(160.dp * line)
                    .height(3.dp)
                    .background(primary, androidx.compose.foundation.shape.RoundedCornerShape(50)),
            )
        }
    }
}

private val petalRes =
    intArrayOf(
        R.drawable.lily_petal_1,
        R.drawable.lily_petal_2,
        R.drawable.lily_petal_3,
        R.drawable.lily_petal_4,
        R.drawable.lily_petal_5,
    )

@Composable
private fun PinkIntro(p: Float) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.lily_player_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }

            // Lily corners slide in from the top and bottom.
            val corners = seg(p, 0f, 0.4f)
            Image(
                painter = painterResource(R.drawable.lily_player_top),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .graphicsLayer { translationY = -(1f - corners) * h * 0.25f; alpha = corners },
            )
            Image(
                painter = painterResource(R.drawable.lily_player_bottom),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { translationY = (1f - corners) * h * 0.25f; alpha = corners },
            )

            // Falling petals.
            val xs = floatArrayOf(0.12f, 0.78f, 0.35f, 0.9f, 0.55f, 0.2f, 0.68f)
            val sizes = floatArrayOf(46f, 38f, 52f, 34f, 42f, 30f, 48f)
            for (i in xs.indices) {
                val start = i * 0.07f
                val fall = ((p - start) / (1f - start)).coerceIn(0f, 1f)
                Image(
                    painter = painterResource(petalRes[i % petalRes.size]),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(sizes[i].dp)
                            .graphicsLayer {
                                translationX = xs[i] * w + sin(fall * 6f + i) * 36f
                                translationY = -0.1f * h + fall * h * 1.15f
                                rotationZ = i * 40f + fall * 240f
                                alpha = if (fall <= 0f) 0f else (1f - seg(fall, 0.8f, 1f)) * 0.95f
                            },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Glass bubble with a bud that blooms into the open lily.
            Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                val bubble = seg(p, 0.05f, 0.35f)
                Image(
                    painter = painterResource(R.drawable.lily_bubble),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = bubble
                        scaleX = 0.6f + 0.4f * bubble
                        scaleY = 0.6f + 0.4f * bubble
                    },
                )
                val bloom = seg(p, 0.3f, 0.58f)
                val budsIn = seg(p, 0.12f, 0.3f)
                Image(
                    painter = painterResource(R.drawable.lily_buds),
                    contentDescription = null,
                    modifier = Modifier.size(130.dp).graphicsLayer {
                        alpha = budsIn * (1f - bloom)
                        scaleX = 0.85f + 0.15f * budsIn
                        scaleY = 0.85f + 0.15f * budsIn
                    },
                )
                Image(
                    painter = painterResource(R.drawable.lily_flower),
                    contentDescription = null,
                    modifier = Modifier.size(150.dp).graphicsLayer {
                        alpha = bloom
                        scaleX = 0.5f + 0.5f * bloom
                        scaleY = 0.5f + 0.5f * bloom
                        rotationZ = (1f - bloom) * -18f
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            val a = seg(p, 0.5f, 0.68f)
            Text(
                text = stringResource(R.string.intro_welcome_to),
                fontFamily = playball,
                fontSize = 32.sp,
                color = PinkInk,
                modifier = Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 30f },
            )
            LetterReveal(
                text = stringResource(R.string.app_name),
                p = p,
                start = 0.58f,
                fontSize = 76.sp,
                color = PinkInk,
                fontFamily = playball,
            )

            // Swoosh that draws itself under the name.
            val draw = seg(p, 0.78f, 0.95f)
            Canvas(modifier = Modifier.size(width = 190.dp, height = 16.dp)) {
                val path =
                    Path().apply {
                        moveTo(0f, size.height * 0.85f)
                        cubicTo(
                            size.width * 0.3f, size.height * 0.1f,
                            size.width * 0.7f, size.height * 1.0f,
                            size.width, size.height * 0.2f,
                        )
                    }
                val measure = PathMeasure().apply { setPath(path, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * draw, part, true)
                drawPath(part, PinkSwoosh, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }

            val bow = seg(p, 0.86f, 1f)
            Image(
                painter = painterResource(R.drawable.lily_bow),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.width(240.dp).graphicsLayer { alpha = bow; translationY = (1f - bow) * 20f },
            )
        }
    }
}
