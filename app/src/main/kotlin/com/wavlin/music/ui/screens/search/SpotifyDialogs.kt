package com.wavlin.music.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.R
import com.wavlin.music.constants.ListThumbnailSize
import com.wavlin.music.constants.ThumbnailCornerRadius
import com.wavlin.music.spotify.SpotifyMatcher
import com.wavlin.music.spotify.SpotifyResult
import com.wavlin.music.spotify.SpotifyTrack
import com.wavlin.music.ui.component.DefaultDialog
import com.wavlin.music.ui.component.ListItem
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import com.wavlin.music.ui.component.lilyNinePatch
import com.wavlin.music.ui.theme.PinkInk
import com.wavlin.music.ui.theme.playball
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private sealed interface MatchState {
    data class Found(val song: SongItem) : MatchState

    data class FallbackFound(val videoId: String) : MatchState

    data object Missing : MatchState
}

/**
 * Full preview of a Spotify playlist/album: cover mosaic, editable name, and every track with its
 * YouTube cover art as it is matched. Matching starts immediately; Import uses what has matched so far.
 */
@Composable
fun SpotifyPlaylistPreviewDialog(
    collection: SpotifyResult.Tracks,
    onDismiss: () -> Unit,
    onImport: (name: String, matches: Map<Int, SongItem>, download: Boolean) -> Unit,
    onMissingClick: (SpotifyTrack) -> Unit = {},
) {
    val pinkLily = LocalPinkLilyTheme.current
    var name by remember(collection) { mutableStateOf(collection.title) }
    val matches = remember(collection) { mutableStateMapOf<Int, MatchState>() }
    val total = collection.tracks.size

    LaunchedEffect(collection) {
        val semaphore = Semaphore(5)
        collection.tracks.forEachIndexed { index, track ->
            launch {
                semaphore.withPermit {
                    val song =
                        try {
                            withContext(Dispatchers.IO) { SpotifyMatcher.match(track) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    if (song != null) {
                        matches[index] = MatchState.Found(song)
                    } else {
                        val query = "${track.title} ${track.artist}".replace(Regex("[!,]"), "").trim()
                        val videoId =
                            try {
                                withContext(Dispatchers.IO) { YouTube.searchFirstVideoId(query).getOrNull() }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                        matches[index] = if (videoId != null) MatchState.FallbackFound(videoId) else MatchState.Missing
                    }
                }
            }
        }
    }

    val done = matches.size
    val found = matches.values.count { it is MatchState.Found || it is MatchState.FallbackFound }
    val covers = (0 until total).mapNotNull { (matches[it] as? MatchState.Found)?.song?.thumbnail }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(horizontal = 12.dp)
                    .let { base ->
                        if (pinkLily) base.lilyNinePatch(res = R.drawable.lily_panel, srcCap = 150, dstCap = 46.dp) else base
                    },
            shape = if (pinkLily) MaterialTheme.shapes.large else RoundedCornerShape(28.dp),
            color = if (pinkLily) Color.Transparent else AlertDialogDefaults.containerColor,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (pinkLily) 30.dp else 16.dp, vertical = if (pinkLily) 34.dp else 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverMosaic(covers = covers)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle =
                                if (pinkLily) {
                                    MaterialTheme.typography.titleLarge.copy(fontFamily = playball, color = PinkInk)
                                } else {
                                    MaterialTheme.typography.titleLarge
                                },
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "$total tracks \u2022 $found matched",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }

                if (done < total) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    )
                } else {
                    Spacer(Modifier.height(18.dp))
                }
                Spacer(Modifier.height(6.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(collection.tracks) { index, track ->
                        TrackRow(track = track, state = matches[index], onMissingClick = { onMissingClick(track) })
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(4.dp))
                    val doImport = { download: Boolean ->
                        onImport(
                            name.trim(),
                            matches.mapNotNull { (index, state) -> (state as? MatchState.Found)?.let { index to it.song } }.toMap(),
                            download,
                        )
                        onDismiss()
                    }
                    OutlinedButton(
                        enabled = name.isNotBlank(),
                        onClick = { doImport(false) },
                    ) { Text(if (done < total) "Import ($found)" else "Import", maxLines = 1) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = { doImport(true) },
                    ) { Text("Import & download", maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun CoverMosaic(covers: List<String>) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier =
            Modifier
                .size(96.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            covers.size >= 4 ->
                Column {
                    Row {
                        CoverTile(covers[0])
                        CoverTile(covers[1])
                    }
                    Row {
                        CoverTile(covers[2])
                        CoverTile(covers[3])
                    }
                }

            covers.isNotEmpty() ->
                AsyncImage(
                    model = covers.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

            else ->
                Icon(
                    painter = painterResource(R.drawable.queue_music),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
        }
    }
}

@Composable
private fun CoverTile(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(48.dp),
    )
}

@Composable
private fun TrackRow(track: SpotifyTrack, state: MatchState?, onMissingClick: () -> Unit) {
    val missing = state is MatchState.Missing
    ListItem(
        title = track.title,
        subtitle = if (missing) "Not found on YouTube" else track.artist,
        modifier =
            Modifier
                .alpha(if (missing) 0.55f else 1f)
                .let { if (missing) it.clickable(onClick = onMissingClick) else it },
        thumbnailContent = {
            Box(
                modifier =
                    Modifier
                        .size(ListThumbnailSize)
                        .clip(RoundedCornerShape(ThumbnailCornerRadius))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    is MatchState.Found ->
                        AsyncImage(
                            model = state.song.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                    is MatchState.FallbackFound, MatchState.Missing ->
                        Icon(
                            painter = painterResource(R.drawable.music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    null -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        },
        trailingContent = {
            if (state is MatchState.Found || state is MatchState.FallbackFound) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/** Shows the exact YouTube match for a pasted Spotify track link, art first. */
@Composable
fun SpotifyTrackDialog(
    track: SpotifyTrack,
    match: SongItem,
    onPlay: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(
                onClick = {
                    onSearch()
                    onDismiss()
                },
            ) { Text("Search instead") }
            TextButton(
                onClick = {
                    onPlay()
                    onDismiss()
                },
            ) { Text("Play") }
        },
    ) {
        AsyncImage(
            model = match.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = match.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = match.artists.joinToString(", ") { it.name }.ifEmpty { track.artist },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shown for a Spotify track that has no YouTube match. The track is still displayed from the link's
 * own metadata (cover, title, artist) so it isn't a dead end; the person can try a manual search or
 * open it in Spotify.
 */
@Composable
fun SpotifyUnmatchedDialog(
    track: SpotifyTrack,
    onSearch: () -> Unit,
    onOpenSpotify: () -> Unit,
    onDownload: () -> Unit,
    isDownloading: Boolean = false,
    onDismiss: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            if (track.url != null) {
                TextButton(
                    onClick = {
                        onOpenSpotify()
                        onDismiss()
                    },
                ) { Text("Spotify") }
            }
            TextButton(
                enabled = !isDownloading,
                onClick = { onDownload() },
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = androidx.compose.ui.Modifier.size(16.dp),
                    )
                } else {
                    Text("Download")
                }
            }
            TextButton(
                onClick = {
                    onSearch()
                    onDismiss()
                },
            ) { Text("Search") }
        },
    ) {
        Box(
            modifier =
                Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (track.coverUrl != null) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Not found on YouTube Music",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
