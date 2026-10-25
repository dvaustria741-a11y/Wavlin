/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Playback approach adapted from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.spotify.account

import com.wavlin.innertube.YouTube
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.models.MediaMetadata
import com.wavlin.music.models.toMediaMetadata
import com.wavlin.music.playback.PlayerConnection
import com.wavlin.music.playback.queues.ListQueue
import com.wavlin.music.spotify.SpotifyMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays Spotify tracks by finding the matching YouTube Music song for each one. The first playable
 * track starts straight away and the rest of the list is matched in the background and appended in order.
 */
object SpotifyPlayback {
    private const val CHUNK_SIZE = 8
    private const val PARALLEL_MATCHES = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolved = ConcurrentHashMap<String, MediaMetadata>()
    private var queueJob: Job? = null

    /**
     * Finds the song to play for [track]: the YouTube match, or when YouTube has none, whatever
     * [fallback] provides (a Spotify download saved on the device). Null when nothing is found.
     */
    suspend fun resolve(
        track: SpotifyWebTrack,
        fallback: (suspend (SpotifyWebTrack) -> MediaMetadata?)? = null,
    ): MediaMetadata? {
        val key = track.id.ifEmpty { "${track.name}|${track.artistNames}" }
        resolved[key]?.let { return it }

        val importTrack = track.toImportTrack()
        val metadata =
            withContext(Dispatchers.IO) {
                try {
                    SpotifyMatcher.match(importTrack)?.toMediaMetadata()
                        ?: fallbackMatch(track)
                        ?: fallback?.invoke(track)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        if (metadata != null) resolved[key] = metadata
        return metadata
    }

    private suspend fun fallbackMatch(track: SpotifyWebTrack): MediaMetadata? {
        val query = "${track.name} ${track.artistNames}".replace(Regex("[!,]"), "").trim()
        val videoId = YouTube.searchFirstVideoId(query).getOrNull() ?: return null
        return MediaMetadata(
            id = videoId,
            title = track.name,
            artists = track.artists.map { MediaMetadata.Artist(id = null, name = it.name) },
            duration = track.durationMs / 1000,
            thumbnailUrl = track.thumbnailUrl() ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        )
    }

    /**
     * Starts playing [tracks] from [startIndex]. Tracks that can't be matched are skipped.
     * Returns false when none of the tracks could be matched.
     */
    suspend fun play(
        playerConnection: PlayerConnection,
        title: String,
        tracks: List<SpotifyWebTrack>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        fallback: (suspend (SpotifyWebTrack) -> MediaMetadata?)? = null,
    ): Boolean {
        queueJob?.cancel()
        val ordered = if (shuffle) tracks.shuffled() else tracks.drop(startIndex.coerceAtLeast(0))

        var firstIndex = 0
        var first: MediaMetadata? = null
        while (firstIndex < ordered.size && first == null) {
            first = resolve(ordered[firstIndex], fallback)
            firstIndex++
        }
        val firstItem = first ?: return false

        // Every playQueue bumps the generation by one, which tells us if the user started something else meanwhile
        val generationBefore = playerConnection.service.queueGeneration.value
        playerConnection.playQueue(ListQueue(title = title, items = listOf(firstItem.toMediaItem())))

        val remaining = ordered.drop(firstIndex)
        if (remaining.isNotEmpty()) {
            queueJob =
                scope.launch {
                    for (chunk in remaining.chunked(CHUNK_SIZE)) {
                        val items = resolveChunk(chunk, fallback)
                        ensureActive()
                        if (!isStillOurQueue(playerConnection, generationBefore)) return@launch
                        if (items.isNotEmpty()) {
                            withContext(Dispatchers.Main) { playerConnection.addToQueue(items.map { it.toMediaItem() }) }
                        }
                    }
                }
        }
        return true
    }

    private suspend fun isStillOurQueue(
        playerConnection: PlayerConnection,
        generationBefore: Long,
    ): Boolean {
        // The service may still be starting up and apply our playQueue a moment later
        repeat(10) {
            val generation = playerConnection.service.queueGeneration.value
            if (generation == generationBefore + 1) return true
            if (generation != generationBefore) return false
            delay(200)
        }
        return false
    }

    private suspend fun resolveChunk(
        chunk: List<SpotifyWebTrack>,
        fallback: (suspend (SpotifyWebTrack) -> MediaMetadata?)?,
    ): List<MediaMetadata> {
        val semaphore = Semaphore(PARALLEL_MATCHES)
        return coroutineScope {
            chunk.map { track -> async { semaphore.withPermit { resolve(track, fallback) } } }.awaitAll().filterNotNull()
        }
    }
}
