package com.wavlin.music.spotify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.SongItem
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
    ) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                toast(appContext, "Importing ${tracks.size} tracks...")
                val entity = PlaylistEntity(name = name)
                database.withTransaction { insert(entity) }
                val playlist = database.playlist(entity.id).first()
                if (playlist == null) {
                    toast(appContext, "Couldn't create the playlist")
                    return@launch
                }

                val semaphore = Semaphore(5)
                var imported = 0
                // Chunked so songs show up in the playlist as they match, in the original order.
                for (chunk in tracks.chunked(CHUNK_SIZE)) {
                    val ids =
                        coroutineScope {
                            chunk
                                .map { track -> async { semaphore.withPermit { matchTrack(database, track) } } }
                                .awaitAll()
                        }.filterNotNull()
                    if (ids.isNotEmpty()) {
                        val current = database.playlist(entity.id).first() ?: playlist
                        database.addSongsToPlaylist(current, ids.map { it to null })
                        imported += ids.size
                    }
                }
                toast(appContext, "Imported $imported of ${tracks.size} tracks into \"$name\"")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(appContext, "Import failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    // Best YouTube song for the track: first result within 5s of Spotify's length, else the top result.
    private suspend fun matchTrack(
        database: MusicDatabase,
        track: SpotifyTrack,
    ): String? =
        try {
            val items =
                YouTube
                    .search(track.query, YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    .orEmpty()
            val best =
                items.firstOrNull { song ->
                    val seconds = song.duration
                    track.durationSeconds > 0 && seconds != null && kotlin.math.abs(seconds - track.durationSeconds) <= 5
                } ?: items.firstOrNull()
            best?.let {
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
