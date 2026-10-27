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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Software take on the system "Dolby Atmos" (Dolby Audio) processing chain, applied in ExoPlayer's
 * audio processor chain so it behaves the same on every phone.
 *
 * Modelled on the public Dolby Audio component list:
 * - **Dialogue enhancer / presence**: peaking lift on the mid (centre) signal. Level 0..3 only adds a little.
 * - **Surround virtualizer feel**: always-on base widening + pseudo-stereo; Expanded stereo (0..3, earphones only) adds more.
 *   Only the side (background) signal is darkened, so vocals stay clear while instruments sit behind them.
 * - **Intelligent equalizer**: slowly measures the low/mid/high balance of the song and nudges it toward a target tone
 *   (leaves already-balanced songs alone).
 * - **Volume leveler**: per-band (3 bands) downward compression + makeup gain, so everything is evened out without pumping.
 * - **Audio regulator**: per-band limits that tighten as the system volume goes up, so max volume doesn't hurt.
 * - **Volume maximizer**: master gain followed by a look-ahead limiter and a soft clip.
 *
 * Settings come from [com.wavlin.music.audio.dolby.DolbyAtmosManager]. Like [ReverbAudioProcessor], [isActive] is always
 * true and the processor bypasses itself while off; [flush]/[reset] clear audio state only, never the settings.
 * Only 2-channel PCM_16BIT / PCM_FLOAT is processed; everything else is passed through unchanged.
 */
@UnstableApi
class DolbyAtmosAudioProcessor : AudioProcessor {

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        // ── Dialogue / presence (mid signal) ─────────────────────────────────
        private val DIALOGUE_GAIN_DB = floatArrayOf(0f, 0.2f, 0.4f, 0.6f)
        private const val BASE_PRESENCE_DB = 2.0
        private const val DIALOGUE_FREQ_HZ = 2500.0
        private const val DIALOGUE_Q = 0.7

        // ── Stereo ───────────────────────────────────────────────────────────
        private val WIDEN_GAIN = floatArrayOf(0f, 0.7f, 1.4f, 2.2f)
        private val DECOR_GAIN = floatArrayOf(0f, 0.12f, 0.25f, 0.4f)
        private const val BASE_WIDEN = 0.5f
        private const val BASE_DECOR = 0.1f
        private const val SIDE_HP_HZ = 250.0
        private const val DECOR_DELAY_S = 0.015
        private const val DECOR_HP_HZ = 300.0

        /** Only the side (background) signal is darkened → vocals stay clear, instruments recede. */
        private const val SIDE_SHELF_HZ = 3000.0
        private const val SIDE_SHELF_DB = -7.0

        // ── Static tone ──────────────────────────────────────────────────────
        private const val BODY_HZ = 250.0
        private const val BODY_GAIN_DB = 4.0
        private const val BODY_Q = 0.7
        private const val SHELF_HZ = 8000.0
        private const val SHELF_GAIN_DB = -1.5

        // ── 3-band split (low | mid | high) ──────────────────────────────────
        private const val XOVER_LOW_HZ = 250.0
        private const val XOVER_HIGH_HZ = 2500.0
        private const val BUTTERWORTH_Q = 0.7071

        // ── Volume leveler (per band: low, mid, high) ────────────────────────
        private val LEVEL_T = floatArrayOf(0.12f, 0.10f, 0.04f)
        private const val LEVEL_RATIO = 2.2f
        private const val LEVEL_MAKEUP = 1.8f
        private val BAND_ATTACK_S = doubleArrayOf(0.008, 0.005, 0.002)
        private val BAND_RELEASE_S = doubleArrayOf(0.25, 0.15, 0.08)

        // ── Audio regulator (per band limits, tighter at high system volume) ─
        private val REG_LIMIT = floatArrayOf(0.50f, 0.45f, 0.20f)
        private const val REG_RATIO = 6f
        private const val REG_VOLUME_TIGHTEN = 0.4f

