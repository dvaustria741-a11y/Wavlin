/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.spotify.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Reads the signed-in user's library through the Spotify web player's GraphQL API
 * (api-partner.spotify.com), using the persisted-query hashes from [SpotifyHashProvider].
 */
object SpotifyClient {
    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val MAX_RETRIES = 3

    class SpotifyException(
        val statusCode: Int,
        override val message: String,
    ) : Exception(message)

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenRefresher: (suspend () -> Boolean)? = null

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
            defaultRequest {
                header("User-Agent", SpotifyAuth.USER_AGENT)
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                header("Accept", "application/json")
            }
            expectSuccess = false
        }
    }

    fun setTokenRefresher(refresher: (suspend () -> Boolean)?) {
        tokenRefresher = refresher
    }

    fun setToken(token: String) {
        accessToken = token
    }

    fun clearToken() {
        accessToken = null
    }

    // ── JSON navigation helpers ──────────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonObject }.getOrNull()

    private fun JsonObject.arr(key: String): JsonArray? = runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonArray }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull }.getOrNull()

    // ── GraphQL core ─────────────────────────────────────────────────────────

    private suspend fun graphqlPost(
        operationName: String,
        variables: JsonObject = buildJsonObject {},
    ): JsonObject {
        val body =
            buildJsonObject {
                put("variables", variables)
                put("operationName", operationName)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", SpotifyHashProvider.getHash(operationName))
                    }
                }
            }
        var refreshedTokenOnce = false

        for (attempt in 0 until MAX_RETRIES) {
            val token = accessToken
            if (token.isNullOrBlank()) {
                if (!refreshedTokenOnce && tokenRefresher?.invoke() == true) {
                    refreshedTokenOnce = true
                    continue
                }
                throw SpotifyException(401, "Not authenticated")
            }

            val response =
                client.post(GQL_URL) {
                    header("Authorization", "Bearer $token")
                    setBody(TextContent(body.toString(), ContentType.Application.Json.withParameter("charset", "UTF-8")))
                }

            when {
                response.status == HttpStatusCode.Unauthorized -> {
                    if (!refreshedTokenOnce && tokenRefresher?.invoke() == true) {
                        refreshedTokenOnce = true
                        continue
                    }
                    throw SpotifyException(401, "Token expired or invalid")
                }

                response.status == HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: (2L * (attempt + 1))
                    if (attempt < MAX_RETRIES - 1) {
                        delay(retryAfter * 1000)
                        continue
                    }
                    throw SpotifyException(429, "Rate limited")
                }

                response.status == HttpStatusCode.PreconditionFailed ->
                    throw SpotifyException(412, "Spotify changed its web player. Wavlin needs an update to read $operationName")

                response.status.value !in 200..299 ->
                    throw SpotifyException(response.status.value, "Spotify error ${response.status.value}")
            }

            val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val errors = responseJson.arr("errors")
            if (errors != null && errors.isNotEmpty()) {
                val message = errors[0].jsonObject.str("message") ?: "Unknown GraphQL error"
                if (message.contains("PersistedQueryNotFound", ignoreCase = true)) {
                    throw SpotifyException(412, "Spotify changed its web player. Wavlin needs an update to read $operationName")
                }
                throw SpotifyException(400, "GraphQL: $message")
            }
            return responseJson
        }
        throw SpotifyException(429, "Rate limited after $MAX_RETRIES retries")
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    private fun parseImages(sources: JsonArray?): List<SpotifyImage> =
        sources.orEmpty().mapNotNull { source ->
            val obj = source.jsonObject
            obj.str("url")?.let { SpotifyImage(url = it, height = obj.int("height"), width = obj.int("width")) }
        }

    private fun parseArtist(artist: JsonObject): SpotifySimpleArtist? {
        val uri = artist.str("uri") ?: return null
        return SpotifySimpleArtist(
            id = uri.substringAfterLast(":"),
            name = artist.obj("profile")?.str("name").orEmpty(),
            uri = uri,
        )
    }

    private fun parseDurationMs(track: JsonObject): Int {
        val duration = track.obj("duration") ?: track.obj("trackDuration")
        val milliseconds =
            listOfNotNull(
                duration?.long("totalMilliseconds"),
                duration?.long("milliseconds"),
                track.long("durationMs"),
                track.long("duration_ms"),
            ).firstOrNull { it > 0 }
        return milliseconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }

    private fun parseTrack(
        track: JsonObject,
        uriOverride: String?,
    ): SpotifyWebTrack {
        val uri = uriOverride ?: track.str("uri") ?: track.str("_uri").orEmpty()
        val albumData = track.obj("albumOfTrack")
        val albumUri = albumData?.str("uri").orEmpty()
        return SpotifyWebTrack(
            id = uri.substringAfterLast(":"),
            name = track.str("name").orEmpty(),
            artists = track.obj("artists")?.arr("items").orEmpty().mapNotNull { parseArtist(it.jsonObject) },
            album =
                SpotifySimpleAlbum(
                    id = albumUri.substringAfterLast(":"),
                    name = albumData?.str("name").orEmpty(),
                    images = parseImages(albumData?.obj("coverArt")?.arr("sources")),
                    uri = albumUri.ifEmpty { null },
                ),
            durationMs = parseDurationMs(track),
            explicit = track.obj("contentRating")?.str("label") == "EXPLICIT",
            uri = uri.ifEmpty { null },
        )
    }

    private fun parsePlaylistImages(images: JsonObject?): List<SpotifyImage> =
        images?.arr("items").orEmpty().flatMap { parseImages(it.jsonObject.arr("sources")) }

    private fun parseTrackCount(data: JsonObject): Int? =
        data.obj("content")?.int("totalCount")
            ?: data.obj("contents")?.int("totalCount")
            ?: data.obj("tracks")?.int("totalCount")
            ?: data.obj("tracksV2")?.int("totalCount")
            ?: data.int("totalCount")
            ?: data.int("trackCount")
            ?: data.int("numTracks")

    private fun parsePlaylistWrapper(wrapper: JsonObject): SpotifyPlaylist? {
        val data = wrapper.obj("data") ?: return null
        if (data.str("__typename") != "Playlist") return null
        val uri = wrapper.str("_uri") ?: return null
        val owner = data.obj("ownerV2")?.obj("data")
        return SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = data.str("name").orEmpty(),
            description = data.str("description"),
            images = parsePlaylistImages(data.obj("images")),
            owner =
                SpotifyPlaylistOwner(
                    id = owner?.str("uri")?.substringAfterLast(":") ?: owner?.str("id").orEmpty(),
                    displayName = owner?.str("name"),
                    uri = owner?.str("uri"),
                ),
            totalTracks = parseTrackCount(data),
            uri = uri,
        )
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** The current user's profile. */
    suspend fun me(): Result<SpotifyUser> =
        runCatching {
            val profile =
                graphqlPost("profileAttributes").obj("data")?.obj("me")?.obj("profile")
                    ?: throw SpotifyException(500, "Invalid profileAttributes response")
            SpotifyUser(
                id = profile.str("uri").orEmpty().substringAfterLast(":"),
                displayName = profile.str("name"),
                images = parseImages(profile.obj("avatar")?.arr("sources")),
            )
        }

    /** One page of the user's playlists, flattened out of folders. */
    suspend fun myPlaylists(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylist>> =
        runCatching {
            val variables =
                buildJsonObject {
                    putJsonArray("filters") { add("Playlists") }
                    put("order", null as String?)
                    put("textFilter", "")
                    putJsonArray("features") {
                        add("LIKED_SONGS")
                        add("YOUR_EPISODES_V2")
                        add("PRERELEASES")
                        add("EVENTS")
                    }
                    put("limit", limit)
                    put("offset", offset)
                    put("flatten", true)
                    putJsonArray("expandedFolders") {}
                    put("folderUri", null as String?)
                    put("includeFoldersWhenFlattening", false)
                }
            val library =
                graphqlPost("libraryV3", variables).obj("data")?.obj("me")?.obj("libraryV3")
                    ?: throw SpotifyException(500, "Invalid libraryV3 response")
            val paging = library.obj("pagingInfo")
            SpotifyPaging(
                items =
                    library.arr("items").orEmpty().mapNotNull { item ->
                        val wrapper = item.jsonObject.obj("item") ?: return@mapNotNull null
                        if (wrapper.str("__typename") != "PlaylistResponseWrapper") return@mapNotNull null
                        parsePlaylistWrapper(wrapper)
                    },
                total = library.int("totalCount") ?: 0,
                limit = paging?.int("limit") ?: limit,
                offset = paging?.int("offset") ?: offset,
            )
        }

    /** One page of tracks of a playlist. */
    suspend fun playlistTracks(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyWebTrack>> =
        runCatching {
            val variables =
                buildJsonObject {
                    put("uri", "spotify:playlist:$playlistId")
                    put("offset", offset)
                    put("limit", limit)
                    put("enableWatchFeedEntrypoint", false)
                }
            val content =
                graphqlPost("fetchPlaylist", variables).obj("data")?.obj("playlistV2")?.obj("content")
                    ?: throw SpotifyException(500, "No content in fetchPlaylist response")
            SpotifyPaging(
                items =
                    content.arr("items").orEmpty().mapNotNull { item ->
                        val wrapper = item.jsonObject.obj("itemV2") ?: return@mapNotNull null
                        val data = wrapper.obj("data") ?: return@mapNotNull null
                        parseTrack(data, wrapper.str("_uri") ?: wrapper.str("uri"))
                    },
                total = content.int("totalCount") ?: 0,
                limit = limit,
                offset = offset,
            )
        }

    /** One page of the user's Liked Songs. */
    suspend fun likedSongs(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyWebTrack>> =
        runCatching {
            val variables =
                buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                }
            val tracks =
                graphqlPost("fetchLibraryTracks", variables).obj("data")?.obj("me")?.obj("library")?.obj("tracks")
                    ?: throw SpotifyException(500, "Invalid fetchLibraryTracks response")
            SpotifyPaging(
                items =
                    tracks.arr("items").orEmpty().mapNotNull { item ->
                        val wrapper = item.jsonObject.obj("track") ?: return@mapNotNull null
                        val data = wrapper.obj("data") ?: return@mapNotNull null
                        parseTrack(data, wrapper.str("_uri") ?: wrapper.str("uri"))
                    },
                total = tracks.int("totalCount") ?: 0,
                limit = limit,
                offset = offset,
            )
        }
}
