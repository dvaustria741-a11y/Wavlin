/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.search

import androidx.compose.foundation.layout.Box
import com.wavlin.music.ui.theme.playball
import com.wavlin.music.ui.theme.PinkInk
import com.wavlin.music.ui.component.LilyDiscBox
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import com.wavlin.music.LocalNavController
import com.wavlin.innertube.models.WatchEndpoint
import com.wavlin.innertube.utils.YouTubeUrlParser
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalIsPlayerExpanded
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.constants.PauseSearchHistoryKey
import com.wavlin.music.constants.SearchSource
import com.wavlin.music.constants.SearchSourceKey
import com.wavlin.music.db.entities.SearchHistory
import com.wavlin.music.playback.queues.YouTubeQueue
import com.wavlin.music.ui.component.HideOnScrollFAB
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.spotify.SpotifyImporter
import com.wavlin.music.spotify.SpotifyMatcher
import com.wavlin.music.local.LocalSongs
import com.wavlin.music.models.MediaMetadata
import com.wavlin.music.playback.queues.ListQueue
import com.wavlin.music.ui.menu.AddToPlaylistDialog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import android.net.Uri
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.spotify.SpotifyTrack
import com.wavlin.music.spotify.SpotifyProvider
import com.wavlin.music.spotify.SpotifyResult
import com.wavlin.music.utils.SearchRoutes
import com.wavlin.music.utils.rememberEnumPreference
import com.wavlin.music.utils.rememberPreference
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    pureBlack: Boolean,
    savedStateHandle: SavedStateHandle,
) {
    val navController = LocalNavController.current
    val pinkLily = LocalPinkLilyTheme.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val spotifyProvider = remember { SpotifyProvider() }
    var spotifyCollection by remember { mutableStateOf<SpotifyResult.Tracks?>(null) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var spotifyTrackMatch by remember { mutableStateOf<Pair<SpotifyTrack, SongItem>?>(null) }
    var spotifyUnmatched by remember { mutableStateOf<SpotifyTrack?>(null) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val playerConnection = LocalPlayerConnection.current
    var isDownloadingSpotify by remember { mutableStateOf(false) }
    var downloadedMetadata by remember { mutableStateOf<MediaMetadata?>(null) }
    val isPlayerExpanded = LocalIsPlayerExpanded.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lazyListState = rememberLazyListState()
    var isHandlingScrollToTop by remember { mutableStateOf(false) }

    val scrollToTopCount by savedStateHandle.getStateFlow("scrollToTopCount", 0).collectAsStateWithLifecycle(initialValue = 0)

    var lastHandledCount by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (!isPlayerExpanded) {
            kotlinx.coroutines.delay(100)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (e: Exception) {
            }
        }
    }
    LaunchedEffect(scrollToTopCount) {
        if (scrollToTopCount > lastHandledCount) {
            lastHandledCount = scrollToTopCount
            isHandlingScrollToTop = true

            kotlinx.coroutines.delay(100)

            if (!isPlayerExpanded) {
                focusManager.clearFocus(force = true)
                kotlinx.coroutines.delay(50)
                try {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                } catch (e: Exception) {
                }
            }

            kotlinx.coroutines.delay(500)
            isHandlingScrollToTop = false
        }
    }

    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)

    fun handleSearch(searchQuery: String) {
        if (searchQuery.isEmpty()) {
            return
        }

        focusManager.clearFocus()

        if (SpotifyProvider.isSpotifyUrl(searchQuery)) {
            coroutineScope.launch {
                try {
                    Toast.makeText(context, "Reading Spotify link...", Toast.LENGTH_SHORT).show()
                    when (val result = withContext(Dispatchers.IO) { spotifyProvider.resolve(searchQuery) }) {
                        is SpotifyResult.Track -> {
                            val match = withContext(Dispatchers.IO) { SpotifyMatcher.match(result.track) }
                            if (match != null) {
                                spotifyTrackMatch = result.track to match
                            } else {
                                spotifyUnmatched = result.track
                            }
                        }

                        is SpotifyResult.Tracks -> {
                            spotifyCollection = result
                            showSpotifyImport = true
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Couldn't read Spotify link", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        when (val parsedUrl = YouTubeUrlParser.parse(searchQuery)) {
            is YouTubeUrlParser.ParsedUrl.Video -> {
                playerConnection?.playQueue(
                    YouTubeQueue(
                        WatchEndpoint(videoId = parsedUrl.id),
                    ),
                )
            }

            is YouTubeUrlParser.ParsedUrl.Playlist -> {
                navController.navigate("online_playlist/${parsedUrl.id}")
            }

            is YouTubeUrlParser.ParsedUrl.Album -> {
                navController.navigate("album/MPREb_${parsedUrl.id}")
            }

            is YouTubeUrlParser.ParsedUrl.Artist -> {
                navController.navigate("artist/${parsedUrl.id}")
            }

            null -> {
                navController.navigate(SearchRoutes.resultRoute(searchQuery))
            }
        }

        if (!pauseSearchHistory) {
            coroutineScope.launch(Dispatchers.IO) {
                database.query {
                    insert(SearchHistory(query = searchQuery))
                }
            }
        }
    }

    val pendingSpotify = spotifyCollection
    if (showSpotifyImport && pendingSpotify != null) {
        SpotifyPlaylistPreviewDialog(
            collection = pendingSpotify,
            onDismiss = { showSpotifyImport = false },
            onImport = { finalName, matched, download ->
                SpotifyImporter.start(context, database, finalName, pendingSpotify.tracks, matched, download)
            },
            onMissingClick = { spotifyUnmatched = it },
        )
    }

    spotifyTrackMatch?.let { (spotifyTrack, matched) ->
        SpotifyTrackDialog(
            track = spotifyTrack,
            match = matched,
            onPlay = {
                playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = matched.id)))
            },
            onSearch = { navController.navigate(SearchRoutes.resultRoute(spotifyTrack.query)) },
            onDismiss = { spotifyTrackMatch = null },
        )
    }

    spotifyUnmatched?.let { track ->
        SpotifyUnmatchedDialog(
            track = track,
            onSearch = {
                showSpotifyImport = false
                navController.navigate(SearchRoutes.resultRoute(track.query))
            },
            onOpenSpotify = {
                track.url?.let { link ->
                    try {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "No app to open the link", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDownload = {
                if (isDownloadingSpotify) return@SpotifyUnmatchedDialog
                isDownloadingSpotify = true
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        // SpotifyMatcher already searched YTM with scoring and failed,
                        // so skip straight to the spotdl/Cobalt download path.
                        // Step 1: YouTube web search + Cobalt audio download
                        val httpClient = HttpClient(CIO) {
                            install(HttpTimeout) {
                                requestTimeoutMillis = 30_000
                                connectTimeoutMillis = 10_000
                            }
                            install(ContentNegotiation) {
                                json(Json { ignoreUnknownKeys = true })
                            }
                        }

                        // 1a. Search YouTube for the exact title + artist (quoted for precision)
                        val searchQuery = java.net.URLEncoder.encode(
                            "\"${track.title}\" \"${track.artist}\"", "UTF-8",
                        )
                        val searchHtml = httpClient.get(
                            "https://www.youtube.com/results?search_query=$searchQuery",
                        ) {
                            header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36")
                        }.bodyAsText()

                        val videoId = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                            .find(searchHtml)?.groupValues?.get(1)

                        if (videoId == null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "\"${track.title}\" not found anywhere", Toast.LENGTH_LONG).show()
                                isDownloadingSpotify = false
                            }
                            httpClient.close()
                            return@launch
                        }

                        // 1b. Cobalt API: get audio download URL for the found video
                        val cobaltRaw = httpClient.post("https://api.cobalt.tools/") {
                            contentType(ContentType.Application.Json)
                            header("Accept", "application/json")
                            setBody(
                                """{"url":"https://www.youtube.com/watch?v=$videoId","downloadMode":"audio","audioFormat":"best","filenameStyle":"basic"}""",
                            )
                        }.bodyAsText()

                        val cobaltObj = Json { ignoreUnknownKeys = true }.parseToJsonElement(cobaltRaw).jsonObject
                        val cobaltStatus = cobaltObj["status"]?.jsonPrimitive?.contentOrNull
                        val cobaltUrl   = cobaltObj["url"]?.jsonPrimitive?.contentOrNull

                        if (cobaltUrl == null || cobaltStatus !in listOf("stream", "redirect", "tunnel")) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Download unavailable for \"${track.title}\"", Toast.LENGTH_LONG).show()
                                isDownloadingSpotify = false
                            }
                            httpClient.close()
                            return@launch
                        }

                        // 1c. Download the audio bytes
                        val audioBytes = httpClient.get(cobaltUrl) {
                            header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                        }.readBytes()
                        httpClient.close()

                        // 1d. Save to disk and register as local song
                        val safeName = "${track.artist} - ${track.title}"
                            .replace(Regex("""[/\\:*?"<>|]"""), "_")
                        val musicDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                            ?: context.filesDir
                        val audioFile = File(musicDir, "$safeName.opus")
                        audioFile.writeBytes(audioBytes)

                        val added = LocalSongs.add(
                            context, database, Uri.fromFile(audioFile),
                            track.title, track.artist, track.coverUrl,
                        )

                        withContext(Dispatchers.Main) {
                            if (added != null) {
                                playerConnection?.playQueue(
                                    ListQueue(title = added.title, items = listOf(added.toMediaItem())),
                                )
                                downloadedMetadata = added
                                spotifyUnmatched = null
                            } else {
                                Toast.makeText(context, "Saved but couldn't read the file", Toast.LENGTH_SHORT).show()
                            }
                            isDownloadingSpotify = false
                        }
                    } catch (e: CancellationException) {
                        withContext(Dispatchers.Main) { isDownloadingSpotify = false }
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            isDownloadingSpotify = false
                        }
                    }
                }
            },
            isDownloading = isDownloadingSpotify,
            onDismiss = {
                spotifyUnmatched = null
                isDownloadingSpotify = false
            },
        )
    }

    // Playlist picker shown after a successful spotdl/Cobalt download
    downloadedMetadata?.let { meta ->
        AddToPlaylistDialog(
            isVisible = true,
            allowSyncing = false,
            onGetSong = { _ -> listOf(meta.id) },
            onGetSongIds = { listOf(meta.id) },
            onDismiss = { downloadedMetadata = null },
        )
    }

    val onSearch: (String) -> Unit = { searchQuery -> handleSearch(searchQuery) }

    val onSearchFromSuggestion: (String) -> Unit = { searchQuery -> handleSearch(searchQuery) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .let { base ->
                                    if (pinkLily) {
                                        // Secret Pink: frosted glass search pill
                                        base
                                            .background(Color.White.copy(alpha = 0.55f), CircleShape)
                                            .border(
                                                1.5.dp,
                                                Brush.linearGradient(listOf(Color.White, Color(0xFFF4B6CB))),
                                                CircleShape,
                                            ).padding(start = 18.dp)
                                    } else {
                                        base
                                    }
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                            textStyle =
                                TextStyle(
                                    color = if (pinkLily) PinkInk else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                ),
                            cursorBrush = SolidColor(if (pinkLily) PinkInk else MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (query.text.isEmpty()) {
                                    Text(
                                        text =
                                            stringResource(
                                                when (searchSource) {
                                                    SearchSource.LOCAL -> R.string.search_library
                                                    SearchSource.ONLINE -> R.string.search_yt_music
                                                },
                                            ),
                                        style =
                                            if (pinkLily) {
                                                TextStyle(
                                                    color = PinkInk.copy(alpha = 0.7f),
                                                    fontFamily = playball,
                                                    fontSize = 20.sp,
                                                )
                                            } else {
                                                TextStyle(
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                    fontSize = 16.sp,
                                                )
                                            },
                                    )
                                }
                                innerTextField()
                            },
                            keyboardOptions =
                                KeyboardOptions(
                                    imeAction = ImeAction.Search,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onSearch = { onSearch(query.text) },
                                ),
                        )

                        Row {
                            if (query.text.isNotEmpty()) {
                                IconButton(onClick = { query = TextFieldValue("") }) {
                                    Icon(
                                        painter = painterResource(R.drawable.close),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    searchSource =
                                        if (searchSource == SearchSource.ONLINE) {
                                            SearchSource.LOCAL
                                        } else {
                                            SearchSource.ONLINE
                                        }
                                },
                            ) {
                                LilyDiscBox(pinkLily) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                when (searchSource) {
                                                    SearchSource.LOCAL -> R.drawable.library_music
                                                    SearchSource.ONLINE -> R.drawable.language
                                                },
                                            ),
                                        contentDescription = null,
                                        tint = if (pinkLily) PinkInk else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        LilyDiscBox(pinkLily) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back),
                                contentDescription = stringResource(R.string.dismiss),
                                tint = if (pinkLily) PinkInk else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            if (pureBlack) {
                                Color.Black
                            } else if (pinkLily) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                    ),
            )
        },
        containerColor =
            if (pureBlack) {
                Color.Black
            } else if (pinkLily) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.background
            },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .padding(top = paddingValues.calculateTopPadding())
                    .fillMaxSize(),
        ) {
            when (searchSource) {
                SearchSource.LOCAL -> {
                    LocalSearchScreen(
                        query = query.text,
                        onDismiss = { navController.navigateUp() },
                        pureBlack = pureBlack,
                    )
                }

                SearchSource.ONLINE -> {
                    OnlineSearchScreen(
                        query = query.text,
                        onQueryChange = { query = it },
                        onSearch = onSearchFromSuggestion,
                        onDismiss = { /* Don't dismiss when searching from suggestions */ },
                        pureBlack = pureBlack,
                    )
                }
            }

            HideOnScrollFAB(
                lazyListState = lazyListState,
                icon = R.drawable.mic,
                onClick = { navController.navigate("recognition") },
            )
        }
    }

    // Handle lifecycle events to manage keyboard visibility
    DisposableEffect(lifecycleOwner, isPlayerExpanded) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        if (isHandlingScrollToTop) return@LifecycleEventObserver
                        // Always hide keyboard when resuming if player is expanded
                        if (isPlayerExpanded) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        if (isHandlingScrollToTop) return@LifecycleEventObserver
                        // Clear focus when pausing to prevent keyboard from showing on resume
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Initial check - hide keyboard if player is expanded
        if (isPlayerExpanded) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}