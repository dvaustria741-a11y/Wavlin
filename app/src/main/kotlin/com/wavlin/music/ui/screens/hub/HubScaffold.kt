/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.hub

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
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
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.models.toMediaMetadata
import com.wavlin.music.playback.queues.ListQueue
import com.wavlin.music.playback.queues.YouTubeQueue
import com.wavlin.music.ui.component.HubArt
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.LocalMenuState
import com.wavlin.music.ui.component.shimmer.GridItemPlaceHolder
import com.wavlin.music.ui.component.shimmer.ShimmerHost
import com.wavlin.music.ui.menu.YouTubeAlbumMenu
import com.wavlin.music.ui.menu.YouTubeArtistMenu
import com.wavlin.music.ui.menu.YouTubePlaylistMenu
import com.wavlin.music.ui.menu.YouTubeSongMenu
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.viewmodels.HubSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardSize = 148.dp
private val BackdropHeight = 480.dp

/**
 * Shared page for every browse hub (new releases, charts, podcasts and moods): a tinted header
 * with a fan of covers and play controls, followed by one shelf per section.
 *
 * [sections] is null while loading and empty when nothing could be loaded.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HubScaffold(
    navController: NavController,
    title: String,
    sections: List<HubSection>?,
    fallbackAccent: Color,
    onRetry: (() -> Unit)? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    val covers = remember(sections) { pickCovers(sections.orEmpty()) }
    val tint = rememberArtTint(covers.firstOrNull())
    val accent by animateColorAsState(tint ?: fallbackAccent, label = "HubAccent")
    val background = MaterialTheme.colorScheme.background
    val backdropTop = lerp(background, accent, 0.40f)

    val playable =
        remember(sections) {
            sections
                .orEmpty()
                .flatMap { it.items }
                .mapNotNull {
                    when (it) {
                        is SongItem -> it.toMediaMetadata()
                        is EpisodeItem -> it.toMediaMetadata()
                        else -> null
                    }
                }.distinctBy { it.id }
        }

    fun playAll(shuffled: Boolean) {
        val queue = if (shuffled) playable.shuffled() else playable
        playerConnection.playQueue(ListQueue(title = title, items = queue.map { it.toMediaItem() }))
    }

    fun open(item: YTItem) {
        when (item) {
            is SongItem ->
                if (item.id == mediaMetadata?.id) {
                    playerConnection.togglePlayPause()
                } else {
                    playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                }

            is EpisodeItem ->
                if (item.id == mediaMetadata?.id) {
                    playerConnection.togglePlayPause()
                } else {
                    playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                }

            is AlbumItem -> navController.navigate("album/${item.id}")
            is ArtistItem -> navController.navigate("artist/${item.id}")
            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
            is PodcastItem -> navController.navigate("online_podcast/${item.id}")
        }
    }

    fun showMenu(item: YTItem) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuState.show {
            when (item) {
                is SongItem -> YouTubeSongMenu(song = item, onDismiss = menuState::dismiss)
                is AlbumItem -> YouTubeAlbumMenu(albumItem = item, onDismiss = menuState::dismiss)
                is ArtistItem -> YouTubeArtistMenu(artist = item, onDismiss = menuState::dismiss)
                is PlaylistItem ->
                    YouTubePlaylistMenu(playlist = item, coroutineScope = coroutineScope, onDismiss = menuState::dismiss)

                is PodcastItem ->
                    YouTubePlaylistMenu(
                        playlist = item.asPlaylistItem(),
                        coroutineScope = coroutineScope,
                        onDismiss = menuState::dismiss,
                    )

                is EpisodeItem -> YouTubeSongMenu(song = item.asSongItem(), onDismiss = menuState::dismiss)
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(background)
                .drawBehind {
                    val height = BackdropHeight.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(listOf(backdropTop, background), endY = height),
                        size = Size(size.width, height),
                    )
                },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
        ) {
            item(key = "hub_header") {
                HubHeader(
                    title = title,
                    sectionCount = sections?.size ?: 0,
                    covers = covers,
                    accent = accent,
                    canPlay = playable.isNotEmpty(),
                    onPlay = { playAll(shuffled = false) },
                    onShuffle = { playAll(shuffled = true) },
                    modifier = Modifier.padding(top = statusTop + 64.dp),
                )
            }

            when {
                sections == null ->
                    item(key = "hub_loading") {
                        ShimmerHost(modifier = Modifier.padding(top = 28.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                repeat(2) { GridItemPlaceHolder() }
                            }
                        }
                    }

                sections.isEmpty() ->
                    item(key = "hub_empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.hub_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (onRetry != null) {
                                TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                            }
                        }
                    }

                else ->
                    sections.forEachIndexed { sectionIndex, section ->
                        item(key = "hub_section_${sectionIndex}_${section.title}") {
                            Column(modifier = Modifier.padding(top = 28.dp)) {
                                Row(
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(width = 4.dp, height = 22.dp)
                                                .clip(CircleShape)
                                                .background(accent),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    itemsIndexed(
                                        items = section.items,
                                        key = { _, item -> "hub_${sectionIndex}_${item.id}" },
                                    ) { position, item ->
                                        HubCard(
                                            item = item,
                                            rank = if (section.ranked) position + 1 else null,
                                            isActive =
                                                when (item) {
                                                    is SongItem, is EpisodeItem -> item.id == mediaMetadata?.id
                                                    is AlbumItem -> item.id == mediaMetadata?.album?.id
                                                    else -> false
                                                },
                                            accent = accent,
                                            onClick = { open(item) },
                                            onLongClick = { showMenu(item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
        }

        HubBackButton(
            navController = navController,
            modifier = Modifier.padding(start = 16.dp, top = statusTop + 6.dp),
        )
    }
}

@Composable
fun HubBackButton(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = navController::navigateUp,
            onLongClick = navController::backToMain,
        ) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
        }
    }
}

@Composable
private fun HubHeader(
    title: String,
    sectionCount: Int,
    covers: List<String>,
    accent: Color,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.displaySmall.copy(
                            fontSize = 34.sp,
                            lineHeight = 38.sp,
                            letterSpacing = (-0.5).sp,
                        ),
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sectionCount > 0) {
                    Text(
                        text = stringResource(R.string.hub_collections, sectionCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            CoverFan(urls = covers)
        }

        if (canPlay) {
            Row(
                modifier = Modifier.padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HubPillButton(
                    label = stringResource(R.string.play),
                    iconRes = R.drawable.play,
                    container = accent,
                    content = if (accent.luminance() > 0.5f) Color.Black else Color.White,
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                )
                HubPillButton(
                    label = stringResource(R.string.shuffle),
                    iconRes = R.drawable.shuffle,
                    container = Color.White.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.onSurface,
                    onClick = onShuffle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HubPillButton(
    label: String,
    iconRes: Int,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier.height(48.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Three overlapping covers, the middle one on top. */
