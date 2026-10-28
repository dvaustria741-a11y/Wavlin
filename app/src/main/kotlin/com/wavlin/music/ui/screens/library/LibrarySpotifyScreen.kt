/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.constants.CONTENT_TYPE_HEADER
import com.wavlin.music.constants.GridThumbnailHeight
import com.wavlin.music.spotify.account.SpotifyAuthState
import com.wavlin.music.spotify.account.SpotifyPlaylist
import com.wavlin.music.spotify.account.thumbnailUrl
import com.wavlin.music.ui.component.GridItem
import com.wavlin.music.ui.screens.spotify.SpotifyCenteredMessage
import com.wavlin.music.ui.screens.spotify.SpotifyConnectCard
import com.wavlin.music.ui.screens.spotify.SpotifyTrackItem
import com.wavlin.music.ui.screens.spotify.playSpotifyTracks
import com.wavlin.music.viewmodels.SpotifyViewModel
import kotlinx.coroutines.launch

/** The Spotify tab of the Library: playlists and liked songs of the connected account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySpotifyScreen(
    navController: NavController,
    filterContent: @Composable () -> Unit,
    viewModel: SpotifyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    when (authState) {
        SpotifyAuthState.CONNECTED -> {
            if (selectedTab == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "filter", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) { filterContent() }
                    item(key = "tabs", span = { GridItemSpan(maxLineSpan) }, contentType = CONTENT_TYPE_HEADER) {
                        SpotifyTabs(selectedTab) { selectedTab = it }
                    }
                    if (playlists.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SpotifyCenteredMessage(stringResource(R.string.spotify_no_playlists), Modifier.padding(vertical = 48.dp))
                        }
                    }
                    items(playlists.distinctBy { it.id }, key = { it.id }) { playlist ->
                        SpotifyPlaylistGridItem(
                            playlist = playlist,
                            modifier = Modifier.clickable { navController.navigate("spotify_playlist/${playlist.id}") },
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                    item(key = "filter", contentType = CONTENT_TYPE_HEADER) { filterContent() }
                    item(key = "tabs", contentType = CONTENT_TYPE_HEADER) {
                        SpotifyTabs(selectedTab) { selectedTab = it }
                    }
                    if (likedTracks.isEmpty()) {
                        item { SpotifyCenteredMessage(stringResource(R.string.spotify_no_tracks), Modifier.padding(vertical = 48.dp)) }
                    }
                    itemsIndexed(likedTracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                        SpotifyTrackItem(
                            track = track,
                            modifier =
                                Modifier.clickable {
                                    val connection = playerConnection ?: return@clickable
                                    scope.launch {
                                        playSpotifyTracks(
                                            context,
                                            database,
                                            connection,
                                            context.getString(R.string.spotify_liked_songs_tab),
                                            likedTracks,
                                            startIndex = index,
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }

        else ->
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item(key = "filter", contentType = CONTENT_TYPE_HEADER) { filterContent() }
                item(key = "state") {
                    when (authState) {
                        SpotifyAuthState.LOADING ->
                            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }

                        SpotifyAuthState.ERROR ->
                            SpotifyCenteredMessage(
                                message = errorMessage ?: stringResource(R.string.spotify_connection_failed),
                                modifier = Modifier.padding(vertical = 32.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = viewModel::reload) { Text(stringResource(R.string.retry)) }
                                    OutlinedButton(onClick = viewModel::logout) { Text(stringResource(R.string.disconnect)) }
                                }
                            }

                        else -> SpotifyConnectCard(onConnect = { navController.navigate("spotify_signin") })
                    }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    LibrarySegmentedFilter(
        options = listOf(
            0 to stringResource(R.string.spotify_playlists_tab),
            1 to stringResource(R.string.spotify_liked_songs_tab),
        ),
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun SpotifyPlaylistGridItem(
    playlist: SpotifyPlaylist,
    modifier: Modifier = Modifier,
) {
    GridItem(
        title = playlist.name,
        subtitle = playlist.owner?.displayName.orEmpty(),
        thumbnailContent = {
            val cover = playlist.thumbnailUrl()
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(painterResource(R.drawable.queue_music), contentDescription = null, modifier = Modifier.size(48.dp))
            }
        },
        fillMaxWidth = true,
        modifier = modifier,
    )
}
