/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.menu

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalSyncUtils
import com.wavlin.music.R
import com.wavlin.music.db.entities.Song
import com.wavlin.music.playback.ExoDownloadService
import com.wavlin.music.ui.component.CreatePlaylistDialog
import com.wavlin.music.ui.component.ListDialog
import com.wavlin.music.ui.component.ListItem
import com.wavlin.music.ui.component.PlaylistListItem
import com.wavlin.music.viewmodels.PlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shown whenever the user chooses "Download" for one song, a multi-selection of songs,
 * or a whole playlist. Lets them pick where the song(s) should end up:
 *  - "Download Library" (i.e. actually download the audio) — checked by default
 *  - any of their own playlists — multi-select, in addition to or instead of the above
 */
@Composable
fun DownloadDestinationDialog(
    isVisible: Boolean,
    songs: List<Song>,
    onDismiss: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    if (!isVisible) return

    val context = LocalContext.current
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val coroutineScope = rememberCoroutineScope()
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()

    var downloadToLibrary by rememberSaveable(isVisible) { mutableStateOf(true) }
    val selectedPlaylistIds = remember(isVisible) { mutableStateListOf<String>() }
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }

    fun startDownloads(context: Context) {
        songs.forEach { song ->
            val downloadRequest =
                DownloadRequest
                    .Builder(song.id, song.id.toUri())
                    .setCustomCacheKey(song.id)
                    .setData(song.song.title.toByteArray())
                    .build()
            DownloadService.sendAddDownload(
                context,
                ExoDownloadService::class.java,
                downloadRequest,
                false,
            )
        }
    }

    ListDialog(
        onDismiss = onDismiss,
    ) {
        item {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.n_song,
                        songs.size,
                        songs.size,
                    ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        item {
            ListItem(
                title = stringResource(R.string.download_library),
                subtitle = null as String?,
                thumbnailContent = {
                    Icon(
                        painter = painterResource(R.drawable.download),
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Checkbox(
                        checked = downloadToLibrary,
                        onCheckedChange = { downloadToLibrary = it },
                    )
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { downloadToLibrary = !downloadToLibrary },
            )
        }

        item {
            ListItem(
                title = stringResource(R.string.create_playlist),
                subtitle = null as String?,
                thumbnailContent = {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showCreatePlaylistDialog = true },
            )
        }

        items(playlists) { playlist ->
            val checked = playlist.id in selectedPlaylistIds
            PlaylistListItem(
                playlist = playlist,
                trailingContent = {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            if (it) {
                                selectedPlaylistIds.add(playlist.id)
                            } else {
                                selectedPlaylistIds.remove(playlist.id)
                            }
                        },
                    )
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (checked) {
                                selectedPlaylistIds.remove(playlist.id)
                            } else {
                                selectedPlaylistIds.add(playlist.id)
                            }
                        },
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    enabled = downloadToLibrary || selectedPlaylistIds.isNotEmpty(),
                    onClick = {
                        if (downloadToLibrary || selectedPlaylistIds.isNotEmpty()) {
                            startDownloads(context)
                        }
                        if (selectedPlaylistIds.isNotEmpty()) {
                            val songIds = songs.map { it.id to null as String? }
                            coroutineScope.launch(Dispatchers.IO) {
                                selectedPlaylistIds.forEach { playlistId ->
                                    val playlist = playlists.find { it.id == playlistId } ?: return@forEach
                                    database.addSongsToPlaylist(playlist, songIds, prepend = true)
                                    playlist.playlist.browseId?.let { browseId ->
                                        songs.forEach { song ->
                                            syncUtils.addToPlaylist(browseId, playlist.id, song.id)
                                        }
                                    }
                                }
                            }
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
        )
    }
}
