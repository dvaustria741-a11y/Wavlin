/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.spotify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.spotify.account.thumbnailUrl
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.pinkTopBarColors
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.viewmodels.SpotifyPlaylistState
import com.wavlin.music.viewmodels.SpotifyPlaylistViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistScreen(
    navController: NavController,
    viewModel: SpotifyPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = playlist?.name ?: stringResource(R.string.spotify)

    fun play(
        tracks: List<com.wavlin.music.spotify.account.SpotifyWebTrack>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
    ) {
        val connection = playerConnection ?: return
        scope.launch { playSpotifyTracks(context, database, connection, title, tracks, startIndex, shuffle) }
    }

    when (val current = state) {
        SpotifyPlaylistState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        is SpotifyPlaylistState.Failed ->
            SpotifyCenteredMessage(current.message) {
                Button(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
            }

        is SpotifyPlaylistState.Loaded ->
            LazyColumn(
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val cover = playlist?.thumbnailUrl()
                        Box(
                            Modifier
                                .width(220.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cover != null) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(painterResource(R.drawable.queue_music), contentDescription = null, modifier = Modifier.size(64.dp))
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val owner = playlist?.owner?.displayName
                        Text(
                            text =
                                if (owner.isNullOrBlank()) {
                                    stringResource(R.string.spotify_playlist_subtitle_no_owner, current.tracks.size)
                                } else {
                                    stringResource(R.string.spotify_playlist_subtitle, owner, current.tracks.size)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        if (current.isOfflineCopy) {
                            Text(
                                stringResource(R.string.spotify_offline_copy),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { play(current.tracks) }, modifier = Modifier.weight(1f)) {
                                Icon(painterResource(R.drawable.play), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.play_all))
                            }
                            OutlinedButton(onClick = { play(current.tracks, shuffle = true) }, modifier = Modifier.weight(1f)) {
                                Icon(painterResource(R.drawable.shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.shuffle))
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                }

                if (current.tracks.isEmpty()) {
                    item { SpotifyCenteredMessage(stringResource(R.string.spotify_no_tracks), Modifier.padding(vertical = 32.dp)) }
                }

                itemsIndexed(current.tracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                    SpotifyTrackItem(
                        track = track,
                        modifier = Modifier.clickable { play(current.tracks, startIndex = index) },
                    )
                }
            }
    }

    TopAppBar(
        colors = pinkTopBarColors(),
        title = { Text(if (state is SpotifyPlaylistState.Loaded) "" else title) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}
