/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback

import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.YouTubeClient
import com.wavlin.music.spotify.account.SpotifyAuthState
import com.wavlin.music.spotify.account.SpotifySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and caches looping video-canvas URLs for the now-playing song.
 *
 * Resolution order
 *  1. YouTube / YTM adaptive video stream: lowest-bitrate MP4 that needs no cipher decoding.
 *  2. Spotify Canvas (stub – requires a Spotify track ID; wired up in a follow-up commit).
 *
 * All results (including "no canvas") are cached in an LRU so each videoId is only
 * queried once per app session.
 */
@Singleton
class VideoCanvasManager @Inject constructor(
    private val spotifySession: SpotifySession,
) {
    /** Sentinel stored for a "fetched, nothing found" entry so we skip re-fetching. */
    private val NO_CANVAS = "\u0001"

    private val cache: MutableMap<String, String?> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String?>) = size > 100
        }
    )

    /**
     * Returns a direct, playable MP4 video URL for [videoId], or `null` when none is available.
     * IO-safe: dispatches to [Dispatchers.IO] internally.
     */
    suspend fun getCanvasUrl(videoId: String): String? {
        cache[videoId]?.let { cached -> return if (cached == NO_CANVAS) null else cached }
        val url = withContext(Dispatchers.IO) {
            tryYtmVideoStream(videoId) ?: trySpotifyCanvas(videoId)
        }
        cache[videoId] = url ?: NO_CANVAS
        return url
    }

    /** Clears cached results (e.g. on account sign-out). */
    fun clearCache() = cache.clear()

    // ── YouTube / YTM ──────────────────────────────────────────────────────

    /**
     * Calls the innertube /player endpoint and picks the smallest direct-URL MP4 video stream.
     *
     * Streams protected by a signatureCipher need n-parameter deobfuscation that this path
     * does not perform; those are filtered out. What remains is typically ≤240 p – adequate
     * for a background canvas loop.
     */
    private suspend fun tryYtmVideoStream(videoId: String): String? = runCatching {
        YouTube.player(
            videoId = videoId,
            client = YouTubeClient.WEB_REMIX,
        ).getOrNull()
            ?.streamingData
            ?.adaptiveFormats
            ?.filter { fmt ->
                fmt.width != null                        // video track (not audio-only)
                    && fmt.mimeType.startsWith("video/mp4")
                    && fmt.url != null                   // direct URL present
                    && fmt.signatureCipher == null        // no cipher needed
                    && fmt.cipher == null
            }
            ?.minByOrNull { it.bitrate }
            ?.url
    }.getOrElse {
        Timber.d("VideoCanvas: YTM lookup failed for $videoId — ${it.message}")
        null
    }

    // ── Spotify Canvas ─────────────────────────────────────────────────────

    /**
     * Future: match [videoId] to a Spotify track then call SpotifyClient.fetchCanvas().
     * A YTM video ID != a Spotify track ID, so a search/match step is needed first.
     * TODO: implement SpotifyMatcher lookup + SpotifyClient.fetchCanvas(trackId)
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun trySpotifyCanvas(videoId: String): String? {
        if (spotifySession.authState.value != SpotifyAuthState.CONNECTED) return null
        return null
    }
}
