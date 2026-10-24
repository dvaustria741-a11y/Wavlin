/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.local

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime

/**
 * Audio files picked from the device, stored as normal library songs. The song id is
 * "local:" + the file's content URI, so no database schema change is needed and playback
 * can resolve the file straight from the id.
 */
object LocalSongs {
    private const val PREFIX = "local:"

    fun isLocal(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    fun uriOf(id: String): Uri = Uri.parse(id.removePrefix(PREFIX))

    /** Adds [uri] to the library. Spotify-style title/artist/cover override what the file's tags say. */
    fun add(
        context: Context,
        database: MusicDatabase,
        uri: Uri,
        title: String? = null,
        artist: String? = null,
        coverUrl: String? = null,
    ): MediaMetadata? {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // not every provider grants a persistable permission; the current grant still works
        }

        var tagTitle: String? = null
        var tagArtist: String? = null
        var durationSec = -1
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            tagTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            tagArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            durationSec = ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // unreadable tags: fall back to the file name below
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }

        val finalTitle =
            title?.takeIf { it.isNotBlank() }
                ?: tagTitle?.takeIf { it.isNotBlank() }
                ?: displayName(context, uri)
                ?: "Local song"
        val finalArtist = artist?.takeIf { it.isNotBlank() } ?: tagArtist?.takeIf { it.isNotBlank() }

        val metadata =
            MediaMetadata(
                id = PREFIX + uri.toString(),
                title = finalTitle,
                artists = listOfNotNull(finalArtist?.let { MediaMetadata.Artist(id = null, name = it) }),
                duration = durationSec,
                thumbnailUrl = coverUrl,
            )
        val now = LocalDateTime.now()
        database.insert(metadata) { it.copy(isLocal = true, inLibrary = now, dateDownload = now) }
        return metadata
    }

    /**
     * Searches the database for a local file that matches the provided title and artist.
     * Used as a fallback when YouTube streams are unavailable or for spotdl-downloaded tracks.
     */
    fun findLocalMatch(database: com.wavlin.music.db.MusicDatabase, title: String, artist: String): String? =
        database.findLocalSongByMetadata(title, artist)?.id

    private fun displayName(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
            }
        } catch (e: Exception) {
            null
        }
}

/**
 * Sends local songs (key starts with "local:") to a plain file/content data source and everything
 * else to the YouTube stream resolver, so local audio never touches the network or the stream caches.
 */
@OptIn(UnstableApi::class)
class LocalRoutingDataSource(
    private val remote: DataSource,
    private val local: DataSource,
    /**
     * Optional fallback called when the remote (YouTube) stream fails.
     * Receives the mediaId; returns a local content URI to play instead, or null to re-throw.
     */
    private val localFallback: ((mediaId: String) -> android.net.Uri?)? = null,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        remote.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val key = dataSpec.key
        return if (LocalSongs.isLocal(key)) {
            active = local
            local.open(dataSpec.withUri(LocalSongs.uriOf(key!!)))
        } else {
            try {
                active = remote
                remote.open(dataSpec)
            } catch (e: Exception) {
                val fallbackUri = key?.let { localFallback?.invoke(it) }
                if (fallbackUri != null) {
                    active = local
                    local.open(dataSpec.withUri(fallbackUri))
                } else {
                    throw e
                }
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
