package com.wavlin.music.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * Unauthenticated Spotify metadata provider for Wavlin. Resolves tracks, albums,
 * and playlists using public web endpoints and Open Graph scraping (no API keys or Premium required).
 */
class SpotifyProvider(
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10000
            connectTimeoutMillis = 10000
        }
    }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private val TRACK_REGEX = Regex("spotify\\.com/(?:intl-[a-z]+/)?track/([a-zA-Z0-9]+)")
        private val PLAYLIST_REGEX = Regex("spotify\\.com/(?:intl-[a-z]+/)?playlist/([a-zA-Z0-9]+)")
        private val ALBUM_REGEX = Regex("spotify\\.com/(?:intl-[a-z]+/)?album/([a-zA-Z0-9]+)")
        private const val OEMBED_URL = "https://open.spotify.com/oembed"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun isSpotifyUrl(text: String): Boolean =
            TRACK_REGEX.containsMatchIn(text) ||
                PLAYLIST_REGEX.containsMatchIn(text) ||
                ALBUM_REGEX.containsMatchIn(text)

        private fun decodeHtml(text: String): String =
            android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

        private fun metaContent(html: String, property: String): String? {
            val propertyFirst = Regex("""<meta[^>]*property=["']$property["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            val contentFirst = Regex("""<meta[^>]*content=["']([^"']*)["'][^>]*property=["']$property["']""", RegexOption.IGNORE_CASE)
            val raw = propertyFirst.find(html)?.groupValues?.get(1) ?: contentFirst.find(html)?.groupValues?.get(1)
            return raw?.let(::decodeHtml)?.takeIf { it.isNotBlank() }
        }

        // og:description looks like "Artist · Album · Song · 2017" (older pages: "Listen to X on Spotify. Artist · Song · 2017.")
        private fun artistFromDescription(description: String?, title: String): String? {
            if (description == null) return null
            val cleaned = description.replace(Regex("^Listen to .* on Spotify\\.?\\s*", RegexOption.IGNORE_CASE), "")
            return cleaned
                .split("·")
                .map { it.trim().trimEnd('.') }
                .firstOrNull {
                    it.isNotEmpty() &&
                        !it.equals("Song", ignoreCase = true) &&
                        !it.equals(title, ignoreCase = true) &&
                        !it.matches(Regex("\\d{4}"))
                }
        }
    }

    suspend fun resolve(url: String): SpotifyResult {
        TRACK_REGEX.find(url)?.let { return SpotifyResult.Track(getTrack(it.groupValues[1])) }
        PLAYLIST_REGEX.find(url)?.let { return SpotifyResult.Tracks(getPlaylistTracks(it.groupValues[1])) }
        ALBUM_REGEX.find(url)?.let { return SpotifyResult.Tracks(getAlbumTracks(it.groupValues[1])) }
        throw SpotifyException("Unsupported or invalid Spotify URL: $url")
    }

    suspend fun getTrack(trackId: String): SpotifyTrack {
        val spotifyUrl = "https://open.spotify.com/track/$trackId"
        var title: String? = null
        var artist: String? = null

        try {
            val response = client.get("$OEMBED_URL?url=$spotifyUrl") { header("User-Agent", USER_AGENT) }
            if (response.status.value == 200) {
                val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
                title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                artist = obj["author_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }

        // oEmbed often has no artist, so fall back to the public page's Open Graph tags
        if (title == null || artist == null) {
            try {
                val html = fetchHtml(spotifyUrl)
                title = title ?: metaContent(html, "og:title")
                artist = artist ?: artistFromDescription(metaContent(html, "og:description"), title.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }

        if (title.isNullOrBlank()) throw SpotifyException("Couldn't read this Spotify track")
        return SpotifyTrack(
            title = title,
            artist = artist.orEmpty(),
            query = if (artist.isNullOrBlank()) title else "$artist - $title",
            durationSeconds = 0,
            url = spotifyUrl,
        )
    }

    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrack> =
        listOf(collectionAsTrack("https://open.spotify.com/playlist/$playlistId", "Playlist"))

    suspend fun getAlbumTracks(albumId: String): List<SpotifyTrack> =
        listOf(collectionAsTrack("https://open.spotify.com/album/$albumId", "Album"))

    // Without the Web API there is no track list, so a playlist/album becomes one searchable entry (its title).
    private suspend fun collectionAsTrack(spotifyUrl: String, kind: String): SpotifyTrack {
        val title = metaContent(fetchHtml(spotifyUrl), "og:title")
            ?: throw SpotifyException("Couldn't read this Spotify ${kind.lowercase()}")
        return SpotifyTrack(title = title, artist = kind, query = title, durationSeconds = 0, url = spotifyUrl)
    }

    private suspend fun fetchHtml(url: String): String {
        val response = client.get(url) { header("User-Agent", USER_AGENT) }
        if (response.status.value != 200) throw SpotifyException("Spotify returned ${response.status.value}")
        return response.bodyAsText()
    }
}
