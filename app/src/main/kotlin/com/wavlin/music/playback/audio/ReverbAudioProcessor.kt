/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.wavlin.music.audio.reverb.ReverbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Freeverb-style reverb (8 parallel combs + 4 series all-passes per channel) that runs inside
 * the player's audio processor chain, so it works on every device and with offload off or on.
 * Settings come from [ReverbManager] and are read on each buffer, so changes apply instantly.
 * Formats other than 16-bit PCM mono/stereo pass through untouched.
 */
@UnstableApi
@Suppress("DEPRECATION")
class ReverbAudioProcessor : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var supported = false

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private var combs: Array<Array<FloatArray>> = emptyArray()
    private var combLengths: Array<IntArray> = emptyArray()
    private var combIndex: Array<IntArray> = emptyArray()
    private var combFilter: Array<FloatArray> = emptyArray()
    private var allpasses: Array<Array<FloatArray>> = emptyArray()
    private var allpassLengths: Array<IntArray> = emptyArray()
    private var allpassIndex: Array<IntArray> = emptyArray()
    private var predelayBuffer = FloatArray(0)
    private var predelayIndex = 0

    private var appliedPreset: ReverbManager.Preset? = null

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            (channelCount == 1 || channelCount == 2) && sampleRate > 0
        if (supported) allocate()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    private fun allocate() {
        val ratio = sampleRate / 44100f
        combs = Array(channelCount) { ch ->
            Array(COMB_TUNINGS.size) { i ->
                FloatArray(ceil((COMB_TUNINGS[i] + ch * STEREO_SPREAD) * ratio * MAX_COMB_SCALE).toInt() + 2)
            }
        }
        combLengths = Array(channelCount) { IntArray(COMB_TUNINGS.size) { 1 } }
        combIndex = Array(channelCount) { IntArray(COMB_TUNINGS.size) }
        combFilter = Array(channelCount) { FloatArray(COMB_TUNINGS.size) }
        allpassLengths = Array(channelCount) { ch ->
            IntArray(ALLPASS_TUNINGS.size) { i -> ((ALLPASS_TUNINGS[i] + ch * STEREO_SPREAD) * ratio).roundToInt().coerceAtLeast(2) }
        }
        allpasses = Array(channelCount) { ch -> Array(ALLPASS_TUNINGS.size) { i -> FloatArray(allpassLengths[ch][i]) } }
        allpassIndex = Array(channelCount) { IntArray(ALLPASS_TUNINGS.size) }
        predelayBuffer = FloatArray((sampleRate * MAX_PREDELAY_MS / 1000f).toInt() + 2)
        predelayIndex = 0
        appliedPreset = null
    }

    private fun clearState() {
        combs.forEach { ch -> ch.forEach { it.fill(0f) } }
        combFilter.forEach { it.fill(0f) }
        allpasses.forEach { ch -> ch.forEach { it.fill(0f) } }
        predelayBuffer.fill(0f)
        combIndex.forEach { it.fill(0) }
        allpassIndex.forEach { it.fill(0) }
        predelayIndex = 0
    }

    private fun applyPreset(preset: ReverbManager.Preset) {
        val ratio = sampleRate / 44100f
        for (ch in 0 until channelCount) {
            for (i in COMB_TUNINGS.indices) {
                combLengths[ch][i] = ((COMB_TUNINGS[i] + ch * STEREO_SPREAD) * ratio * preset.combScale)
                    .roundToInt().coerceIn(8, combs[ch][i].size)
            }
        }
        clearState()
        appliedPreset = preset
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            outputBuffer = EMPTY_BUFFER
            return
        }
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        val preset = if (supported) ReverbManager.activePreset() else null
        val intensity = ReverbManager.intensity.value

        if (preset == null || intensity < 0.01f) {
            appliedPreset = null
            out.put(inputBuffer)
        } else {
            if (appliedPreset !== preset) applyPreset(preset)
            process(inputBuffer, out, preset, intensity)
        }
        out.flip()
    }

    private fun process(input: ByteBuffer, out: ByteBuffer, preset: ReverbManager.Preset, intensity: Float) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)

        val channels = channelCount
        val frames = input.remaining() / (2 * channels)
        val start = input.position()

        val feedback = (0.28f + preset.room * 0.7f).coerceAtMost(0.98f)
        val damp1 = preset.damping * 0.4f
        val damp2 = 1f - damp1
        val wetGain = preset.wet * intensity * 3f
        val dryGain = 1f - 0.3f * intensity
        val predelay = (sampleRate * preset.predelayMs / 1000).coerceIn(0, predelayBuffer.size - 1)

        for (frame in 0 until frames) {
            val base = start + frame * channels * 2
            val l = input.getShort(base) / 32768f
            val r = if (channels == 2) input.getShort(base + 2) / 32768f else l

            val mono = (l + r) * 0.5f * FIXED_GAIN * 2f
            predelayBuffer[predelayIndex] = mono
            val delayedIndex = (predelayIndex - predelay + predelayBuffer.size) % predelayBuffer.size
            val reverbIn = predelayBuffer[delayedIndex]
            predelayIndex = (predelayIndex + 1) % predelayBuffer.size

            for (ch in 0 until channels) {
                var acc = 0f
                for (i in COMB_TUNINGS.indices) {
                    val buf = combs[ch][i]
                    val idx = combIndex[ch][i]
                    val bufOut = buf[idx]
                    val filtered = bufOut * damp2 + combFilter[ch][i] * damp1
                    combFilter[ch][i] = filtered
                    buf[idx] = reverbIn + filtered * feedback
                    combIndex[ch][i] = if (idx + 1 >= combLengths[ch][i]) 0 else idx + 1
                    acc += bufOut
                }
                for (i in ALLPASS_TUNINGS.indices) {
                    val buf = allpasses[ch][i]
                    val idx = allpassIndex[ch][i]
                    val bufOut = buf[idx]
                    buf[idx] = acc + bufOut * 0.5f
                    acc = bufOut - acc
                    allpassIndex[ch][i] = if (idx + 1 >= allpassLengths[ch][i]) 0 else idx + 1
                }
                val dry = if (ch == 0) l else r
                val mixed = dry * dryGain + acc * wetGain
                out.putShort((mixed * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        input.position(start + frames * channels * 2)
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
        if (supported) clearState()
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        supported = false
        appliedPreset = null
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
        private val COMB_TUNINGS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_TUNINGS = intArrayOf(556, 441, 341, 225)
        private const val STEREO_SPREAD = 23
        private const val FIXED_GAIN = 0.015f
        private const val MAX_COMB_SCALE = 1.4f
        private const val MAX_PREDELAY_MS = 60
    }
}
