/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.spotify.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs in to Spotify with the `sp_dc` session cookie of the web player, so no Spotify developer
 * Client ID is needed. The cookie is turned into short-lived access tokens through the web player's
 * own token endpoint.
 */
object SpotifyAuth {
    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val NUANCE_URL =
        "https://gist.githubusercontent.com/sonic-liberation/22ed9c6ba463899e933427f7de1f0eef/raw/nuances.json"
    private const val NUANCE_CACHE_TTL_NANOS = 5 * 60 * 1_000_000_000L
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val TIMEOUT_MS = 15_000

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    @Serializable
    private data class Nuance(
        val s: String,
        val v: Int,
    )

    private class CachedNuance(
        val value: Nuance,
        val fetchedAtNanos: Long,
    )

    @Serializable
    private data class ServerTimeResponse(
        val serverTime: Long,
    )

    private val nuanceMutex = Mutex()
    private var cachedNuance: CachedNuance? = null

    // Used when the remote list of secrets cannot be reached
    private val fallbackNuance =
        Nuance(
            s = "GM3TMMJTGYZTQNZVGM4DINJZHA4TGOBYGMZTCMRTGEYDSMJRHE4TEOBUG4YTCMRUGQ4DQOJUGQYTAMRRGA2TCMJSHE3TCMBY",
            v = 61,
        )

    class SpotifyAuthException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    /**
     * Extracts `sp_dc` and `sp_key` from pasted input: a bare sp_dc value, a `Cookie:` header or
     * a `name=value; name=value` list.
     */
    fun parseCookieInput(
        input: String,
        manualKey: String = "",
    ): Pair<String, String> {
        val cleaned =
            input
                .trim()
                .removePrefix("Cookie:")
                .removePrefix("cookie:")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
        var spKey = manualKey.trim().removeSurrounding("\"").removeSurrounding("'")

        fun cookieValue(name: String): String =
            cleaned
                .split(';', '\n', '\r')
                .map { it.trim() }
                .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
                ?.substringAfter("=")
                ?.trim()
                ?.removeSurrounding("\"")
                ?.removeSurrounding("'")
                .orEmpty()

        val spDc =
            when {
                cleaned.contains("sp_dc=", ignoreCase = true) -> cookieValue("sp_dc")
                // A bare value without any "name=" part
                !cleaned.contains("=") -> cleaned
                else -> cookieValue("sp_dc").ifBlank { cleaned }
            }
        if (spKey.isBlank()) spKey = cookieValue("sp_key")
        return spDc to spKey
    }

    /** Fetches a web-player access token for the signed-in user. */
    suspend fun fetchAccessToken(
        spDc: String,
        spKey: String = "",
    ): Result<SpotifyInternalToken> =
        runCatching {
            val cookieHeader =
                buildString {
                    append("sp_dc=$spDc")
                    if (spKey.isNotEmpty()) append("; sp_key=$spKey")
                }
            val token = requestToken(mapOf("Cookie" to cookieHeader))
            if (token.isAnonymous || token.accessToken.isBlank()) {
                throw SpotifyAuthException("Received anonymous token — sp_dc cookie is invalid or expired")
            }
            token
        }

    private suspend fun requestToken(headers: Map<String, String>): SpotifyInternalToken {
        val nuance = fetchNuance()
        val totp = generateTotp(nuance.s, fetchServerTime())
        val url = "$TOKEN_URL?reason=transport&productType=web-player&totp=$totp&totpServer=$totp&totpVer=${nuance.v}"
        val body = withContext(Dispatchers.IO) { httpGet(url, headers) }
        return json.decodeFromString<SpotifyInternalToken>(body)
    }

    private suspend fun fetchNuance(): Nuance =
        withContext(Dispatchers.IO) {
            nuanceMutex.withLock {
                val nowNanos = System.nanoTime()
                val cached = cachedNuance
                if (cached != null && nowNanos - cached.fetchedAtNanos < NUANCE_CACHE_TTL_NANOS) {
                    return@withLock cached.value
                }
                try {
                    val latest =
                        json
                            .decodeFromString<List<Nuance>>(httpGet(NUANCE_URL, emptyMap()))
                            .filter { it.v > 0 && it.s.isValidBase32Secret() }
                            .maxByOrNull(Nuance::v)
                            ?: fallbackNuance
                    cachedNuance = CachedNuance(latest, nowNanos)
                    latest
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Timber.w(error, "Failed to fetch remote Spotify secrets, using fallback")
                    cached?.value ?: fallbackNuance
                }
            }
        }

    private fun String.isValidBase32Secret(): Boolean = isNotBlank() && all { it == '=' || it.uppercaseChar() in BASE32_ALPHABET }

    private suspend fun fetchServerTime(): Long =
        withContext(Dispatchers.IO) {
            try {
                json.decodeFromString<ServerTimeResponse>(httpGet(SERVER_TIME_URL, emptyMap())).serverTime
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Timber.w(error, "Failed to fetch Spotify server time, using local time")
                System.currentTimeMillis() / 1000L
            }
        }

    /** 6-digit TOTP with HMAC-SHA1 (RFC 6238). */
    private fun generateTotp(
        secret: String,
        serverTimeSec: Long,
    ): String {
        var timeStep = serverTimeSec / 30L
        val timeBytes = ByteArray(8)
        for (i in 7 downTo 0) {
            timeBytes[i] = (timeStep and 0xFF).toByte()
            timeStep = timeStep shr 8
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(base32Decode(secret), "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code =
            ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val output = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in input.uppercase().replace("=", "")) {
            val value = BASE32_ALPHABET.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }

    private fun httpGet(
        urlString: String,
        extraHeaders: Map<String, String>,
    ): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Language", "en")
            extraHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw SpotifyAuthException("HTTP $responseCode: ${errorBody.take(200)}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
