/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.spotify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.wavlin.music.constants.AppBarHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.Toast
import com.wavlin.music.spotify.account.SpotifyPlayback
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.wavlin.music.playback.ExoDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.spotify.account.thumbnailUrl
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.pinkTopBarColors
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.viewmodels.SpotifyPlaylistState
import com.wavlin.music.viewmodels.SpotifyPlaylistViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistScreen(
    navController: NavController,
    viewModel: SpotifyPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = playlist?.name ?: stringResource(R.string.spotify)

    var showDownloadSheet by remember { mutableStateOf(false) }

    val downloadUtil = com.wavlin.music.LocalDownloadUtil.current
    val downloads by downloadUtil.downloads.collectAsStateWithLifecycle()
    val trackToSong by com.wavlin.music.spotify.SpotifyDownloadStore.trackToSong.collectAsStateWithLifecycle()

    // Finds the YouTube Music match for each picked song, saves it and starts its download
    fun startDownload(picked: List<com.wavlin.music.spotify.account.SpotifyWebTrack>, format: SpotifyDownloadFormat = SpotifyDownloadFormat.OPUS) {
        if (picked.isEmpty()) return
        Toast.makeText(context, context.getString(R.string.spotify_download_preparing), Toast.LENGTH_SHORT).show()
        scope.launch {
            var started = 0
            picked.chunked(5).forEach { chunk ->
                val matches = coroutineScope { chunk.map { async { SpotifyPlayback.resolve(it) } }.awaitAll() }
                chunk.zip(matches).forEach { (spotifyTrack, metadata) ->
                    if (metadata == null) return@forEach
                    withContext(Dispatchers.IO) { database.query { insert(metadata) } }
                    // Stays with this Spotify playlist: kept out of the Downloaded library, shown with a check here
                    com.wavlin.music.spotify.SpotifyDownloadStore.record(context, spotifyTrack.id, metadata.id)
                    // Encode "<title>|<format>" so ExoDownloadService can honour the
                    // chosen container format when transcoding support is added later.
                    val requestData = "${metadata.title}|${format.name}".toByteArray()
                    val request =
                        DownloadRequest
                            .Builder(metadata.id, metadata.id.toUri())
                            .setCustomCacheKey(metadata.id)
                            .setData(requestData)
                            .build()
                    DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
                    started++
                }
            }
            val message =
                when {
                    started == 0 -> context.getString(R.string.spotify_no_match)
                    started < picked.size -> context.getString(R.string.spotify_download_started_partial, started, picked.size - started)
                    else -> context.getString(R.string.spotify_download_started, started)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun play(
        tracks: List<com.wavlin.music.spotify.account.SpotifyWebTrack>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
    ) {
        val connection = playerConnection ?: return
        scope.launch { playSpotifyTracks(context, database, connection, title, tracks, startIndex, shuffle) }
    }

    val listState = rememberLazyListState()
    val scrolledPastHero by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val overlayTop = state is SpotifyPlaylistState.Loaded && !scrolledPastHero

    when (val current = state) {
        SpotifyPlaylistState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        is SpotifyPlaylistState.Failed ->
            SpotifyCenteredMessage(current.message) {
                Button(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
            }

        is SpotifyPlaylistState.Loaded ->
            LazyColumn(
                state = listState,
                contentPadding =
                    run {
                        val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
                        val dir = LocalLayoutDirection.current
                        PaddingValues(
                            start = insets.calculateLeftPadding(dir),
                            end = insets.calculateRightPadding(dir),
                            top = 0.dp,
                            bottom = insets.calculateBottomPadding(),
                        )
                    },
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    val cover = playlist?.thumbnailUrl()
                    val heroBase = MaterialTheme.colorScheme.background
                    val heroText = if (heroBase.luminance() > 0.5f) Color.Black else Color.White
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Hero: full-bleed cover, half the screen, fading into the page with the title on top
                        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                            if (cover != null) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(
                                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(R.drawable.queue_music), contentDescription = null, modifier = Modifier.size(96.dp))
                                }
                            }
                            // Fade the cover into the page so the title stays readable
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.42f to Color.Transparent,
                                            0.72f to heroBase.copy(alpha = 0.7f),
                                            0.92f to heroBase.copy(alpha = 0.96f),
                                            1f to heroBase,
                                        ),
                                    ),
                            )
                            // Keeps the status bar and the floating top buttons legible over bright covers
                            Box(
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + AppBarHeight)
                                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))),
                            )
                            Column(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .padding(bottom = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = heroText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.size(4.dp))
                                val owner = playlist?.owner?.displayName
                                Text(
                                    text =
                                        if (owner.isNullOrBlank()) {
                                            stringResource(R.string.spotify_playlist_subtitle_no_owner, current.tracks.size)
                                        } else {
                                            stringResource(R.string.spotify_playlist_subtitle, owner, current.tracks.size)
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = heroText.copy(alpha = 0.75f),
                                )
                                if (current.isOfflineCopy) {
                                    Text(
                                        stringResource(R.string.spotify_offline_copy),
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center,
                                        color = heroText.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { play(current.tracks) }, modifier = Modifier.weight(1f)) {
                                Icon(painterResource(R.drawable.play), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.play_all))
                            }
                            OutlinedButton(onClick = { play(current.tracks, shuffle = true) }, modifier = Modifier.weight(1f)) {
                                Icon(painterResource(R.drawable.shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.shuffle))
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                }

                if (current.tracks.isEmpty()) {
                    item { SpotifyCenteredMessage(stringResource(R.string.spotify_no_tracks), Modifier.padding(vertical = 32.dp)) }
                }

                itemsIndexed(current.tracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                    SpotifyTrackItem(
                        track = track,
                        downloadState = trackToSong[track.id]?.let { downloads[it]?.state },
                        modifier = Modifier.clickable { play(current.tracks, startIndex = index) },
                    )
                }
            }
    }

    val heroButtonModifier =
        if (overlayTop) Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)) else Modifier
    val heroIconTint = if (overlayTop) Color.White else LocalContentColor.current
    TopAppBar(
        colors = if (overlayTop) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else pinkTopBarColors(),
        title = { Text(if (state is SpotifyPlaylistState.Loaded && !scrolledPastHero) "" else title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
                modifier = heroButtonModifier,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null, tint = heroIconTint)
            }
        },
        actions = {
            val loaded = state as? SpotifyPlaylistState.Loaded
            if (loaded != null && loaded.tracks.isNotEmpty()) {
                IconButton(
                    onClick = { showDownloadSheet = true },
                    onLongClick = {},
                    modifier = heroButtonModifier,
                ) {
                    Icon(painterResource(R.drawable.download), contentDescription = stringResource(R.string.action_download), tint = heroIconTint)
                }
            }
        },
    )

    val loadedForSheet = state as? SpotifyPlaylistState.Loaded
    if (showDownloadSheet && loadedForSheet != null) {
        SpotifyDownloadSheet(
            playlistTitle = title,
            owner = playlist?.owner?.displayName,
            coverUrl = playlist?.thumbnailUrl(),
            tracks = loadedForSheet.tracks,
            onDismiss = { showDownloadSheet = false },
            onDownload = { picked, format ->
                showDownloadSheet = false
                startDownload(picked, format)
            },
        )
    }
}
