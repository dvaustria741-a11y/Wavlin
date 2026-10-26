/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.wavlin.innertube.pages.MoodAndGenres
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.ui.component.HubArt
import com.wavlin.music.ui.component.shimmer.ShimmerHost
import com.wavlin.music.ui.component.shimmer.TextPlaceholder
import com.wavlin.music.ui.screens.hub.HubBackButton
import com.wavlin.music.viewmodels.MoodAndGenresViewModel

@Composable
fun MoodAndGenresScreen(
    navController: NavController,
    viewModel: MoodAndGenresViewModel = hiltViewModel(),
) {
    val columns = if (LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE) 4 else 2

    val moodAndGenresList by viewModel.moodAndGenres.collectAsStateWithLifecycle()
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()

    val moods =
        remember(moodAndGenresList) {
            moodAndGenresList
                ?.flatMap { it.items }
                ?.distinctBy { MoodAndGenresViewModel.artworkKey(it) }
        }
    val rows = remember(moods, columns) { moods?.chunked(columns) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = statusTop + 60.dp,
                    bottom = bottomInset + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rows == null) {
                items(count = 6, key = { "mood_placeholder_$it" }) {
                    ShimmerHost {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(columns) {
                                TextPlaceholder(
                                    height = 100.dp,
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = rows,
                    key = { index, row -> "mood_row_${index}_${row.firstOrNull()?.title}" },
                ) { _, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowItems.forEach { mood ->
                            MoodCard(
                                title = mood.title,
                                imageUrl = artwork[MoodAndGenresViewModel.artworkKey(mood)],
                                gradientColors = remember(mood.stripeColor) { moodGradientFor(mood.stripeColor) },
                                onClick = { navController.navigate(moodDetailRoute(mood)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
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

fun moodDetailRoute(mood: MoodAndGenres.Item): String =
    "mood_detail/${mood.endpoint.browseId}?params=${mood.endpoint.params}&title=${Uri.encode(mood.title)}"

/**
 * Mood tile: tinted gradient with the title at the bottom and a vinyl record of the mood's
 * cover art spinning out of the top-right corner.
 */
@Composable
fun MoodCard(
    title: String,
    imageUrl: String?,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "MoodCardPressScale",
    )
    val gradientBrush =
        remember(gradientColors) {
            Brush.linearGradient(colors = listOf(gradientColors[0], gradientColors[1]))
        }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(22.dp),
        modifier =
            modifier
                .aspectRatio(1.6f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
    ) {
        Box(modifier = Modifier.fillMaxSize().background(gradientBrush)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent))),
            )

            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                        lineHeight = 20.sp,
                    ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, end = 76.dp, bottom = 12.dp),
            )

            VinylDisc(
                imageUrl = imageUrl,
                holeColor = gradientColors[0],
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 18.dp, y = (-18).dp)
                        .size(96.dp),
            )
        }
    }
}

@Composable
private fun VinylDisc(
    imageUrl: String?,
    holeColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .border(2.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .drawWithContent {
                    drawContent()
                    val radius = size.minDimension / 2f
                    listOf(0.86f, 0.72f, 0.58f).forEach { fraction ->
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.10f),
                            radius = radius * fraction,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                    drawCircle(color = holeColor, radius = radius * 0.13f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        radius = radius * 0.13f,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
    ) {
        if (imageUrl != null) {
            HubArt(model = imageUrl, shape = CircleShape, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun MoodAndGenresButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
        modifier
            .height(MoodAndGenresButtonHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

val MoodAndGenresButtonHeight = 48.dp
