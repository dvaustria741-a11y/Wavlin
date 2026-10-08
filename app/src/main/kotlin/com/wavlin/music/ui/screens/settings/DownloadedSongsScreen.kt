/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.playback.ExoDownloadService
import com.wavlin.music.playback.queues.ListQueue
import com.wavlin.music.ui.component.ActionPromptDialog
import com.wavlin.music.ui.component.EmptyPlaceholder
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.SongListItem
import com.wavlin.music.ui.utils.backToMain

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadedSongsScreen(navController: NavController) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current

    val downloadedSongs by remember { database.downloadedSongsByCreateDateAsc() }
        .collectAsState(initial = emptyList())
    val songs = remember(downloadedSongs) { downloadedSongs.asReversed() }

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val exitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    if (inSelectMode) {
        BackHandler(onBack = exitSelectionMode)
    }

    // Drop selected ids whose downloads are gone (e.g. after deletion).
    LaunchedEffect(songs) {
        val ids = songs.mapTo(HashSet()) { it.id }
        selection.removeAll { it !in ids }
        if (songs.isEmpty()) inSelectMode = false
    }

    if (showDeleteDialog) {
        ActionPromptDialog(
            title = stringResource(R.string.delete_downloads_title),
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                selection.toList().forEach { songId ->
                    DownloadService.sendRemoveDownload(
                        context,
                        ExoDownloadService::class.java,
                        songId,
                        false,
                    )
                }
                showDeleteDialog = false
                exitSelectionMode()
            },
            onCancel = { showDeleteDialog = false },
            content = {
                Text(
                    text = pluralStringResource(
                        R.plurals.delete_downloads_message,
                        selection.size,
                        selection.size,
                    ),
                )
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
            if (songs.isEmpty()) {
                item(key = "empty_placeholder") {
                    EmptyPlaceholder(
                        icon = R.drawable.music_note,
                        text = stringResource(R.string.no_downloaded_songs),
                    )
                }
            }

            items(songs, key = { it.id }) { song ->
                val toggle: (Boolean) -> Unit = { checked ->
                    if (checked) {
                        if (song.id !in selection) selection.add(song.id)
                    } else {
                        selection.remove(song.id)
                    }
                }

                SongListItem(
                    song = song,
                    showInLibraryIcon = true,
                    trailingContent = {
                        if (inSelectMode) {
                            Checkbox(
                                checked = song.id in selection,
                                onCheckedChange = toggle,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .combinedClickable(
                            onClick = {
                                if (inSelectMode) {
                                    toggle(song.id !in selection)
                                } else {
                                    playerConnection?.playQueue(
                                        ListQueue(
                                            title = "Downloaded Songs",
                                            items = songs.map { it.toMediaItem() },
                                            startIndex = songs.indexOfFirst { it.id == song.id },
                                        ),
                                    )
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!inSelectMode) inSelectMode = true
                                toggle(true)
                            },
                        ),
                )
            }
        }

        TopAppBar(
            title = {
                if (inSelectMode) {
                    Text(
                        text = pluralStringResource(R.plurals.n_song, selection.size, selection.size),
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.downloaded_songs),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = { if (inSelectMode) exitSelectionMode() else navController.navigateUp() },
                    onLongClick = { if (!inSelectMode) navController.backToMain() },
                ) {
                    Icon(
                        painter = painterResource(if (inSelectMode) R.drawable.close else R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                if (inSelectMode) {
                    Checkbox(
                        checked = selection.size == songs.size && selection.isNotEmpty(),
                        onCheckedChange = {
                            if (selection.size == songs.size) {
                                selection.clear()
                            } else {
                                selection.clear()
                                selection.addAll(songs.map { it.id })
                            }
                        },
                    )
                    IconButton(
                        enabled = selection.isNotEmpty(),
                        onClick = { showDeleteDialog = true },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                } else if (songs.isNotEmpty()) {
                    IconButton(onClick = { inSelectMode = true }, onLongClick = {}) {
                        Icon(
                            painter = painterResource(R.drawable.select_all),
                            contentDescription = null,
                        )
                    }
                }
            },
        )
    }
}
