/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.mix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-player DSP used while two tracks overlap in a mixed playlist: a low-pass and a high-pass sweep,
 * a low-shelf for bass swaps, and a tempo-synced echo or a small diffuse "space" wash.
 *
 * It stays in the chain permanently and copies audio untouched while every control is neutral.
 * Supports mono and stereo PCM_16BIT / PCM_FLOAT.
 */
@UnstableApi
class MixAudioProcessor : BaseAudioProcessor() {
    @Volatile private var lowPassHz = MixCurves.LP_OPEN

    @Volatile private var highPassHz = MixCurves.HP_OPEN

    @Volatile private var bassDb = 0f

    @Volatile private var effect = MixEffect.NONE

    @Volatile private var effectWet = 0f

    @Volatile private var echoDelayMs = 500f

    private var sampleRate = 44_100
    private var channels = 2
    private var isFloat = false

    private val lowPass = Array(2) { Biquad() }
    private val highPass = Array(2) { Biquad() }
    private val shelf = Array(2) { Biquad() }
    private var coefLp = -1f
    private var coefHp = -1f
    private var coefBass = Float.NaN

    private var echoLines = Array(2) { FloatArray(0) }
    private var echoPos = 0
    private var combLines = Array(2) { Array(COMB_MS.size) { FloatArray(0) } }
    private var combPos = IntArray(COMB_MS.size)
    private var combDamp = Array(2) { FloatArray(COMB_MS.size) }

    /** Applies one side of a [MixFrame]. [beatMs] drives the echo time. */
    fun apply(
        side: MixSide,
        effect: MixEffect,
        beatMs: Float,
    ) {
        lowPassHz = side.lowPassHz
        highPassHz = side.highPassHz
        bassDb = side.bassDb
        this.effect = effect
        effectWet = side.effectWet
        if (beatMs > 0f) echoDelayMs = (beatMs * 0.75f).coerceIn(120f, 900f)
    }

    fun setNeutral() {
        lowPassHz = MixCurves.LP_OPEN
        highPassHz = MixCurves.HP_OPEN
        bassDb = 0f
        effect = MixEffect.NONE
        effectWet = 0f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if ((encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount !in 1..2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        allocateEffectState()
        return inputAudioFormat
    }

    private fun allocateEffectState() {
        val echoLen = (sampleRate * 1.0f).toInt()
        echoLines = Array(2) { FloatArray(echoLen) }
        echoPos = 0
        combLines = Array(2) { Array(COMB_MS.size) { i -> FloatArray((COMB_MS[i] * sampleRate / 1000f).toInt().coerceAtLeast(8)) } }
        combPos = IntArray(COMB_MS.size)
        combDamp = Array(2) { FloatArray(COMB_MS.size) }
        coefLp = -1f
        coefHp = -1f
        coefBass = Float.NaN
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)

        val lp = lowPassHz
        val hp = highPassHz
        val bass = bassDb
        val kind = effect
        val wet = effectWet
        val useLp = lp < LP_BYPASS
        val useHp = hp > HP_BYPASS
        val useShelf = kotlin.math.abs(bass) > 0.1f
        val useEffect = kind != MixEffect.NONE && wet > 0.002f

        if (!useLp && !useHp && !useShelf && !useEffect) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (useLp && lp != coefLp) {
            lowPass.forEach { it.lowPass(lp, sampleRate) }
            coefLp = lp
        }
        if (useHp && hp != coefHp) {
            highPass.forEach { it.highPass(hp, sampleRate) }
            coefHp = hp
        }
        if (useShelf && bass != coefBass) {
            shelf.forEach { it.lowShelf(bass, SHELF_HZ, sampleRate) }
            coefBass = bass
        }

        val bytesPerSample = if (isFloat) 4 else 2
        val frames = size / (bytesPerSample * channels)
        val echoLen = echoLines[0].size
        val echoDelay = (echoDelayMs * sampleRate / 1000f).toInt().coerceIn(1, echoLen - 1)

        for (f in 0 until frames) {
            var combAdvance = false
            for (c in 0 until channels) {
                var x = if (isFloat) inputBuffer.getFloat() else inputBuffer.getShort() / 32768f
                if (useHp) x = highPass[c].process(x)
                if (useLp) x = lowPass[c].process(x)
                if (useShelf) x = shelf[c].process(x)

                if (useEffect || effectTail) {
                    when (kind) {
                        MixEffect.ECHO_OUT -> {
                            val line = echoLines[c]
                            val readPos = (echoPos - echoDelay + echoLen) % echoLen
                            val delayed = line[readPos]
                            line[echoPos] = x * ECHO_SEND + delayed * ECHO_FEEDBACK
                            x += delayed * wet
                        }
                        MixEffect.SPACE_OUT -> {
                            var sum = 0f
                            for (i in COMB_MS.indices) {
                                val line = combLines[c][i]
                                val delayed = line[combPos[i]]
                                combDamp[c][i] += (delayed - combDamp[c][i]) * COMB_DAMP
                                line[combPos[i]] = x * COMB_SEND + combDamp[c][i] * COMB_FEEDBACK
                                sum += delayed
                            }
                            x += sum * 0.25f * wet
                            combAdvance = true
                        }
                        MixEffect.NONE -> Unit
                    }
                }

                if (isFloat) {
                    out.putFloat(x)
                } else {
                    out.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
            }
            if (useEffect || effectTail) {
                if (kind == MixEffect.ECHO_OUT) echoPos = (echoPos + 1) % echoLen
                if (combAdvance) for (i in combPos.indices) combPos[i] = (combPos[i] + 1) % combLines[0][i].size
            }
        }
        effectTail = useEffect
        out.flip()
    }

    private var effectTail = false

    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        fun clear() {
            z1 = 0f
            z2 = 0f
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        private fun set(
            nb0: Float,
            nb1: Float,
            nb2: Float,
            a0: Float,
            na1: Float,
            na2: Float,
        ) {
            b0 = nb0 / a0
            b1 = nb1 / a0
            b2 = nb2 / a0
            a1 = na1 / a0
            a2 = na2 / a0
        }

        fun lowPass(
            hz: Float,
            sampleRate: Int,
        ) {
            val w = 2.0 * PI * hz.coerceIn(20f, sampleRate * 0.45f) / sampleRate
            val alpha = (sin(w) / (2.0 * Q)).toFloat()
            val c = cos(w).toFloat()
            set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(
            hz: Float,
            sampleRate: Int,
        ) {
            val w = 2.0 * PI * hz.coerceIn(20f, sampleRate * 0.45f) / sampleRate
            val alpha = (sin(w) / (2.0 * Q)).toFloat()
            val c = cos(w).toFloat()
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowShelf(
            gainDb: Float,
            hz: Float,
            sampleRate: Int,
        ) {
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w = 2.0 * PI * hz / sampleRate
            val c = cos(w).toFloat()
            val s = sin(w).toFloat()
            val beta = 2f * sqrt(a) * (s / 2f * sqrt(2f))
            set(
                a * ((a + 1) - (a - 1) * c + beta),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - beta),
                (a + 1) + (a - 1) * c + beta,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - beta,
            )
        }

        private companion object {
            const val Q = 0.8
        }
    }

    private companion object {
        const val LP_BYPASS = 19_000f
        const val HP_BYPASS = 24f
        const val SHELF_HZ = 220f
        const val ECHO_SEND = 0.9f
        const val ECHO_FEEDBACK = 0.5f
        val COMB_MS = floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)
        const val COMB_SEND = 0.5f
        const val COMB_FEEDBACK = 0.8f
        const val COMB_DAMP = 0.45f
    }
}
