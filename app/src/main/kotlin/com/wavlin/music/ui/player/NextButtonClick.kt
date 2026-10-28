/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.wavlin.music.constants.DoubleTapToSkipNextKey
import com.wavlin.music.playback.PlayerConnection
import com.wavlin.music.utils.rememberPreference

private const val DOUBLE_TAP_WINDOW_MS = 400L

/** One tap skips by default; with the "double tap to skip" setting on, the second tap within 400 ms skips. */
@Composable
fun rememberNextClickHandler(playerConnection: PlayerConnection): () -> Unit {
    val (doubleTapToSkip, _) = rememberPreference(DoubleTapToSkipNextKey, defaultValue = false)
    return remember(doubleTapToSkip, playerConnection) {
        var lastTap = 0L
        {
            if (!doubleTapToSkip) {
                playerConnection.seekToNext()
            } else {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap <= DOUBLE_TAP_WINDOW_MS) {
                    lastTap = 0L
                    playerConnection.seekToNext()
                } else {
                    lastTap = now
                }
            }
        }
    }
}
