/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.menu

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wavlin.music.ui.component.DialogWindowBlur
import com.wavlin.music.ui.component.LocalInterfaceAppleMusic
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import com.wavlin.music.ui.component.glassDialogColor
import com.wavlin.music.ui.component.glassStrokeBrush
import com.wavlin.music.ui.component.pinkDialogShape
import com.wavlin.music.ui.component.pinkGlassPanel
import com.wavlin.music.ui.component.rememberWindowBlurAvailable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

    val apple = LocalInterfaceAppleMusic.current
    val pinkLily = LocalPinkLilyTheme.current
    val canSave = downloadToLibrary || selectedPlaylistIds.isNotEmpty()

    fun save() {
        if (!canSave) return
        if (!downloadToLibrary) {
            // Saved to a playlist only - still cache the audio for offline playback, but don't count it
            // as an explicit "Download Library" download (that's reserved for the toggle above).
            viewModel.markSilentDownloads(songs.map { it.id })
        }
        startDownloads(context)
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
    }

    val blurAvailable = rememberWindowBlurAvailable()
    val panelShape = if (pinkLily) pinkDialogShape() else RoundedCornerShape(if (apple) 32.dp else 28.dp)
    // Rows sit on a soft inset "group" tint, the way Apple Music groups its lists.
    val groupColor =
        if (pinkLily) {
            Color.White.copy(alpha = 0.14f)
        } else if (apple) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogWindowBlur(enabled = !pinkLily && apple && blurAvailable)
        Surface(
            modifier =
                Modifier
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .heightIn(max = screenHeight * 0.82f)
                    .let { base -> if (pinkLily) base.pinkGlassPanel(panelShape) else base },
            shape = panelShape,
            color =
                when {
                    pinkLily -> Color.Transparent
                    apple -> glassDialogColor(blurAvailable)
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
            border = if (pinkLily || !apple) null else BorderStroke(1.dp, glassStrokeBrush()),
            tonalElevation = if (apple || pinkLily) 0.dp else 3.dp,
        ) {
            Column(modifier = Modifier.padding(top = 22.dp, bottom = 16.dp)) {
                // Fixed header
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    item {
                        DestinationRow(
                            shape = RoundedCornerShape(20.dp),
                            color = groupColor,
                            onClick = { downloadToLibrary = !downloadToLibrary },
                            leading = {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(if (apple) 12.dp else 14.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.download),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            title = stringResource(R.string.download_library),
                            trailing = { SelectionIndicator(downloadToLibrary, apple || pinkLily) },
                        )
                    }

                    item {
                        Text(
                            text = stringResource(R.string.playlists),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
                        )
                    }

                    item {
                        DestinationRow(
                            shape = groupShape(index = 0, count = playlists.size + 1),
                            color = groupColor,
                            onClick = { showCreatePlaylistDialog = true },
                            leading = {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(if (apple) 8.dp else 12.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.add),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            title = stringResource(R.string.create_playlist),
                            titleColor = MaterialTheme.colorScheme.primary,
                        )
                    }

                    itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                        val checked = playlist.id in selectedPlaylistIds
                        val toggle = {
                            if (checked) selectedPlaylistIds.remove(playlist.id) else selectedPlaylistIds.add(playlist.id)
                            Unit
                        }
                        val shape = groupShape(index = index + 1, count = playlists.size + 1)
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            )
                            PlaylistListItem(
                                playlist = playlist,
                                trailingContent = { SelectionIndicator(checked, apple || pinkLily) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .background(
                                            if (checked) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                            } else {
                                                groupColor
                                            },
                                            shape,
                                        ).clickable(onClick = toggle),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Fixed footer: always reachable, no matter how long the playlist list is.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(50),
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = groupColor,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        Text(
                            text = stringResource(android.R.string.cancel),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(
                        onClick = { save() },
                        enabled = canSave,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.save),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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

/** Rounded corners only on the outer edges of a grouped list, flat in between. */
private fun groupShape(
    index: Int,
    count: Int,
): Shape {
    val outer = 20.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else 0.dp,
        topEnd = if (index == 0) outer else 0.dp,
        bottomStart = if (index == count - 1) outer else 0.dp,
        bottomEnd = if (index == count - 1) outer else 0.dp,
    )
}

@Composable
private fun DestinationRow(
    shape: Shape,
    color: Color,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    titleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color, shape)
                .clickable(onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        leading()
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
        )
        trailing?.invoke()
    }
}

/** Apple-style round tick when [round] is set, otherwise the regular Material checkbox. */
@Composable
private fun SelectionIndicator(
    checked: Boolean,
    round: Boolean,
) {
    if (!round) {
        Checkbox(checked = checked, onCheckedChange = null)
        return
    }
    val fill by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "selectionFill",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .padding(end = 4.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = 1.5.dp,
                    color = if (checked) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    shape = CircleShape,
                ),
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
