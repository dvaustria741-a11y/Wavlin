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
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.models.toMediaMetadata
import com.wavlin.music.spotify.SpotifyProvider
import com.wavlin.music.spotify.SpotifyTrack
import com.wavlin.music.ui.menu.ImportPlaylistDialog
import androidx.compose.runtime.key
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val playerConnection = LocalPlayerConnection.current
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
                    when (val result = withContext(Dispatchers.IO) { spotifyProvider.resolve(searchQuery) }) {
                        is SpotifyResult.Track ->
                            navController.navigate(SearchRoutes.resultRoute(result.track.query))

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

    // Matches each Spotify track to a YouTube song (best duration match among the top results), keeping playlist order.
    suspend fun importSpotifyTracks(tracks: List<SpotifyTrack>): List<String> {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Importing ${tracks.size} tracks...", Toast.LENGTH_SHORT).show()
        }
        val semaphore = Semaphore(5)
        val matched =
            coroutineScope {
                tracks
                    .map { track ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                try {
                                    val items =
                                        YouTube
                                            .search(track.query, YouTube.SearchFilter.FILTER_SONG)
                                            .getOrNull()
                                            ?.items
                                            ?.filterIsInstance<SongItem>()
                                            .orEmpty()
                                    val best =
                                        items.firstOrNull { song ->
                                            track.durationSeconds > 0 &&
                                                song.duration != null &&
                                                kotlin.math.abs(song.duration!! - track.durationSeconds) <= 5
                                        } ?: items.firstOrNull()
                                    best?.let {
                                        database.insert(it.toMediaMetadata())
                                        it.id
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                    }.awaitAll()
            }
        val ids = matched.filterNotNull().distinct()
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Imported ${ids.size} of ${tracks.size} tracks", Toast.LENGTH_LONG).show()
        }
        return ids
    }

    key(spotifyCollection) {
        ImportPlaylistDialog(
            isVisible = showSpotifyImport,
            onGetSong = { importSpotifyTracks(spotifyCollection?.tracks.orEmpty()) },
            playlistTitle = spotifyCollection?.title.orEmpty(),
            onDismiss = { showSpotifyImport = false },
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