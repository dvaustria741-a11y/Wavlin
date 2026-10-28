/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wavlin.music.ui.component.LocalInterfaceAppleMusic
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

    val appleMusic = LocalInterfaceAppleMusic.current
    val categories = listOf(
        Triple(LibraryFilter.PLAYLISTS, R.drawable.playlist_play, stringResource(R.string.filter_playlists)),
        Triple(LibraryFilter.SPOTIFY, R.drawable.ic_spotify_logo, stringResource(R.string.spotify)),
        Triple(LibraryFilter.SONGS, R.drawable.music_note, stringResource(R.string.filter_songs)),
        Triple(LibraryFilter.ALBUMS, R.drawable.album, stringResource(R.string.filter_albums)),
        Triple(LibraryFilter.ARTISTS, R.drawable.artist, stringResource(R.string.filter_artists)),
        Triple(LibraryFilter.PODCASTS, R.drawable.mic, stringResource(R.string.filter_podcasts)),
    )

    val filterContent = @Composable {
        if (appleMusic && filterType == LibraryFilter.LIBRARY) {
            // Apple Music style: the library root is a list of category rows rather than filter chips.
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                categories.forEachIndexed { index, (filter, iconRes, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { filterType = filter }
                            .padding(vertical = 14.dp),
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = label,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            painter = painterResource(R.drawable.navigate_next),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index != categories.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 42.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        } else Row {
            ChipsRow(
                chips = listOf(
                    LibraryFilter.PLAYLISTS to stringResource(R.string.filter_playlists),
                    LibraryFilter.SPOTIFY to stringResource(R.string.spotify),
                    LibraryFilter.SONGS to stringResource(R.string.filter_songs),
                    LibraryFilter.ALBUMS to stringResource(R.string.filter_albums),
                    LibraryFilter.ARTISTS to stringResource(R.string.filter_artists),
                    LibraryFilter.PODCASTS to stringResource(R.string.filter_podcasts),
                ),
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
