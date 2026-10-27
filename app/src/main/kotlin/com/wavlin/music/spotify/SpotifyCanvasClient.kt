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
import kotlin.math.abs

/**
 * Looks up the short looping Spotify Canvas video of a track.
 *
 * Flow: client token -> track search (title + artist, duration-matched) -> canvaz-cache request
 * (protobuf) -> mp4 url. Needs the signed-in web-player access token from the Spotify account.
 */
object SpotifyCanvasClient {
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val PATHFINDER_SEARCH_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val REST_SEARCH_URL = "https://api.spotify.com/v1/search"
    private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
    private const val SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
    private const val CANVAS_USER_AGENT = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private const val TIMEOUT_MS = 15_000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val clientTokenMutex = Mutex()

    @Volatile
    private var clientToken: String = ""

    @Volatile
    private var clientTokenExpiresAtMs: Long = 0L

    /** Thrown for failures that are worth retrying later (network, auth) instead of caching a miss. */
    class CanvasLookupException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    /**
     * Returns the canvas mp4 url, or null when the track has no canvas.
     * Throws [CanvasLookupException] when the lookup itself failed.
     */
    suspend fun fetchCanvasUrl(
        accessToken: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? =
        withContext(Dispatchers.IO) {
            val client = ensureClientToken(accessToken)
            val trackId = findTrackId(accessToken, client, title, artist, durationMs) ?: return@withContext null
            Timber.d("VideoCanvas: spotify track %s for '%s'", trackId, title)
            requestCanvas(accessToken, client, trackId)
        }

    // ── Client token ─────────────────────────────────────────────────────────

    private suspend fun ensureClientToken(accessToken: String): String =
        clientTokenMutex.withLock {
            if (clientToken.isNotEmpty() && clientTokenExpiresAtMs > System.currentTimeMillis() + 60_000L) {
                return@withLock clientToken
            }
            val body =
                """{"client_data":{"client_version":"1.2.62.476.g2ad6e7f3","client_id":"d8a5ed958d274c2e8ee717e6a4b0971d","js_sdk_data":{"device_brand":"Apple","device_model":"unknown","os":"macos","os_version":"10.15.7","device_id":"4fd0c748-b282-4927-9658-6d51a24e58b7","device_type":"computer"}}}"""
            val response =
                http(
                    url = CLIENT_TOKEN_URL,
                    method = "POST",
                    headers =
                        mapOf(
                            "Accept" to "application/json",
                            "Content-Type" to "application/json",
                            "User-Agent" to
                                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
                        ),
                    body = body.toByteArray(),
                )
            if (response.code !in 200..299) {
                throw CanvasLookupException("client token HTTP ${response.code}")
            }
            val granted =
                runCatching { json.parseToJsonElement(response.text()).jsonObject["granted_token"]?.jsonObject }
                    .getOrNull() ?: throw CanvasLookupException("client token missing")
            val token = granted["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (token.isEmpty()) throw CanvasLookupException("client token empty")
            val ttlSec = granted["expires_after_seconds"]?.jsonPrimitive?.longOrNull ?: 3600L
            clientToken = token
            clientTokenExpiresAtMs = System.currentTimeMillis() + ttlSec * 1000L
            token
        }

    // ── Track search ─────────────────────────────────────────────────────────

    private class Candidate(
        val id: String,
        val name: String,
        val durationMs: Long,
    )

    private fun searchQueries(
        title: String,
        artist: String,
    ): List<String> {
        fun clean(s: String) =
            s
                .replace(Regex("[()\\[\\]]"), " ")
                .replace("&", " ")
                .replace(".", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        val baseTitle = title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()
        return listOf(clean("$title $artist"), clean("$baseTitle $artist"))
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun pickBest(
        candidates: List<Candidate>,
        title: String,
        durationMs: Long,
    ): Candidate? {
        if (candidates.isEmpty()) return null
        val wantedTitle = normalize(title)
        if (durationMs > 0) {
            candidates
                .filter { abs(it.durationMs - durationMs) <= 2_500L }
                .let { close ->
                    close.firstOrNull { normalize(it.name) == wantedTitle } ?: close.firstOrNull()
                }?.let { return it }
        }
        return candidates.firstOrNull { normalize(it.name) == wantedTitle }
    }

    private fun findTrackId(
        accessToken: String,
        clientToken: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        var anySearchWorked = false
        for (query in searchQueries(title, artist)) {
            val candidates =
                runCatching { searchPathfinder(accessToken, clientToken, query) }
                    .onFailure { Timber.d("VideoCanvas: pathfinder search failed: %s", it.message) }
                    .getOrNull()
                    ?: runCatching { searchRest(accessToken, query) }
                        .onFailure { Timber.d("VideoCanvas: rest search failed: %s", it.message) }
                        .getOrNull()
            if (candidates == null) continue
            anySearchWorked = true
            pickBest(candidates, title, durationMs)?.let { return it.id }
        }
        if (!anySearchWorked) throw CanvasLookupException("Spotify search unavailable")
        return null
    }

    private fun searchPathfinder(
        accessToken: String,
        clientToken: String,
        query: String,
    ): List<Candidate> {
        val variables =
            """{"searchTerm":${JsonPrimitive(query)},"offset":0,"limit":6,"numberOfTopResults":6,"includeAudiobooks":true,"includePreReleases":false}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$SEARCH_HASH"}}"""
        val url =
            "$PATHFINDER_SEARCH_URL?operationName=searchTracks" +
                "&variables=${URLEncoder.encode(variables, "UTF-8")}" +
                "&extensions=${URLEncoder.encode(extensions, "UTF-8")}"
        val response =
            http(
                url = url,
                method = "GET",
                headers =
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "Client-Token" to clientToken,
                        "Accept" to "application/json",
                        "app-platform" to "WebPlayer",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    ),
            )
        if (response.code !in 200..299) error("pathfinder HTTP ${response.code}")
        val items =
            json
                .parseToJsonElement(response.text())
                .jsonObject["data"]
                ?.jsonObject
                ?.get("searchV2")
                ?.jsonObject
                ?.get("tracksV2")
                ?.jsonObject
                ?.get("items")
                ?.jsonArray ?: error("pathfinder shape changed")
        return items.mapNotNull { entry ->
            val data = entry.jsonObject["item"]?.jsonObject?.get("data")?.jsonObject ?: return@mapNotNull null
            val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Candidate(
                id = id,
                name = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationMs =
                    data["duration"]
                        ?.jsonObject
                        ?.get("totalMilliseconds")
                        ?.jsonPrimitive
                        ?.longOrNull ?: 0L,
            )
        }
    }

    private fun searchRest(
        accessToken: String,
        query: String,
    ): List<Candidate> {
        val url = "$REST_SEARCH_URL?type=track&limit=6&q=${URLEncoder.encode(query, "UTF-8")}"
        val response =
            http(
                url = url,
                method = "GET",
                headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json"),
            )
        if (response.code !in 200..299) error("rest search HTTP ${response.code}")
        val items =
            json
                .parseToJsonElement(response.text())
                .jsonObject["tracks"]
                ?.jsonObject
                ?.get("items")
                ?.jsonArray ?: error("rest search shape changed")
        return items.mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Candidate(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    // ── Canvas request (protobuf) ────────────────────────────────────────────

    private fun requestCanvas(
        accessToken: String,
        clientToken: String,
        trackId: String,
    ): String? {
        val response =
            http(
                url = CANVAS_URL,
                method = "POST",
                headers =
                    mapOf(
                        "Accept" to "application/protobuf",
                        "Content-Type" to "application/protobuf",
                        "Authorization" to "Bearer $accessToken",
                        "Client-Token" to clientToken,
                        "User-Agent" to CANVAS_USER_AGENT,
                    ),
                body = encodeCanvasRequest("spotify:track:$trackId"),
            )
        if (response.code == 404) return null
        if (response.code !in 200..299) throw CanvasLookupException("canvas HTTP ${response.code}")
        val urls = decodeCanvasUrls(response.bytes)
        Timber.d("VideoCanvas: %d canvas url(s) for %s", urls.size, trackId)
        return urls.firstOrNull { it.contains(".mp4") } ?: urls.firstOrNull()
    }

    /** CanvasRequest { repeated Track tracks = 1 { string track_uri = 1 } } */
    private fun encodeCanvasRequest(trackUri: String): ByteArray {
        val uri = trackUri.toByteArray(Charsets.UTF_8)
        val inner = ByteArrayOutputStream().apply {
            write(0x0A)
            writeVarint(uri.size)
            write(uri)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x0A)
            writeVarint(inner.size)
            write(inner)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var v = value
        while (v >= 0x80) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    /** CanvasResponse { repeated Canvas canvases = 1 { string canvas_url = 2 } } */
    private fun decodeCanvasUrls(bytes: ByteArray): List<String> {
        val urls = mutableListOf<String>()
        forEachField(bytes, 0, bytes.size) { field, wire, start, end ->
            if (field == 1 && wire == 2) {
                forEachField(bytes, start, end) { innerField, innerWire, s, e ->
                    if (innerField == 2 && innerWire == 2) {
                        urls += String(bytes, s, e - s, Charsets.UTF_8)
                    }
                }
            }
        }
        return urls
    }

    private fun forEachField(
        bytes: ByteArray,
        from: Int,
        to: Int,
        onField: (field: Int, wire: Int, start: Int, end: Int) -> Unit,
    ) {
        var pos = from
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < to) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        while (pos < to) {
            val tag = readVarint().toInt()
            val field = tag ushr 3
            when (val wire = tag and 7) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > to) return
                    onField(field, wire, pos, pos + len)
                    pos += len
                }
                5 -> pos += 4
                else -> return
            }
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private class HttpResult(
        val code: Int,
        val bytes: ByteArray,
    ) {
        fun text(): String = String(bytes, Charsets.UTF_8)
    }

    private fun http(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
    ): HttpResult {
        val connection =
            try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw CanvasLookupException("bad url", e)
            }
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
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
            return HttpResult(code, data)
        } catch (e: CanvasLookupException) {
            throw e
        } catch (e: Exception) {
            throw CanvasLookupException(e.message ?: "network error", e)
        } finally {
            connection.disconnect()
        }
    }
}
