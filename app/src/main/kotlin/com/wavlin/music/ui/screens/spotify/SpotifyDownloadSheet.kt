/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.spotify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wavlin.music.R
import com.wavlin.music.spotify.account.SpotifyWebTrack
import com.wavlin.music.spotify.account.artistNames
import com.wavlin.music.spotify.account.thumbnailUrl
import com.wavlin.music.ui.component.IconButton

/** The audio container format the user wants their Spotify download delivered in. */
enum class SpotifyDownloadFormat {
    OPUS,
    MP3,
    FLAC,
}

/** Pick which songs of a Spotify playlist to download. Every song starts selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyDownloadSheet(
    playlistTitle: String,
    owner: String?,
    coverUrl: String?,
    tracks: List<SpotifyWebTrack>,
    onDismiss: () -> Unit,
    onDownload: (List<SpotifyWebTrack>, SpotifyDownloadFormat) -> Unit,
) {
    val selected = remember(tracks) { mutableStateListOf<Int>().apply { addAll(tracks.indices) } }
    val allSelected = selected.size == tracks.size
    var selectedFormat by remember { mutableStateOf(SpotifyDownloadFormat.OPUS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (coverUrl != null) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.spotify_download_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = listOfNotNull(playlistTitle, owner?.takeIf { it.isNotBlank() }).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss, onLongClick = {}) {
                    Icon(painterResource(R.drawable.close), contentDescription = null)
                }
            }

            // ── Audio Format ──────────────────────────────────────────────────
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    stringResource(R.string.spotify_audio_format),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val formats = SpotifyDownloadFormat.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    formats.forEachIndexed { index, format ->
                        SegmentedButton(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
                            label = {
                                Text(
                                    when (format) {
                                        SpotifyDownloadFormat.OPUS -> stringResource(R.string.spotify_format_opus)
                                        SpotifyDownloadFormat.MP3  -> stringResource(R.string.spotify_format_mp3)
                                        SpotifyDownloadFormat.FLAC -> stringResource(R.string.spotify_format_flac)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
                Text(
                    text = when (selectedFormat) {
                        SpotifyDownloadFormat.OPUS -> stringResource(R.string.spotify_format_opus_desc)
                        SpotifyDownloadFormat.MP3  -> stringResource(R.string.spotify_format_mp3_desc)
                        SpotifyDownloadFormat.FLAC -> stringResource(R.string.spotify_format_flac_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            // ── Select Tracks ─────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.spotify_select_tracks), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.spotify_selected_count, selected.size, tracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = {
                        selected.clear()
                        if (!allSelected) selected.addAll(tracks.indices)
                    },
                ) {
                    Text(stringResource(if (allSelected) R.string.spotify_deselect_all else R.string.spotify_select_all))
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(tracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                    val checked = index in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                            .clickable { if (checked) selected.remove(index) else selected.add(index) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { if (it) selected.add(index) else selected.remove(index) })
                        val cover = track.thumbnailUrl()
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            if (cover != null) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artistNames, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Button(
                onClick = { onDownload(selected.sorted().map { tracks[it] }, selectedFormat) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(56.dp),
            ) {
                Icon(painterResource(R.drawable.download), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        R.string.spotify_download_n_format,
                        selected.size,
                        when (selectedFormat) {
                            SpotifyDownloadFormat.OPUS -> stringResource(R.string.spotify_format_opus)
                            SpotifyDownloadFormat.MP3  -> stringResource(R.string.spotify_format_mp3)
                            SpotifyDownloadFormat.FLAC -> stringResource(R.string.spotify_format_flac)
                        },
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
