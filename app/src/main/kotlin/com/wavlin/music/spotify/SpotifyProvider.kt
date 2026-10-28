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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Provides Spotify content without CLIENT_ID / CLIENT_SECRET.
 *
 * Uses the same two-token strategy as [SpotifyCanvasClient]:
 *   1. Client token  — from clienttoken.spotify.com (no user auth, web-player client_id)
 *   2. Access token  — anonymous token from open.spotify.com/get_access_token
 * Search goes through the Pathfinder partner API (same endpoint + hash as SpotifyCanvasClient)
 * which is more reliable than the public REST /v1/search on anonymous tokens.
 */
class SpotifyProvider {

    companion object {
        // ── Constants (mirrored from SpotifyCanvasClient) ─────────────────────
        private const val CLIENT_TOKEN_URL   = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val PATHFINDER_URL     = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val SEARCH_HASH        = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        private const val CLIENT_ID          = "d8a5ed958d274c2e8ee717e6a4b0971d"
        private const val ANON_TOKEN_URL     = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
        private const val TIMEOUT_MS         = 15_000

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // ── Client token cache ────────────────────────────────────────────────
        @Volatile private var clientToken: String = ""
        @Volatile private var clientTokenExpiresMs: Long = 0L
        private val clientTokenMutex = Mutex()

        // ── Anonymous access token cache ──────────────────────────────────────
        @Volatile private var cachedToken: String? = null
        @Volatile private var tokenExpiresAt: Long = 0L
        private val tokenMutex = Mutex()

        // ── HTTP helper (same pattern as SpotifyCanvasClient) ─────────────────
        internal fun http(
            url: String,
            headers: Map<String, String>,
            body: ByteArray? = null,
            method: String = if (body == null) "GET" else "POST",
        ): String {
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
                if (code !in 200..299) error("HTTP $code: ${String(data, Charsets.UTF_8).take(300)}")
                return String(data, Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        /** Gets or refreshes the Spotify web-player client token (no user login needed). */
        internal suspend fun fetchClientToken(): String = clientTokenMutex.withLock {
            if (clientToken.isNotEmpty() && clientTokenExpiresMs > System.currentTimeMillis() + 60_000L)
                return clientToken
            Timber.d("SpotifyProvider: fetching client token")
            val body = """{"client_data":{"client_version":"1.2.62.476.g2ad6e7f3","client_id":"$CLIENT_ID","js_sdk_data":{"device_brand":"Apple","device_model":"unknown","os":"macos","os_version":"10.15.7","device_id":"4fd0c748-b282-4927-9658-6d51a24e58b7","device_type":"computer"}}}"""
            val raw = http(
                url = CLIENT_TOKEN_URL,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
                ),
                body = body.toByteArray(),
            )
            val granted = json.parseToJsonElement(raw).jsonObject["granted_token"]?.jsonObject
                ?: error("client token missing")
            val tok = granted["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ttl = granted["expires_after_seconds"]?.jsonPrimitive?.longOrNull ?: 3600L
            clientToken = tok
            clientTokenExpiresMs = System.currentTimeMillis() + ttl * 1000L
            Timber.d("SpotifyProvider: client token OK")
            tok
        }

        /** Gets or refreshes the anonymous Spotify access token. */
        internal suspend fun fetchToken(): String = tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedToken != null && now < tokenExpiresAt - 60_000) return cachedToken!!
            Timber.d("SpotifyProvider: fetching anon access token")
            val raw = http(
                url = ANON_TOKEN_URL,
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
            Timber.d("SpotifyProvider: anon token OK")
            resp.accessToken
        }

        // ── URL helpers ───────────────────────────────────────────────────────
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

        // ── Pathfinder response parser ─────────────────────────────────────────
        private fun parsePathfinderTracks(raw: String): List<SpotifyTrack> {
            val root = json.parseToJsonElement(raw).jsonObject
            val items = root["data"]?.jsonObject
                ?.get("searchV2")?.jsonObject
                ?.get("tracksV2")?.jsonObject
                ?.get("items")?.jsonArray
                ?: return emptyList()
            return items.mapNotNull { entry ->
                val data = entry.jsonObject["item"]?.jsonObject?.get("data")?.jsonObject ?: return@mapNotNull null
                val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = data["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val durationMs = data["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.intOrNull ?: 0
                val artistNames = data["artists"]?.jsonObject?.get("items")?.jsonArray
                    ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
                    ?: emptyList()
                val coverUrl = data["albumOfTrack"]?.jsonObject?.get("coverArt")?.jsonObject
                    ?.get("sources")?.jsonArray
                    ?.maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0 }
                    ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                SpotifyTrack(
                    id = id,
                    title = name,
                    artists = artistNames.map { SpotifyArtist(name = it) },
                    album = coverUrl?.let { url -> SpotifyAlbum(images = listOf(SpotifyImage(url = url))) },
                    durationMs = durationMs,
                    externalUrls = SpotifyExternalUrls(spotify = "https://open.spotify.com/track/$id"),
                )
            }
        }

        // ── Singleton convenience delegates ───────────────────────────────────
        private val _instance by lazy { SpotifyProvider() }
        suspend fun searchTracks(query: String, limit: Int = 10) = _instance.searchTracks(query, limit)
        suspend fun getNewReleases(limit: Int = 12)              = _instance.getNewReleases(limit)
    }

    private suspend fun getClientToken() = fetchClientToken()
    private suspend fun getToken()       = fetchToken()
    private fun authHeaders(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
    private val partnerHeaders = mapOf(
        "app-platform" to "WebPlayer",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json",
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Resolve a Spotify URL → SpotifyResult.Track or SpotifyResult.Tracks. */
    suspend fun resolve(url: String): SpotifyResult = withContext(Dispatchers.IO) {
        val (id, type) = extractIdAndType(url)
            ?: throw IllegalArgumentException("Couldn't parse Spotify URL: $url")
        val token = getToken()
        when (type) {
            "track" -> {
                val raw = http("https://api.spotify.com/v1/tracks/$id", authHeaders(token))
                SpotifyResult.Track(json.decodeFromString<SpotifyTrack>(raw))
            }
            "playlist" -> {
                val fields = URLEncoder.encode("name,tracks(items(track(id,name,artists,album,duration_ms,external_urls)),next)", "UTF-8")
                val raw = http("https://api.spotify.com/v1/playlists/$id?fields=$fields&limit=50", authHeaders(token))
                val playlist = json.decodeFromString<SpotifyPlaylistResponse>(raw)
                val tracks = playlist.tracks?.items?.mapNotNull { it.track }.orEmpty().toMutableList()
                var next = playlist.tracks?.next
                while (next != null && tracks.size < 500) {
                    val page = json.decodeFromString<SpotifyPlaylistTracksPage>(http(next, authHeaders(token)))
                    tracks += page.items.mapNotNull { it.track }
                    next = page.next
                }
                SpotifyResult.Tracks(title = playlist.name, tracks = tracks)
            }
            "album" -> {
                val raw = http("https://api.spotify.com/v1/albums/$id", authHeaders(token))
                val album = json.decodeFromString<SpotifyAlbumDetails>(raw)
                SpotifyResult.Tracks(title = album.name, tracks = album.tracks?.items?.map { it.withAlbumImages(album.images) }.orEmpty())
            }
            else -> throw IllegalArgumentException("Unsupported Spotify URL type: $type")
        }
    }

    /**
     * Search Spotify via the Pathfinder partner API — same endpoint SpotifyCanvasClient uses.
     * Works with anonymous tokens and returns all-region results (no market filter).
     */
    suspend fun searchTracks(query: String, limit: Int = 10): List<SpotifyTrack> = runCatching {
        withContext(Dispatchers.IO) {
            val clientTok = getClientToken()
            val accessTok = getToken()
            val variables = URLEncoder.encode(
                """{"searchTerm":${json.encodeToString(kotlinx.serialization.json.JsonPrimitive(query))},"offset":0,"limit":$limit,"numberOfTopResults":$limit,"includeAudiobooks":false,"includePreReleases":false}""",
                "UTF-8",
            )
            val extensions = URLEncoder.encode(
                """{"persistedQuery":{"version":1,"sha256Hash":"$SEARCH_HASH"}}""",
                "UTF-8",
            )
            val url = "$PATHFINDER_URL?operationName=searchTracks&variables=$variables&extensions=$extensions"
            val raw = http(url, partnerHeaders + mapOf(
                "Authorization" to "Bearer $accessTok",
                "Client-Token" to clientTok,
            ))
            parsePathfinderTracks(raw)
        }
    }.onFailure { Timber.e(it, "SpotifyProvider.searchTracks failed: $query") }
     .getOrElse { emptyList() }

    /** Fetch Spotify global new-release albums for the home feed. */
    suspend fun getNewReleases(limit: Int = 12): List<SpotifyAlbumItem> = runCatching {
        withContext(Dispatchers.IO) {
            val raw = http("https://api.spotify.com/v1/browse/new-releases?limit=$limit", authHeaders(getToken()))
            json.decodeFromString<SpotifyNewReleasesResponse>(raw).albums?.items.orEmpty()
        }
    }.onFailure { Timber.e(it, "SpotifyProvider.getNewReleases failed") }
     .getOrElse { emptyList() }
}
