/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.spotify

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.local.LocalSongs
import com.wavlin.music.models.MediaMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Gets a Spotify track as an audio file on the device when YouTube has no match for it, and adds it
 * to the library as a local song with Spotify's title, artist and cover. Because the result is a
 * local file, it also plays with no internet.
 */
object SpotifyDirectDownloader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val TRACK_ID_REGEX = Regex("spotify\\.com/track/([A-Za-z0-9]+)")
    private val UNSAFE_FILE_CHARS = Regex("[/\\\\:*?\"<>|]")
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the new local song, or null when the track can't be fetched. */
    suspend fun downloadAsLocalSong(
        context: Context,
        database: MusicDatabase,
        track: SpotifyTrack,
    ): MediaMetadata? =
        withContext(Dispatchers.IO) {
            val trackId = track.url?.let { TRACK_ID_REGEX.find(it)?.groupValues?.getOrNull(1) } ?: return@withContext null
            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val audioFile = File(musicDir, "${"${track.artist} - ${track.title}".replace(UNSAFE_FILE_CHARS, "_")}.opus")

            try {
                // A file from an earlier attempt is reused instead of downloading it again
                if (!audioFile.exists() || audioFile.length() == 0L) {
                    val bytes = fetchAudio(trackId) ?: return@withContext null
                    audioFile.writeBytes(bytes)
                }
                LocalSongs.add(context, database, Uri.fromFile(audioFile), track.title, track.artist, track.coverUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audioFile.delete()
                null
            }
        }

    private suspend fun fetchAudio(trackId: String): ByteArray? {
        val client =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                }
                // Don't throw on 4xx/5xx so a failure just means "not available"
                expectSuccess = false
            }
        try {
            // Load the site first so its API accepts the next request
            runCatching {
                client.get("https://spotifydown.com/") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                }
            }
            val audioUrl =
                runCatching {
                    val response =
                        client
                            .get("https://spotifydown.com/api/download?trackId=$trackId") {
                                header("Origin", "https://spotifydown.com")
                                header("Referer", "https://spotifydown.com/")
                                header("User-Agent", USER_AGENT)
                                header("Accept", "application/json, text/plain, */*")
                                header("Accept-Language", "en-US,en;q=0.9")
                            }.bodyAsText()
                    val root = json.parseToJsonElement(response).jsonObject
                    val success = root["success"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    if (success == true) root["link"]?.jsonPrimitive?.contentOrNull else null
                }.getOrNull() ?: return null

            return client
                .get(audioUrl) { header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)") }
                .readBytes()
                .takeIf { it.isNotEmpty() }
        } finally {
            client.close()
        }
    }
}
