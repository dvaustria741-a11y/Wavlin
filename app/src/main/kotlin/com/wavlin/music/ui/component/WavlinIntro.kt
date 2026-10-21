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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
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

/** Like [seg] but overshoots slightly before settling, for springy pop-ins. */
private fun segBack(
    p: Float,
    from: Float,
    to: Float,
): Float {
    val t = ((p - from) / (to - from)).coerceIn(0f, 1f)
    val c1 = 1.5f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/**
 * Animated launch intro with "Welcome to Wavlin". Tap anywhere to skip.
 * [pink] switches to the Secret Pink version (lilies, a blooming flower, falling petals and sparkles).
 *
 * Performance: the animation clock is only ever read inside graphicsLayer / draw lambdas (via the
 * `() -> Float` passed down), so nothing recomposes per frame - the whole intro is composed once.
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
        // Let the first frames of the app underneath settle before the clock starts, so the opening
        // of the animation is not eaten by startup work.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        progress.animateTo(1f, tween(3400, easing = LinearEasing))
        if (!done) {
            done = true
            exit.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
            onFinished()
        }
    }

    val p: () -> Float = { progress.value }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Exit: the intro gently zooms toward the viewer while fading, revealing the app.
                .graphicsLayer {
                    val e = exit.value
                    alpha = 1f - e
                    scaleX = 1f + 0.12f * e
                    scaleY = 1f + 0.12f * e
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (!done) {
                        done = true
                        scope.launch {
                            exit.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
                            onFinished()
                        }
                    }
                },
    ) {
        if (pink) PinkIntro(p) else DefaultIntro(p)
    }
}

@Composable
private fun LetterReveal(
    text: String,
    p: () -> Float,
    start: Float,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) {
    Row {
        text.forEachIndexed { i, c ->
            Text(
                text = c.toString(),
                fontSize = fontSize,
                color = color,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                modifier =
                    Modifier.graphicsLayer {
                        val from = start + i * 0.035f
                        val a = seg(p(), from, from + 0.12f)
                        val b = segBack(p(), from, from + 0.2f)
                        alpha = a
                        translationY = (1f - b) * 60f
                        scaleX = 0.7f + 0.3f * b
                        scaleY = 0.7f + 0.3f * b
                    },
            )
        }
    }
}

@Composable
private fun DefaultIntro(p: () -> Float) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground

    Box(modifier = Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pv = p()
            val center = Offset(size.width / 2f, size.height * 0.38f)

            // Soft glow spreading from the centre.
            val glow = seg(pv, 0f, 0.6f)
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = 0.28f * (1f - 0.4f * seg(pv, 0.7f, 1f))), Color.Transparent),
                        center = center,
                        radius = size.width * 0.95f * glow + 1f,
                    ),
            )

            // Two expanding rings.
            for (k in 0..1) {
                val r = seg(pv, 0.08f + k * 0.14f, 0.75f)
                drawCircle(
                    color = primary.copy(alpha = (1f - r) * 0.4f),
                    radius = r * minOf(size.width, size.height) * 0.55f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // Pulsing equalizer bars.
            val appear = seg(pv, 0.05f, 0.3f)
            val bars = 7
            val barW = 10.dp.toPx()
            val gap = 9.dp.toPx()
            val total = bars * barW + (bars - 1) * gap
            val maxH = 92.dp.toPx()
            for (i in 0 until bars) {
                val wave = 0.3f + 0.7f * abs(sin(pv * 15f + i * 0.85f))
                val h = maxH * appear * wave
                val x = center.x - total / 2f + i * (barW + gap)
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(x, center.y - h / 2f),
                    size = Size(barW, h.coerceAtLeast(barW)),
                    cornerRadius = CornerRadius(barW / 2f),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(300.dp))
            Text(
                text = stringResource(R.string.intro_welcome_to),
                fontSize = 22.sp,
                color = ink.copy(alpha = 0.75f),
                modifier =
                    Modifier.graphicsLayer {
                        val a = seg(p(), 0.28f, 0.5f)
                        alpha = a
                        translationY = (1f - a) * 30f
                    },
            )
            LetterReveal(
                text = stringResource(R.string.app_name),
                p = p,
                start = 0.4f,
                fontSize = 56.sp,
                color = ink,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Box(
                Modifier
                    .width(160.dp)
                    .height(3.dp)
                    .graphicsLayer {
                        scaleX = seg(p(), 0.62f, 0.85f)
                        transformOrigin = TransformOrigin.Center
                    }
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

// Falling petal layout: x position (fraction of width), size, start delay, sway speed, flutter speed.
private val petalX = floatArrayOf(0.10f, 0.80f, 0.32f, 0.92f, 0.55f, 0.20f, 0.68f, 0.44f, 0.04f)
private val petalSize = floatArrayOf(46f, 38f, 52f, 34f, 42f, 30f, 48f, 36f, 40f)
private val petalDelay = floatArrayOf(0.10f, 0.14f, 0.20f, 0.24f, 0.30f, 0.34f, 0.40f, 0.46f, 0.52f)

// Sparkle layout around the blooming flower: dx, dy (dp from bubble centre), size (dp), phase.
private val sparkles =
    arrayOf(
        floatArrayOf(-118f, -70f, 16f, 0.0f),
        floatArrayOf(112f, -88f, 20f, 0.7f),
        floatArrayOf(-96f, 78f, 12f, 1.4f),
        floatArrayOf(120f, 56f, 14f, 2.1f),
        floatArrayOf(-24f, -126f, 12f, 2.8f),
        floatArrayOf(40f, 128f, 16f, 3.5f),
        floatArrayOf(-140f, 4f, 10f, 4.2f),
        floatArrayOf(142f, -10f, 10f, 4.9f),
    )

private fun starPath(): Path =
    Path().apply {
        // Unit four-point star centred on (0,0), radius 1.
        val inner = 0.22f
        moveTo(0f, -1f)
        lineTo(inner, -inner)
        lineTo(1f, 0f)
        lineTo(inner, inner)
        lineTo(0f, 1f)
        lineTo(-inner, inner)
        lineTo(-1f, 0f)
        lineTo(-inner, -inner)
        close()
    }

@Composable
private fun PinkIntro(p: () -> Float) {
    val star = remember { starPath() }

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
            val dens = density.density

            // Lily corners glide in from the top and bottom with a little overshoot, then keep swaying.
            Image(
                painter = painterResource(R.drawable.lily_player_top),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            val pv = p()
                            val c = segBack(pv, 0f, 0.4f)
                            translationY = -(1f - c) * h * 0.25f + sin(pv * 9f) * 5f
                            alpha = seg(pv, 0f, 0.25f)
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            rotationZ = sin(pv * 7f) * 0.8f
                        },
            )
            Image(
                painter = painterResource(R.drawable.lily_player_bottom),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            val pv = p()
                            val c = segBack(pv, 0.04f, 0.44f)
                            translationY = (1f - c) * h * 0.25f - sin(pv * 8f + 1f) * 5f
                            alpha = seg(pv, 0.04f, 0.3f)
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            rotationZ = -sin(pv * 6f) * 0.8f
                        },
            )

            // Falling petals: sway side to side and flutter (flip around the horizontal axis) as they drop.
            for (i in petalX.indices) {
                Image(
                    painter = painterResource(petalRes[i % petalRes.size]),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(petalSize[i].dp)
                            .graphicsLayer {
                                val start = petalDelay[i]
                                val fall = ((p() - start) / (1f - start)).coerceIn(0f, 1f)
                                translationX = petalX[i] * w + sin(fall * 6f + i) * 42f
                                translationY = -0.1f * h + fall * h * 1.15f
                                rotationZ = i * 40f + fall * 260f
                                rotationX = sin(fall * 9f + i) * 55f
                                cameraDistance = 12f * dens
                                alpha = if (fall <= 0f) 0f else seg(fall, 0f, 0.08f) * (1f - seg(fall, 0.82f, 1f)) * 0.95f
                            },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Glass bubble with a bud that blooms into the open lily, a ripple and twinkling sparkles.
            Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pv = p()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    // Two soft ripples leave the bubble as the flower opens.
                    for (k in 0..1) {
                        val r = seg(pv, 0.42f + k * 0.08f, 0.85f)
                        if (r > 0f && r < 1f) {
                            drawCircle(
                                color = PinkSwoosh.copy(alpha = (1f - r) * 0.5f),
                                radius = size.width * (0.45f + r * 1.1f),
                                center = c,
                                style = Stroke(width = 2.dp.toPx() * (1f - r) + 0.5f),
                            )
                        }
                    }
                    // Sparkles twinkle in around the flower once it has opened.
                    val on = seg(pv, 0.5f, 0.66f)
                    if (on > 0f) {
                        for (sp in sparkles) {
                            val tw = 0.5f + 0.5f * sin(pv * 22f + sp[3])
                            val sz = sp[2].dp.toPx() * on * (0.55f + 0.45f * tw)
                            // Precompute pixel coords here: Dp.toPx() can't be called inside withTransform.
                            val tx = c.x + sp[0].dp.toPx()
                            val ty = c.y + sp[1].dp.toPx()
                            withTransform({
                                translate(tx, ty)
                                scale(sz, sz, Offset.Zero)
                                rotate(pv * 60f, Offset.Zero)
                            }) {
                                drawPath(star, Color.White.copy(alpha = 0.9f * on * (0.4f + 0.6f * tw)))
                            }
                        }
                    }
                }
                Image(
                    painter = painterResource(R.drawable.lily_bubble),
                    contentDescription = null,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                            val pv = p()
                            val b = segBack(pv, 0.05f, 0.35f)
                            alpha = seg(pv, 0.05f, 0.2f)
                            // Gentle floating bob once it has popped in.
                            translationY = sin(pv * 10f) * 6f * seg(pv, 0.35f, 0.5f)
                            scaleX = 0.5f + 0.5f * b
                            scaleY = 0.5f + 0.5f * b
                        },
                )
                Image(
                    painter = painterResource(R.drawable.lily_buds),
                    contentDescription = null,
                    modifier =
                        Modifier.size(130.dp).graphicsLayer {
                            val pv = p()
                            val bloom = seg(pv, 0.3f, 0.5f)
                            val budsIn = segBack(pv, 0.12f, 0.3f)
                            alpha = seg(pv, 0.12f, 0.24f) * (1f - bloom)
                            translationY = sin(pv * 10f) * 6f * seg(pv, 0.35f, 0.5f)
                            scaleX = 0.7f + 0.3f * budsIn
                            scaleY = 0.7f + 0.3f * budsIn
                        },
                )
                Image(
                    painter = painterResource(R.drawable.lily_flower),
                    contentDescription = null,
                    modifier =
                        Modifier.size(150.dp).graphicsLayer {
                            val pv = p()
                            val a = seg(pv, 0.3f, 0.44f)
                            val bloom = segBack(pv, 0.3f, 0.6f)
                            alpha = a
                            translationY = sin(pv * 10f) * 6f * seg(pv, 0.35f, 0.5f)
                            scaleX = 0.4f + 0.6f * bloom
                            scaleY = 0.4f + 0.6f * bloom
                            rotationZ = (1f - bloom) * -24f + sin(pv * 6f) * 2f * a
                        },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.intro_welcome_to),
                fontFamily = playball,
                fontSize = 32.sp,
                color = PinkInk,
                modifier =
                    Modifier.graphicsLayer {
                        val a = seg(p(), 0.5f, 0.68f)
                        alpha = a
                        translationY = (1f - a) * 30f
                    },
            )
            Box(contentAlignment = Alignment.Center) {
                LetterReveal(
                    text = stringResource(R.string.app_name),
                    p = p,
                    start = 0.58f,
                    fontSize = 76.sp,
                    color = PinkInk,
                    fontFamily = playball,
                )
                // A sparkle glides across the name once it has appeared.
                Canvas(modifier = Modifier.matchParentSize()) {
                    val pv = p()
                    val t = seg(pv, 0.8f, 0.97f)
                    if (t > 0f && t < 1f) {
                        val x = size.width * (-0.05f + 1.1f * t)
                        val y = size.height * (0.55f - 0.1f * sin(t * 3.1416f))
                        val sz = 14.dp.toPx() * sin(t * 3.1416f)
                        withTransform({
                            translate(x, y)
                            scale(sz, sz, Offset.Zero)
                        }) {
                            drawPath(star, Color.White)
                        }
                    }
                }
            }

            // Swoosh that draws itself under the name.
            Canvas(modifier = Modifier.size(width = 190.dp, height = 16.dp)) {
                val draw = seg(p(), 0.78f, 0.95f)
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

            Image(
                painter = painterResource(R.drawable.lily_bow),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier.width(240.dp).graphicsLayer {
                        val bow = segBack(p(), 0.86f, 1f)
                        alpha = seg(p(), 0.86f, 0.98f)
                        translationY = (1f - bow) * 24f
                        scaleX = 0.85f + 0.15f * bow
                        scaleY = 0.85f + 0.15f * bow
                    },
            )
        }
    }
}
