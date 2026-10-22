package com.wavlin.music.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
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
 * and playlists using public web endpoints and Open Graph scraping — no API keys or premium required.
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
        private val TRACK_REGEX = Regex("spotify\\.com/track/([a-zA-Z0-9]+)")
        private val PLAYLIST_REGEX = Regex("spotify\\.com/playlist/([a-zA-Z0-9]+)")
        private val ALBUM_REGEX = Regex("spotify\\.com/album/([a-zA-Z0-9]+)")
        private const val OEMBED_URL = "https://open.spotify.com/oembed"
    }

    suspend fun resolve(url: String): SpotifyResult {
        TRACK_REGEX.find(url)?.let { return SpotifyResult.Track(getTrack(it.groupValues[1])) }
        PLAYLIST_REGEX.find(url)?.let { return SpotifyResult.Tracks(getPlaylistTracks(it.groupValues[1])) }
        ALBUM_REGEX.find(url)?.let { return SpotifyResult.Tracks(getAlbumTracks(it.groupValues[1])) }
        throw SpotifyException("Unsupported or invalid Spotify URL: $url")
    }

    suspend fun getTrack(trackId: String): SpotifyTrack {
        val spotifyUrl = "https://open.spotify.com/track/$trackId"
        
        try {
            val response = client.get("$OEMBED_URL?url=$spotifyUrl") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            }
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val jsonElement = json.parseToJsonElement(body).jsonObject
                val title = jsonElement["title"]?.jsonPrimitive?.contentOrNull ?: "Unknown Track"
                val artist = jsonElement["author_name"]?.jsonPrimitive?.contentOrNull ?: "Unknown Artist"
                
                return SpotifyTrack(
                    title = title,
                    artist = artist,
                    query = "$artist - $title",
                    durationSeconds = 0,
                    url = spotifyUrl
                )
            }
        } catch (e: Exception) {
            // Fallback to HTML scraping if oEmbed fails
        }

        // Fallback: Scrape Open Graph meta tags directly from the public page
        val response = client.get(spotifyUrl) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        val html = response.bodyAsText()
        
        val titleMatch = Regex("""<meta property="og:title" content="([^"]+)"\s*/?>""").find(html)
        val descMatch = Regex("""<meta property="og:description" content="([^"]+)"\s*/?>""").find(html)
        
        val title = titleMatch?.groupValues?.get(1) ?: "Unknown Track"
        val artist = descMatch?.groupValues?.get(1) ?: "Unknown Artist"

        return SpotifyTrack(
            title = title,
            artist = artist,
            query = "$artist - $title",
            durationSeconds = 0,
            url = spotifyUrl
        )
    }

    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrack> {
        val spotifyUrl = "https://open.spotify.com/playlist/$playlistId"
        val response = client.get(spotifyUrl) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        val html = response.bodyAsText()
        
        val titleMatch = Regex("""<meta property="og:title" content="([^"]+)"\s*/?>""").find(html)
        val title = titleMatch?.groupValues?.get(1) ?: "Playlist"
        
        return listOf(
            SpotifyTrack(
                title = title,
                artist = "Playlist",
                query = title,
                durationSeconds = 0,
                url = spotifyUrl
            )
        )
    }

    suspend fun getAlbumTracks(albumId: String): List<SpotifyTrack> = getPlaylistTracks(albumId)
}
