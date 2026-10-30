/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.menu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.wavlin.music.LocalListenTogetherManager
import com.wavlin.music.R
import com.wavlin.music.db.entities.Playlist
import com.wavlin.music.db.entities.PlaylistSong
import com.wavlin.music.db.entities.Song
import com.wavlin.music.constants.AccountNameKey
import com.wavlin.music.ui.component.DefaultDialog
import com.wavlin.music.ui.component.LocalMenuState
import com.wavlin.music.ui.component.PlaylistShareSheet
import com.wavlin.music.utils.rememberPreference
import com.wavlin.music.ui.component.Material3MenuGroup
import com.wavlin.music.ui.component.Material3MenuItemData
import com.wavlin.music.utils.PlaylistExporter
import com.wavlin.music.utils.getExportFileUri
import com.wavlin.music.utils.saveToPublicDocuments
import kotlinx.coroutines.launch

/**
 * Menu for Local Playlist Screen
 */
@Composable
fun LocalPlaylistMenu(
    playlist: Playlist,
    songs: List<PlaylistSong>,
    context: Context,
    downloadState: Int,
    onEdit: () -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost
    val coroutineScope = rememberCoroutineScope()
    val localContext = LocalContext.current
    val shareMenuState = LocalMenuState.current
    val (shareOwnerName) = rememberPreference(AccountNameKey, "")

    val (showExportDialog, setShowExportDialog) = remember { mutableStateOf(false) }

    val isYouTubePlaylist = playlist.playlist.browseId != null

    val circle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val shareLabel = stringResource(R.string.share)
    val quickActions =
        buildList {
            add(
                PlayerQuickAction(
                    iconRes = R.drawable.edit,
                    label = stringResource(R.string.edit),
                    circleColor = circle,
                    onClick = {
                        onEdit()
                        onDismiss()
                    },
                ),
            )
            if (!isGuest) {
                add(
                    PlayerQuickAction(
                        iconRes = R.drawable.queue_music,
                        label = stringResource(R.string.add_to_queue),
                        circleColor = circle,
                        onClick = {
                            onQueue()
                            onDismiss()
                        },
                    ),
                )
            }
            add(
                PlayerQuickAction(
                    iconRes = if (downloadState == Download.STATE_COMPLETED) R.drawable.offline else R.drawable.download,
                    label =
                        stringResource(
                            when (downloadState) {
                                Download.STATE_COMPLETED -> R.string.remove_download
                                Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> R.string.downloading
                                else -> R.string.action_download
                            },
                        ),
                    circleColor = circle,
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                ),
            )
            add(
                PlayerQuickAction(
                    iconRes = R.drawable.share,
                    label = shareLabel,
                    circleColor = circle,
                    onClick = {
                        onDismiss()
                        shareMenuState.show(expandFully = true) {
                            PlaylistShareSheet(
                                playlist = playlist,
                                songs = songs,
                                ownerName = shareOwnerName.ifBlank { context.getString(R.string.app_name) },
                                onDismiss = shareMenuState::dismiss,
                            )
                        }
                    },
                ),
            )
        }

    val menuItems =
        buildList {
            // Show sync button only for YouTube playlists
            if (isYouTubePlaylist) {
                add(
                    Material3MenuItemData(
                        title = { Text(stringResource(R.string.action_sync)) },
                        description = { Text(stringResource(R.string.sync_playlist_desc)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.sync),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onSync()
                            onDismiss()
                        },
                    ),
                )
            }

            add(
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.export_playlist)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                        )
                    },
                    onClick = { setShowExportDialog(true) },
                ),
            )

            add(
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.delete)) },
                    description = { Text(stringResource(R.string.delete_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                ),
            )
        }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                PlayerMenuHeaderCard(
                    title = playlist.playlist.name,
                    subtitle = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                    thumbnailUrl = playlist.playlist.thumbnailUrl ?: songs.firstOrNull()?.song?.song?.thumbnailUrl,
                )
                PlayerMenuQuickActions(quickActions)
                PlayerMenuListCard(menuItems)
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            onDismiss = { setShowExportDialog(false) },
            onShare = { format ->
                coroutineScope.launch {
                    val result =
                        when (format) {
                            "csv" -> PlaylistExporter.exportPlaylistAsCSV(localContext, playlist.playlist.name, songs)
                            "m3u" -> PlaylistExporter.exportPlaylistAsM3U(localContext, playlist.playlist.name, songs)
                            else -> Result.failure(IllegalArgumentException("Unknown format"))
                        }
                    result
                        .onSuccess { file ->
                            val uri = getExportFileUri(localContext, file)
                            val mimeType =
                                when (format) {
                                    "csv" -> "text/csv"
                                    "m3u" -> "audio/x-mpegurl"
                                    else -> "*/*"
                                }
                            shareExportFile(localContext, uri, mimeType)
                        }.onFailure {
                            Toast.makeText(localContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
                        }
                }
                onDismiss()
            },
            onSave = { format ->
                coroutineScope.launch {
                    val exportResult =
                        when (format) {
                            "csv" -> PlaylistExporter.exportPlaylistAsCSV(localContext, playlist.playlist.name, songs)
                            "m3u" -> PlaylistExporter.exportPlaylistAsM3U(localContext, playlist.playlist.name, songs)
                            else -> Result.failure(IllegalArgumentException("Unknown format"))
                        }
                    exportResult
                        .onSuccess { file ->
                            val mimeType =
                                when (format) {
                                    "csv" -> "text/csv"
                                    "m3u" -> "audio/x-mpegurl"
                                    else -> "application/octet-stream"
                                }
                            val saveResult = saveToPublicDocuments(localContext, file, mimeType)
                            saveResult
                                .onSuccess {
                                    Toast.makeText(localContext, R.string.export_success, Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(localContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
                                }
                        }.onFailure {
                            Toast.makeText(localContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
                        }
                }
                onDismiss()
            },
        )
    }
}

/**
 * Menu for Auto Playlist Screen (Liked Songs, Downloaded Songs, etc.)
 */
@Composable
fun AutoPlaylistMenu(
    downloadState: Int,
    onQueue: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    songs: List<Song> = emptyList(),
    playlistName: String = "Playlist",
) {
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost
    val coroutineScope = rememberCoroutineScope()
    val localContext = LocalContext.current

    val (showExportDialog, setShowExportDialog) = remember { mutableStateOf(false) }

    val downloadMenuItem =
        when (downloadState) {
            Download.STATE_COMPLETED -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.remove_download)) },
                    description = { Text(stringResource(R.string.remove_download_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.offline),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }

            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.downloading)) },
                    description = { Text(stringResource(R.string.download_in_progress_desc)) },
                    icon = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }

            else -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.action_download)) },
                    description = { Text(stringResource(R.string.download_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.download),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }
        }

    Material3MenuGroup(
        items =
            listOfNotNull(
                if (!isGuest) {
                    Material3MenuItemData(
                        title = { Text(stringResource(R.string.add_to_queue)) },
                        description = { Text(stringResource(R.string.add_to_queue_desc)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onQueue()
                            onDismiss()
                        },
                    )
                } else {
                    null
                },
                if (songs.isNotEmpty()) {
                    Material3MenuItemData(
                        title = { Text(stringResource(R.string.export_playlist)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = null,
                            )
                        },
                        onClick = { setShowExportDialog(true) },
                    )
                } else {
                    null
                },
                downloadMenuItem,
            ),
    )

    if (showExportDialog) {
        // Convert Song objects to a format that PlaylistExporter can handle
        val playlistSongs =
            songs.map { song ->
                PlaylistSong(
                    map =
                        com.wavlin.music.db.entities.PlaylistSongMap(
                            songId = song.id,
                            playlistId = "auto_playlist",
                            position = 0,
                        ),
                    song = song,
                )
            }

        ExportDialog(
            onDismiss = { setShowExportDialog(false) },
            onShare = { format ->
                coroutineScope.launch {
                    val result =
                        when (format) {
                            "csv" -> PlaylistExporter.exportPlaylistAsCSV(localContext, playlistName, playlistSongs)
                            "m3u" -> PlaylistExporter.exportPlaylistAsM3U(localContext, playlistName, playlistSongs)
                            else -> Result.failure(IllegalArgumentException("Unknown format"))
                        }
                    result
                        .onSuccess { file ->
                            val uri = getExportFileUri(localContext, file)
                            val mimeType =
                                when (format) {
                                    "csv" -> "text/csv"
                                    "m3u" -> "audio/x-mpegurl"
                                    else -> "*/*"
                                }
                            shareExportFile(localContext, uri, mimeType)
                        }.onFailure {
                            Toast.makeText(localContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
                        }
                }
                onDismiss()
            },
            onSave = { format ->
                coroutineScope.launch {
                    val exportResult =
                        when (format) {
                            "csv" -> PlaylistExporter.exportPlaylistAsCSV(localContext, playlistName, playlistSongs)
                            "m3u" -> PlaylistExporter.exportPlaylistAsM3U(localContext, playlistName, playlistSongs)
                            else -> Result.failure(IllegalArgumentException("Unknown format"))
                        }
                    exportResult
                        .onSuccess { file ->
                            val mimeType =
                                when (format) {
                                    "csv" -> "text/csv"
                                    "m3u" -> "audio/x-mpegurl"
                                    else -> "application/octet-stream"
                                }
                            val saveResult = saveToPublicDocuments(localContext, file, mimeType)
                            saveResult
                                .onSuccess {
                                    Toast.makeText(localContext, R.string.export_success, Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(localContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
                                }
                        }.onFailure {
                            Toast.makeText(localContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
                        }
                }
                onDismiss()
            },
        )
    }
}

/**
 * Menu for Top Playlist Screen
 */
@Composable
fun TopPlaylistMenu(
    downloadState: Int,
    onQueue: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    val downloadMenuItem =
        when (downloadState) {
            Download.STATE_COMPLETED -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.remove_download)) },
                    description = { Text(stringResource(R.string.remove_download_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.offline),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }

            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.downloading)) },
                    description = { Text(stringResource(R.string.download_in_progress_desc)) },
                    icon = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }

            else -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.action_download)) },
                    description = { Text(stringResource(R.string.download_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.download),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }
        }

    Material3MenuGroup(
        items =
            listOfNotNull(
                if (!isGuest) {
                    Material3MenuItemData(
                        title = { Text(stringResource(R.string.add_to_queue)) },
                        description = { Text(stringResource(R.string.add_to_queue_desc)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onQueue()
                            onDismiss()
                        },
                    )
                } else {
                    null
                },
                downloadMenuItem,
            ),
    )
}

