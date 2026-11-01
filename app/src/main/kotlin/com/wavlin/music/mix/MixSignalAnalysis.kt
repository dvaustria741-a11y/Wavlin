/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of [MixSignalAnalysis.analyze]. */
class MixSignalResult(
    val bpm: Float,
    val firstBeatMs: Long,
    val key: String,
    val peaks: ByteArray,
)

/**
 * Estimates tempo, beat phase and musical key from a mono signal.
 *
 * It is an estimate, not a studio-grade analysis: tempo comes from the autocorrelation of the spectral flux,
 * refined with a comb score over the whole track, and the key from a chroma profile matched against the
 * Krumhansl-Schmuckler templates.
 */
object MixSignalAnalysis {
    const val SAMPLE_RATE = 11_025
    private const val FLUX_FFT = 512
    private const val FLUX_HOP = 160
    private const val KEY_FFT = 4096

    private val MAJOR = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MINOR = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)
    private val CAMELOT_MAJOR = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val CAMELOT_MINOR = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

    fun analyze(
        mono: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
    ): MixSignalResult {
        val peaks = peaks(mono)
        if (mono.size < sampleRate * 8) return MixSignalResult(0f, 0L, "", peaks)

        val (flux, lowFlux) = onsetEnvelope(mono, sampleRate)
        val fps = sampleRate.toFloat() / FLUX_HOP
        val tempo = estimateTempo(flux, lowFlux, fps)
        val key = estimateKey(mono, sampleRate)
        if (tempo == null) return MixSignalResult(0f, 0L, key, peaks)
        val (bpm, firstBeatFrame) = tempo
        return MixSignalResult(
            bpm = bpm,
            firstBeatMs = (firstBeatFrame / fps * 1000f).toLong(),
            key = key,
            peaks = peaks,
        )
    }

    fun peaks(mono: FloatArray): ByteArray {
        val count = MixTrackAnalysis.PEAK_COUNT
        val out = FloatArray(count)
        if (mono.isEmpty()) return ByteArray(count)
        val perBin = mono.size.toDouble() / count
        var maxAll = 1e-6f
        for (b in 0 until count) {
            val from = (b * perBin).toInt()
            val to = min(mono.size, ((b + 1) * perBin).toInt().coerceAtLeast(from + 1))
            var m = 0f
            for (i in from until to) {
                val a = abs(mono[i])
                if (a > m) m = a
            }
            out[b] = m
            if (m > maxAll) maxAll = m
        }
        return ByteArray(count) { (sqrt(out[it] / maxAll) * 255f).roundToInt().coerceIn(0, 255).toByte() }
    }

    /** @return the full-band onset envelope and one restricted to the kick-drum range. */
    private fun onsetEnvelope(
        x: FloatArray,
        sampleRate: Int,
    ): Pair<FloatArray, FloatArray> {
        val n = FLUX_FFT
        val frames = (x.size - n) / FLUX_HOP
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
        val re = FloatArray(n)
        val im = FloatArray(n)
        val binLow = max(2, (40f * n / sampleRate).toInt())
        val binHigh = min(n / 2 - 1, (6_000f * n / sampleRate).toInt())
        var prev = FloatArray(binHigh + 1)
        var cur = FloatArray(binHigh + 1)
        val flux = FloatArray(max(frames, 0))
        val low = FloatArray(max(frames, 0))
        val lowHigh = min(binHigh, (190f * n / sampleRate).toInt())
        for (f in 0 until frames) {
            val off = f * FLUX_HOP
            for (i in 0 until n) {
                re[i] = x[off + i] * window[i]
                im[i] = 0f
            }
            Fft.transform(re, im)
            var sum = 0f
            var lowSum = 0f
            for (k in binLow..binHigh) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val v = ln(1f + 100f * mag)
                cur[k] = v
                val d = v - prev[k]
                if (d > 0f) {
                    sum += d
                    if (k <= lowHigh) lowSum += d
                }
            }
            flux[f] = sum
            low[f] = lowSum
            val t = prev
            prev = cur
            cur = t
        }
        return flux to low
    }

    /** @return tempo in BPM and the frame of the first downbeat, or null when no steady pulse is found. */
    private fun estimateTempo(
        fluxRaw: FloatArray,
        lowRaw: FloatArray,
        fps: Float,
    ): Pair<Float, Float>? {
        if (fluxRaw.size < fps * 6) return null
        val flux = detrend(fluxRaw, (fps * 0.4f).toInt())
        val low = detrend(lowRaw, (fps * 0.4f).toInt())

        val maxWindow = (fps * 150).toInt()
        val start = if (flux.size > maxWindow) (flux.size - maxWindow) / 2 else 0
        val len = min(flux.size, maxWindow)

        val minLag = (fps * 60f / 200f).toInt()
        val maxLag = (fps * 60f / 60f).toInt()
        val ac = FloatArray(maxLag * 4 + 2)
        for (lag in minLag..min(ac.size - 1, maxLag * 4)) {
            if (lag >= len) break
            var s = 0f
            for (i in 0 until len - lag) s += flux[start + i] * flux[start + i + lag]
            ac[lag] = s / (len - lag)
        }

        var bestLag = -1
        var bestScore = 0f
        for (lag in minLag..maxLag) {
            val bpm = 60f * fps / lag
            val prior = exp(-0.5f * (ln(bpm / 120f) / ln(2f) / 0.6f).pow(2))
            val support = ac[lag] + 0.4f * ac.getOrElse(lag * 2) { 0f }
            val score = support * prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestScore <= 0f) return null

        // Comb refinement: the true period folds the onset envelope into the sharpest peak.
        val coarse = fps * 60f / bestLag
        var bestBpm = coarse
        var bestFold = -1f
        var bpm = coarse * 0.97f
        while (bpm <= coarse * 1.03f) {
            val period = fps * 60f / bpm
            val fold = foldPeak(flux, start, len, period)
            if (fold > bestFold) {
                bestFold = fold
                bestBpm = bpm
            }
            bpm += 0.04f
        }
        bestBpm = foldOctave(bestBpm)

        val period = fps * 60f / bestBpm
        // The kick drum marks the beat far more reliably than off-beat hats, so prefer it when it carries energy.
        val lowCoherence = foldPeak(low, 0, low.size, period)
        val fullCoherence = foldPeak(flux, 0, flux.size, period)
        val grid = if (lowCoherence >= 0.12f && lowCoherence >= fullCoherence * 0.6f) low else flux
        val phaseFrame = beatPhase(grid, period)
        val firstBeat = firstDownbeat(grid, period, phaseFrame) + ONSET_LATENCY_FRAMES
        return (bestBpm * 10f).roundToInt() / 10f to firstBeat
    }

    private fun exp(x: Float): Float = kotlin.math.exp(x)

    private fun foldOctave(bpm: Float): Float {
        var b = bpm
        while (b < 60f) b *= 2f
        while (b > 200f) b /= 2f
        return b
    }

    private fun detrend(
        x: FloatArray,
        radius: Int,
    ): FloatArray {
        val out = FloatArray(x.size)
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        for (i in x.indices) {
            val a = max(0, i - radius)
            val b = min(x.size, i + radius + 1)
            val mean = ((prefix[b] - prefix[a]) / (b - a)).toFloat()
            out[i] = max(0f, x[i] - mean)
        }
        return out
    }

    private const val FOLD_BINS = 48

    /** The envelope frame is stamped at the start of its analysis window, so onsets are seen slightly early. */
    private const val ONSET_LATENCY_FRAMES = 2f

    private fun foldPeak(
        x: FloatArray,
        start: Int,
        len: Int,
        period: Float,
    ): Float {
        val hist = FloatArray(FOLD_BINS)
        for (i in 0 until len) {
            val phase = ((start + i) % period) / period
            hist[(phase * FOLD_BINS).toInt().coerceIn(0, FOLD_BINS - 1)] += x[start + i]
        }
        var m = 0f
        var total = 0f
        for (h in hist) {
            if (h > m) m = h
            total += h
        }
        return if (total > 0f) m / total else 0f
    }

    private fun beatPhase(
        x: FloatArray,
        period: Float,
    ): Float {
        val bins = 96
        val hist = FloatArray(bins)
        for (i in x.indices) {
            val phase = (i % period) / period
            hist[(phase * bins).toInt().coerceIn(0, bins - 1)] += x[i]
        }
        // Smooth over neighbouring bins so one noisy bin does not win.
        var best = 0
        var bestV = -1f
        for (b in 0 until bins) {
            val v = hist[(b + bins - 1) % bins] * 0.5f + hist[b] + hist[(b + 1) % bins] * 0.5f
            if (v > bestV) {
                bestV = v
                best = b
            }
        }
        return (best + 0.5f) / bins * period
    }

    private fun firstDownbeat(
        x: FloatArray,
        period: Float,
        phase: Float,
    ): Float {
        val sums = FloatArray(4)
        var k = 0
        var pos = phase
        while (pos < x.size - 1) {
            val i = pos.toInt()
            val v = max(x[i], max(x.getOrElse(i + 1) { 0f }, x.getOrElse(i - 1) { 0f }))
            sums[k % 4] += v
            k++
            pos += period
        }
        var best = 0
        for (j in 1 until 4) if (sums[j] > sums[best]) best = j
        return phase + best * period
    }

    private fun estimateKey(
        x: FloatArray,
        sampleRate: Int,
    ): String {
        val n = KEY_FFT
        val maxSamples = sampleRate * 120
        val from = if (x.size > maxSamples) (x.size - maxSamples) / 2 else 0
        val to = min(x.size, from + maxSamples)
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
        val re = FloatArray(n)
        val im = FloatArray(n)
        val chroma = DoubleArray(12)
        val binPc = IntArray(n / 2) { -1 }
        for (k in 1 until n / 2) {
            val f = k.toDouble() * sampleRate / n
            if (f < 65.0 || f > 2_000.0) continue
            val midi = 69.0 + 12.0 * ln(f / 440.0) / ln(2.0)
            binPc[k] = ((midi.roundToInt() % 12) + 12) % 12
        }
        var pos = from
        while (pos + n <= to) {
            for (i in 0 until n) {
                re[i] = x[pos + i] * window[i]
                im[i] = 0f
            }
            Fft.transform(re, im)
            for (k in 1 until n / 2) {
                val pc = binPc[k]
                if (pc >= 0) chroma[pc] += sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())
            }
            pos += n
        }
        val total = chroma.sum()
        if (total <= 0.0) return ""

        var bestScore = -2.0
        var bestKey = ""
        for (tonic in 0 until 12) {
            for (minor in 0..1) {
                val profile = if (minor == 1) MINOR else MAJOR
                val score = correlation(chroma, profile, tonic)
                if (score > bestScore) {
                    bestScore = score
                    bestKey = if (minor == 1) "${CAMELOT_MINOR[tonic]}A" else "${CAMELOT_MAJOR[tonic]}B"
                }
            }
        }
        return bestKey
    }

    private fun correlation(
        chroma: DoubleArray,
        profile: FloatArray,
        tonic: Int,
    ): Double {
        var cm = 0.0
        var pm = 0.0
        for (i in 0 until 12) {
            cm += chroma[(tonic + i) % 12]
            pm += profile[i]
        }
        cm /= 12
        pm /= 12
        var num = 0.0
        var dc = 0.0
        var dp = 0.0
        for (i in 0 until 12) {
            val c = chroma[(tonic + i) % 12] - cm
            val p = profile[i] - pm
            num += c * p
            dc += c * c
            dp += p * p
        }
        return if (dc <= 0.0 || dp <= 0.0) 0.0 else num / sqrt(dc * dp)
    }
}

/** In-place radix-2 complex FFT. */
internal object Fft {
    private val cache = HashMap<Int, Pair<FloatArray, FloatArray>>()

    private fun twiddles(n: Int): Pair<FloatArray, FloatArray> =
        synchronized(cache) {
            cache.getOrPut(n) {
                FloatArray(n / 2) { cos(-2.0 * PI * it / n).toFloat() } to FloatArray(n / 2) { sin(-2.0 * PI * it / n).toFloat() }
            }
        }

    fun transform(
        re: FloatArray,
        im: FloatArray,
    ) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        val (wr, wi) = twiddles(n)
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in i until i + half) {
                    val cr = wr[k]
                    val ci = wi[k]
                    val xr = re[m + half] * cr - im[m + half] * ci
                    val xi = re[m + half] * ci + im[m + half] * cr
                    re[m + half] = re[m] - xr
                    im[m + half] = im[m] - xi
                    re[m] += xr
                    im[m] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
