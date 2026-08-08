package com.wavlin.spotify

import com.wavlin.spotify.models.ArtistAlbumsResponse
import com.wavlin.spotify.models.ArtistSearchResponse
import com.wavlin.spotify.models.SpotifyAlbum
import com.wavlin.spotify.models.SpotifyArtist
import com.wavlin.spotify.models.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Client for Spotify's official Web API, using the Client Credentials flow.
 * This flow only ever grants access to *public catalog* data (search, artist
 * albums) - it can't see anyone's personal library, playlists, or play audio.
 *
 * Requires a Client ID + Secret from https://developer.spotify.com/dashboard.
 * As of Feb 2026, Spotify requires the registering account to have an active
 * Premium subscription for these credentials to work in Development Mode -
 * if auth fails, every call here fails closed (empty list / null), so callers
 * should treat that as "Spotify unavailable" and fall back to another source.
 */
class Spotify(
    private val clientId: String,
    private val clientSecret: String,
) {
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
        }
    }

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiresAtMillis: Long = 0L

    val isConfigured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank() && clientId != clientSecret

    private suspend fun getAccessToken(): String? {
        if (!isConfigured) return null

        tokenMutex.withLock {
            val now = System.currentTimeMillis()
            cachedToken?.let { token ->
                if (now < tokenExpiresAtMillis) return token
            }

            val credentials = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

            return runCatching {
                val response =
                    client
                        .post("https://accounts.spotify.com/api/token") {
                            header("Authorization", "Basic $credentials")
                            contentType(ContentType.Application.FormUrlEncoded)
                            setBody("grant_type=client_credentials")
                        }.body<TokenResponse>()

                cachedToken = response.access_token
                // Refresh a minute early to be safe.
                tokenExpiresAtMillis = now + (response.expires_in - 60).coerceAtLeast(0) * 1000L
                response.access_token
            }.getOrNull()
        }
    }

    suspend fun searchArtist(name: String): SpotifyArtist? {
        val token = getAccessToken() ?: return null

        return runCatching {
            val results =
                client
                    .get("https://api.spotify.com/v1/search") {
                        header("Authorization", "Bearer $token")
                        parameter("q", name)
                        parameter("type", "artist")
                        parameter("limit", 5)
                    }.body<ArtistSearchResponse>()
                    .artists
                    ?.items
                    .orEmpty()

            results.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: results.firstOrNull()
        }.getOrNull()
    }

    suspend fun getArtistAlbums(
        artistId: String,
        limit: Int = 20,
    ): List<SpotifyAlbum> {
        val token = getAccessToken() ?: return emptyList()

        return runCatching {
            client
                .get("https://api.spotify.com/v1/artists/$artistId/albums") {
                    header("Authorization", "Bearer $token")
                    parameter("include_groups", "album,single")
                    parameter("limit", limit)
                }.body<ArtistAlbumsResponse>()
                .items
        }.getOrDefault(emptyList())
    }

    /**
     * Returns albums/singles by [artistName] released within the last [sinceDays] days.
     * Empty list on any failure - including an unconfigured or auth-rejected client -
     * so callers can safely fall back to another source without special-casing errors.
     */
    suspend fun getRecentReleases(
        artistName: String,
        sinceDays: Int = 45,
    ): List<SpotifyAlbum> {
        if (!isConfigured) return emptyList()

        val artist = searchArtist(artistName) ?: return emptyList()
        val albums = getArtistAlbums(artist.id)
        val cutoff = LocalDate.now().minusDays(sinceDays.toLong())

        return albums.filter { album ->
            val releaseDate = parseReleaseDate(album.release_date, album.release_date_precision) ?: return@filter false
            releaseDate.isAfter(cutoff)
        }
    }

    private fun parseReleaseDate(
        dateStr: String?,
        precision: String?,
    ): LocalDate? {
        if (dateStr == null) return null
        return runCatching {
            when (precision) {
                "day" -> LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                "month" -> YearMonth.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM")).atDay(1)
                "year" -> LocalDate.of(dateStr.toInt(), 1, 1)
                else -> LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            }
        }.getOrNull()
    }
}
