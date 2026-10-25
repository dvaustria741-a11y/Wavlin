/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.utils

import android.content.Context
import android.net.Uri
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.ui.utils.resize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Keeps the cover art of downloaded songs in app storage, so it still shows with no internet.
 * Coil's own cache lives in the cache directory and can be cleared or evicted by Android; these
 * files are kept until the app is cleared. [OfflineArtworkInterceptor] serves them wherever a cover is shown.
 */
object ArtworkStore {
    private const val DIR_NAME = "artwork"
    private const val SAVE_SIZE = 544
    private const val MAX_BYTES = 6 * 1024 * 1024
    private const val TIMEOUT_MS = 15_000

    // Google image hosts serve the same picture at any size through a "=w544-h544" style suffix
    private val RESIZABLE_HOST = Regex("^(https://(?:lh3|yt3)\\.googleusercontent\\.com/[^=]*)=.*$")
    private val RESIZABLE_GGPHT = Regex("^(https://yt3\\.ggpht\\.com/[^=]*)=.*$")

    /** The same key for every size variant of one picture. */
    private fun keyOf(url: String): String =
        RESIZABLE_HOST.matchEntire(url)?.groupValues?.get(1)
            ?: RESIZABLE_GGPHT.matchEntire(url)?.groupValues?.get(1)
            ?: url

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun fileFor(
        context: Context,
        url: String,
    ): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(keyOf(url).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), "$hash.img")
    }

    /** The saved copy of [url], if there is one. */
    fun existing(
        context: Context,
        url: String,
    ): File? = fileFor(context, url).takeIf { it.isFile && it.length() > 0 }

    /** Downloads [url] into the store unless it is already there. Returns whether a copy exists afterwards. */
    suspend fun save(
        context: Context,
        url: String?,
    ): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http")) return false
        return withContext(Dispatchers.IO) {
            val target = fileFor(context, url)
            if (target.isFile && target.length() > 0) return@withContext true
            val temp = File(target.parentFile, "${target.name}.tmp")
            try {
                target.parentFile?.mkdirs()
                val connection = URL(url.resize(SAVE_SIZE, SAVE_SIZE)).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    if (connection.responseCode !in 200..299) return@withContext false
                    var total = 0
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_BYTES) return@withContext false
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                temp.renameTo(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't save artwork")
                false
            } finally {
                temp.delete()
            }
        }
    }

    /** Saves the cover of the song with [songId]. */
    suspend fun saveForSong(
        context: Context,
        database: MusicDatabase,
        songId: String,
    ) {
        val url = withContext(Dispatchers.IO) { database.getSongByIdBlocking(songId)?.song?.thumbnailUrl }
        save(context, url)
    }

    /** Saves the covers of songs downloaded before this store existed. */
    suspend fun backfillDownloaded(
        context: Context,
        database: MusicDatabase,
    ) {
        val songs = database.downloadedSongsByCreateDateAsc().first()
        songs.forEach { save(context, it.song.thumbnailUrl) }
    }
}

/** Shows the saved copy of a cover instead of loading it from the network or Coil's cache. */
class OfflineArtworkInterceptor(
    private val context: Context,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (data is String && data.startsWith("http")) {
            val saved = withContext(Dispatchers.IO) { ArtworkStore.existing(context, data) }
            if (saved != null) {
                val request: ImageRequest = chain.request.newBuilder().data(Uri.fromFile(saved)).build()
                return chain.withRequest(request).proceed()
            }
        }
        return chain.proceed()
    }
}
