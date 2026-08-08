package com.wavlin.deezer

import com.wavlin.deezer.models.AlbumsResponse
import com.wavlin.deezer.models.ArtistSearchResponse
import com.wavlin.deezer.models.DeezerAlbum
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Client for Deezer's public Web API (https://developers.deezer.com/api).
 *
 * No API key, no OAuth, no account requirement of any kind - every endpoint used
 * here is anonymous/public catalog data. This is used purely to detect when an
 * artist the user listens to has dropped a new release; actual playback always
 * stays on YouTube Music via the existing innertube pipeline.
 */
object Deezer {
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            defaultRequest {
                url("https://api.deezer.com")
            }

            expectSuccess = true
        }
    }

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Resolve an artist name to a Deezer artist id. Picks the exact case-insensitive
     * name match if present, otherwise falls back to the top search result.
     */
    suspend fun searchArtist(name: String): com.wavlin.deezer.models.DeezerArtist? =
        runCatching {
            val results =
                client
                    .get("/search/artist") {
                        parameter("q", name)
                        parameter("limit", 5)
                    }.body<ArtistSearchResponse>()
                    .data

            results.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: results.firstOrNull()
        }.getOrNull()

    suspend fun getArtistAlbums(
        artistId: Long,
        limit: Int = 25,
    ): List<DeezerAlbum> =
        runCatching {
            client
                .get("/artist/$artistId/albums") {
                    parameter("limit", limit)
                }.body<AlbumsResponse>()
                .data
        }.getOrDefault(emptyList())

    /**
     * Returns albums/singles/EPs by [artistName] released within the last [sinceDays] days.
     * Empty list on any failure (unknown artist, network error, etc) - callers should treat
     * this as "nothing new found" rather than an error.
     */
    suspend fun getRecentReleases(
        artistName: String,
        sinceDays: Int = 45,
    ): List<DeezerAlbum> {
        val artist = searchArtist(artistName) ?: return emptyList()
        val albums = getArtistAlbums(artist.id)
        val cutoff = LocalDate.now().minusDays(sinceDays.toLong())

        return albums.filter { album ->
            val releaseDate =
                album.release_date?.let {
                    runCatching { LocalDate.parse(it, dateFormatter) }.getOrNull()
                } ?: return@filter false

            album.record_type in setOf("album", "single", "ep") && releaseDate.isAfter(cutoff)
        }
    }
}
