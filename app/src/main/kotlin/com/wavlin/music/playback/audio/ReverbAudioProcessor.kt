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
 * Software Freeverb reverb applied in ExoPlayer's audio processor chain.
 *
 * Parameters are controlled by [com.wavlin.music.audio.reverb.ReverbManager].
 *
 * [isActive] always returns true so ExoPlayer keeps this processor in the chain permanently
 * (ExoPlayer only evaluates it at configure-time, so a processor that starts out inactive would
 * never be fed audio again). While the effect is switched off the processor copies input to
 * output unchanged.
 *
 * IMPORTANT: [reset] and [flush] are called by ExoPlayer whenever the audio sink is torn down
 * (stop, new queue, output route change ...). They must only clear the audio *state* (delay
 * lines, buffers). The user-controlled settings ([setEnabled] / [setParameters]) have to
 * survive them, otherwise the effect silently turns itself off while the UI still shows it as on.
 *
 * Supports PCM_FLOAT and PCM_16BIT. Any other encoding is passed through unchanged.
 */
@UnstableApi
class ReverbAudioProcessor : AudioProcessor {

    companion object {
        // Comb-filter delay lengths calibrated for 44 100 Hz
        private val COMB_DELAYS    = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_DELAYS = intArrayOf(556, 441, 341, 225)
        private const val SPREAD   = 23       // stereo width (samples)
        private const val GAIN     = 0.015f   // input gain before comb bank

        // Classic Freeverb scaling: damping 0..1 maps to 0..0.4, wet 0..1 maps to 0..3.
        private const val DAMP_SCALE = 0.4f
        private const val WET_SCALE  = 3.0f

        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    // ── Parameters (written on main thread, read on audio thread) ────────────
    @Volatile private var roomFeedback = 0.84f   // comb feedback = room*0.28+0.70
    @Volatile private var damp1        = 0.2f
    @Volatile private var damp2        = 0.8f    // 1 - damp1
    @Volatile private var wet          = 1.0f
    @Volatile private var active       = false   // reverb on/off (NOT the same as isActive())

    // ── Filter banks ─────────────────────────────────────────────────────────
    private var combL = Array(8) { i -> CombFilter(COMB_DELAYS[i]) }
    private var combR = Array(8) { i -> CombFilter(COMB_DELAYS[i] + SPREAD) }
    private var apL   = Array(4) { i -> AllpassFilter(ALLPASS_DELAYS[i]) }
    private var apR   = Array(4) { i -> AllpassFilter(ALLPASS_DELAYS[i] + SPREAD) }

    private var sampleRate      = 44100
    private var channelCount    = 2
    private var isFloat         = true   // PCM_FLOAT → true, PCM_16BIT → false
    private var bytesPerSample  = 4      // 4 for float, 2 for s16, 0 for unsupported

    private var buffer: ByteBuffer       = EMPTY   // reusable storage
    private var outputBuffer: ByteBuffer = EMPTY   // pending output handed out by getOutput()
    private var inputEnded               = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called by ReverbManager
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param room     room size   [0, 1]  → comb feedback = room * 0.28 + 0.70
     * @param damping  HF damping  [0, 1]  (scaled by 0.4 like classic Freeverb)
     * @param wetLevel wet level   [0, 1]  (scaled by 3 like classic Freeverb)
     */
    fun setParameters(room: Float, damping: Float, wetLevel: Float) {
        roomFeedback = room.coerceIn(0f, 1f) * 0.28f + 0.70f
        damp1 = damping.coerceIn(0f, 1f) * DAMP_SCALE
        damp2 = 1.0f - damp1
        wet   = wetLevel.coerceIn(0f, 1f) * WET_SCALE
        pushParamsToCombFilters()
    }

    fun setEnabled(enabled: Boolean) { active = enabled }

    // ─────────────────────────────────────────────────────────────────────────
    // AudioProcessor
    // ─────────────────────────────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        when (inputAudioFormat.encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> { isFloat = true;  bytesPerSample = 4 }
            android.media.AudioFormat.ENCODING_PCM_16BIT -> { isFloat = false; bytesPerSample = 2 }
            else                                         -> { isFloat = false; bytesPerSample = 0 }
        }
        if (inputAudioFormat.sampleRate != sampleRate) {
            sampleRate = inputAudioFormat.sampleRate
            rebuildFilters()
        }
        return inputAudioFormat
    }

    /** Always true — see class doc. Bypass while disabled happens inside [queueInput]. */
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

        // Passthrough: reverb disabled or unsupported encoding → copy bytes unchanged.
        if (!active || bytesPerSample == 0) {
            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        val frameBytes = bytesPerSample * channelCount
        val frames = remaining / frameBytes

        if (isFloat) {
            // ── PCM_FLOAT path ────────────────────────────────────────────────
            val inFB  = inputBuffer.asFloatBuffer()
            val outFB = buffer.asFloatBuffer()

            if (channelCount >= 2) {
                for (f in 0 until frames) {
                    val inL = inFB.get()
                    val inR = inFB.get()
                    var revL = 0f; var revR = 0f
                    for (c in combL) revL += c.process(inL * GAIN)
                    for (c in combR) revR += c.process(inR * GAIN)
                    for (a in apL)   revL  = a.process(revL)
                    for (a in apR)   revR  = a.process(revR)
                    outFB.put((inL + revL * wet).coerceIn(-1f, 1f))
                    outFB.put((inR + revR * wet).coerceIn(-1f, 1f))
                    repeat(channelCount - 2) { outFB.put(inFB.get()) }
                }
            } else {
                for (f in 0 until frames) {
                    val inS = inFB.get()
                    var rev = 0f
                    for (c in combL) rev += c.process(inS * GAIN)
                    for (a in apL)   rev  = a.process(rev)
                    outFB.put((inS + rev * wet).coerceIn(-1f, 1f))
                }
            }
        } else {
            // ── PCM_16BIT path ────────────────────────────────────────────────
            val inSB  = inputBuffer.asShortBuffer()
            val outSB = buffer.asShortBuffer()

            if (channelCount >= 2) {
                for (f in 0 until frames) {
                    val inL = inSB.get() / 32768f
                    val inR = inSB.get() / 32768f
                    var revL = 0f; var revR = 0f
                    for (c in combL) revL += c.process(inL * GAIN)
                    for (c in combR) revR += c.process(inR * GAIN)
                    for (a in apL)   revL  = a.process(revL)
                    for (a in apR)   revR  = a.process(revR)
                    outSB.put(toShort(inL + revL * wet))
                    outSB.put(toShort(inR + revR * wet))
                    repeat(channelCount - 2) { outSB.put(inSB.get()) }
                }
            } else {
                for (f in 0 until frames) {
                    val inS = inSB.get() / 32768f
                    var rev = 0f
                    for (c in combL) rev += c.process(inS * GAIN)
                    for (a in apL)   rev  = a.process(rev)
                    outSB.put(toShort(inS + rev * wet))
                }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        buffer.position(0)
        buffer.limit(frames * frameBytes)
        outputBuffer = buffer
    }

    override fun queueEndOfStream() { inputEnded = true }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY
        return out
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    /** Clears audio state only (delay lines + pending output). Settings are kept. */
    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        inputEnded   = false
        outputBuffer = EMPTY
        clearFilterState()
    }

    /** Releases audio state only. Settings ([setEnabled]/[setParameters]) are deliberately kept. */
    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        buffer = EMPTY
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────

    private fun toShort(v: Float): Short =
        (v * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()

    private fun clearFilterState() {
        combL.forEach { it.reset() }; combR.forEach { it.reset() }
        apL.forEach   { it.reset() }; apR.forEach   { it.reset() }
    }

    private fun pushParamsToCombFilters() {
        combL.forEach { it.feedback = roomFeedback; it.damp1 = damp1; it.damp2 = damp2 }
        combR.forEach { it.feedback = roomFeedback; it.damp1 = damp1; it.damp2 = damp2 }
    }

    private fun rebuildFilters() {
        val scale = sampleRate.toDouble() / 44100.0
        combL = Array(8) { i -> CombFilter(((COMB_DELAYS[i])          * scale).toInt().coerceAtLeast(8)) }
        combR = Array(8) { i -> CombFilter(((COMB_DELAYS[i] + SPREAD)  * scale).toInt().coerceAtLeast(8)) }
        apL   = Array(4) { i -> AllpassFilter(((ALLPASS_DELAYS[i])         * scale).toInt().coerceAtLeast(4)) }
        apR   = Array(4) { i -> AllpassFilter(((ALLPASS_DELAYS[i] + SPREAD) * scale).toInt().coerceAtLeast(4)) }
        pushParamsToCombFilters()
    }

    // ── Comb filter (LPF-feedback comb, Freeverb style) ──────────────────────
    private class CombFilter(size: Int) {
        private val buf  = FloatArray(size)
        private var idx  = 0
        private var store = 0f
        var feedback = 0.84f
        var damp1    = 0.2f
        var damp2    = 0.8f

        fun process(input: Float): Float {
            val out   = buf[idx]
            store     = out * damp2 + store * damp1
            buf[idx]  = input + store * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }

        fun reset() { buf.fill(0f); store = 0f; idx = 0 }
    }

    // ── Allpass filter ────────────────────────────────────────────────────────
    private class AllpassFilter(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0

        fun process(input: Float): Float {
            val out  = buf[idx]
            buf[idx] = input + out * 0.5f
            if (++idx >= buf.size) idx = 0
            return out - input
        }

        fun reset() { buf.fill(0f); idx = 0 }
    }
}
