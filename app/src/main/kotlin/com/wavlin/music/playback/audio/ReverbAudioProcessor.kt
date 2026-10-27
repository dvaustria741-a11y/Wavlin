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
 * [isActive] returns false when disabled, so ExoPlayer bypasses this processor
 * entirely and adds zero latency overhead.
 *
 * Assumes PCM_FLOAT input (4 bytes / sample). If another encoding arrives the
 * processor deactivates itself and passes audio through unchanged.
 */
@UnstableApi
class ReverbAudioProcessor : AudioProcessor {

    companion object {
        // Comb-filter delay lengths calibrated for 44 100 Hz
        private val COMB_DELAYS    = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_DELAYS = intArrayOf(556, 441, 341, 225)
        private const val SPREAD   = 23       // stereo width (samples)
        private const val GAIN     = 0.015f   // input gain before comb bank
        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    // ── Parameters (written on main thread, read on audio thread) ────────────
    @Volatile private var roomFeedback = 0.84f   // comb feedback = room*0.28+0.70
    @Volatile private var damp1        = 0.5f
    @Volatile private var damp2        = 0.5f    // 1 - damp1
    @Volatile private var wet          = 0.33f
    @Volatile private var active       = false

    // ── Filter banks ─────────────────────────────────────────────────────────
    private var combL = Array(8) { i -> CombFilter(COMB_DELAYS[i]) }
    private var combR = Array(8) { i -> CombFilter(COMB_DELAYS[i] + SPREAD) }
    private var apL   = Array(4) { i -> AllpassFilter(ALLPASS_DELAYS[i]) }
    private var apR   = Array(4) { i -> AllpassFilter(ALLPASS_DELAYS[i] + SPREAD) }

    private var sampleRate   = 44100
    private var channelCount = 2
    private var isFloat      = true   // false → passthrough only

    private var outputBuffer: ByteBuffer = EMPTY
    private var inputEnded               = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called by ReverbManager
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param room     room size   [0, 1]  → comb feedback = room * 0.28 + 0.70
     * @param damping  HF damping  [0, 1]
     * @param wetLevel wet level   [0, 1]
     */
    fun setParameters(room: Float, damping: Float, wetLevel: Float) {
        roomFeedback = room * 0.28f + 0.70f
        damp1 = damping
        damp2 = 1.0f - damping
        wet   = wetLevel
        pushParamsToCombFilters()
    }

    fun setEnabled(enabled: Boolean) { active = enabled }

    // ─────────────────────────────────────────────────────────────────────────
    // AudioProcessor
    // ─────────────────────────────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        isFloat      = (inputAudioFormat.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT)
        if (inputAudioFormat.sampleRate != sampleRate) {
            sampleRate = inputAudioFormat.sampleRate
            rebuildFilters()
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = active && isFloat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) {
            outputBuffer = EMPTY
            return
        }

        if (outputBuffer === EMPTY || outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        outputBuffer.clear()
        outputBuffer.limit(remaining)

        val inFB  = inputBuffer.asFloatBuffer()
        val outFB = outputBuffer.asFloatBuffer()
        val frames = remaining / (4 * channelCount)

        if (channelCount >= 2) {
            for (f in 0 until frames) {
                val inL = inFB.get()
                val inR = inFB.get()
                var revL = 0f; var revR = 0f
                for (c in combL) revL += c.process(inL * GAIN)
                for (c in combR) revR += c.process(inR * GAIN)
                for (a in apL)   revL  = a.process(revL)
                for (a in apR)   revR  = a.process(revR)
                outFB.put(inL + revL * wet)
                outFB.put(inR + revR * wet)
                repeat(channelCount - 2) { outFB.put(inFB.get()) }
            }
        } else {
            for (f in 0 until frames) {
                val inS = inFB.get()
                var rev = 0f
                for (c in combL) rev += c.process(inS * GAIN)
                for (a in apL)   rev  = a.process(rev)
                outFB.put(inS + rev * wet)
            }
        }

        inputBuffer.position(inputBuffer.limit())
        outputBuffer.position(0)
    }

    override fun queueEndOfStream() { inputEnded = true }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        inputEnded   = false
        outputBuffer = EMPTY
        combL.forEach { it.reset() }; combR.forEach { it.reset() }
        apL.forEach   { it.reset() }; apR.forEach   { it.reset() }
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        roomFeedback = 0.84f; damp1 = 0.5f; damp2 = 0.5f; wet = 0.33f; active = false
        flush()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────

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
        var damp1    = 0.5f
        var damp2    = 0.5f

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
