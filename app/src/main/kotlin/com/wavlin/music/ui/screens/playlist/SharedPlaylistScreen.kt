/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.playlist

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.db.entities.Playlist
import com.wavlin.music.db.entities.PlaylistEntity
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.models.toMediaMetadata
import com.wavlin.music.playback.queues.ListQueue
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.YouTubeListItem
import com.wavlin.music.ui.component.pinkTopBarColors
import com.wavlin.music.ui.screens.spotify.SpotifySaveDialog
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.viewmodels.SharedPlaylistState
import com.wavlin.music.viewmodels.SharedPlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Preview of a playlist someone shared. It is not part of the user's library, so there is nothing to
 * edit or delete: the menu only offers to keep it (as a new playlist or inside an existing one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedPlaylistScreen(
    navController: NavController,
    viewModel: SharedPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val loaded = state as? SharedPlaylistState.Loaded
    val defaultName = stringResource(R.string.shared_playlist)
    val title = loaded?.name?.takeIf { it.isNotBlank() } ?: defaultName

    var showSaveDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scrolledPastHero by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val overlayTop = loaded != null && !scrolledPastHero

    fun play(
        songs: List<SongItem>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
    ) {
        val ordered = if (shuffle) songs.shuffled() else songs
        playerConnection.playQueue(
            ListQueue(
                title = title,
                items = ordered.map { it.toMediaMetadata().toMediaItem() },
                startIndex = if (shuffle) 0 else startIndex,
            ),
        )
    }

    fun saveAsNew(
        name: String,
        songs: List<SongItem>,
    ) {
        scope.launch(Dispatchers.IO) {
            // bookmarkedAt is what makes a playlist show up in the Library (same as the Create playlist dialog)
            val entity = PlaylistEntity(name = name, bookmarkedAt = LocalDateTime.now(), isEditable = true)
            database.query {
                insert(entity)
                songs.forEach { insert(it.toMediaMetadata()) }
            }
            database.playlist(entity.id).first()?.let { playlist ->
                database.query { addSongsToPlaylist(playlist, songs.map { it.id to null }) }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.shared_playlist_saved, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addToExisting(
        target: Playlist,
        songs: List<SongItem>,
    ) {
        scope.launch(Dispatchers.IO) {
            val already = songs.map { it.id }.chunked(500).flatMap { database.playlistDuplicates(target.id, it) }.toSet()
            val fresh = songs.filterNot { it.id in already }
            if (fresh.isNotEmpty()) {
                database.query { fresh.forEach { insert(it.toMediaMetadata()) } }
                val current = database.playlist(target.id).first() ?: target
                database.query { addSongsToPlaylist(current, fresh.map { it.id to null }) }
            }
            withContext(Dispatchers.Main) {
                val message =
                    if (fresh.isEmpty()) {
                        context.getString(R.string.shared_playlist_already_added, target.playlist.name)
                    } else {
                        context.getString(R.string.shared_playlist_added, fresh.size, target.playlist.name)
                    }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            SharedPlaylistState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            SharedPlaylistState.Failed ->
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.shared_playlist_load_failed))
                    Button(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
                }

            is SharedPlaylistState.Loaded ->
                LazyColumn(
                    state = listState,
                    contentPadding = heroListPadding(true),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") {
                        PlaylistHeroHeader(
                            title = title,
                            subtitle = pluralStringResource(R.plurals.n_song, current.songs.size, current.songs.size),
                            coverUrls = current.songs.mapNotNull { it.thumbnail },
                            onShuffle = { play(current.songs, shuffle = true) },
                            onPlay = { play(current.songs) },
                            onMenu = { showSaveDialog = true },
                        )
                    }

                    itemsIndexed(current.songs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                        YouTubeListItem(
                            item = song,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            modifier =
                                Modifier.clickable {
                                    if (song.id == mediaMetadata?.id) {
                                        playerConnection.togglePlayPause()
                                    } else {
                                        play(current.songs, startIndex = index)
                                    }
                                },
                        )
                    }
                }
        }

        TopAppBar(
            colors =
                if (overlayTop) {
                    TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                } else {
                    pinkTopBarColors()
                },
            title = {
                Text(
                    text = if (overlayTop) "" else title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                    modifier = heroTopButtonModifier(overlayTop),
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = heroTopIconTint(overlayTop),
                    )
                }
            },
        )
    }

    if (showSaveDialog && loaded != null) {
        SpotifySaveDialog(
            initialName = title,
            onDismiss = { showSaveDialog = false },
            onSaveNew = { newName ->
                showSaveDialog = false
                saveAsNew(newName, loaded.songs)
            },
            onAddToExisting = { target ->
                showSaveDialog = false
                addToExisting(target, loaded.songs)
            },
        )
    }
}