        // ── Intelligent equalizer ────────────────────────────────────────────
        private const val TARGET_LOW_DB = 4f   // low band level relative to mid band
        private const val TARGET_HIGH_DB = -11f // high band level relative to mid band
        private const val EQ_STRENGTH = 0.6f
        private const val EQ_MAX_DB = 4f
        private const val EQ_POWER_TAU_S = 1.5
        private const val EQ_SMOOTH_TAU_S = 0.5
        private const val EQ_UPDATE_FRAMES = 2048

        // ── Volume maximizer ─────────────────────────────────────────────────
        private const val MASTER_GAIN_DB = 3.0
        private const val LIM_CEIL = 0.9f
        private const val LIM_LOOKAHEAD_S = 0.0015
        private const val LIM_RELEASE_S = 0.08
        private const val CLIP_KNEE = 0.92f

        private const val MIX_SMOOTHING = 0.0015f
    }

    // ── Settings (main thread writes, audio thread reads) ────────────────────
    @Volatile private var enabled = false
    @Volatile private var dialogueLevel = 3
    @Volatile private var stereoLevel = 0
    @Volatile private var systemVolume = 0.5f

    // ── Format ───────────────────────────────────────────────────────────────
    private var sampleRate = 44100
    private var channelCount = 2
    private var isFloat = true
    private var bytesPerSample = 4

    // ── Mid peaking (dialogue / presence) ────────────────────────────────────
    private var appliedDialogue = -1
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f
    private var outGain = 1f

    // ── Stereo state ─────────────────────────────────────────────────────────
    private var hpCoef = 0f
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var decBuf = FloatArray(1)
    private var decIdx = 0
    private var decHpCoef = 0f
    private var decPrevIn = 0f
    private var decPrevOut = 0f
    private val sideShelf = Biquad()

    // ── Static tone ──────────────────────────────────────────────────────────
    private val bodyL = Biquad()
    private val bodyR = Biquad()
    private val shelfL = Biquad()
    private val shelfR = Biquad()

    // ── Multiband ────────────────────────────────────────────────────────────
    private val lp1L = Biquad(); private val lp1R = Biquad() // low split
    private val lp2L = Biquad(); private val lp2R = Biquad() // mid/high split
    private val bandEnv = FloatArray(3)
    private val bandAtk = FloatArray(3) { 0.01f }
    private val bandRel = FloatArray(3) { 0.0005f }
    private val bandPow = FloatArray(3)
    private var powCoef = 0.00002f
    private val eqGain = FloatArray(3) { 1f }
    private val eqTarget = FloatArray(3) { 1f }
    private var eqSmooth = 0.00004f
    private var eqCounter = 0
    private var regScale = 1f
    private val masterGain = 10.0.pow(MASTER_GAIN_DB / 20.0).toFloat()

    // ── Look-ahead limiter ───────────────────────────────────────────────────
    private var laBufL = FloatArray(1)
    private var laBufR = FloatArray(1)
    private var laIdx = 0
    private var limGain = 1f
    private var limAtk = 0.05f
    private var limRel = 0.0003f

    // ── Output / buffers ─────────────────────────────────────────────────────
    private var mix = 0f
    private var outL = 0f
    private var outR = 0f
    private var bandOutL = 0f
    private var bandOutR = 0f

    private var buffer: ByteBuffer = EMPTY
    private var outputBuffer: ByteBuffer = EMPTY
    private var inputEnded = false

    // ── Public API (DolbyAtmosManager) ───────────────────────────────────────

    /** @param volume system music volume 0..1 (drives the audio regulator). */
    fun setParameters(enabled: Boolean, dialogueLevel: Int, stereoLevel: Int, volume: Float = 0.5f) {
        this.dialogueLevel = dialogueLevel.coerceIn(0, 3)
        this.stereoLevel = stereoLevel.coerceIn(0, 3)
        this.systemVolume = volume.coerceIn(0f, 1f)
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
        val sr = sampleRate.toDouble()
        appliedDialogue = -1 // force coefficient rebuild for the new sample rate

        hpCoef = onePoleHp(SIDE_HP_HZ)
        decHpCoef = onePoleHp(DECOR_HP_HZ)
        decBuf = FloatArray((DECOR_DELAY_S * sr).toInt().coerceAtLeast(8))
        decIdx = 0

        applyHighShelf(SHELF_HZ, SHELF_GAIN_DB, shelfL, shelfR)
        applyHighShelf(SIDE_SHELF_HZ, SIDE_SHELF_DB, sideShelf)
        applyPeaking(BODY_HZ, BODY_GAIN_DB, BODY_Q, bodyL, bodyR)
        applyLowpass(XOVER_LOW_HZ, lp1L, lp1R)
        applyLowpass(XOVER_HIGH_HZ, lp2L, lp2R)

        for (i in 0 until 3) {
            bandAtk[i] = (1.0 - exp(-1.0 / (BAND_ATTACK_S[i] * sr))).toFloat()
            bandRel[i] = (1.0 - exp(-1.0 / (BAND_RELEASE_S[i] * sr))).toFloat()
        }
        powCoef = (1.0 - exp(-1.0 / (EQ_POWER_TAU_S * sr))).toFloat()
        eqSmooth = (1.0 - exp(-1.0 / (EQ_SMOOTH_TAU_S * sr))).toFloat()

        val laLen = (LIM_LOOKAHEAD_S * sr).toInt().coerceAtLeast(8)
        laBufL = FloatArray(laLen)
        laBufR = FloatArray(laLen)
        laIdx = 0
        limAtk = (1.0 - exp(-3.0 / laLen)).toFloat()
        limRel = (1.0 - exp(-1.0 / (LIM_RELEASE_S * sr))).toFloat()

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
        val widen = BASE_WIDEN + WIDEN_GAIN[stereoLevel]
        val decor = BASE_DECOR + DECOR_GAIN[stereoLevel]
        regScale = 1f - REG_VOLUME_TIGHTEN * systemVolume
        if (dLevel != appliedDialogue) updateDialogueFilter(dLevel)

        val frames = remaining / (bytesPerSample * 2)
        if (isFloat) {
            val inFB = inputBuffer.asFloatBuffer()
            val outFB = buffer.asFloatBuffer()
            repeat(frames) {
                val l = inFB.get()
                val r = inFB.get()
                processFrame(l, r, widen, decor, target)
                outFB.put(outL)
                outFB.put(outR)
            }
        } else {
            val inSB = inputBuffer.asShortBuffer()
            val outSB = buffer.asShortBuffer()
            repeat(frames) {
                val l = inSB.get() / 32768f
                val r = inSB.get() / 32768f
                processFrame(l, r, widen, decor, target)
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
    private fun processFrame(l: Float, r: Float, widen: Float, decor: Float, target: Float) {
        mix += (target - mix) * MIX_SMOOTHING
        if (target == 0f && mix < 0.0005f) mix = 0f

        var m = (l + r) * 0.5f
        var s = (l - r) * 0.5f

        // Dialogue / presence: peaking filter on the mid signal (transposed direct form II).
        val my = b0 * m + z1
        z1 = b1 * m - a1 * my + z2
        z2 = b2 * m - a2 * my
        m = my

        // Stereo: widen side above the high-pass corner, add pseudo-stereo, darken only the side.
        val sHp = hpCoef * (hpPrevOut + s - hpPrevIn)
        hpPrevIn = s
        hpPrevOut = sHp
        s += widen * sHp

        val mHp = decHpCoef * (decPrevOut + m - decPrevIn)
        decPrevIn = m
        decPrevOut = mHp
        val delayed = decBuf[decIdx]
        decBuf[decIdx] = mHp
        if (++decIdx >= decBuf.size) decIdx = 0
        s += decor * delayed
        s = sideShelf.process(s)

        // Static tone.
        val tl = shelfL.process(bodyL.process((m + s) * outGain))
        val tr = shelfR.process(bodyR.process((m - s) * outGain))

        // Intelligent EQ + volume leveler + audio regulator (3 bands).
        processBands(tl, tr)

        // Volume maximizer: master gain → look-ahead limiter → soft clip.
        val xl = bandOutL * masterGain
        val xr = bandOutR * masterGain
        val pk = max(abs(xl), abs(xr))
        val tg = if (pk > LIM_CEIL) LIM_CEIL / pk else 1f
        limGain += (tg - limGain) * (if (tg < limGain) limAtk else limRel)
        val dl = laBufL[laIdx]
        val dr = laBufR[laIdx]
        laBufL[laIdx] = xl
        laBufR[laIdx] = xr
        if (++laIdx >= laBufL.size) laIdx = 0
        val wl = softClip(dl * limGain)
        val wr = softClip(dr * limGain)

        outL = (l + (wl - l) * mix).coerceIn(-1f, 1f)
        outR = (r + (wr - r) * mix).coerceIn(-1f, 1f)
    }

    /** Splits into low/mid/high (exact-reconstruction subtraction crossover), applies per-band gain, recombines. */
    private fun processBands(xl: Float, xr: Float) {
        val lowL = lp1L.process(xl)
        val restL = xl - lowL
        val midL = lp2L.process(restL)
        val highL = restL - midL

        val lowR = lp1R.process(xr)
        val restR = xr - lowR
        val midR = lp2R.process(restR)
        val highR = restR - midR

        // Envelope (linked L/R) and slow power per band.
        var pk = max(abs(lowL), abs(lowR))
        bandEnv[0] += (pk - bandEnv[0]) * (if (pk > bandEnv[0]) bandAtk[0] else bandRel[0])
        pk = max(abs(midL), abs(midR))
        bandEnv[1] += (pk - bandEnv[1]) * (if (pk > bandEnv[1]) bandAtk[1] else bandRel[1])
        pk = max(abs(highL), abs(highR))
        bandEnv[2] += (pk - bandEnv[2]) * (if (pk > bandEnv[2]) bandAtk[2] else bandRel[2])

        bandPow[0] += ((lowL * lowL + lowR * lowR) * 0.5f - bandPow[0]) * powCoef
        bandPow[1] += ((midL * midL + midR * midR) * 0.5f - bandPow[1]) * powCoef
        bandPow[2] += ((highL * highL + highR * highR) * 0.5f - bandPow[2]) * powCoef

        if (++eqCounter >= EQ_UPDATE_FRAMES) {
            eqCounter = 0
            updateEqTargets()
        }
        eqGain[0] += (eqTarget[0] - eqGain[0]) * eqSmooth
        eqGain[1] += (eqTarget[1] - eqGain[1]) * eqSmooth
        eqGain[2] += (eqTarget[2] - eqGain[2]) * eqSmooth

        val g0 = bandGain(0)
        val g1 = bandGain(1)
        val g2 = bandGain(2)

        bandOutL = lowL * g0 + midL * g1 + highL * g2
        bandOutR = lowR * g0 + midR * g1 + highR * g2
    }

    private fun bandGain(b: Int): Float {
        val e = bandEnv[b]
        val t = LEVEL_T[b]
        var g = if (e > t) (t + (e - t) / LEVEL_RATIO) / e else 1f
        g *= LEVEL_MAKEUP
        val lvl = e * g
        val lim = REG_LIMIT[b] * regScale
        if (lvl > lim) g *= (lim + (lvl - lim) / REG_RATIO) / lvl
        return g * eqGain[b]
    }

    /** Moves low/high toward the target tone relative to mid; leaves balanced songs alone. */
    private fun updateEqTargets() {
        val pMid = bandPow[1]
        if (pMid < 1e-8f) return // silence: keep current targets
        val relLow = 10f * log10((bandPow[0] + 1e-12f) / pMid)
        val relHigh = 10f * log10((bandPow[2] + 1e-12f) / pMid)
        val dbLow = ((TARGET_LOW_DB - relLow) * EQ_STRENGTH).coerceIn(-EQ_MAX_DB, EQ_MAX_DB)
        val dbHigh = ((TARGET_HIGH_DB - relHigh) * EQ_STRENGTH).coerceIn(-EQ_MAX_DB, EQ_MAX_DB)
        eqTarget[0] = 10.0.pow(dbLow / 20.0).toFloat()
        eqTarget[1] = 1f
        eqTarget[2] = 10.0.pow(dbHigh / 20.0).toFloat()
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= CLIP_KNEE) return x
        val y = CLIP_KNEE + (1f - CLIP_KNEE) * tanh((a - CLIP_KNEE) / (1f - CLIP_KNEE))
        return if (x < 0f) -y else y
    }

    // ── Filter design (RBJ cookbook) ─────────────────────────────────────────

    private fun onePoleHp(hz: Double): Float {
        val rc = 1.0 / (2.0 * PI * hz)
        return (rc / (rc + 1.0 / sampleRate)).toFloat()
    }

    private fun applyLowpass(hz: Double, vararg targets: Biquad) {
        val w0 = 2.0 * PI * hz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
        val a0 = 1.0 + alpha
        val nb0 = ((1.0 - cosW) / 2.0 / a0).toFloat()
        val nb1 = ((1.0 - cosW) / a0).toFloat()
        val na1 = ((-2.0 * cosW) / a0).toFloat()
        val na2 = ((1.0 - alpha) / a0).toFloat()
        targets.forEach { it.set(nb0, nb1, nb0, na1, na2) }
    }

    private fun applyPeaking(hz: Double, gainDb: Double, q: Double, vararg targets: Biquad) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * hz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha / a
        val nb0 = ((1.0 + alpha * a) / a0).toFloat()
        val nb1 = ((-2.0 * cosW) / a0).toFloat()
        val nb2 = ((1.0 - alpha * a) / a0).toFloat()
        val na2 = ((1.0 - alpha / a) / a0).toFloat()
        targets.forEach { it.set(nb0, nb1, nb2, nb1, na2) }
    }

    private fun applyHighShelf(hz: Double, gainDb: Double, vararg targets: Biquad) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * hz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val t = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cosW + t
        val nb0 = (a * ((a + 1) + (a - 1) * cosW + t) / a0).toFloat()
        val nb1 = (-2.0 * a * ((a - 1) + (a + 1) * cosW) / a0).toFloat()
        val nb2 = (a * ((a + 1) + (a - 1) * cosW - t) / a0).toFloat()
        val na1 = (2.0 * ((a - 1) - (a + 1) * cosW) / a0).toFloat()
        val na2 = (((a + 1) - (a - 1) * cosW - t) / a0).toFloat()
        targets.forEach { it.set(nb0, nb1, nb2, na1, na2) }
    }

    /** Mid-signal presence peaking filter; output gain leaves headroom for the boost. */
    private fun updateDialogueFilter(level: Int) {
        appliedDialogue = level
        val gainDb = BASE_PRESENCE_DB + DIALOGUE_GAIN_DB[level].toDouble()
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

    /** Clears audio state only (filters, envelopes, delay lines); user settings are kept. */
    private fun clearState() {
        z1 = 0f; z2 = 0f
        hpPrevIn = 0f; hpPrevOut = 0f
        decBuf.fill(0f); decIdx = 0
        decPrevIn = 0f; decPrevOut = 0f
        sideShelf.reset()
        bodyL.reset(); bodyR.reset()
        shelfL.reset(); shelfR.reset()
        lp1L.reset(); lp1R.reset(); lp2L.reset(); lp2R.reset()
        for (i in 0 until 3) {
            bandEnv[i] = 0f
            bandPow[i] = 0f
            eqGain[i] = 1f
            eqTarget[i] = 1f
        }
        eqCounter = 0
        laBufL.fill(0f); laBufR.fill(0f); laIdx = 0
        limGain = 1f
    }

    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun set(b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() { z1 = 0f; z2 = 0f }
    }
}