@Composable
private fun CoverFan(urls: List<String>) {
    if (urls.isEmpty()) return
    Box(modifier = Modifier.size(width = 148.dp, height = 128.dp)) {
        // Back covers first so the first url ends up in front.
        listOf(1, 2, 0).forEach { index ->
            val url = urls.getOrNull(index) ?: return@forEach
            val (alignment, rotation, drop) =
                when (index) {
                    1 -> Triple(Alignment.TopStart, -9f, 20.dp)
                    2 -> Triple(Alignment.TopEnd, 9f, 20.dp)
                    else -> Triple(Alignment.TopCenter, 0f, 0.dp)
                }
            FanCover(url = url, rotation = rotation, drop = drop, modifier = Modifier.align(alignment))
        }
    }
}

@Composable
private fun FanCover(
    url: String,
    rotation: Float,
    drop: Dp,
    modifier: Modifier = Modifier,
) {
    val coverShape = RoundedCornerShape(16.dp)
    HubArt(
        model = url,
        shape = coverShape,
        modifier =
            modifier
                .offset(y = drop)
                .size(92.dp)
                .graphicsLayer {
                    rotationZ = rotation
                    shadowElevation = 14f
                    shape = coverShape
                    clip = true
                }.border(1.dp, Color.White.copy(alpha = 0.18f), coverShape),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubCard(
    item: YTItem,
    rank: Int?,
    isActive: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isArtist = item is ArtistItem
    Column(
        modifier = Modifier.width(CardSize).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(modifier = Modifier.size(CardSize)) {
            HubArt(
                model = item.thumbnail,
                shape = if (isArtist) CircleShape else RoundedCornerShape(18.dp),
                cropBadge = item is PlaylistItem || item is PodcastItem || item is EpisodeItem,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            if (rank != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = rank.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.padding(top = 8.dp),
        )
        item.subtitle()?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

private fun YTItem.subtitle(): String? =
    when (this) {
        is SongItem -> artists.joinToString { it.name }
        is AlbumItem -> artists?.joinToString { it.name }
        is PlaylistItem -> author?.name
        is PodcastItem -> author?.name
        is EpisodeItem -> author?.name
        is ArtistItem -> null
    }?.takeIf(String::isNotBlank)

/** First cover of each section, then more from the first section, up to three distinct urls. */
fun pickCovers(sections: List<HubSection>): List<String> {
    val urls = LinkedHashSet<String>()
    sections.forEach { section ->
        section.items.firstNotNullOfOrNull { it.thumbnail?.takeIf(String::isNotBlank) }?.let(urls::add)
    }
    sections.firstOrNull()?.items?.forEach { item ->
        item.thumbnail?.takeIf(String::isNotBlank)?.let(urls::add)
    }
    return urls.take(3)
}

/** Dominant swatch of the artwork at [url], used to tint the page. */
@Composable
fun rememberArtTint(url: String?): Color? {
    val context = LocalContext.current
    var tint by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        tint =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .allowHardware(false)
                            .size(128)
                            .build()
                    val bitmap = SingletonImageLoader.get(context).execute(request).image?.toBitmap()
                    bitmap?.let {
                        val palette = Palette.from(it).generate()
                        val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                        swatch?.let { s -> Color(s.rgb) }
                    }
                }.getOrNull()
            }
    }
    return tint
}
