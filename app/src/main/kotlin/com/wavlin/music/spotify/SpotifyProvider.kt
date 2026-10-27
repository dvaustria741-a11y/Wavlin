/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Provides Spotify content using the anonymous web-player token endpoint.
 * No CLIENT_ID / CLIENT_SECRET required — same flow the Spotify web player uses.
 *
 * Use [SpotifyProvider.isSpotifyUrl] and [SpotifyProvider.searchTracks]/[SpotifyProvider.getNewReleases]
 * as static helpers (companion) anywhere; instantiate [SpotifyProvider()] in the UI for [resolve].
 */
class SpotifyProvider {

    // ── Companion: static helpers + shared singleton ─────────────────────────

    companion object {

        private val sharedJson = Json { isLenient = true; ignoreUnknownKeys = true }

        private val sharedClient = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 10_000 }
            install(ContentNegotiation) { json(sharedJson) }
        }

        @Volatile private var cachedToken: String? = null
        @Volatile private var tokenExpiresAt: Long = 0L
        private val tokenMutex = Mutex()

        internal suspend fun fetchToken(): String = tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedToken != null && now < tokenExpiresAt - 60_000) return cachedToken!!
            Timber.d("SpotifyProvider: fetching new anon token")
            val raw = sharedClient.get("https://open.spotify.com/get_access_token") {
                parameter("reason", "transport")
                parameter("productType", "web_player")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0")
                header("Referer", "https://open.spotify.com/")
                header("spotify-app-version", "1.2.31.862.g6b3b6a7a")
                header("App-Platform", "WebPlayer")
            }.bodyAsText()
            val resp = sharedJson.decodeFromString<SpotifyTokenResponse>(raw)
            cachedToken = resp.accessToken
            tokenExpiresAt = resp.expiresAt
            Timber.d("SpotifyProvider: token OK, expires ${resp.expiresAt}")
            resp.accessToken
        }

        /** Returns true if [url] looks like a Spotify link (open.spotify.com or spotify: URI). */
        fun isSpotifyUrl(url: String): Boolean =
            url.trim().let { it.startsWith("https://open.spotify.com/") || it.startsWith("http://open.spotify.com/") || it.startsWith("spotify:") }

        private fun extractIdAndType(url: String): Pair<String, String>? {
            val m = Regex("""open\.spotify\.com/(track|playlist|album)/([A-Za-z0-9]+)""").find(url)
                ?: return null
            return m.groupValues[2] to m.groupValues[1]
        }

        // ── Convenience delegation (same as calling SpotifyProvider().method) ──

        private val _sharedInstance by lazy { SpotifyProvider() }

        /** Search Spotify tracks by keyword. Anonymous — no credentials needed. */
        suspend fun searchTracks(query: String, limit: Int = 10): List<SpotifyTrack> =
            _sharedInstance.searchTracks(query, limit)

        /** Fetch Spotify global new-release albums for the home feed. */
        suspend fun getNewReleases(limit: Int = 12): List<SpotifyAlbumItem> =
            _sharedInstance.getNewReleases(limit)
    }

    // ── Per-instance helpers ──────────────────────────────────────────────────

    private val json get() = sharedJson
    private val client get() = sharedClient
    private suspend fun getToken(): String = fetchToken()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolves a Spotify track / playlist / album URL into a [SpotifyResult].
     * Called from [SearchScreen] when the user pastes a Spotify link.
     */
    suspend fun resolve(url: String): SpotifyResult {
        val (id, type) = extractIdAndType(url)
            ?: throw IllegalArgumentException("Couldn't parse Spotify URL: $url")
        val token = getToken()
        return when (type) {
            "track" -> {
                val raw = client.get("https://api.spotify.com/v1/tracks/$id") {
                    header("Authorization", "Bearer $token")
                }.bodyAsText()
                SpotifyResult.Track(json.decodeFromString<SpotifyTrack>(raw))
            }
            "playlist" -> {
                val playlistRaw = client.get("https://api.spotify.com/v1/playlists/$id") {
                    header("Authorization", "Bearer $token")
                    parameter("fields", "name,tracks(items(track(id,name,artists,album,duration_ms,external_urls)),next)")
                    parameter("limit", "50")
                }.bodyAsText()
                val playlist = json.decodeFromString<SpotifyPlaylistResponse>(playlistRaw)
                val tracks = playlist.tracks?.items?.mapNotNull { it.track }.orEmpty().toMutableList()
                var next = playlist.tracks?.next
                while (next != null && tracks.size < 500) {
                    val pageRaw = client.get(next) { header("Authorization", "Bearer $token") }.bodyAsText()
                    val page = json.decodeFromString<SpotifyPlaylistTracksPage>(pageRaw)
                    tracks += page.items.mapNotNull { it.track }
                    next = page.next
                }
                SpotifyResult.Tracks(title = playlist.name, tracks = tracks)
            }
            "album" -> {
                val albumRaw = client.get("https://api.spotify.com/v1/albums/$id") {
                    header("Authorization", "Bearer $token")
                }.bodyAsText()
                val album = json.decodeFromString<SpotifyAlbumDetails>(albumRaw)
                val tracks = album.tracks?.items?.map { it.withAlbumImages(album.images) }.orEmpty()
                SpotifyResult.Tracks(title = album.name, tracks = tracks)
            }
            else -> throw IllegalArgumentException("Unsupported Spotify URL type: $type")
        }
    }

    /** Search Spotify tracks by query. */
    suspend fun searchTracks(query: String, limit: Int = 10): List<SpotifyTrack> = runCatching {
        val token = getToken()
        val raw = client.get("https://api.spotify.com/v1/search") {
            parameter("q", query)
            parameter("type", "track")
            parameter("limit", limit)
            parameter("market", "US")
            header("Authorization", "Bearer $token")
        }.bodyAsText()
        json.decodeFromString<SpotifySearchResponse>(raw).tracks?.items.orEmpty()
    }.onFailure { Timber.e(it, "SpotifyProvider.searchTracks failed") }.getOrElse { emptyList() }

    /** Fetch Spotify global new-release albums for the home feed. */
    suspend fun getNewReleases(limit: Int = 12): List<SpotifyAlbumItem> = runCatching {
        val token = getToken()
        val raw = client.get("https://api.spotify.com/v1/browse/new-releases") {
            parameter("limit", limit)
            parameter("country", "US")
            header("Authorization", "Bearer $token")
        }.bodyAsText()
        json.decodeFromString<SpotifyNewReleasesResponse>(raw).albums?.items.orEmpty()
    }.onFailure { Timber.e(it, "SpotifyProvider.getNewReleases failed") }.getOrElse { emptyList() }
}
