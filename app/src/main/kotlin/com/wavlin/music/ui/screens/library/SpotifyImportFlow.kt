/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.library

import androidx.compose.foundation.Image
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wavlin.innertube.models.SongItem
import com.wavlin.innertube.models.WatchEndpoint
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalNavController
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.playback.queues.YouTubeQueue
import com.wavlin.music.spotify.SpotifyImporter
import com.wavlin.music.spotify.SpotifyMatcher
import com.wavlin.music.spotify.SpotifyProvider
import com.wavlin.music.spotify.SpotifyResult
import com.wavlin.music.spotify.SpotifyTrack
import com.wavlin.music.ui.component.GlassDialog
import com.wavlin.music.ui.component.GlassIconButton
import com.wavlin.music.ui.screens.search.SpotifyPlaylistPreviewDialog
import com.wavlin.music.ui.screens.search.SpotifyTrackDialog
import com.wavlin.music.utils.SearchRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The floating "import from Spotify" button that sits right above the create-playlist + button. */
@Composable
fun BoxScope.SpotifyImportButton(onClick: () -> Unit) {
    GlassIconButton(
        onClick = onClick,
        size = 56.dp,
        contentDescription = stringResource(R.string.spotify_flow_title),
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 84.dp),
    ) {
        // Original import artwork, drawn as a tinted Icon so it follows the theme like the + button.
        Icon(
            painter = painterResource(R.drawable.ic_spotify_import),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
    }
}

/**
 * Same import as pasting a Spotify link in Search: paste a playlist, album or track link, match it to
 * YouTube Music, then preview and import (playlist/album) or play (single track).
 */
@Composable
fun SpotifyImportFlow(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val provider = remember { SpotifyProvider() }

    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var collection by remember { mutableStateOf<SpotifyResult.Tracks?>(null) }
    var trackMatch by remember { mutableStateOf<Pair<SpotifyTrack, SongItem>?>(null) }

    val notFoundMsg = stringResource(R.string.spotify_flow_not_found)
    val errorMsg = stringResource(R.string.spotify_flow_error)

    val pendingCollection = collection
    val pendingTrack = trackMatch
    when {
        pendingCollection != null ->
            SpotifyPlaylistPreviewDialog(
                collection = pendingCollection,
                onDismiss = onDismiss,
                onImport = { finalName, matched, download ->
                    SpotifyImporter.start(context, database, finalName, pendingCollection.tracks, matched, download)
                },
            )

        pendingTrack != null ->
            SpotifyTrackDialog(
                track = pendingTrack.first,
                match = pendingTrack.second,
                onPlay = {
                    playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = pendingTrack.second.id)))
                },
                onSearch = { navController.navigate(SearchRoutes.resultRoute(pendingTrack.first.query)) },
                onDismiss = onDismiss,
            )

        else -> {
            val valid = SpotifyProvider.isSpotifyUrl(link.trim())
            val start = {
                if (valid && !loading) {
                    loading = true
                    scope.launch {
                        try {
                            when (val result = withContext(Dispatchers.IO) { provider.resolve(link.trim()) }) {
                                is SpotifyResult.Tracks -> collection = result
                                is SpotifyResult.Track -> {
                                    val match = withContext(Dispatchers.IO) { SpotifyMatcher.match(result.track) }
                                    if (match != null) {
                                        trackMatch = result.track to match
                                    } else {
                                        Toast.makeText(context, notFoundMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: errorMsg, Toast.LENGTH_LONG).show()
                        } finally {
                            loading = false
                        }
                    }
                }
            }

            GlassDialog(onDismiss = onDismiss, maxWidth = 340.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1DB954).copy(alpha = 0.18f)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_spotify_logo),
                            contentDescription = null,
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.spotify_flow_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.spotify_flow_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                TextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.spotify_flow_field)) },
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        TextButton(onClick = { clipboard.getText()?.text?.let { link = it } }) {
                            Text(stringResource(R.string.spotify_flow_paste))
                        }
                    },
                    colors =
                        TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                        ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { start() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(enabled = valid && !loading, onClick = { start() }) {
                        if (loading) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Text(stringResource(R.string.spotify_flow_go))
                        }
                    }
                }
            }
        }
    }
}