private fun shareExportFile(
    context: Context,
    uri: Uri,
    mimeType: String,
) {
    val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.export_playlist)))
}

@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    initialFormat: String = "csv",
    onShare: (format: String) -> Unit,
    onSave: (format: String) -> Unit,
) {
    val (selected, setSelected) = remember { mutableStateOf(initialFormat) }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.export_playlist)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(onClick = { onSave(selected) }) {
                Text(text = stringResource(R.string.export_option_save))
            }
            TextButton(onClick = { onShare(selected) }) {
                Text(text = stringResource(R.string.export_option_share))
            }
        },
        horizontalAlignment = Alignment.Start,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { setSelected("csv") }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                RadioButton(selected = selected == "csv", onClick = null)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = stringResource(R.string.export_as_csv))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { setSelected("m3u") }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                RadioButton(selected = selected == "m3u", onClick = null)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = stringResource(R.string.export_as_m3u))
                }
            }
        }
    }
}

/**
 * Menu for Cache Playlist Screen
 */
@Composable
fun CachePlaylistMenu(
    downloadState: Int,
    onQueue: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    val downloadMenuItem =
        when (downloadState) {
            Download.STATE_COMPLETED -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.remove_download)) },
                    description = { Text(stringResource(R.string.remove_download_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.offline),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }

            Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.downloading)) },
                    description = { Text(stringResource(R.string.download_in_progress_desc)) },
                    icon = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }

            else -> {
                Material3MenuItemData(
                    title = { Text(stringResource(R.string.action_download)) },
                    description = { Text(stringResource(R.string.download_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.download),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }
        }

    Material3MenuGroup(
        items =
            listOfNotNull(
                if (!isGuest) {
                    Material3MenuItemData(
                        title = { Text(stringResource(R.string.add_to_queue)) },
                        description = { Text(stringResource(R.string.add_to_queue_desc)) },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onQueue()
                            onDismiss()
                        },
                    )
                } else {
                    null
                },
                downloadMenuItem,
            ),
    )
}
