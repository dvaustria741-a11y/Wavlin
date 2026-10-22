package com.wavlin.music.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class SpotifyTrack(
    val title: String,
    val artist: String,
    val query: String,
    val durationSeconds: Int,
    val url: String?,
)

sealed interface SpotifyResult {
    data class Track(val track: SpotifyTrack) : SpotifyResult

    data class Tracks(val title: String, val tracks: List<SpotifyTrack>) : SpotifyResult
}

class SpotifyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Keyless Spotify metadata provider. Reads the public embed pages (open.spotify.com/embed/...),
 * which list the title and artists of a track and the tracks of a public playlist or album.
 * Private playlists are not readable without a login.
 */
class SpotifyProvider(
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
        }
    },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private val TRACK_REGEX = Regex("spotify\\.com/(?:intl-[a-z]+/)?track/([a-zA-Z0-9]+)")
        private val PLAYLIST_REGEX = Regex("spotify\\.com/(?:intl-[a-z]+/)?playlist/([a-zA-Z0-9]+)")
        private val ALBUM_REGEX = Regex("spotify\\.com/(?:intl-[a-z]+/)?album/([a-zA-Z0-9]+)")
        private val NEXT_DATA_REGEX =
            Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun isSpotifyUrl(text: String): Boolean =
            TRACK_REGEX.containsMatchIn(text) ||
                PLAYLIST_REGEX.containsMatchIn(text) ||
                ALBUM_REGEX.containsMatchIn(text)
    }

    suspend fun resolve(url: String): SpotifyResult {
        TRACK_REGEX.find(url)?.let { return SpotifyResult.Track(getTrack(it.groupValues[1])) }
        PLAYLIST_REGEX.find(url)?.let { return getCollection("playlist", it.groupValues[1]) }
        ALBUM_REGEX.find(url)?.let { return getCollection("album", it.groupValues[1]) }
        throw SpotifyException("Unsupported or invalid Spotify URL: $url")
    }

    suspend fun getTrack(trackId: String): SpotifyTrack {
        val entity = fetchEntity("track", trackId)
            ?: throw SpotifyException("Couldn't read this Spotify track")
        val title = entity.string("title") ?: entity.string("name")
            ?: throw SpotifyException("Couldn't read this Spotify track")
        val artist = (entity["artists"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("name") }
            ?.joinToString(", ")
            .orEmpty()
        return buildTrack(title, artist, entity.long("duration"), "https://open.spotify.com/track/$trackId")
    }

    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrack> =
        (getCollection("playlist", playlistId) as SpotifyResult.Tracks).tracks

    suspend fun getAlbumTracks(albumId: String): List<SpotifyTrack> =
        (getCollection("album", albumId) as SpotifyResult.Tracks).tracks

    private suspend fun getCollection(kind: String, id: String): SpotifyResult.Tracks {
        val entity = fetchEntity(kind, id)
            ?: throw SpotifyException("Couldn't read this Spotify $kind. Make sure it's public.")
        val title = entity.string("title") ?: entity.string("name") ?: kind.replaceFirstChar { it.uppercase() }
        val tracks = (entity["trackList"] as? JsonArray)
            ?.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val trackTitle = obj.string("title") ?: return@mapNotNull null
                val artist = obj.string("subtitle").orEmpty()
                val uri = obj.string("uri")?.substringAfterLast(':')
                buildTrack(trackTitle, artist, obj.long("duration"), uri?.let { "https://open.spotify.com/track/$it" })
            }
            .orEmpty()
        if (tracks.isEmpty()) throw SpotifyException("This Spotify $kind has no readable tracks")
        return SpotifyResult.Tracks(title, tracks)
    }

    private fun buildTrack(title: String, artist: String, durationMs: Long?, url: String?): SpotifyTrack {
        val cleanArtist = artist.replace('\u00a0', ' ').trim()
        return SpotifyTrack(
            title = title.trim(),
            artist = cleanArtist,
            query = if (cleanArtist.isEmpty()) title.trim() else "$cleanArtist - ${title.trim()}",
            durationSeconds = ((durationMs ?: 0L) / 1000).toInt(),
            url = url,
        )
    }

    private suspend fun fetchEntity(kind: String, id: String): JsonObject? {
        val response = try {
            client.get("https://open.spotify.com/embed/$kind/$id") {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", "en")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SpotifyException("Couldn't reach Spotify", e)
        }
        if (response.status.value != 200) throw SpotifyException("Spotify returned ${response.status.value}")
        val data = NEXT_DATA_REGEX.find(response.bodyAsText())?.groupValues?.get(1) ?: return null
        val root = try {
            json.parseToJsonElement(data)
        } catch (e: Exception) {
            return null
        }
        return findEntity(root)
    }

    private fun findEntity(element: JsonElement): JsonObject? {
        when (element) {
            is JsonObject -> {
                (element["entity"] as? JsonObject)
                    ?.takeIf { it.containsKey("title") || it.containsKey("name") }
                    ?.let { return it }
                for (value in element.values) findEntity(value)?.let { return it }
            }
            is JsonArray -> for (value in element) findEntity(value)?.let { return it }
            else -> {}
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
}
