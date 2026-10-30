/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.db.entities.PlaylistSong
import com.wavlin.music.db.entities.Song
import com.wavlin.music.extensions.move
import com.wavlin.music.ui.component.ItemThumbnail
import com.wavlin.music.ui.component.LocalInterfaceAppleMusic
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import com.wavlin.music.ui.component.artworkShape
import com.wavlin.music.ui.player.queueGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** One row of the edit page: [key] identifies it, [song] is what gets shown. */
@Immutable
data class EditPlaylistEntry(
    val key: String,
    val song: Song,
)

/** Edit page for a local playlist: songs can be removed and dragged into a new order. */
@Composable
fun PlaylistEditPage(
    songs: List<PlaylistSong>,
    pureBlack: Boolean,
    onDismiss: () -> Unit,
    onSave: (order: List<PlaylistSong>, removed: List<PlaylistSong>) -> Unit,
) {
    val byKey = remember(songs) { songs.associateBy { it.map.id.toString() } }
    val entries =
        remember(songs) {
            songs.sortedBy { it.map.position }.map { EditPlaylistEntry(it.map.id.toString(), it.song) }
        }
    SongListEditPage(
        entries = entries,
        pureBlack = pureBlack,
        allowReorder = true,
        onDismiss = onDismiss,
        onSave = { order, removed ->
            onSave(
                order.mapNotNull { byKey[it.key] },
                removed.mapNotNull { byKey[it.key] },
            )
        },
    )
}

/**
 * Spotify-style "Edit playlist" page. It works on a copy of the list, so nothing is applied until Save:
 * songs can be removed (minus button, or Select for several at once) and, when [allowReorder] is true,
 * dragged into a new order. Lists that are sorted by the app (Liked, Downloaded, Cached) pass false.
 *
 * [onSave] receives the final order of the kept songs and the songs that were removed.
 */
@Composable
fun SongListEditPage(
    entries: List<EditPlaylistEntry>,
    pureBlack: Boolean,
    allowReorder: Boolean,
    onDismiss: () -> Unit,
    onSave: (order: List<EditPlaylistEntry>, removed: List<EditPlaylistEntry>) -> Unit,
) {
    val original = remember { entries.toList() }
    val editItems = remember { mutableStateListOf<EditPlaylistEntry>().apply { addAll(original) } }
    val selection = remember { mutableStateListOf<String>() }
    var selecting by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val changed = editItems.map { it.key } != original.map { it.key }
    val haze = remember { HazeState() }
    val lazyListState = rememberLazyListState()
    val reorderState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            editItems.move(from.index, to.index)
        }

    val exitSelecting = {
        selecting = false
        selection.clear()
    }

    BackHandler {
        when {
            selecting -> exitSelecting()
            changed -> confirmDiscard = true
            else -> onDismiss()
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.playlist_edit_discard_title)) },
            text = { Text(stringResource(R.string.playlist_edit_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.playlist_edit_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.playlist_edit_keep_editing))
                }
            },
        )
    }

    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (LocalPinkLilyTheme.current) {
                        // Secret Pink draws its gingham wallpaper instead of a flat fill.
                        Modifier.paint(
                            painter = painterResource(R.drawable.bg_pink_gingham),
                            sizeToIntrinsics = false,
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Modifier.background(if (pureBlack) Color.Black else colors.background)
                    },
                ).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { },
    ) {
        val bottomInset = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
        val bottomPadding = with(LocalDensity.current) { bottomInset.getBottom(this).toDp() }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 4.dp),
            ) {
                IconButton(
                    onClick = {
                        when {
                            selecting -> exitSelecting()
                            changed -> confirmDiscard = true
                            else -> onDismiss()
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(if (selecting) R.drawable.close else R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
                Text(
                    text =
                        if (selecting) {
                            pluralStringResource(R.plurals.n_selected, selection.size, selection.size)
                        } else {
                            stringResource(R.string.edit_playlist)
                        },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (!selecting) {
                    TextButton(
                        enabled = changed,
                        onClick = {
                            onSave(
                                editItems.toList(),
                                original.filter { o -> editItems.none { it.key == o.key } },
                            )
                            onDismiss()
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.save),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }

            LazyColumn(
                state = lazyListState,
                contentPadding =
                    PaddingValues(
                        top = 4.dp,
                        bottom = 96.dp + bottomPadding,
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .hazeSource(haze),
            ) {
                items(items = editItems, key = { it.key }) { song ->
                    ReorderableItem(state = reorderState, key = song.key) { isDragging ->
                        val checked = song.key in selection
                        EditSongRow(
                            song = song,
                            allowReorder = allowReorder,
                            selecting = selecting,
                            checked = checked,
                            dragging = isDragging,
                            onRemove = { editItems.remove(song) },
                            onToggle = {
                                if (checked) selection.remove(song.key) else selection.add(song.key)
                            },
                            handleModifier = Modifier.draggableHandle(enabled = allowReorder && !selecting),
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        val glassShape = CircleShape
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(bottomInset)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .queueGlass(haze, glassShape, pureBlack),
        ) {
            if (!selecting) {
                PillAction(
                    text = stringResource(R.string.playlist_edit_select),
                    enabled = editItems.isNotEmpty(),
                    onClick = { selecting = true },
                    modifier = Modifier.weight(1f),
                )
            } else {
                PillAction(
                    text = stringResource(R.string.cancel),
                    enabled = true,
                    onClick = exitSelecting,
                    modifier = Modifier.weight(1f),
                )
                PillAction(
                    text = stringResource(R.string.playlist_edit_remove_n, selection.size),
                    enabled = selection.isNotEmpty(),
                    emphasized = true,
                    onClick = {
                        editItems.removeAll { it.key in selection }
                        exitSelecting()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PillAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f),
            maxLines = 1,
        )
    }
}

@Composable
private fun EditSongRow(
    song: EditPlaylistEntry,
    allowReorder: Boolean,
    selecting: Boolean,
    checked: Boolean,
    dragging: Boolean,
    onRemove: () -> Unit,
    onToggle: () -> Unit,
    handleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val apple = LocalInterfaceAppleMusic.current
    val shape = RoundedCornerShape(if (apple) 14.dp else 20.dp)
    val background by animateColorAsState(
        targetValue =
            when {
                dragging -> colors.onSurface.copy(alpha = 0.14f)
                checked -> colors.primary.copy(alpha = 0.16f)
                else -> Color.Transparent
            },
        animationSpec = tween(160),
        label = "editRowBackground",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .clip(shape)
                .background(background)
                .then(if (selecting) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onRemove),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(26.dp)
                            .border(2.dp, colors.onSurfaceVariant, CircleShape),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.remove),
                        contentDescription = stringResource(R.string.remove_from_playlist),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        ItemThumbnail(
            thumbnailUrl = song.song.song.thumbnailUrl,
            albumIndex = null,
            isActive = false,
            isPlaying = false,
            shape = artworkShape(),
            modifier = Modifier.padding(start = 4.dp).size(52.dp),
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
        ) {
            Text(
                text = song.song.song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (allowReorder) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).then(handleModifier),
            ) {
                Icon(
                    painter = painterResource(R.drawable.drag_handle),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = if (selecting) 0.3f else 1f),
                )
            }
        } else {
            Spacer(Modifier.size(12.dp))
        }
    }
}
