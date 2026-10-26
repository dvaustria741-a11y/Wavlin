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
import kotlin.math.roundToInt

/**
 * Software reverb AudioProcessor using a Freeverb (Schroeder-Moorer) algorithm.
 *
 * Replaces the previous EnvironmentalReverb hardware-effect approach which silently
 * did nothing on most devices. This processor runs entirely in ExoPlayer's audio
 * pipeline so it works on all devices, exactly like [CustomEqualizerAudioProcessor].
 *
 * Algorithm: 8 parallel comb filters (feedback + lowpass) summed, then 4 series
 * all-pass filters, mixed wet+dry. Stereo uses independent L/R filter banks with a
 * 23-sample delay spread for width.
 *
 * Parameters are pushed by [com.wavlin.music.audio.reverb.ReverbManager] via
 * [setReverbParams] whenever the user changes preset, intensity or enable state.
 */
@UnstableApi
class ReverbAudioProcessor : AudioProcessor {

    // ── Format state ─────────────────────────────────────────────────────────────
    private var sampleRate   = 0
    private var channelCount = 0
    private var encoding     = C.ENCODING_INVALID
    private var processorActive = false

    private var outputBuffer: ByteBuffer = EMPTY
    private var inputEnded = false

    // ── Reverb parameters (volatile – written on main thread, read on audio thread) ──
    @Volatile private var reverbEnabled = false
    @Volatile private var wetLevel      = 0f
    @Volatile private var dryLevel      = 0.7f   // keep direct signal audible

    // Comb/allpass filter state (only touched on audio thread after configure())
    private var combL  = emptyArray<CombFilter>()
    private var combR  = emptyArray<CombFilter>()
    private var apL    = emptyArray<AllPassFilter>()
    private var apR    = emptyArray<AllPassFilter>()

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        // Standard Freeverb tuning delays at 44100 Hz
        private val COMB_DELAYS    = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_DELAYS = intArrayOf(556, 441, 341, 225)
        private const val STEREO_SPREAD = 23

