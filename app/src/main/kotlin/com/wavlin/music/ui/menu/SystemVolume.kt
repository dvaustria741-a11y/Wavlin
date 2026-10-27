/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.menu

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** Device media volume (STREAM_MUSIC) as a 0..1 value that follows the hardware volume keys and system UI. */
@Stable
class SystemVolumeState(
    private val audioManager: AudioManager,
) {
    var volume by mutableFloatStateOf(read())
        private set

    private var lastUserChange = 0L

    private fun read(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun set(value: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val clamped = value.coerceIn(0f, 1f)
        volume = clamped
        lastUserChange = SystemClock.uptimeMillis()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (clamped * max).roundToInt(), 0)
    }

    /** Called when the system volume changes; ignored briefly after a drag so the thumb doesn't snap to a step. */
    fun refresh() {
        if (SystemClock.uptimeMillis() - lastUserChange < 300L) return
        volume = read()
    }
}

@Composable
fun rememberSystemVolume(): SystemVolumeState {
    val context = LocalContext.current
    val state =
        remember(context) {
            SystemVolumeState(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        }
    DisposableEffect(context, state) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = state.refresh()
            }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        state.refresh()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}
