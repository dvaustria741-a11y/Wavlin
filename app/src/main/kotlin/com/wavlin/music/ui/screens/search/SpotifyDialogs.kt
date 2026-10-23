package com.wavlin.music.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.spotify.SpotifyResult
import com.wavlin.music.spotify.SpotifyTrack

/** Shows what is inside a Spotify playlist/album before it is imported. */
@Composable
fun SpotifyPlaylistPreviewDialog(
    collection: SpotifyResult.Tracks,
    onDismiss: () -> Unit,
    onImport: (name: String) -> Unit,
) {
    var name by remember(collection) { mutableStateOf(collection.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from Spotify") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${collection.tracks.size} tracks",
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    itemsIndexed(collection.tracks) { index, track ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = "${index + 1}. ${track.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (track.artist.isNotEmpty()) {
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onImport(name.trim())
                    onDismiss()
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows the exact YouTube match for a pasted Spotify track link. */
@Composable
fun SpotifyTrackDialog(
    track: SpotifyTrack,
    match: SongItem,
    onPlay: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spotify song") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = match.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = match.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = match.artists.joinToString(", ") { it.name }.ifEmpty { track.artist },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onPlay()
                    onDismiss()
                },
            ) { Text("Play") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onSearch()
                    onDismiss()
                },
            ) { Text("Search instead") }
        },
    )
}
