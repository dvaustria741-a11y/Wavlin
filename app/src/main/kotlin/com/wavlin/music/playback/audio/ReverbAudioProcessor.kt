/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * No-op audio processor.
 *
 * Reverb is now applied via Android's [android.media.audiofx.EnvironmentalReverb] hardware
 * effect managed by [com.wavlin.music.audio.reverb.ReverbManager].  Since that effect runs at
 * the system audio layer (outside ExoPlayer's processor chain), this class is kept as a
 * registered-but-inactive slot so the renderer factory signature stays unchanged.
 *
 * [isActive] returns false, so ExoPlayer bypasses this processor entirely and no audio data
 * is fed to [queueInput].
 */
@UnstableApi
class ReverbAudioProcessor : AudioProcessor {

    private var outputBuffer: ByteBuffer = EMPTY

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        inputAudioFormat   // Accept any format; we never touch the samples.

    /** Always inactive — hardware reverb handles the processing. */
    override fun isActive(): Boolean = false

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Never called while isActive() == false.
        outputBuffer = EMPTY
    }

    override fun queueEndOfStream() = Unit

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY
        return out
    }

    override fun isEnded(): Boolean = outputBuffer === EMPTY

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() { outputBuffer = EMPTY }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() { outputBuffer = EMPTY }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
