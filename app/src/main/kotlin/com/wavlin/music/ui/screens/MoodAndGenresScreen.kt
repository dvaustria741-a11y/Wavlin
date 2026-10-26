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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.innertube.pages.MoodAndGenres
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.shimmer.ShimmerHost
import com.wavlin.music.ui.component.shimmer.TextPlaceholder
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.viewmodels.MoodAndGenresViewModel

private val MoodCardBorderColors =
    listOf(
        Color.White.copy(alpha = 0.15f),
        Color.White.copy(alpha = 0.03f),
    )

private val MoodCardGlowColors =
    listOf(
        Color.White.copy(alpha = 0.10f),
        Color.Transparent,
    )

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

    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = insets.calculateTopPadding() + 68.dp,
                    bottom = insets.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rows == null) {
                items(count = 6, key = { "mood_placeholder_$it" }) {
                    ShimmerHost {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(columns) {
                                TextPlaceholder(
                                    height = 110.dp,
                                    shape = RoundedCornerShape(16.dp),
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

        Box(
            modifier =
                Modifier
                    .padding(start = 16.dp, top = insets.calculateTopPadding() + 8.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
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

fun moodDetailRoute(mood: MoodAndGenres.Item): String =
    "mood_detail/${mood.endpoint.browseId}?params=${mood.endpoint.params}&title=${Uri.encode(mood.title)}"

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
        targetValue = if (isPressed) 0.95f else 1f,
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
        shape = RoundedCornerShape(16.dp),
        modifier =
            modifier
                .aspectRatio(1.6f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(gradientBrush),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Brush.radialGradient(colors = MoodCardGlowColors, radius = 220f)),
            )

            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                        lineHeight = 18.sp,
                    ),
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 14.dp, end = 64.dp),
            )

            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 10.dp, y = 10.dp)
                        .graphicsLayer {
                            rotationZ = -14f
                            shadowElevation = 12f
                            shape = RoundedCornerShape(10.dp)
                            clip = true
                        }.size(68.dp)
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(MoodCardBorderColors),
                            shape = RoundedCornerShape(10.dp),
                        ).background(Color.Black.copy(alpha = 0.3f)),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
