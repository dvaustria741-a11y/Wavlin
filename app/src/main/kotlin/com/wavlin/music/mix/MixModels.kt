/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.mix

import org.json.JSONArray
import org.json.JSONObject

/** A control point of a volume curve: [x] is the transition progress (0..1), [y] the gain (0..1). */
data class MixPoint(
    val x: Float,
    val y: Float,
)

enum class MixPreset { AUTO, CUSTOM, FADE, RISE, DROP, ECHO }

enum class MixVolume { CUSTOM, SMOOTH_CROSSFADE, OVERLAP, FADE_IN_FADE_OUT, CUT_IN_FADE_OUT, FADE_IN_CUT_OUT, CUT_IN_CUT_OUT }

enum class MixEq { NONE, START_BASS_SWAP, END_BASS_SWAP }

enum class MixFilter {
    NONE,
    LP_OUT,
    LP_IN,
    LP_IN_LP_OUT,
    LP_IN_HP_OUT,
    HP_OUT,
    HP_IN,
    HP_IN_HP_OUT,
    HP_IN_LP_OUT,
}

enum class MixEffect { NONE, ECHO_OUT, SPACE_OUT }

enum class MixLoop(val bars: Float) {
    NONE(0f),
    HALF_BAR(0.5f),
    ONE_BAR(1f),
    TWO_BARS(2f),
}

/**
 * One transition between two neighbouring tracks of a mixed playlist.
 *
 * [outStartMs] is the position in the outgoing track where the overlap begins, [inStartMs] the position in the
 * incoming track that plays at that very moment. [bars] defines how long the overlap lasts.
 */
data class MixTransition(
    val fromId: String,
    val toId: String,
    val bars: Int = 4,
    val outStartMs: Long = 0L,
    val inStartMs: Long = 0L,
    val preset: MixPreset = MixPreset.AUTO,
    val volume: MixVolume = MixVolume.SMOOTH_CROSSFADE,
    val customOut: List<MixPoint> = MixCurves.defaultOut,
    val customIn: List<MixPoint> = MixCurves.defaultIn,
    val eq: MixEq = MixEq.NONE,
    val filter: MixFilter = MixFilter.NONE,
    val effect: MixEffect = MixEffect.NONE,
    val loop: MixLoop = MixLoop.NONE,
    val beatmatch: Boolean = false,
) {
    /** Length of the overlap for a given tempo of the outgoing track. */
    fun durationMs(outBpm: Float): Long {
        val bpm = if (outBpm in 40f..260f) outBpm else 120f
        return (bars * 4 * 60_000f / bpm).toLong()
    }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("from", fromId)
            put("to", toId)
            put("bars", bars)
            put("outStart", outStartMs)
            put("inStart", inStartMs)
            put("preset", preset.name)
            put("volume", volume.name)
            put("customOut", customOut.toJson())
            put("customIn", customIn.toJson())
            put("eq", eq.name)
            put("filter", filter.name)
            put("effect", effect.name)
            put("loop", loop.name)
            put("beatmatch", beatmatch)
        }

    companion object {
        fun fromJson(o: JSONObject): MixTransition =
            MixTransition(
                fromId = o.getString("from"),
                toId = o.getString("to"),
                bars = o.optInt("bars", 4),
                outStartMs = o.optLong("outStart", 0L),
                inStartMs = o.optLong("inStart", 0L),
                preset = enumOr(o.optString("preset"), MixPreset.AUTO),
                volume = enumOr(o.optString("volume"), MixVolume.SMOOTH_CROSSFADE),
                customOut = o.optJSONArray("customOut").toPoints(MixCurves.defaultOut),
                customIn = o.optJSONArray("customIn").toPoints(MixCurves.defaultIn),
                eq = enumOr(o.optString("eq"), MixEq.NONE),
                filter = enumOr(o.optString("filter"), MixFilter.NONE),
                effect = enumOr(o.optString("effect"), MixEffect.NONE),
                loop = enumOr(o.optString("loop"), MixLoop.NONE),
                beatmatch = o.optBoolean("beatmatch", false),
            )
    }
}

/** Result of analysing a track. [peaks] holds [PEAK_COUNT] waveform bars, each 0..255. */
class MixTrackAnalysis(
    val songId: String,
    val bpm: Float,
    val firstBeatMs: Long,
    val key: String,
    val durationMs: Long,
    val peaks: ByteArray,
) {
    val beatMs: Float get() = if (bpm > 0f) 60_000f / bpm else 0f
    val barMs: Float get() = beatMs * 4f

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("id", songId)
            put("bpm", bpm.toDouble())
            put("first", firstBeatMs)
            put("key", key)
            put("dur", durationMs)
            put("peaks", android.util.Base64.encodeToString(peaks, android.util.Base64.NO_WRAP))
        }

    companion object {
        const val PEAK_COUNT = 600

        fun fromJson(o: JSONObject): MixTrackAnalysis =
            MixTrackAnalysis(
                songId = o.getString("id"),
                bpm = o.optDouble("bpm", 0.0).toFloat(),
                firstBeatMs = o.optLong("first", 0L),
                key = o.optString("key", ""),
                durationMs = o.optLong("dur", 0L),
                peaks = android.util.Base64.decode(o.optString("peaks", ""), android.util.Base64.NO_WRAP),
            )
    }
}

private fun List<MixPoint>.toJson(): JSONArray =
    JSONArray().also { arr ->
        forEach { arr.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
    }

private fun JSONArray?.toPoints(fallback: List<MixPoint>): List<MixPoint> {
    if (this == null || length() < 2) return fallback
    return (0 until length()).map { i ->
        val p = getJSONArray(i)
        MixPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
    }
}

private inline fun <reified E : Enum<E>> enumOr(
    name: String?,
    fallback: E,
): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback
