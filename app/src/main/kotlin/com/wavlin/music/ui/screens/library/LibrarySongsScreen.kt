/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.library

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachReversed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.wavlin.innertube.YouTube
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.constants.CONTENT_TYPE_HEADER
import com.wavlin.music.constants.CONTENT_TYPE_SONG
import com.wavlin.music.constants.HideExplicitKey
import com.wavlin.music.constants.SongFilter
import com.wavlin.music.constants.SongFilterKey
import com.wavlin.music.constants.SongSortDescendingKey
import com.wavlin.music.constants.SongSortType
import com.wavlin.music.constants.SongSortTypeKey
import com.wavlin.music.constants.YtmSyncKey
import com.wavlin.music.extensions.matchesNormalizedQuery
import com.wavlin.music.extensions.normalizeForSearch
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.playback.queues.ListQueue
import com.wavlin.music.ui.component.ChipsRow
import com.wavlin.music.ui.component.DefaultDialog
import com.wavlin.music.ui.component.HideOnScrollFAB
import com.wavlin.music.ui.component.LibrarySearchEmptyPlaceholder
import com.wavlin.music.ui.component.LibrarySearchHeader
import com.wavlin.music.ui.component.LocalMenuState
import com.wavlin.music.ui.component.SongListItem
import com.wavlin.music.ui.component.SortHeader
import com.wavlin.music.ui.menu.SelectionSongMenu
import com.wavlin.music.ui.menu.SongMenu
import com.wavlin.music.ui.utils.isScrollingUp
import com.wavlin.music.utils.rememberEnumPreference
import com.wavlin.music.utils.rememberPreference
import com.wavlin.music.viewmodels.LibrarySongsViewModel
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibrarySongsScreen(
    navController: NavController,
    onDeselect: () -> Unit,
    viewModel: LibrarySongsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val uploadUnsupportedFormatStr = stringResource(R.string.upload_unsupported_format)
    val uploadFileTooLargeStr = stringResource(R.string.upload_file_too_large)
    val uploadFailedStr = stringResource(R.string.upload_failed)
    val uploadCompleteStr = stringResource(R.string.upload_complete)
    val queueAllSongsStr = stringResource(R.string.queue_all_songs)
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection: SnapshotStateList<String> =
        rememberSaveable(
            saver =
                listSaver(
                    save = { it.toList() },
                    restore = { it.toMutableStateList() },
                ),
        ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            SongSortTypeKey,
            SongSortType.CREATE_DATE,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)

    val (ytmSync) = rememberPreference(YtmSyncKey, true)
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    val songs by viewModel.allSongs.collectAsStateWithLifecycle()
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val debouncedSearchQuery by viewModel.debouncedSearchQuery.collectAsStateWithLifecycle()
    val normalizedQuery = remember(debouncedSearchQuery) { debouncedSearchQuery.normalizeForSearch() }

    var filter by rememberEnumPreference(SongFilterKey, SongFilter.LIKED)

    // Upload state
    var showUploadDialog by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var currentUploadIndex by remember { mutableIntStateOf(0) }
    var totalUploads by remember { mutableIntStateOf(0) }
    var currentFileName by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    try {
                        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: SecurityException) {
                        Timber.w(e, "Could not take persistable permission")
                    }
                }
                uploadJob =
                    scope.launch {
                        isUploading = true
                        showUploadDialog = true
                        totalUploads = uris.size
                        var successCount = 0

                        uris.forEachIndexed { index, uri ->
                            currentUploadIndex = index + 1
                            uploadProgress = 0f

                            try {
                                // Get actual display name from content resolver
                                var fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "unknown"
                                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                        if (displayNameIndex >= 0) {
                                            val name = cursor.getString(displayNameIndex)
                                            if (!name.isNullOrBlank()) {
                                                fileName = name
                                            }
                                        }
                                    }
                                }
                                currentFileName = fileName
                                val extension = fileName.substringAfterLast('.', "").lowercase()

                                if (extension !in YouTube.SUPPORTED_UPLOAD_TYPES) {
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                context,
                                                uploadUnsupportedFormatStr,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                    return@forEachIndexed
                                }

                                val inputStream = context.contentResolver.openInputStream(uri)
                                val data = inputStream?.readBytes()
                                inputStream?.close()

                                if (data == null) return@forEachIndexed

                                if (data.size > YouTube.MAX_UPLOAD_SIZE) {
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                context,
                                                uploadFileTooLargeStr,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                    return@forEachIndexed
                                }

                                val result =
                                    YouTube.uploadSong(
                                        filename = fileName,
                                        data = data,
                                        onProgress = { progress ->
                                            uploadProgress = progress
                                        },
                                    )

                                if (result.isSuccess && result.getOrDefault(false)) {
                                    successCount++
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast
                                        .makeText(
                                            context,
                                            uploadFailedStr + ": ${e.message}",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        }

                        isUploading = false

                        if (successCount > 0) {
                            // Show completion briefly
                            uploadProgress = 1f
                            currentFileName = uploadCompleteStr
                            kotlinx.coroutines.delay(1000)

                            // Show toast on main thread
                            withContext(Dispatchers.Main) {
                                Toast
                                    .makeText(
                                        context,
                                        uploadCompleteStr,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }

                            showUploadDialog = false

                            // Refresh uploaded songs
                            viewModel.syncUploadedSongs()
                        } else {
                            showUploadDialog = false
                        }
                    }
            }
        }

    LaunchedEffect(Unit) {
        if (ytmSync) {
            when (filter) {
                SongFilter.LIKED -> viewModel.syncLikedSongs()
                SongFilter.LIBRARY -> viewModel.syncLibrarySongs()
                SongFilter.UPLOADED -> viewModel.syncUploadedSongs()
                else -> return@LaunchedEffect
            }
        }
    }

    val lazyListState = rememberLazyListState()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazyListState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    val filteredSongs =
        (if (hideExplicit) {
            songs.filter { !it.song.explicit }
        } else {
            songs
        }).filter { song ->
            val artistNames = song.artists.map { it.name }.toTypedArray()
            matchesNormalizedQuery(normalizedQuery, song.song.title, song.album?.title, *artistNames)
        }

    BackHandler(enabled = inSelectMode, onBack = onExitSelectionMode)

    LaunchedEffect(filteredSongs) {
        val validIds = filteredSongs.map { it.id }.toSet()
        selection.fastForEachReversed { id ->
            if (id !in validIds) {
                selection.remove(id)
            }
        }
    }

    // Upload progress dialog
    if (showUploadDialog) {
        DefaultDialog(
            onDismiss = {
                if (isUploading) {
                    uploadJob?.cancel()
                    isUploading = false
                }
                showUploadDialog = false
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.upload),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.uploading)) },
            buttons = {
                TextButton(
                    onClick = {
                        if (isUploading) {
                            uploadJob?.cancel()
                            isUploading = false
                        }
                        showUploadDialog = false
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.upload_progress, currentUploadIndex, totalUploads),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentFileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            item(
                key = "filter",
                contentType = CONTENT_TYPE_HEADER,
            ) {
                if (inSelectMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    ) {
                        IconButton(onClick = onExitSelectionMode) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = null,
                            )
                        }
                        Text(
                            text = pluralStringResource(R.plurals.n_selected, selection.size, selection.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp).weight(1f).align(androidx.compose.ui.Alignment.CenterVertically),
                        )
                        Checkbox(
                            checked = selection.size == filteredSongs.size && selection.isNotEmpty(),
                            onCheckedChange = {
                                if (selection.size == filteredSongs.size) {
                                    selection.clear()
                                } else {
                                    selection.clear()
                                    selection.addAll(filteredSongs.map { it.id })
                                }
                            },
                        )
                        IconButton(
                            enabled = selection.isNotEmpty(),
                            onClick = {
                                menuState.show {
                                    SelectionSongMenu(
                                        songSelection = filteredSongs.filter { it.id in selection },
                                        onDismiss = menuState::dismiss,
                                        clearAction = onExitSelectionMode,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    }
                } else {
                    Row {
                        Spacer(Modifier.width(12.dp))
                        FilterChip(
                            label = { Text(stringResource(R.string.songs)) },
                            selected = true,
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                            onClick = onDeselect,
                            shape = RoundedCornerShape(16.dp),
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = "",
                                )
                            },
                        )
                        ChipsRow(
                            chips =
                                listOf(
                                    SongFilter.LIKED to stringResource(R.string.filter_liked),
                                    SongFilter.LIBRARY to stringResource(R.string.filter_library),
                                    SongFilter.UPLOADED to stringResource(R.string.filter_uploaded),
                                    SongFilter.DOWNLOADED to stringResource(R.string.filter_downloaded),
                                ),
                            currentValue = filter,
                            onValueUpdate = {
                                filter = it
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item(
                key = "header",
                contentType = CONTENT_TYPE_HEADER,
            ) {
                LibrarySearchHeader(
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onBack = {
                        isSearchActive = false
                        viewModel.updateSearchQuery("")
                    },
                    keyboardController = keyboardController,
                    modifier = Modifier.padding(start = 16.dp),
                ) {
                    SortHeader(
                        sortType = sortType,
                        sortDescending = sortDescending,
                        onSortTypeChange = onSortTypeChange,
                        onSortDescendingChange = onSortDescendingChange,
                        sortTypeText = { sortType ->
                            when (sortType) {
                                SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                SongSortType.NAME -> R.string.sort_by_name
                                SongSortType.ARTIST -> R.string.sort_by_artist
                                SongSortType.PLAY_TIME -> R.string.sort_by_play_time
                            }
                        },
                    )

                    Spacer(Modifier.weight(1f))

                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.n_song,
                                filteredSongs.size,
                                filteredSongs.size,
                            ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )

                    IconButton(
                        onClick = { isSearchActive = true },
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp).size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                }
            }

            if (filteredSongs.isEmpty() && searchQuery.isNotBlank()) {
                item(
                    key = "empty_search_result",
                    contentType = CONTENT_TYPE_HEADER,
                ) {
                    LibrarySearchEmptyPlaceholder(modifier = Modifier.animateItem())
                }
            }

            itemsIndexed(
                items = filteredSongs,
                key = { index, item -> "${item.song.id}_$index" },
                contentType = { _, _ -> CONTENT_TYPE_SONG },
            ) { index, song ->
                val onCheckedChange: (Boolean) -> Unit = {
                    if (it) {
                        selection.add(song.id)
                    } else {
                        selection.remove(song.id)
                    }
                }

                SongListItem(
                    song = song,
                    showInLibraryIcon = true,
                    isActive = song.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                    showLikedIcon = true,
                    showDownloadIcon = filter != SongFilter.DOWNLOADED,
                    trailingContent = {
                        if (inSelectMode) {
                            Checkbox(
                                checked = song.id in selection,
                                onCheckedChange = onCheckedChange,
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    menuState.show {
                                        SongMenu(
                                            originalSong = song,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (inSelectMode) {
                                        onCheckedChange(song.id !in selection)
                                    } else if (song.id == mediaMetadata?.id) {
                                        playerConnection.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = queueAllSongsStr,
                                                items = filteredSongs.map { it.toMediaItem() },
                                                startIndex = index,
                                            ),
                                        )
                                    }
                                },
                                onLongClick = {
                                    if (!inSelectMode) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        inSelectMode = true
                                        onCheckedChange(true)
                                    }
                                },
                            ).animateItem(),
                )
            }
        }

        // Show upload FAB when on UPLOADED filter, shuffle FAB otherwise
        HideOnScrollFAB(
            visible = !inSelectMode && (if (filter == SongFilter.UPLOADED) true else filteredSongs.isNotEmpty()),
            lazyListState = lazyListState,
            icon = if (filter == SongFilter.UPLOADED) R.drawable.upload else R.drawable.shuffle,
            onClick = {
                if (filter == SongFilter.UPLOADED) {
                    filePickerLauncher.launch(
                        arrayOf(
                            "audio/mpeg",
                            "audio/mp4",
                            "audio/x-m4a",
                            "audio/flac",
                            "audio/ogg",
                            "audio/x-ms-wma",
                        ),
                    )
                } else {
                    playerConnection.playQueue(
                        ListQueue(
                            title = queueAllSongsStr,
                            items = filteredSongs.shuffled().map { it.toMediaItem() },
                        ),
                    )
                }
            },
        )
    }
}
