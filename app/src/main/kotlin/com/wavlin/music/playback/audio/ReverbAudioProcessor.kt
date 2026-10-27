/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback.audio

import androidx.annotation.Keep
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Software Freeverb reverb applied in ExoPlayer's audio processor chain.
 *
 * Parameters are set by [com.wavlin.music.audio.reverb.ReverbManager] via
 * [setParameters] / [setEnabled].  [isActive] controls whether ExoPlayer
 * routes audio through here; when false the processor is bypassed entirely
 * and adds zero latency.
 *
 * Audio format assumed: PCM_FLOAT (4 bytes / sample), any channel count,
 * any sample rate.  For other encodings the processor is transparent.
 */
@UnstableApi
class ReverbAudioProcessor : AudioProcessor {

    // ── Freeverb tuning constants (calibrated for 44 100 Hz) ────────────────
    companion object {
        private val COMB_DELAYS    = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_DELAYS = intArrayOf(556, 441, 341, 225)
        private const val SPREAD   = 23      // stereo width offset (samples)
        private const val GAIN     = 0.015f  // input gain before comb filters
    }

    // ── Mutable parameters (written from main thread, read on audio thread) ──
    @Volatile private var roomFeedback = 0.84f   // comb feedback: room * 0.28 + 0.70
    @Volatile private var damp1        = 0.5f    // HF damping factor
    @Volatile private var damp2        = 0.5f    // 1 - damp1
    @Volatile private var wet          = 0.33f   // reverb send level
    @Volatile private var active       = false

    // ── Filter banks (L / R) ────────────────────────────────────────────────
    private var combL   = buildCombs(44100)
    private var combR   = buildCombs(44100, spread = SPREAD)
    private var apL     = buildAllpasses(44100)
    private var apR     = buildAllpasses(44100, spread = SPREAD)

    private var sampleRate   = 44100
    private var channelCount = 2
    private var isFloat      = true   // false → passthrough, audio format not PCM_FLOAT

    private var outputBuffer  = EMPTY
    private var inputEnded    = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API called by ReverbManager
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param room     room size   [0.0, 1.0]  (mapped: feedback = room * 0.28 + 0.70)
     * @param damping  HF damping  [0.0, 1.0]
     * @param wetLevel wet mix     [0.0, 1.0]
     */
    fun setParameters(room: Float, damping: Float, wetLevel: Float) {
        roomFeedback = room * 0.28f + 0.70f
        damp1 = damping
        damp2 = 1.0f - damping
        wet   = wetLevel
        pushToCombFilters()
    }

    fun setEnabled(enabled: Boolean) { active = enabled }

    // ─────────────────────────────────────────────────────────────────────────
    // AudioProcessor
    // ─────────────────────────────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        isFloat = (inputAudioFormat.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT)
        if (inputAudioFormat.sampleRate != sampleRate) {
            sampleRate = inputAudioFormat.sampleRate
            rebuildFilters()
        }
        return inputAudioFormat   // same format in and out
    }

    override fun isActive(): Boolean = active && isFloat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) { outputBuffer = EMPTY; return }

        // Ensure capacity
        if (outputBuffer === EMPTY || outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        outputBuffer.clear().limit(remaining)

        val inFB  = inputBuffer.asFloatBuffer()   // read-only view
        val outFB = outputBuffer.asFloatBuffer()   // write view
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

                // Skip any extra channels unchanged
                repeat(channelCount - 2) { outFB.put(inFB.get()) }
            }
        } else {
            // Mono
            for (f in 0 until frames) {
                val inS = inFB.get()
                var rev = 0f
                for (c in combL) rev += c.process(inS * GAIN)
                for (a in apL)   rev  = a.process(rev)
                outFB.put(inS + rev * wet)
            }
        }

        inputBuffer.position(inputBuffer.limit())   // mark input consumed
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
        inputEnded = false
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

    private fun pushToCombFilters() {
        combL.forEach { it.feedback = roomFeedback; it.damp1 = damp1; it.damp2 = damp2 }
        combR.forEach { it.feedback = roomFeedback; it.damp1 = damp1; it.damp2 = damp2 }
    }

    private fun rebuildFilters() {
        val scale = sampleRate.toDouble() / 44100.0
        combL = buildCombs(sampleRate, scale = scale)
        combR = buildCombs(sampleRate, scale = scale, spread = SPREAD)
        apL   = buildAllpasses(sampleRate, scale = scale)
        apR   = buildAllpasses(sampleRate, scale = scale, spread = SPREAD)
        pushToCombFilters()
    }

    private fun buildCombs(rate: Int, scale: Double = 1.0, spread: Int = 0) =
        Array(8) { i -> CombFilter(((COMB_DELAYS[i] + spread) * scale).toInt().coerceAtLeast(8)) }

    private fun buildAllpasses(rate: Int, scale: Double = 1.0, spread: Int = 0) =
        Array(4) { i -> AllpassFilter(((ALLPASS_DELAYS[i] + spread) * scale).toInt().coerceAtLeast(4)) }

    // ── Comb filter (Freeverb LPF-comb) ─────────────────────────────────────
    private class CombFilter(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var store = 0f
        var feedback = 0.84f
        var damp1    = 0.5f
        var damp2    = 0.5f

        fun process(input: Float): Float {
            val out = buf[idx]
            store = out * damp2 + store * damp1  // LPF on feedback
            buf[idx] = input + store * feedback
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
            val out = buf[idx]
            buf[idx] = input + out * 0.5f
            if (++idx >= buf.size) idx = 0
            return out - input
        }

        fun reset() { buf.fill(0f); idx = 0 }
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
