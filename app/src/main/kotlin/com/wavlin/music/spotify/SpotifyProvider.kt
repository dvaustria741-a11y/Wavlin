package com.wavlin.music.spotify

import android.util.Base64
import com.wavlin.music.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SpotifyTrack(
    val title: String,
    val artist: String,
    val query: String,
    val durationSeconds: Int,
    val url: String?,
)

sealed interface SpotifyResult {
    data class Track(val track: SpotifyTrack) : SpotifyResult

    data class Tracks(val tracks: List<SpotifyTrack>) : SpotifyResult
}

class SpotifyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Resolves Spotify track, playlist and album links into "Artist - Title" search queries
 * for the YouTube backend. Uses the Client Credentials flow (public metadata only).
 */
class SpotifyProvider(
    private val clientId: String = BuildConfig.SPOTIFY_CLIENT_ID,
    private val clientSecret: String = BuildConfig.SPOTIFY_CLIENT_SECRET,
    private val client: HttpClient = defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tokenMutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    suspend fun resolve(url: String): SpotifyResult {
        TRACK_REGEX.find(url)?.let { return SpotifyResult.Track(getTrack(it.groupValues[1])) }
        PLAYLIST_REGEX.find(url)?.let { return SpotifyResult.Tracks(getPlaylistTracks(it.groupValues[1])) }
        ALBUM_REGEX.find(url)?.let { return SpotifyResult.Tracks(getAlbumTracks(it.groupValues[1])) }
        throw SpotifyException("Unsupported or invalid Spotify URL: $url")
    }

    suspend fun getTrack(trackId: String): SpotifyTrack =
        parseTrack(getJson("$API_BASE_URL/tracks/$trackId"))
            ?: throw SpotifyException("Track $trackId has no metadata")

    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var next: String? = "$API_BASE_URL/playlists/$playlistId/tracks?limit=100"
        while (next != null) {
            val page = getJson(next)
            page["items"]?.jsonArray?.forEach { item ->
                (item as? JsonObject)?.get("track")
                    ?.takeIf { it is JsonObject }
                    ?.let { parseTrack(it.jsonObject) }
                    ?.let(tracks::add)
            }
            next = page["next"]?.jsonPrimitive?.contentOrNull
        }
        return tracks
    }

    suspend fun getAlbumTracks(albumId: String): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var next: String? = "$API_BASE_URL/albums/$albumId/tracks?limit=50"
        while (next != null) {
            val page = getJson(next)
            page["items"]?.jsonArray?.forEach { item ->
                (item as? JsonObject)?.let(::parseTrack)?.let(tracks::add)
            }
            next = page["next"]?.jsonPrimitive?.contentOrNull
        }
        return tracks
    }

    private fun parseTrack(obj: JsonObject): SpotifyTrack? {
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val artist =
            (obj["artists"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                ?.joinToString(", ")
                .orEmpty()
        val durationMs = obj["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0
        val spotifyUrl =
            (obj["external_urls"] as? JsonObject)?.get("spotify")?.jsonPrimitive?.contentOrNull
        return SpotifyTrack(
            title = title,
            artist = artist,
            query = if (artist.isNotEmpty()) "$artist - $title" else title,
            durationSeconds = durationMs / 1000,
            url = spotifyUrl,
        )
    }

    private suspend fun getJson(url: String): JsonObject {
        var response = authorizedGet(url)
        if (response.status == HttpStatusCode.Unauthorized) {
            authenticate(force = true)
            response = authorizedGet(url)
        }
        if (response.status != HttpStatusCode.OK) {
            throw SpotifyException("Spotify request failed (${response.status.value})")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun authorizedGet(url: String): HttpResponse =
        client.get(url) { header(HttpHeaders.Authorization, "Bearer ${authenticate()}") }

    private suspend fun authenticate(force: Boolean = false): String =
        tokenMutex.withLock {
            accessToken?.takeIf { !force }?.let { return@withLock it }
            if (clientId.isBlank() || clientSecret.isBlank()) {
                throw SpotifyException("Spotify credentials are not configured")
            }
            val basic =
                Base64.encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val response =
                client.post(API_AUTH_URL) {
                    header(HttpHeaders.Authorization, "Basic $basic")
                    setBody(FormDataContent(Parameters.build { append("grant_type", "client_credentials") }))
                }
            if (response.status != HttpStatusCode.OK) {
                throw SpotifyException("Spotify auth failed (${response.status.value})")
            }
            val token: JsonElement? = json.parseToJsonElement(response.bodyAsText()).jsonObject["access_token"]
            (token as? JsonPrimitive)?.contentOrNull
                ?.also { accessToken = it }
                ?: throw SpotifyException("Spotify auth response had no access_token")
        }

    companion object {
        private const val API_AUTH_URL = "https://accounts.spotify.com/api/token"
        private const val API_BASE_URL = "https://api.spotify.com/v1"

        private val TRACK_REGEX = Regex("""spotify\.com/(?:intl-[a-z]+/)?track/([a-zA-Z0-9]+)""")
        private val PLAYLIST_REGEX = Regex("""spotify\.com/(?:intl-[a-z]+/)?playlist/([a-zA-Z0-9]+)""")
        private val ALBUM_REGEX = Regex("""spotify\.com/(?:intl-[a-z]+/)?album/([a-zA-Z0-9]+)""")

        private fun defaultClient() =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 10000
                    socketTimeoutMillis = 15000
                }
            }
    }
}