        // Input gain that keeps comb filter accumulators in [-1, 1]
        private const val FIXED_GAIN = 0.007f
    }

    // ── Called by ReverbManager ───────────────────────────────────────────────────

    /**
     * Push current enabled/preset/intensity state.  Safe to call from any thread.
     *
     * @param enabled   whether reverb is active
     * @param roomSize  0..1 — maps to comb-filter feedback (longer tail at higher values)
     * @param damping   0..1 — high-frequency absorption (0 = bright, 1 = dark)
     * @param wet       0..1 — wet mix level (already scaled by intensity in ReverbManager)
     */
    fun setReverbParams(enabled: Boolean, roomSize: Float, damping: Float, wet: Float) {
        // Push scalar params first; reverbEnabled last so the audio thread never
        // sees a stale wetLevel when it reads reverbEnabled == true.
        wetLevel = wet
        dryLevel = 0.7f
        updateCombParams(roomSize, damping)
        reverbEnabled = enabled
    }

    private fun updateCombParams(roomSize: Float, damping: Float) {
        // feedback ∈ [0.28, 0.98], damp1 controls lowpass cutoff
        val feedback = roomSize * 0.70f + 0.28f
        val damp1    = damping * 0.40f
        combL.forEach { it.setFeedbackAndDamp(feedback, damp1) }
        combR.forEach { it.setFeedbackAndDamp(feedback, damp1) }
    }

    // ── AudioProcessor lifecycle ──────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate   = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding     = inputAudioFormat.encoding

        if (encoding != C.ENCODING_PCM_16BIT || channelCount < 1 || channelCount > 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        rebuildFilters()
        processorActive = true
        return inputAudioFormat
    }

    private fun rebuildFilters() {
        val scale = sampleRate.toFloat() / 44100f
        val feedback = combL.firstOrNull()?.currentFeedback ?: 0.84f
        val damp1    = combL.firstOrNull()?.currentDamp1    ?: 0.20f

        combL = Array(COMB_DELAYS.size)    { i -> CombFilter((COMB_DELAYS[i]    * scale).roundToInt()).also { it.setFeedbackAndDamp(feedback, damp1) } }
        combR = Array(COMB_DELAYS.size)    { i -> CombFilter(((COMB_DELAYS[i] + STEREO_SPREAD) * scale).roundToInt()).also { it.setFeedbackAndDamp(feedback, damp1) } }
        apL   = Array(ALLPASS_DELAYS.size) { i -> AllPassFilter((ALLPASS_DELAYS[i]    * scale).roundToInt()) }
        apR   = Array(ALLPASS_DELAYS.size) { i -> AllPassFilter(((ALLPASS_DELAYS[i] + STEREO_SPREAD) * scale).roundToInt()) }
    }

    override fun isActive(): Boolean = processorActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        if (outputBuffer === EMPTY || outputBuffer.capacity() < inputSize) {
            outputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        if (!reverbEnabled) {
            // Passthrough
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        when (channelCount) {
            1    -> processMono(inputBuffer, outputBuffer)
            else -> processStereo(inputBuffer, outputBuffer)
        }
        outputBuffer.flip()
    }

    private fun processMono(input: ByteBuffer, output: ByteBuffer) {
        val wet = wetLevel
        val dry = dryLevel
        while (input.remaining() >= 2) {
            val sample = input.getShort().toFloat() / 32768f
            val revIn  = sample * FIXED_GAIN

            var reverbOut = 0f
            for (cf in combL) reverbOut += cf.process(revIn)
            for (ap in apL)   reverbOut  = ap.process(reverbOut)

            val mixed = sample * dry + reverbOut * wet
            output.putShort(mixed.coerceIn(-1f, 1f).times(32767f).toInt().toShort())
        }
    }

    private fun processStereo(input: ByteBuffer, output: ByteBuffer) {
        val wet = wetLevel
        val dry = dryLevel
        while (input.remaining() >= 4) {
            val dryL  = input.getShort().toFloat() / 32768f
            val dryR  = input.getShort().toFloat() / 32768f
            val revIn = (dryL + dryR) * FIXED_GAIN   // mono sum into reverb (standard Freeverb)

            var reverbL = 0f
            var reverbR = 0f
            for (i in combL.indices) {
                reverbL += combL[i].process(revIn)
                reverbR += combR[i].process(revIn)
            }
            for (ap in apL) reverbL = ap.process(reverbL)
            for (ap in apR) reverbR = ap.process(reverbR)

            val mixedL = dryL * dry + reverbL * wet
            val mixedR = dryR * dry + reverbR * wet

            output.putShort(mixedL.coerceIn(-1f, 1f).times(32767f).toInt().toShort())
            output.putShort(mixedR.coerceIn(-1f, 1f).times(32767f).toInt().toShort())
        }
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer.remaining() == 0

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY
        inputEnded   = false
        combL.forEach { it.clear() }
        combR.forEach { it.clear() }
        apL.forEach   { it.clear() }
        apR.forEach   { it.clear() }
    }

    override fun reset() {
        @Suppress("DEPRECATION")
        flush()
        sampleRate      = 0
        channelCount    = 0
        encoding        = C.ENCODING_INVALID
        processorActive = false
    }

    override fun queueEndOfStream() { inputEnded = true }

    // ── DSP building blocks ───────────────────────────────────────────────────────

    /** Feedback comb filter with first-order lowpass on the feedback path (Freeverb-style). */
    private class CombFilter(size: Int) {
        private val buffer    = FloatArray(size.coerceAtLeast(1))
        private var index     = 0
        private var filterStore = 0f

        var currentFeedback = 0.84f; private set
        var currentDamp1    = 0.20f; private set
        private var damp2   = 0.80f

        fun setFeedbackAndDamp(feedback: Float, damp1: Float) {
            currentFeedback = feedback
            currentDamp1    = damp1
            damp2           = 1f - damp1
        }

        fun process(input: Float): Float {
            val output = buffer[index]
            filterStore = output * damp2 + filterStore * currentDamp1
            buffer[index] = input + filterStore * currentFeedback
            if (++index >= buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            filterStore = 0f
            index       = 0
        }
    }

    /** All-pass filter (Schroeder) with fixed 0.5 gain. */
    private class AllPassFilter(size: Int) {
        private val buffer = FloatArray(size.coerceAtLeast(1))
        private var index  = 0

        fun process(input: Float): Float {
            val bufOut = buffer[index]
            val output = -input + bufOut
            buffer[index] = input + bufOut * 0.5f
            if (++index >= buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
        }
    }
}
