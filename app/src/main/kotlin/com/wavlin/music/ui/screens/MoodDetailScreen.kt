/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.wavlin.innertube.models.AlbumItem
import com.wavlin.innertube.models.ArtistItem
import com.wavlin.innertube.models.EpisodeItem
import com.wavlin.innertube.models.PlaylistItem
import com.wavlin.innertube.models.PodcastItem
import com.wavlin.innertube.models.SongItem
import com.wavlin.innertube.models.YTItem
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.models.toMediaMetadata
import com.wavlin.music.playback.queues.YouTubeQueue
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.LocalMenuState
import com.wavlin.music.ui.component.shimmer.GridItemPlaceHolder
import com.wavlin.music.ui.component.shimmer.ShimmerHost
import com.wavlin.music.ui.menu.YouTubeAlbumMenu
import com.wavlin.music.ui.menu.YouTubeArtistMenu
import com.wavlin.music.ui.menu.YouTubePlaylistMenu
import com.wavlin.music.ui.menu.YouTubeSongMenu
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.ui.utils.resize
import com.wavlin.music.viewmodels.YouTubeBrowseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mood Hub: full-bleed cover hero with the mood name over it, followed by one horizontal
 * row per category the mood contains.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoodDetailScreen(
    navController: NavController,
    moodTitle: String?,
    viewModel: YouTubeBrowseViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val browseResult by viewModel.result.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val listState = rememberLazyListState()

    val sections = browseResult?.items.orEmpty()
    val heroUrl =
        remember(sections) {
            sections
                .asSequence()
                .flatMap { it.items.asSequence() }
                .mapNotNull { it.thumbnail?.takeIf(String::isNotBlank) }
                .firstOrNull()
        }
    val displayTitle = moodTitle?.takeIf(String::isNotBlank) ?: browseResult?.title.orEmpty()

    var artworkTint by remember(heroUrl) { mutableStateOf<Color?>(null) }
    LaunchedEffect(heroUrl) {
        if (heroUrl == null) return@LaunchedEffect
        artworkTint =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(heroUrl)
                            .allowHardware(false)
                            .size(128)
                            .build()
                    val bitmap = SingletonImageLoader.get(context).execute(request).image?.toBitmap()
                    bitmap?.let {
                        val palette = Palette.from(it).generate()
                        val swatch = palette.dominantSwatch ?: palette.mutedSwatch ?: palette.vibrantSwatch
                        swatch?.let { s -> Color(s.rgb) }
                    }
                }.getOrNull()
            }
    }

    val background = MaterialTheme.colorScheme.background
    val surfaceColor by animateColorAsState(
        targetValue = artworkTint?.let { lerp(background, it, 0.24f) } ?: background,
        label = "MoodHubSurface",
    )
    val secondarySurface = remember(surfaceColor) { lerp(surfaceColor, Color.Black, 0.18f) }

    val heroHeight = (configuration.screenHeightDp.dp * 0.45f).coerceIn(380.dp, 440.dp)
    val heroHeightPx = with(density) { heroHeight.toPx() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(surfaceColor, secondarySurface))),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .clipToBounds()
                    .graphicsLayer {
                        val scrollOffset =
                            if (listState.firstVisibleItemIndex == 0) {
                                -listState.firstVisibleItemScrollOffset.toFloat()
                            } else {
                                -heroHeightPx
                            }
                        val scrollProgress = (-scrollOffset / heroHeightPx).coerceIn(0f, 1f)
                        translationY = scrollOffset * 0.42f
                        alpha = (1f - scrollProgress * 1.35f).coerceIn(0f, 1f)
                    },
        ) {
            if (heroUrl != null) {
                AsyncImage(
                    model = heroUrl.resize(1080, 1080),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.30f to Color.Transparent,
                                0.60f to surfaceColor.copy(alpha = 0.50f),
                                0.82f to surfaceColor.copy(alpha = 0.85f),
                                1.00f to surfaceColor,
                            ),
                        ),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + 16.dp),
        ) {
            item(key = "mood_hero_header") {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(heroHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    ) {
                        Text(
                            text = displayTitle,
                            style =
                                MaterialTheme.typography.displayMedium.copy(
                                    fontSize = 36.sp,
                                    lineHeight = 40.sp,
                                    letterSpacing = (-0.6).sp,
                                ),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (browseResult != null) {
                            Text(
                                text = stringResource(R.string.mood_hub_categories, sections.size),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item(key = "mood_divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }

            if (browseResult == null) {
                item(key = "mood_shimmer") {
                    ShimmerHost {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            repeat(2) {
                                GridItemPlaceHolder()
                            }
                        }
                    }
                }
            }

            sections.forEachIndexed { index, section ->
                item(key = "mood_section_${index}_${section.title}") {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        section.title?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = section.items.distinctBy { it.id },
                                key = { "mood_${index}_${it.id}" },
                            ) { item ->
                                MoodHubCard(
                                    item = item,
                                    onClick = {
                                        when (item) {
                                            is SongItem -> {
                                                if (item.id == mediaMetadata?.id) {
                                                    playerConnection.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                                                }
                                            }

                                            is EpisodeItem -> {
                                                if (item.id == mediaMetadata?.id) {
                                                    playerConnection.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                                                }
                                            }

                                            is AlbumItem -> navController.navigate("album/${item.id}")
                                            is ArtistItem -> navController.navigate("artist/${item.id}")
                                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                                            is PodcastItem -> navController.navigate("online_podcast/${item.id}")
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            when (item) {
                                                is SongItem ->
                                                    YouTubeSongMenu(song = item, onDismiss = menuState::dismiss)

                                                is AlbumItem ->
                                                    YouTubeAlbumMenu(albumItem = item, onDismiss = menuState::dismiss)

                                                is ArtistItem ->
                                                    YouTubeArtistMenu(artist = item, onDismiss = menuState::dismiss)

                                                is PlaylistItem ->
                                                    YouTubePlaylistMenu(
                                                        playlist = item,
                                                        coroutineScope = coroutineScope,
                                                        onDismiss = menuState::dismiss,
                                                    )

                                                is PodcastItem ->
                                                    YouTubePlaylistMenu(
                                                        playlist = item.asPlaylistItem(),
                                                        coroutineScope = coroutineScope,
                                                        onDismiss = menuState::dismiss,
                                                    )

                                                is EpisodeItem ->
                                                    YouTubeSongMenu(song = item.asSongItem(), onDismiss = menuState::dismiss)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .padding(start = 16.dp, top = insets.calculateTopPadding() + 8.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoodHubCard(
    item: YTItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(168.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(if (item is ArtistItem) CircleShape else RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item is PlaylistItem || item is PodcastItem) {
                Box(
                    modifier =
                        Modifier
                            .padding(8.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
