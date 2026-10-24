package com.wavlin.music.spotify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.playback.ExoDownloadService
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.db.entities.PlaylistEntity
import com.wavlin.music.models.toMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDateTime

/**
 * Turns a resolved Spotify playlist/album into a local playlist. Runs in an app-lifetime scope so
 * leaving the screen that started it does not cancel the import.
 */
object SpotifyImporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val CHUNK_SIZE = 10

    fun start(
        context: Context,
        database: MusicDatabase,
        name: String,
        tracks: List<SpotifyTrack>,
        preMatched: Map<Int, SongItem> = emptyMap(),
        download: Boolean = false,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                toast(appContext, "Importing ${tracks.size} tracks...")
                // bookmarkedAt is what makes a playlist show up in the Library (same as the Create playlist dialog)
                val entity = PlaylistEntity(name = name, bookmarkedAt = LocalDateTime.now(), isEditable = true)
                database.withTransaction { insert(entity) }
                val playlist = database.playlist(entity.id).first()
                if (playlist == null) {
                    toast(appContext, "Couldn't create the playlist")
                    return@launch
                }

                val semaphore = Semaphore(5)
                var imported = 0
                var chunkStart = 0
                val missing = mutableListOf<String>()
                // Chunked so songs show up in the playlist as they match, in the original order.
                for (chunk in tracks.chunked(CHUNK_SIZE)) {
                    val results =
                        coroutineScope {
                            chunk
                                .mapIndexed { offset, track ->
                                    val index = chunkStart + offset
                                    async {
                                        semaphore.withPermit {
                                            track to (preMatched[index]?.let { saveSong(database, it) } ?: matchTrack(database, track))
                                        }
                                    }
                                }.awaitAll()
                        }
                    chunkStart += chunk.size
                    val ids = results.mapNotNull { it.second }
                    missing += results.filter { it.second == null }.map { it.first.query }
                    if (ids.isNotEmpty()) {
                        val current = database.playlist(entity.id).first() ?: playlist
                        database.addSongsToPlaylist(current, ids.map { it to null })
                        imported += ids.size
                        if (download) ids.forEach { id -> queueDownload(appContext, database, id) }
                    }
                }
                val notFound =
                    if (missing.isEmpty()) {
                        ""
                    } else {
                        ". Not found: " + missing.take(3).joinToString("; ") + if (missing.size > 3) " (+${missing.size - 3} more)" else ""
                    }
                val downloading = if (download && imported > 0) " and downloading" else ""
                toast(appContext, "Imported$downloading $imported of ${tracks.size} tracks into \"$name\"$notFound")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(appContext, "Import failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Same request the song menu's Download action sends, so it lands in the Downloaded library. */
    private suspend fun queueDownload(context: Context, database: MusicDatabase, songId: String) {
        try {
            val title = database.song(songId).first()?.song?.title ?: songId
            val request =
                DownloadRequest
                    .Builder(songId, songId.toUri())
                    .setCustomCacheKey(songId)
                    .setData(title.toByteArray())
                    .build()
            DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // skip this song, keep importing the rest
        }
    }

    private fun saveSong(database: MusicDatabase, song: SongItem): String? =
        try {
            database.insert(song.toMediaMetadata())
            song.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun matchTrack(
        database: MusicDatabase,
        track: SpotifyTrack,
    ): String? =
        try {
            SpotifyMatcher.match(track)?.let {
                database.insert(it.toMediaMetadata())
                it.id
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
