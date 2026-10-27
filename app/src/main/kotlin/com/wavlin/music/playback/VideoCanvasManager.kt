/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.playback

import android.content.Context
import com.wavlin.music.spotify.SpotifyCanvasClient
import com.wavlin.music.spotify.account.SpotifyAuthState
import com.wavlin.music.spotify.account.SpotifySession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the short looping Spotify Canvas video of the now-playing song, like SimpMusic does.
 *
 * The track is matched on Spotify by title, artist and duration, its canvas mp4 is fetched once and
 * kept in the app cache, and the player loops that local file. Needs a signed-in Spotify account.
 * Canvases are never part of song downloads.
 */
@Singleton
class VideoCanvasManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val spotifySession: SpotifySession,
    ) {
        private val noCanvas = "\u0001"

        private val memory: MutableMap<String, String> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, String>(64, 0.75f, true) {
                    override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > 200
                },
            )

        private val dir by lazy { File(context.cacheDir, "video_canvas").also { it.mkdirs() } }

        /**
         * A local file uri of the looping canvas for [mediaId], or null when the song has none, the
         * Spotify account is not connected, or the lookup failed (failures are retried next time).
         */
        suspend fun getCanvasUrl(
            mediaId: String,
            title: String,
            artist: String,
            durationSec: Int,
        ): String? {
            memory[mediaId]?.let { hit ->
                if (hit == noCanvas) return null
                if (File(hit.removePrefix("file://")).exists()) return hit
                memory.remove(mediaId)
            }
            return withContext(Dispatchers.IO) {
                val key = fileKey(mediaId)
                val cached = File(dir, "$key.mp4")
                val missMarker = File(dir, "$key.none")
                if (cached.exists() && cached.length() > 0) {
                    return@withContext "file://${cached.absolutePath}".also { memory[mediaId] = it }
                }
                if (missMarker.exists() && System.currentTimeMillis() - missMarker.lastModified() < MISS_TTL_MS) {
                    memory[mediaId] = noCanvas
                    return@withContext null
                }
                if (spotifySession.authState.value != SpotifyAuthState.CONNECTED) {
                    Timber.d("VideoCanvas: Spotify not connected, skipping")
                    return@withContext null
                }
                try {
                    val token = spotifySession.validAccessToken() ?: return@withContext null
                    val remote =
                        SpotifyCanvasClient.fetchCanvasUrl(
                            accessToken = token,
                            title = title,
                            artist = artist,
                            durationMs = durationSec * 1000L,
                        )
                    if (remote == null) {
                        Timber.d("VideoCanvas: no canvas for '%s'", title)
                        missMarker.writeText("")
                        memory[mediaId] = noCanvas
                        return@withContext null
                    }
                    download(remote, cached)
                    trimCache()
                    "file://${cached.absolutePath}".also { memory[mediaId] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d("VideoCanvas: lookup failed for '%s': %s", title, e.message)
                    null
                }
            }
        }

        /** Clears cached results (e.g. on account sign-out). */
        fun clearCache() {
            memory.clear()
            dir.listFiles()?.forEach { it.delete() }
        }

        private fun fileKey(mediaId: String): String =
            MessageDigest
                .getInstance("SHA-1")
                .digest(mediaId.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun download(
            url: String,
            target: File,
        ) {
            val tmp = File(target.parentFile, target.name + ".part")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                if (connection.responseCode !in 200..299) {
                    throw SpotifyCanvasClient.CanvasLookupException("canvas download HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (tmp.length() == 0L || !tmp.renameTo(target)) {
                tmp.delete()
                throw SpotifyCanvasClient.CanvasLookupException("canvas download empty")
            }
        }

        private fun trimCache() {
            val files = dir.listFiles { f -> f.extension == "mp4" }?.sortedBy { it.lastModified() } ?: return
            if (files.size > MAX_CACHED) files.take(files.size - MAX_CACHED).forEach { it.delete() }
        }

        private companion object {
            const val MAX_CACHED = 60
            const val MISS_TTL_MS = 6 * 60 * 60 * 1000L
        }
    }
