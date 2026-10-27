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
 */
object SpotifyProvider {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        install(ContentNegotiation) { json(json) }
    }

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    private val tokenMutex = Mutex()

    private suspend fun getToken(): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAt - 60_000) return cachedToken!!
        Timber.d("SpotifyProvider: fetching new anon token")
        val raw = client.get("https://open.spotify.com/get_access_token") {
            parameter("reason", "transport")
            parameter("productType", "web_player")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0")
            header("Referer", "https://open.spotify.com/")
            header("spotify-app-version", "1.2.31.862.g6b3b6a7a")
            header("App-Platform", "WebPlayer")
        }.bodyAsText()
        val resp = json.decodeFromString<SpotifyTokenResponse>(raw)
        cachedToken = resp.accessToken
        tokenExpiresAt = resp.expiresAt
        Timber.d("SpotifyProvider: token OK, expires ${resp.expiresAt}")
        resp.accessToken
    }

    /** Search Spotify tracks by query. Anonymous — no credentials needed. */
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
