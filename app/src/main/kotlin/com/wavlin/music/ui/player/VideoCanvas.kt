/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.wavlin.music.models.MediaMetadata

/** Inactivity threshold before the canvas expands to full-screen. */
const val CANVAS_IDLE_TIMEOUT_MS = 5_000L

/**
 * Muted, looping video canvas driven by a dedicated [ExoPlayer] instance.
 *
 * Audio output is silenced (volume = 0) – the main MusicService player handles audio.
 * [TextureView] is used instead of SurfaceView so layers drawn above it in a Compose
 * Box (album-art, gradient, text) composite correctly.
 */
@Composable
fun VideoCanvasPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(videoUrl) {
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        exoPlayer.setVideoSurface(Surface(st))
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        exoPlayer.setVideoSurface(null)
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Full-screen idle overlay for the video canvas.
 *
 * Triggered after [CANVAS_IDLE_TIMEOUT_MS] of no user interaction.
 * The looping video fills the screen; a bottom gradient reveals song title + artist.
 * Tapping anywhere dismisses the overlay via [onExit].
 */
@Composable
fun VideoCanvasFullscreenOverlay(
    videoUrl: String,
    metadata: MediaMetadata?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExit,
            ),
    ) {
        // Looping video background
        VideoCanvasPlayer(
            videoUrl = videoUrl,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom-gradient scrim + song metadata
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.45f to Color.Transparent,
                            1.00f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (metadata != null) {
                Column(
                    modifier = Modifier
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
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = metadata.artists.joinToString(" \u00b7 ") { it.name },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
