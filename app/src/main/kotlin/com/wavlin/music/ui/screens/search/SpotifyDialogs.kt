package com.wavlin.music.ui.screens.search

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogWindowProvider
import com.wavlin.music.ui.component.DialogWindowBlur
import com.wavlin.music.ui.component.glassDialogColor
import com.wavlin.music.ui.component.glassDialogShape
import com.wavlin.music.ui.component.rememberWindowBlurAvailable
import com.wavlin.music.ui.component.glassStrokeBrush
import com.wavlin.music.ui.component.pinkDialogShape
import com.wavlin.music.ui.component.pinkGlassPanel
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
    val covers =
        (0 until total).mapNotNull {
            collection.tracks.getOrNull(it)?.coverUrl
                ?: (matches[it] as? MatchState.Found)?.song?.thumbnail
        }

    val blurAvailable = rememberWindowBlurAvailable()
    val glassShape = glassDialogShape()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogWindowBlur(enabled = !pinkLily && blurAvailable)
        Surface(
            modifier =
                Modifier
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.74f)
                    .let { base ->
                        if (pinkLily) base.pinkGlassPanel(pinkDialogShape()) else base
                    },
            shape = if (pinkLily) pinkDialogShape() else glassShape,
            color = if (pinkLily) Color.Transparent else glassDialogColor(blurAvailable),
            border = if (pinkLily) null else BorderStroke(1.dp, glassStrokeBrush()),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (pinkLily) 26.dp else 16.dp, vertical = if (pinkLily) 28.dp else 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverMosaic(covers = covers)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle =
                                if (pinkLily) {
                                    MaterialTheme.typography.titleMedium.copy(fontFamily = playball, color = PinkInk)
                                } else {
                                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
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
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(4.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(collection.tracks) { index, track ->
                        TrackRow(track = track, state = matches[index], onMissingClick = { onMissingClick(track) })
                    }
                }

                Spacer(Modifier.height(8.dp))
                val doImport = { download: Boolean ->
                    onImport(
                        name.trim(),
                        matches.mapNotNull { (index, state) -> (state as? MatchState.Found)?.let { index to it.song } }.toMap(),
                        download,
                    )
                    onDismiss()
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = { doImport(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import & download", maxLines = 1) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        OutlinedButton(
                            enabled = name.isNotBlank(),
                            onClick = { doImport(false) },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (done < total) "Import ($found)" else "Import", maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverMosaic(covers: List<String>) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier =
            Modifier
                .size(72.dp)
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
                    modifier = Modifier.size(32.dp),
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
        modifier = Modifier.size(36.dp),
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
                            // Spotify's own cover wins; the matched video's art is only a fallback
                            model = track.coverUrl ?: state.song.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                    is MatchState.FallbackFound, MatchState.Missing -> {
                        val cover =
                            track.coverUrl
                                ?: (state as? MatchState.FallbackFound)?.let { "https://i.ytimg.com/vi/${it.videoId}/hqdefault.jpg" }
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.music_note),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

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
    val pinkLily = LocalPinkLilyTheme.current
    val artists = match.artists.joinToString(", ") { it.name }.ifEmpty { track.artist }
    val search = {
        onSearch()
        onDismiss()
    }
    val play = {
        onPlay()
        onDismiss()
    }

    if (pinkLily) {
        DefaultDialog(
            onDismiss = onDismiss,
            buttons = {
                TextButton(onClick = search) { Text("Search instead") }
                TextButton(onClick = play) { Text("Play") }
            },
        ) {
            TrackCardBody(imageUrl = match.thumbnail, title = match.title, artist = artists, artSize = 150.dp)
        }
        return
    }

    val blurAvailable = rememberWindowBlurAvailable()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogWindowBlur(enabled = blurAvailable)
        Surface(
            modifier =
                Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 300.dp)
                    .fillMaxWidth(),
            shape = glassDialogShape(),
            color = glassDialogColor(blurAvailable),
            border = BorderStroke(1.dp, glassStrokeBrush()),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            ) {
                TrackCardBody(imageUrl = match.thumbnail, title = match.title, artist = artists, artSize = 150.dp)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = search) { Text("Search instead", maxLines = 1) }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(onClick = play) { Text("Play") }
                }
            }
        }
    }
}

@Composable
private fun TrackCardBody(imageUrl: String?, title: String, artist: String, artSize: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(artSize).clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
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
