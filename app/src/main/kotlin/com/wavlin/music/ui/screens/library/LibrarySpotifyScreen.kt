/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.constants.GridItemSize
import com.wavlin.music.constants.GridItemsSizeKey
import com.wavlin.music.constants.GridThumbnailHeight
import com.wavlin.music.constants.SpotifyPromoShownKey
import com.wavlin.music.db.entities.PlaylistEntity
import com.wavlin.music.ui.component.EmptyPlaceholder
import com.wavlin.music.ui.component.LocalMenuState
import com.wavlin.music.ui.component.PlaylistGridItem
import com.wavlin.music.ui.menu.PlaylistMenu
import com.wavlin.music.utils.rememberEnumPreference
import com.wavlin.music.utils.rememberPreference
import com.wavlin.music.viewmodels.LibraryPlaylistsViewModel

// Spotify green
private val SpotifyGreen = Color(0xFF1DB954)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibrarySpotifyScreen(
    navController: NavController,
    filterContent: @Composable () -> Unit,
    viewModel: LibraryPlaylistsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.spotify_playlists),
        stringResource(R.string.spotify_liked_songs),
    )

    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)

    // Local (non-YouTube) playlists: editable, no browseId.
    // These are playlists the user created or imported — Spotify imports land here.
    val localPlaylists = remember(allPlaylists) {
        allPlaylists.filter { it.playlist.isEditable && it.playlist.browseId == null }
    }

    val (promoShown, onPromoShownChange) = rememberPreference(SpotifyPromoShownKey, false)
    var showPromo by remember { mutableStateOf(!promoShown) }

    Column(modifier = Modifier.fillMaxSize()) {
        filterContent()

        // Spotify brand header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.spotify_library_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SpotifyGreen,
            )
            Spacer(Modifier.weight(1f))
            if (!promoShown) {
                TextButton(
                    onClick = {
                        showPromo = true
                        onPromoShownChange(true)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.spotify_how_to_import),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // Sub-tabs: Playlists / Liked Songs
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 16.dp,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        when (selectedTab) {
            0 -> {
                // Playlists tab
                if (localPlaylists.isEmpty()) {
                    SpotifyEmptyState(
                        onImportClick = { navController.navigate("search") },
                    )
                } else {
                    // Show promo card at top if not yet dismissed, then the playlist grid
                    if (showPromo) {
                        SpotifyPromoCard(
                            onDismiss = {
                                showPromo = false
                                onPromoShownChange(true)
                            },
                            onImportClick = { navController.navigate("search") },
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(
                            minSize = GridThumbnailHeight +
                                if (gridItemSize == GridItemSize.BIG) 24.dp else (-24).dp,
                        ),
                        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = localPlaylists,
                            key = { it.playlist.id },
                        ) { playlist ->
                            PlaylistGridItem(
                                playlist = playlist,
                                trailingContent = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            navController.navigate("local_playlist/${playlist.playlist.id}")
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                PlaylistMenu(
                                                    playlist = playlist,
                                                    coroutineScope = coroutineScope,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ),
                            )
                        }
                    }
                }
            }

            1 -> {
                // Liked Songs tab — navigate straight to the Liked system playlist
                navController.navigate("local_playlist/${PlaylistEntity.LIKED_PLAYLIST_ID}")
                selectedTab = 0
            }
        }
    }
}

@Composable
private fun SpotifyEmptyState(onImportClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.queue_music),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.spotify_no_playlists),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.spotify_no_playlists_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onImportClick,
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
        ) {
            Icon(
                painter = painterResource(R.drawable.link),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.spotify_import_cta))
        }
    }
}

@Composable
private fun SpotifyPromoCard(
    onDismiss: () -> Unit,
    onImportClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = SpotifyGreen.copy(alpha = 0.12f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = SpotifyGreen,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.spotify_promo_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SpotifyGreen,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.spotify_promo_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.spotify_promo_dismiss))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onImportClick,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                ) {
                    Text(stringResource(R.string.spotify_import_cta))
                }
            }
        }
    }
}
