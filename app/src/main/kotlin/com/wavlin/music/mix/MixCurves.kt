/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.mix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Everything the audio engine needs to know about one side (outgoing or incoming) at a given moment. */
data class MixSide(
    val volume: Float,
    val lowPassHz: Float,
    val highPassHz: Float,
    val bassDb: Float,
    val effectWet: Float,
)

data class MixFrame(
    val out: MixSide,
    val inc: MixSide,
)

/** Pure functions that turn a [MixTransition] and a progress value into audio parameters. */
object MixCurves {
    const val LP_OPEN = 20_000f
    const val LP_CLOSED = 220f
    const val HP_OPEN = 20f
    const val HP_CLOSED = 1_800f
    const val BASS_CUT_DB = -26f

    val defaultOut = listOf(MixPoint(0f, 1f), MixPoint(1f, 0f))
    val defaultIn = listOf(MixPoint(0f, 0f), MixPoint(1f, 1f))

    /** Volume curves (outgoing, incoming) for a volume mode. */
    fun volumePoints(t: MixTransition): Pair<List<MixPoint>, List<MixPoint>> =
        when (t.volume) {
            MixVolume.CUSTOM -> t.customOut to t.customIn
            MixVolume.SMOOTH_CROSSFADE ->
                listOf(MixPoint(0f, 1f), MixPoint(0.25f, 0.92f), MixPoint(0.75f, 0.2f), MixPoint(1f, 0f)) to
                    listOf(MixPoint(0f, 0f), MixPoint(0.25f, 0.2f), MixPoint(0.75f, 0.92f), MixPoint(1f, 1f))
            MixVolume.OVERLAP ->
                listOf(MixPoint(0f, 1f), MixPoint(0.92f, 1f), MixPoint(1f, 0f)) to
                    listOf(MixPoint(0f, 0f), MixPoint(0.08f, 1f), MixPoint(1f, 1f))
            MixVolume.FADE_IN_FADE_OUT ->
                listOf(MixPoint(0f, 1f), MixPoint(0.5f, 0f), MixPoint(1f, 0f)) to
                    listOf(MixPoint(0f, 0f), MixPoint(0.5f, 0f), MixPoint(1f, 1f))
            MixVolume.CUT_IN_FADE_OUT ->
                listOf(MixPoint(0f, 1f), MixPoint(1f, 0f)) to
                    listOf(MixPoint(0f, 1f), MixPoint(1f, 1f))
            MixVolume.FADE_IN_CUT_OUT ->
                listOf(MixPoint(0f, 1f), MixPoint(1f, 1f)) to
                    listOf(MixPoint(0f, 0f), MixPoint(1f, 1f))
            MixVolume.CUT_IN_CUT_OUT ->
                listOf(MixPoint(0f, 1f), MixPoint(0.5f, 1f), MixPoint(0.5f, 0f), MixPoint(1f, 0f)) to
                    listOf(MixPoint(0f, 0f), MixPoint(0.5f, 0f), MixPoint(0.5f, 1f), MixPoint(1f, 1f))
        }

