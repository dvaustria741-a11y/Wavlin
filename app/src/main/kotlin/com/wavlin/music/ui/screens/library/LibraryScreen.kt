/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wavlin.music.LocalNavController
import com.wavlin.music.R
import com.wavlin.music.constants.ChipSortTypeKey
import com.wavlin.music.constants.LibraryFilter
import com.wavlin.music.ui.component.ChipsRow
import com.wavlin.music.utils.rememberEnumPreference

@Composable
fun LibraryScreen() {
    val navController = LocalNavController.current
    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)

    val categories = listOf(
        LibraryCategory(LibraryFilter.PLAYLISTS, R.drawable.playlist_play, stringResource(R.string.filter_playlists)),
        LibraryCategory(LibraryFilter.SPOTIFY, R.drawable.ic_spotify_logo, stringResource(R.string.spotify)),
        LibraryCategory(LibraryFilter.SONGS, R.drawable.music_note, stringResource(R.string.filter_songs)),
        LibraryCategory(LibraryFilter.ALBUMS, R.drawable.album, stringResource(R.string.filter_albums)),
        LibraryCategory(LibraryFilter.ARTISTS, R.drawable.artist, stringResource(R.string.filter_artists)),
        LibraryCategory(LibraryFilter.PODCASTS, R.drawable.mic, stringResource(R.string.filter_podcasts)),
    )

    val filterContent = @Composable {
        if (filterType == LibraryFilter.LIBRARY) {
            LibraryCategoryGrid(
                categories = categories,
                onSelect = { filterType = it },
            )
        } else Row {
            ChipsRow(
                chips = categories.map { it.filter to it.label },
                currentValue = filterType,
                onValueUpdate = {
                    filterType = if (filterType == it) LibraryFilter.LIBRARY else it
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (filterType) {
            LibraryFilter.LIBRARY -> LibraryMixScreen(navController, filterContent)
            LibraryFilter.PLAYLISTS -> LibraryPlaylistsScreen(navController, filterContent)
            LibraryFilter.SPOTIFY -> LibrarySpotifyScreen(navController, filterContent)
            LibraryFilter.SONGS -> LibrarySongsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.ALBUMS -> LibraryAlbumsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.ARTISTS -> LibraryArtistsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )
            LibraryFilter.PODCASTS -> LibraryPodcastsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY },
            )
        }
    }
}
