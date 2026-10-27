/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Software take on the system "Dolby Atmos" tuning (Enhanced dialogue + Expanded stereo),
 * applied in ExoPlayer's audio processor chain so it can be compared 1:1 with the phone's own Dolby.
 *
 * - **Enhanced dialogue (0..3)**: lifts the mid (centre) signal in the speech band (~2.5 kHz peaking
 *   filter) so vocals/dialogue stand out.
 * - **Expanded stereo (0..3)**: widens the side signal above ~250 Hz (bass stays centred).
 *
 * Settings are controlled by [com.wavlin.music.audio.dolby.DolbyAtmosManager]. Like
 * [ReverbAudioProcessor], [isActive] is always true and the processor bypasses itself while off, and
 * [flush]/[reset] only clear audio state, never the user settings. Only 2-channel PCM_16BIT / PCM_FLOAT
 * is processed; everything else is passed through unchanged.
 */
@UnstableApi
class DolbyAtmosAudioProcessor : AudioProcessor {

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        /** Peaking gain (dB) on the mid channel for dialogue level 0..3. */
        private val DIALOGUE_GAIN_DB = floatArrayOf(0f, 0.8f, 1.6f, 2.4f)

        /** Extra side-channel gain (above the high-pass corner) for expanded stereo level 0..3. */
        private val WIDEN_GAIN = floatArrayOf(0f, 0.25f, 0.55f, 0.85f)

        private const val DIALOGUE_FREQ_HZ = 2500.0
        private const val DIALOGUE_Q = 0.7
        private const val SIDE_HP_HZ = 250.0

        /** Per-frame smoothing of the dry/wet mix so toggling never clicks (~15 ms). */
        private const val MIX_SMOOTHING = 0.0015f
    }

    // ── Settings (main thread writes, audio thread reads) ────────────────────
    @Volatile private var enabled = false
    @Volatile private var dialogueLevel = 3
    @Volatile private var stereoLevel = 0

    // ── Audio-thread state ───────────────────────────────────────────────────
    private var sampleRate = 44100
    private var channelCount = 2
    private var isFloat = true
    private var bytesPerSample = 4

    private var appliedDialogue = -1
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    private var hpCoef = 0f
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    private var mix = 0f
    private var outGain = 1f
    private var outL = 0f
    private var outR = 0f

    private var buffer: ByteBuffer = EMPTY
    private var outputBuffer: ByteBuffer = EMPTY
    private var inputEnded = false

    // ── Public API (DolbyAtmosManager) ───────────────────────────────────────

    fun setParameters(enabled: Boolean, dialogueLevel: Int, stereoLevel: Int) {
        this.dialogueLevel = dialogueLevel.coerceIn(0, 3)
        this.stereoLevel = stereoLevel.coerceIn(0, 3)
        this.enabled = enabled
    }

    // ── AudioProcessor ───────────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        when (inputAudioFormat.encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> { isFloat = true; bytesPerSample = 4 }
            android.media.AudioFormat.ENCODING_PCM_16BIT -> { isFloat = false; bytesPerSample = 2 }
            else -> { isFloat = false; bytesPerSample = 0 }
        }
        sampleRate = inputAudioFormat.sampleRate
        appliedDialogue = -1 // force coefficient rebuild for the new sample rate
        hpCoef = (1.0 / (2.0 * PI * SIDE_HP_HZ)).let { rc -> (rc / (rc + 1.0 / sampleRate)).toFloat() }
        clearState()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        buffer.limit(remaining)

        val target = if (enabled) 1f else 0f
        val unsupported = bytesPerSample == 0 || channelCount != 2
        if (unsupported || (target == 0f && mix == 0f)) {
            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        val dLevel = dialogueLevel
        val widen = WIDEN_GAIN[stereoLevel]
        if (dLevel != appliedDialogue) updateDialogueFilter(dLevel)

        val frames = remaining / (bytesPerSample * 2)
        if (isFloat) {
            val inFB = inputBuffer.asFloatBuffer()
            val outFB = buffer.asFloatBuffer()
            repeat(frames) {
                val l = inFB.get()
                val r = inFB.get()
                processFrame(l, r, widen, target)
                outFB.put(outL)
                outFB.put(outR)
            }
        } else {
            val inSB = inputBuffer.asShortBuffer()
            val outSB = buffer.asShortBuffer()
            repeat(frames) {
                val l = inSB.get() / 32768f
                val r = inSB.get() / 32768f
                processFrame(l, r, widen, target)
                outSB.put((outL * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
                outSB.put((outR * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }

        inputBuffer.position(inputBuffer.limit())
        buffer.position(0)
        buffer.limit(frames * bytesPerSample * 2)
        outputBuffer = buffer
    }

    /** Writes the processed (dry/wet mixed) frame into [outL]/[outR]. */
    private fun processFrame(l: Float, r: Float, widen: Float, target: Float) {
        mix += (target - mix) * MIX_SMOOTHING
        if (target == 0f && mix < 0.0005f) mix = 0f

        var m = (l + r) * 0.5f
        var s = (l - r) * 0.5f

        // Enhanced dialogue: peaking filter on the mid signal (transposed direct form II).
        val my = b0 * m + z1
        z1 = b1 * m - a1 * my + z2
        z2 = b2 * m - a2 * my
        m = my

        // Expanded stereo: boost the side signal above the high-pass corner.
        val sHp = hpCoef * (hpPrevOut + s - hpPrevIn)
        hpPrevIn = s
        hpPrevOut = sHp
        s += widen * sHp

        val wl = (m + s) * outGain
        val wr = (m - s) * outGain
        outL = (l + (wl - l) * mix).coerceIn(-1f, 1f)
        outR = (r + (wr - r) * mix).coerceIn(-1f, 1f)
    }

    /** RBJ peaking EQ at [DIALOGUE_FREQ_HZ]; output gain leaves headroom for the boost. */
    private fun updateDialogueFilter(level: Int) {
        appliedDialogue = level
        val gainDb = DIALOGUE_GAIN_DB[level].toDouble()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * DIALOGUE_FREQ_HZ / sampleRate
        val alpha = sin(w0) / (2.0 * DIALOGUE_Q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha / a
        b0 = ((1.0 + alpha * a) / a0).toFloat()
        b1 = ((-2.0 * cosW) / a0).toFloat()
        b2 = ((1.0 - alpha * a) / a0).toFloat()
        a1 = ((-2.0 * cosW) / a0).toFloat()
        a2 = ((1.0 - alpha / a) / a0).toFloat()
        outGain = 10.0.pow(-gainDb * 0.5 / 20.0).toFloat()
    }

    override fun queueEndOfStream() { inputEnded = true }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY
        return out
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        inputEnded = false
        outputBuffer = EMPTY
        clearState()
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        buffer = EMPTY
    }

    private fun clearState() {
        z1 = 0f; z2 = 0f
        hpPrevIn = 0f; hpPrevOut = 0f
    }
}
