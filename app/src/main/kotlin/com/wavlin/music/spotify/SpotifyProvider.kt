/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Provides Spotify content using the anonymous web-player token.
 * No CLIENT_ID / CLIENT_SECRET required.
 *
 * Uses HttpURLConnection (same as SpotifyCanvasClient) for Android reliability.
 * No market filter on search so non-US tracks (e.g. PH-only indie releases) are returned.
 */
class SpotifyProvider {

    companion object {

        private val json = Json { isLenient = true; ignoreUnknownKeys = true }

        private const val TIMEOUT_MS = 15_000

        // ── Anonymous token cache (shared across all instances) ───────────────

        @Volatile private var cachedToken: String? = null
        @Volatile private var tokenExpiresAt: Long = 0L
        private val tokenMutex = Mutex()

        internal suspend fun fetchToken(): String = tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedToken != null && now < tokenExpiresAt - 60_000) return cachedToken!!
            Timber.d("SpotifyProvider: fetching new anon token")
            val raw = http(
                url = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
                    "Referer" to "https://open.spotify.com/",
                    "spotify-app-version" to "1.2.31.862.g6b3b6a7a",
                    "App-Platform" to "WebPlayer",
                    "Accept" to "application/json",
                ),
            )
            val resp = json.decodeFromString<SpotifyTokenResponse>(raw)
            cachedToken = resp.accessToken
            tokenExpiresAt = resp.expiresAt
            Timber.d("SpotifyProvider: token OK, expires ${resp.expiresAt}")
            resp.accessToken
        }

        /** Returns true if [url] is a Spotify link. */
        fun isSpotifyUrl(url: String): Boolean =
            url.trim().let {
                it.startsWith("https://open.spotify.com/") ||
                it.startsWith("http://open.spotify.com/") ||
                it.startsWith("spotify:")
            }

        private fun extractIdAndType(url: String): Pair<String, String>? {
            val m = Regex("""open\.spotify\.com/(track|playlist|album)/([A-Za-z0-9]+)""").find(url)
                ?: return null
            return m.groupValues[2] to m.groupValues[1]
        }

        // ── HTTP helper (mirrors SpotifyCanvasClient approach) ────────────────

        internal fun http(url: String, headers: Map<String, String>, body: ByteArray? = null, method: String = "GET"): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                var data = stream?.use { it.readBytes() } ?: ByteArray(0)
                if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                    data = GZIPInputStream(data.inputStream()).use { it.readBytes() }
                }
                if (code !in 200..299) error("HTTP $code: ${String(data, Charsets.UTF_8).take(200)}")
                return String(data, Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        // ── Shared singleton convenience delegates ────────────────────────────

        private val _instance by lazy { SpotifyProvider() }

        /** Search Spotify tracks by keyword. No credentials needed. */
        suspend fun searchTracks(query: String, limit: Int = 10): List<SpotifyTrack> =
            _instance.searchTracks(query, limit)

        /** Fetch Spotify global new-release albums for the home feed. */
        suspend fun getNewReleases(limit: Int = 12): List<SpotifyAlbumItem> =
            _instance.getNewReleases(limit)
    }

    // ── Instance helpers ──────────────────────────────────────────────────────

    private suspend fun getToken(): String = fetchToken()

    private fun authHeader(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

    // ── Public API ────────────────────────────────────────────────────────────

    /** Resolve a Spotify URL into a [SpotifyResult]. Called when user pastes a link. */
    suspend fun resolve(url: String): SpotifyResult = withContext(Dispatchers.IO) {
        val (id, type) = extractIdAndType(url)
            ?: throw IllegalArgumentException("Couldn't parse Spotify URL: $url")
        val token = getToken()
        when (type) {
            "track" -> {
                val raw = http("https://api.spotify.com/v1/tracks/$id", authHeader(token))
                SpotifyResult.Track(json.decodeFromString<SpotifyTrack>(raw))
            }
            "playlist" -> {
                val fields = URLEncoder.encode(
                    "name,tracks(items(track(id,name,artists,album,duration_ms,external_urls)),next)",
                    "UTF-8"
                )
                val playlistRaw = http(
                    "https://api.spotify.com/v1/playlists/$id?fields=$fields&limit=50",
                    authHeader(token),
                )
                val playlist = json.decodeFromString<SpotifyPlaylistResponse>(playlistRaw)
                val tracks = playlist.tracks?.items?.mapNotNull { it.track }.orEmpty().toMutableList()
                var next = playlist.tracks?.next
                while (next != null && tracks.size < 500) {
                    val page = json.decodeFromString<SpotifyPlaylistTracksPage>(
                        http(next, authHeader(token))
                    )
                    tracks += page.items.mapNotNull { it.track }
                    next = page.next
                }
                SpotifyResult.Tracks(title = playlist.name, tracks = tracks)
            }
            "album" -> {
                val albumRaw = http("https://api.spotify.com/v1/albums/$id", authHeader(token))
                val album = json.decodeFromString<SpotifyAlbumDetails>(albumRaw)
                val tracks = album.tracks?.items?.map { it.withAlbumImages(album.images) }.orEmpty()
                SpotifyResult.Tracks(title = album.name, tracks = tracks)
            }
            else -> throw IllegalArgumentException("Unsupported Spotify URL type: $type")
        }
    }

    /** Search Spotify tracks — no market filter so all regional tracks are returned. */
    suspend fun searchTracks(query: String, limit: Int = 10): List<SpotifyTrack> = runCatching {
        withContext(Dispatchers.IO) {
            val token = getToken()
            val q = URLEncoder.encode(query, "UTF-8")
            val raw = http(
                "https://api.spotify.com/v1/search?q=$q&type=track&limit=$limit",
                authHeader(token),
            )
            json.decodeFromString<SpotifySearchResponse>(raw).tracks?.items.orEmpty()
        }
    }.onFailure { Timber.e(it, "SpotifyProvider.searchTracks failed for: $query") }
     .getOrElse { emptyList() }

    /** Fetch Spotify new-release albums for the home feed. */
    suspend fun getNewReleases(limit: Int = 12): List<SpotifyAlbumItem> = runCatching {
        withContext(Dispatchers.IO) {
            val token = getToken()
            val raw = http(
                "https://api.spotify.com/v1/browse/new-releases?limit=$limit",
                authHeader(token),
            )
            json.decodeFromString<SpotifyNewReleasesResponse>(raw).albums?.items.orEmpty()
        }
    }.onFailure { Timber.e(it, "SpotifyProvider.getNewReleases failed") }
     .getOrElse { emptyList() }
}
