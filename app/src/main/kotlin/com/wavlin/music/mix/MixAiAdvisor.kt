/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.mix

import android.content.Context
import com.wavlin.music.R
import com.wavlin.music.constants.AiProviderKey
import com.wavlin.music.constants.OpenRouterApiKey
import com.wavlin.music.constants.OpenRouterBaseUrlKey
import com.wavlin.music.constants.OpenRouterModelKey
import com.wavlin.music.utils.dataStore
import com.wavlin.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One pair of neighbouring songs for the advisor. */
class MixAiPair(
    val fromTitle: String,
    val fromArtist: String,
    val toTitle: String,
    val toArtist: String,
    val from: MixTrackAnalysis,
    val to: MixTrackAnalysis,
)

/**
 * Asks the AI provider configured for lyrics translation to choose the transition style for each pair.
 *
 * Only titles, artists, tempo and key are sent. The model never touches audio: placement, timing and the
 * DSP stay deterministic, the AI only picks the preset, length and effects.
 */
object MixAiAdvisor {
    private const val TAG = "MixAiAdvisor"
    private const val BATCH = 10
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private const val SYSTEM_PROMPT = """You are a DJ assistant that chooses transition settings between two songs.
Reply with ONLY a JSON array, no prose and no code fences. One object per pair, in the same order, using this schema:
{"i":<pair index>,"bars":2|4|8,"volume":"SMOOTH_CROSSFADE|OVERLAP|FADE_IN_FADE_OUT|CUT_IN_FADE_OUT|FADE_IN_CUT_OUT|CUT_IN_CUT_OUT","eq":"NONE|START_BASS_SWAP|END_BASS_SWAP","filter":"NONE|LP_OUT|LP_IN|LP_IN_LP_OUT|LP_IN_HP_OUT|HP_OUT|HP_IN|HP_IN_HP_OUT|HP_IN_LP_OUT","effect":"NONE|ECHO_OUT|SPACE_OUT","loop":"NONE|HALF_BAR|ONE_BAR|TWO_BARS","beatmatch":true|false}
Guidelines:
- Close tempos (gap under 6%) with compatible keys can use long blends: 8 bars, SMOOTH_CROSSFADE or OVERLAP, START_BASS_SWAP, beatmatch true.
- Tempo gap over 12% means beatmatch false and a short transition: 2 bars, FADE_IN_FADE_OUT or CUT_IN_FADE_OUT, optionally ECHO_OUT or a filter.
- Clashing keys: prefer short transitions, a filter (LP_OUT or HP_IN_LP_OUT) or ECHO_OUT to mask the change.
- Calm or acoustic songs: gentle SMOOTH_CROSSFADE, no loop, no effect. High-energy songs: bass swap, filters and short loops are fine.
- Keep variety, do not give every pair the same settings."""

