/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.wavlin.music.models.MediaMetadata

/** Inactivity before the player controls fade out and the canvas is shown on its own. */
const val CANVAS_IDLE_TIMEOUT_MS = 5_000L

/**
 * Muted, endlessly looping canvas video on its own [ExoPlayer].
 *
 * The video is cropped to fill the box (like a 9:16 canvas on a tall phone) and fades in once the
 * first frame is actually rendered, so there is never a black flash. Audio comes from MusicService.
 */
@Composable
fun VideoCanvasPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
) {
    // A fresh player, surface and fade-in per video: reusing one across songs left the next canvas invisible
    androidx.compose.runtime.key(videoUrl) {
        VideoCanvasPlayerContent(videoUrl = videoUrl, modifier = modifier)
    }
}

@Composable
private fun VideoCanvasPlayerContent(
    videoUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var firstFrameShown by remember(videoUrl) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (firstFrameShown) 1f else 0f,
        animationSpec = tween(700),
        label = "canvasVideoAlpha",
    )

    val exoPlayer =
        remember {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
        }

    DisposableEffect(exoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrameShown = true
                }
            }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(videoUrl) {
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                tv.surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture,
                            w: Int,
                            h: Int,
                        ) {
                            exoPlayer.setVideoSurface(Surface(st))
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            w: Int,
                            h: Int,
                        ) {}

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            exoPlayer.setVideoSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
            }
        },
        modifier = modifier.alpha(videoAlpha),
    )
}

/**
 * Shown above the faded-out controls after [CANVAS_IDLE_TIMEOUT_MS] without a touch: the canvas
 * (playing behind the player) stays on its own with a bottom gradient and the song title and
 * artist. Tapping anywhere calls [onExit] and the controls come back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCanvasIdleOverlay(
    metadata: MediaMetadata?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExit,
                ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.55f to Color.Transparent,
                                    1.00f to Color.Black.copy(alpha = 0.85f),
                                ),
                        ),
                    ),
        )
        if (metadata != null) {
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = 28.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 48.dp),
            ) {
                Text(
                    text = metadata.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier =
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1000,
                            repeatDelayMillis = 1500,
                            velocity = 40.dp,
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = metadata.artists.joinToString(" \u00b7 ") { it.name },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier =
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1000,
                            repeatDelayMillis = 1500,
                            velocity = 40.dp,
                        ),
                )
            }
        }
    }
}
