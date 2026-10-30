/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.spotify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which songs were downloaded from a Spotify playlist. Those downloads belong to their
 * Spotify playlist (where they get a downloaded check), so they are kept out of the Downloaded library.
 */
object SpotifyDownloadStore {
    private const val PREFS_NAME = "spotify_playlist_downloads"
    private const val KEY_PREFIX = "track_"

    private val _trackToSong = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Spotify track id -> the song id its download was saved under. */
    val trackToSong: StateFlow<Map<String, String>> = _trackToSong.asStateFlow()

    private val _hiddenSongIds = MutableStateFlow<Set<String>>(emptySet())

    /** Song ids that must not show up in the Downloaded library. */
    val hiddenSongIds: StateFlow<Set<String>> = _hiddenSongIds.asStateFlow()

    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val map =
            prefs.all
                .filterKeys { it.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix(KEY_PREFIX) to it } }
                .toMap()
        _trackToSong.value = map
        _hiddenSongIds.value = map.values.toSet()
        loaded = true
    }

    @Synchronized
    fun record(
        context: Context,
        spotifyTrackId: String,
        songId: String,
    ) {
        ensureLoaded(context)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + spotifyTrackId, songId)
            .apply()
        _trackToSong.value = _trackToSong.value + (spotifyTrackId to songId)
        _hiddenSongIds.value = _hiddenSongIds.value + songId
    }

    /** Forgets a downloaded song, e.g. after it was removed from its Spotify playlist's download list. */
    @Synchronized
    fun forget(
        context: Context,
        songId: String,
    ) {
        ensureLoaded(context)
        val editor =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
        _trackToSong.value.filterValues { it == songId }.keys.forEach { editor.remove(KEY_PREFIX + it) }
        editor.apply()
        _trackToSong.value = _trackToSong.value.filterValues { it != songId }
        _hiddenSongIds.value = _hiddenSongIds.value - songId
    }
}