    suspend fun advise(
        context: Context,
        pairs: List<MixAiPair>,
    ): Result<List<MixTransition>> =
        withContext(Dispatchers.IO) {
            val apiKey = (context.dataStore[OpenRouterApiKey] ?: "").trim()
            val provider = context.dataStore[AiProviderKey] ?: "OpenRouter"
            val baseUrl = (context.dataStore[OpenRouterBaseUrlKey] ?: "").trim()
            val model = (context.dataStore[OpenRouterModelKey] ?: "").trim()
            if (apiKey.isBlank() || provider == "DeepL" || baseUrl.isBlank() || model.isBlank()) {
                return@withContext Result.failure(Exception(context.getString(R.string.mix_ai_no_key)))
            }
            try {
                val out = ArrayList<MixTransition>()
                pairs.chunked(BATCH).forEach { batch ->
                    val reply = complete(provider, baseUrl, model, apiKey, buildPrompt(batch))
                    val parsed = parse(reply)
                    batch.forEachIndexed { i, pair -> out.add(build(pair, parsed[i])) }
                }
                Result.success(out)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "AI mix failed")
                Result.failure(e)
            }
        }

    private fun buildPrompt(batch: List<MixAiPair>): String {
        val arr = JSONArray()
        batch.forEachIndexed { i, p ->
            val gap = if (p.from.bpm > 0f && p.to.bpm > 0f) abs(p.to.bpm / p.from.bpm - 1f) * 100f else -1f
            arr.put(
                JSONObject().apply {
                    put("i", i)
                    put("from", songJson(p.fromTitle, p.fromArtist, p.from))
                    put("to", songJson(p.toTitle, p.toArtist, p.to))
                    put("tempoGapPercent", if (gap >= 0f) Math.round(gap * 10f) / 10.0 else JSONObject.NULL)
                    put("keysCompatible", MixCurves.keysCompatible(p.from.key, p.to.key))
                },
            )
        }
        return "Choose the transition for each of these ${batch.size} pairs:\n$arr"
    }

    private fun songJson(
        title: String,
        artist: String,
        a: MixTrackAnalysis,
    ) = JSONObject().apply {
        put("title", title)
        put("artist", artist)
        put("bpm", Math.round(a.bpm).toInt())
        put("key", a.key)
    }

    private fun complete(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
    ): String {
        val anthropic = provider == "Claude" || baseUrl.contains("anthropic.com")
        val body =
            if (anthropic) {
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 2000)
                    put("system", SYSTEM_PROMPT)
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                }
            } else {
                JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.5)
                    put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                            .put(JSONObject().put("role", "user").put("content", prompt)),
                    )
                }
            }
        val request =
            Request
                .Builder()
                .url(baseUrl)
                .apply {
                    if (anthropic) {
                        addHeader("x-api-key", apiKey)
                        addHeader("anthropic-version", "2023-06-01")
                    } else {
                        addHeader("Authorization", "Bearer $apiKey")
                    }
                    if (baseUrl.contains("openrouter.ai")) {
                        addHeader("HTTP-Referer", "https://github.com/dvaustria741-a11y/Wavlin")
                        addHeader("X-Title", "Wavlin")
                    }
                }.post(body.toString().toRequestBody(JSON_TYPE))
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${text.take(160)}")
            val json = JSONObject(text)
            return if (anthropic) {
                json.getJSONArray("content").getJSONObject(0).getString("text")
            } else {
                json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }
    }

    private fun parse(reply: String): Map<Int, JSONObject> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) throw Exception("The AI did not return a list")
        val arr = JSONArray(reply.substring(start, end + 1))
        val map = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.optInt("i", i)] = o
        }
        return map
    }

    private inline fun <reified E : Enum<E>> JSONObject.enumOr(
        key: String,
        fallback: E,
    ): E = enumValues<E>().firstOrNull { it.name == optString(key) } ?: fallback

    private fun build(
        pair: MixAiPair,
        choice: JSONObject?,
    ): MixTransition {
        val base = MixCurves.auto(pair.from, pair.to, pair.from.songId, pair.to.songId)
        if (choice == null) return base
        val canMatch =
            pair.from.bpm > 0f && pair.to.bpm > 0f && abs(pair.to.bpm / pair.from.bpm - 1f) <= MixCurves.MAX_TEMPO_DRIFT
        val bars = choice.optInt("bars", base.bars).takeIf { it == 2 || it == 4 || it == 8 } ?: base.bars
        val volume = choice.enumOr("volume", base.volume).takeIf { it != MixVolume.CUSTOM } ?: base.volume
        val loop = choice.enumOr("loop", MixLoop.NONE)
        val styled =
            base.copy(
                bars = bars,
                volume = volume,
                eq = choice.enumOr("eq", base.eq),
                filter = choice.enumOr("filter", base.filter),
                effect = choice.enumOr("effect", base.effect),
                loop = if (pair.from.bpm > 0f) loop else MixLoop.NONE,
                beatmatch = canMatch && choice.optBoolean("beatmatch", base.beatmatch),
            )
        return MixCurves.place(styled, pair.from, pair.to)
    }
}