    /** Piecewise-linear evaluation; the points must be sorted by x. */
    fun eval(
        points: List<MixPoint>,
        x: Float,
    ): Float {
        if (points.isEmpty()) return 1f
        if (x <= points.first().x) return points.first().y
        if (x >= points.last().x) return points.last().y
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (x <= b.x) {
                val span = b.x - a.x
                return if (span <= 1e-6f) b.y else a.y + (b.y - a.y) * ((x - a.x) / span)
            }
        }
        return points.last().y
    }

    /** Equal-power style shaping keeps the perceived loudness steady when both tracks play. */
    private fun shape(v: Float): Float = v.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) * 0.35f + it * 0.65f }

    private fun lerpLog(
        open: Float,
        closed: Float,
        amount: Float,
    ): Float = open * (closed / open).pow(amount.coerceIn(0f, 1f))

    private fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun frame(
        t: MixTransition,
        progress: Float,
    ): MixFrame {
        val p = progress.coerceIn(0f, 1f)
        val (outPts, inPts) = volumePoints(t)

        var outLp = LP_OPEN
        var outHp = HP_OPEN
        var inLp = LP_OPEN
        var inHp = HP_OPEN
        when (t.filter) {
            MixFilter.NONE -> Unit
            MixFilter.LP_OUT -> outLp = lerpLog(LP_OPEN, LP_CLOSED, p)
            MixFilter.LP_IN -> inLp = lerpLog(LP_OPEN, LP_CLOSED, 1f - p)
            MixFilter.LP_IN_LP_OUT -> {
                outLp = lerpLog(LP_OPEN, LP_CLOSED, p)
                inLp = lerpLog(LP_OPEN, LP_CLOSED, 1f - p)
            }
            MixFilter.LP_IN_HP_OUT -> {
                outHp = lerpLog(HP_OPEN, HP_CLOSED, p)
                inLp = lerpLog(LP_OPEN, LP_CLOSED, 1f - p)
            }
            MixFilter.HP_OUT -> outHp = lerpLog(HP_OPEN, HP_CLOSED, p)
            MixFilter.HP_IN -> inHp = lerpLog(HP_OPEN, HP_CLOSED, 1f - p)
            MixFilter.HP_IN_HP_OUT -> {
                outHp = lerpLog(HP_OPEN, HP_CLOSED, p)
                inHp = lerpLog(HP_OPEN, HP_CLOSED, 1f - p)
            }
            MixFilter.HP_IN_LP_OUT -> {
                outLp = lerpLog(LP_OPEN, LP_CLOSED, p)
                inHp = lerpLog(HP_OPEN, HP_CLOSED, 1f - p)
            }
        }

        var outBass = 0f
        var inBass = 0f
        if (t.eq != MixEq.NONE) {
            val swapAt = if (t.eq == MixEq.START_BASS_SWAP) 0.18f else 0.82f
            val after = smoothstep(swapAt - 0.04f, swapAt + 0.04f, p)
            outBass = BASS_CUT_DB * after
            inBass = BASS_CUT_DB * (1f - after)
        }

        val outWet =
            when (t.effect) {
                MixEffect.NONE -> 0f
                MixEffect.ECHO_OUT -> smoothstep(0f, 0.35f, p) * 0.55f
                MixEffect.SPACE_OUT -> smoothstep(0f, 0.45f, p) * 0.7f
            }

        return MixFrame(
            out = MixSide(shape(eval(outPts, p)), outLp, outHp, outBass, outWet),
            inc = MixSide(shape(eval(inPts, p)), inLp, inHp, inBass, 0f),
        )
    }

    /** Tempo scale factors (outgoing, incoming) that keep both tracks on one shared tempo while they overlap. */
    fun beatmatchSpeeds(
        t: MixTransition,
        progress: Float,
        outBpm: Float,
        inBpm: Float,
    ): Pair<Float, Float> {
        if (!t.beatmatch || outBpm <= 0f || inBpm <= 0f) return 1f to 1f
        val ratio = inBpm / outBpm
        if (abs(ratio - 1f) > MAX_TEMPO_DRIFT) return 1f to 1f
        val p = progress.coerceIn(0f, 1f)
        val shared = outBpm + (inBpm - outBpm) * p
        return (shared / outBpm).coerceIn(0.85f, 1.15f) to (shared / inBpm).coerceIn(0.85f, 1.15f)
    }

    const val MAX_TEMPO_DRIFT = 0.12f

    /** Suggests settings from the analysis of both tracks. */
    fun auto(
        from: MixTrackAnalysis?,
        to: MixTrackAnalysis?,
        fromId: String,
        toId: String,
        preset: MixPreset = MixPreset.AUTO,
    ): MixTransition {
        val bpmOut = from?.bpm ?: 0f
        val bpmIn = to?.bpm ?: 0f
        val canMatch = bpmOut > 0f && bpmIn > 0f && abs(bpmIn / bpmOut - 1f) <= MAX_TEMPO_DRIFT
        val compatible = keysCompatible(from?.key, to?.key)

        val bars =
            when {
                canMatch && compatible -> 8
                canMatch -> 4
                else -> 2
            }
        var transition =
            MixTransition(
                fromId = fromId,
                toId = toId,
                bars = bars,
                preset = preset,
                volume = MixVolume.SMOOTH_CROSSFADE,
                eq = if (canMatch) MixEq.START_BASS_SWAP else MixEq.NONE,
                beatmatch = canMatch,
            )
        when (preset) {
            MixPreset.FADE -> transition = transition.copy(volume = MixVolume.SMOOTH_CROSSFADE, eq = MixEq.NONE, filter = MixFilter.NONE, beatmatch = false)
            MixPreset.RISE -> transition = transition.copy(volume = MixVolume.FADE_IN_FADE_OUT, filter = MixFilter.HP_IN_HP_OUT, eq = MixEq.NONE)
            MixPreset.DROP -> transition = transition.copy(volume = MixVolume.CUT_IN_FADE_OUT, eq = MixEq.START_BASS_SWAP, filter = MixFilter.LP_OUT)
            MixPreset.ECHO -> transition = transition.copy(volume = MixVolume.SMOOTH_CROSSFADE, effect = MixEffect.ECHO_OUT, filter = MixFilter.LP_OUT)
            MixPreset.AUTO, MixPreset.CUSTOM -> Unit
        }
        return place(transition, from, to)
    }

    /** Positions the overlap: it ends close to the end of the outgoing track, on a bar line, and starts the incoming track on its first bar. */
    fun place(
        t: MixTransition,
        from: MixTrackAnalysis?,
        to: MixTrackAnalysis?,
    ): MixTransition {
        val outDur = from?.durationMs ?: 0L
        val bpmOut = from?.bpm ?: 0f
        val lengthMs = t.durationMs(bpmOut)
        var outStart = max(0L, outDur - lengthMs - TAIL_MS)
        if (from != null && from.bpm > 0f) {
            val bar = from.barMs
            val k = ((outStart - from.firstBeatMs) / bar).toLong()
            outStart = (from.firstBeatMs + k * bar).toLong().coerceAtLeast(0L)
        }
        val inStart = if (to != null && to.bpm > 0f) firstBarStart(to) else 0L
        return t.copy(outStartMs = outStart, inStartMs = inStart)
    }

    private const val TAIL_MS = 3_000L

    private fun firstBarStart(a: MixTrackAnalysis): Long = min(a.firstBeatMs, 30_000L)

    /** Camelot wheel compatibility: same key, neighbour on the wheel, or relative major/minor. */
    fun keysCompatible(
        a: String?,
        b: String?,
    ): Boolean {
        val ka = parseCamelot(a) ?: return false
        val kb = parseCamelot(b) ?: return false
        if (ka.second == kb.second) {
            val d = abs(ka.first - kb.first)
            return min(d, 12 - d) <= 1
        }
        return ka.first == kb.first
    }

    private fun parseCamelot(k: String?): Pair<Int, Char>? {
        if (k.isNullOrBlank() || k.length < 2) return null
        val letter = k.last()
        val num = k.dropLast(1).toIntOrNull() ?: return null
        if (letter != 'A' && letter != 'B') return null
        return num to letter
    }
}
