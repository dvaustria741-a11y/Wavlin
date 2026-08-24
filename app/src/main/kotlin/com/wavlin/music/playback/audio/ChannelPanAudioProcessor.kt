/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM pass-through processor that hard-pans a stereo stream to one channel, silencing the
 * other. Used by Dual Play mode so the main player can be confined to the left channel while
 * [DualPlayManager]'s partner player is confined to the right channel (or vice versa), letting
 * each side of a pair of headphones carry a different song.
 *
 * Only 2-channel PCM_16BIT / PCM_FLOAT input is handled; anything else (mono, >2ch, other
 * encodings) is passed through completely unchanged so this never breaks normal playback when
 * Dual Play isn't active.
 */
@UnstableApi
@Suppress("DEPRECATION")
class ChannelPanAudioProcessor : AudioProcessor {

    enum class PanMode {
        NONE,
        LEFT_ONLY,
        RIGHT_ONLY,
    }

    @Volatile
    var panMode: PanMode = PanMode.NONE

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }

        // Unsupported encoding, or not stereo: stay configured but inactive (pass-through).
        return inputAudioFormat
    }

    override fun isActive(): Boolean = channelCount == 2 && bytesPerSample > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val mode = panMode
        val size = inputBuffer.remaining()
        if (size == 0) {
            outputBuffer = EMPTY_BUFFER
            return
        }

        if (mode == PanMode.NONE || !isActive) {
            val out = replaceOutputBuffer(size)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val out = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)

        val frameCount = size / (bytesPerSample * 2)
        repeat(frameCount) {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val left = inputBuffer.short
                    val right = inputBuffer.short
                    when (mode) {
                        PanMode.LEFT_ONLY -> {
                            out.putShort(left)
                            out.putShort(0)
                        }
                        PanMode.RIGHT_ONLY -> {
                            out.putShort(0)
                            out.putShort(right)
                        }
                        PanMode.NONE -> {
                            out.putShort(left)
                            out.putShort(right)
                        }
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    val left = inputBuffer.float
                    val right = inputBuffer.float
                    when (mode) {
                        PanMode.LEFT_ONLY -> {
                            out.putFloat(left)
                            out.putFloat(0f)
                        }
                        PanMode.RIGHT_ONLY -> {
                            out.putFloat(0f)
                            out.putFloat(right)
                        }
                        PanMode.NONE -> {
                            out.putFloat(left)
                            out.putFloat(right)
                        }
                    }
                }
            }
        }
        out.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        channelCount = 0
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        // panMode is intentionally left untouched — it is controlled by DualPlayManager
        // and must survive player/track reconfiguration (e.g. format changes mid-song).
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
