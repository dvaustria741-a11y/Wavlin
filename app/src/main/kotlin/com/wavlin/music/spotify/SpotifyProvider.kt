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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Spotify catalogue access (search, new releases, link resolution) using the signed-in
 * web-player access token from SpotifySession, the same token SpotifyCanvasClient uses.
 * No CLIENT_ID / CLIENT_SECRET. When the user is not signed in to Spotify there is no
 * token and every call returns an empty result.
 */
class SpotifyProvider {

    companion object {
        private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
        private const val CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
        private const val TIMEOUT_MS = 15_000
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Set by SpotifySession on creation; returns a valid signed-in access token or null. */
        @Volatile
        var tokenProvider: (suspend () -> String?)? = null

        internal suspend fun fetchToken(): String? = tokenProvider?.invoke()

        @Volatile private var clientToken: String = ""
        @Volatile private var clientTokenExpiresMs: Long = 0L
        private val clientTokenMutex = Mutex()

        internal fun http(url: String, headers: Map<String, String>, body: ByteArray? = null): String {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = if (body == null) "GET" else "POST"
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                var data = stream?.use { it.readBytes() } ?: ByteArray(0)
                if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                    data = GZIPInputStream(data.inputStream()).use { it.readBytes() }
                }
                if (code !in 200..299) error("HTTP $code: ${String(data, Charsets.UTF_8).take(300)}")
                return String(data, Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }

        private suspend fun fetchClientToken(): String = clientTokenMutex.withLock {
            if (clientToken.isNotEmpty() && clientTokenExpiresMs > System.currentTimeMillis() + 60_000L) return clientToken
            val body = """{"client_data":{"client_version":"1.2.62.476.g2ad6e7f3","client_id":"$CLIENT_ID","js_sdk_data":{"device_brand":"Apple","device_model":"unknown","os":"macos","os_version":"10.15.7","device_id":"4fd0c748-b282-4927-9658-6d51a24e58b7","device_type":"computer"}}}"""
            val raw = http(CLIENT_TOKEN_URL, mapOf("Accept" to "application/json", "Content-Type" to "application/json", "User-Agent" to UA), body.toByteArray())
            val granted = json.parseToJsonElement(raw).jsonObject["granted_token"]?.jsonObject ?: error("client token missing")
            val tok = granted["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (tok.isEmpty()) error("client token empty")
            clientToken = tok
            clientTokenExpiresMs = System.currentTimeMillis() + (granted["expires_after_seconds"]?.jsonPrimitive?.longOrNull ?: 3600L) * 1000L
            tok
        }

        fun isSpotifyUrl(url: String): Boolean = url.trim().let {
            it.startsWith("https://open.spotify.com/") || it.startsWith("http://open.spotify.com/") || it.startsWith("spotify:")
        }

        private fun extractIdAndType(url: String): Pair<String, String>? {
            val m = Regex("""open\.spotify\.com/(track|playlist|album)/([A-Za-z0-9]+)""").find(url) ?: return null
            return m.groupValues[2] to m.groupValues[1]
        }

        private fun parsePathfinderTracks(raw: String): List<SpotifyTrack> {
            val items = json.parseToJsonElement(raw).jsonObject["data"]?.jsonObject
                ?.get("searchV2")?.jsonObject?.get("tracksV2")?.jsonObject?.get("items")?.jsonArray
                ?: error("pathfinder shape changed")
            return items.mapNotNull { entry ->
                val data = entry.jsonObject["item"]?.jsonObject?.get("data")?.jsonObject ?: return@mapNotNull null
                val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = data["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val durationMs = data["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.intOrNull ?: 0
                val artistNames = data["artists"]?.jsonObject?.get("items")?.jsonArray
                    ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
                    ?: emptyList()
                val cover = data["albumOfTrack"]?.jsonObject?.get("coverArt")?.jsonObject?.get("sources")?.jsonArray
                    ?.maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0 }
                    ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                SpotifyTrack(
                    id = id,
                    title = name,
                    artists = artistNames.map { SpotifyArtist(name = it) },
                    album = cover?.let { SpotifyAlbum(images = listOf(SpotifyImage(url = it))) },
                    durationMs = durationMs,
                    externalUrls = SpotifyExternalUrls(spotify = "https://open.spotify.com/track/$id"),
                )
            }
        }

        private val _instance by lazy { SpotifyProvider() }
        suspend fun searchTracks(query: String, limit: Int = 10) = _instance.searchTracks(query, limit)
        suspend fun getNewReleases(limit: Int = 12) = _instance.getNewReleases(limit)
    }

    private fun authHeaders(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

    private suspend fun searchPathfinder(accessToken: String, query: String, limit: Int): List<SpotifyTrack> {
        val clientTok = fetchClientToken()
        val variables = """{"searchTerm":${JsonPrimitive(query)},"offset":0,"limit":$limit,"numberOfTopResults":$limit,"includeAudiobooks":false,"includePreReleases":false}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$SEARCH_HASH"}}"""
        val url = "$PATHFINDER_URL?operationName=searchTracks" +
            "&variables=${URLEncoder.encode(variables, "UTF-8")}" +
            "&extensions=${URLEncoder.encode(extensions, "UTF-8")}"
        val raw = http(url, authHeaders(accessToken) + mapOf("Client-Token" to clientTok, "app-platform" to "WebPlayer", "User-Agent" to UA))
        return parsePathfinderTracks(raw)
    }

    private fun searchRest(accessToken: String, query: String, limit: Int): List<SpotifyTrack> {
        val raw = http("https://api.spotify.com/v1/search?type=track&limit=$limit&q=${URLEncoder.encode(query, "UTF-8")}", authHeaders(accessToken))
        return json.decodeFromString<SpotifySearchResponse>(raw).tracks?.items.orEmpty()
    }

    /** Searches Spotify's catalogue (all regions). Empty when signed out or on failure. */
    suspend fun searchTracks(query: String, limit: Int = 10): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val accessTok = fetchToken()
        if (accessTok == null) {
            Timber.d("SpotifyProvider: no signed-in Spotify session, skipping search")
            return@withContext emptyList()
        }
        runCatching { searchPathfinder(accessTok, query, limit) }
            .onFailure { Timber.w(it, "SpotifyProvider: pathfinder search failed, trying REST") }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { searchRest(accessTok, query, limit) }
                .onFailure { Timber.e(it, "SpotifyProvider: REST search failed for: $query") }
                .getOrElse { emptyList() }
    }

    /** New-release albums for the home feed. Empty when signed out or on failure. */
    suspend fun getNewReleases(limit: Int = 12): List<SpotifyAlbumItem> = withContext(Dispatchers.IO) {
        val accessTok = fetchToken() ?: return@withContext emptyList()
        runCatching {
            val raw = http("https://api.spotify.com/v1/browse/new-releases?limit=$limit", authHeaders(accessTok))
            json.decodeFromString<SpotifyNewReleasesResponse>(raw).albums?.items.orEmpty()
        }.onFailure { Timber.e(it, "SpotifyProvider.getNewReleases failed") }.getOrElse { emptyList() }
    }

    /** Resolves a pasted Spotify track / playlist / album link. */
    suspend fun resolve(url: String): SpotifyResult = withContext(Dispatchers.IO) {
        val (id, type) = extractIdAndType(url) ?: throw IllegalArgumentException("Couldn't parse Spotify URL: $url")
        val token = fetchToken() ?: throw IllegalStateException("Sign in to Spotify to open Spotify links")
        when (type) {
            "track" -> SpotifyResult.Track(json.decodeFromString<SpotifyTrack>(http("https://api.spotify.com/v1/tracks/$id", authHeaders(token))))
            "playlist" -> {
                val fields = URLEncoder.encode("name,tracks(items(track(id,name,artists,album,duration_ms,external_urls)),next)", "UTF-8")
                val playlist = json.decodeFromString<SpotifyPlaylistResponse>(http("https://api.spotify.com/v1/playlists/$id?fields=$fields&limit=50", authHeaders(token)))
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
                val album = json.decodeFromString<SpotifyAlbumDetails>(http("https://api.spotify.com/v1/albums/$id", authHeaders(token)))
                SpotifyResult.Tracks(title = album.name, tracks = album.tracks?.items?.map { it.withAlbumImages(album.images) }.orEmpty())
            }
            else -> throw IllegalArgumentException("Unsupported Spotify URL type: $type")
        }
    }
}
