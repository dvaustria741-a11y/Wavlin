/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.constants.AppBarHeight
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import com.wavlin.music.ui.component.pinkGlass
import com.wavlin.music.ui.theme.PinkInk
import com.wavlin.music.ui.utils.fadingEdge

/** List padding for a screen whose first item is a full-bleed hero: no top padding while the hero shows. */
@Composable
internal fun heroListPadding(heroVisible: Boolean): PaddingValues {
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = insets.calculateLeftPadding(dir),
        end = insets.calculateRightPadding(dir),
        top = if (heroVisible) 0.dp else insets.calculateTopPadding(),
        bottom = insets.calculateBottomPadding(),
    )
}

/** Circle behind the top bar buttons while they float over the cover: dark normally, rose glass in Secret Pink. */
@Composable
internal fun heroTopButtonModifier(overlay: Boolean): Modifier =
    when {
        !overlay -> Modifier
        LocalPinkLilyTheme.current -> Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.3f)).pinkGlass(CircleShape, floating = false)
        else -> Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
    }

@Composable
internal fun heroTopIconTint(overlay: Boolean): Color =
    when {
        !overlay -> LocalContentColor.current
        LocalPinkLilyTheme.current -> PinkInk
        else -> Color.White
    }

/**
 * Secret Pink: the cover fades to real transparency at its bottom edge (like the artist screen), so the gingham
 * wallpaper shows through with no seam. Other themes fade into a solid colour with a gradient instead.
 */
@Composable
internal fun Modifier.heroCoverFade(): Modifier = if (LocalPinkLilyTheme.current) fadingEdge(bottom = 220.dp) else this

/** Colour the cover fades into at its bottom edge, so it meets the page without a seam. */
@Composable
internal fun heroFadeColor(): Color = if (LocalPinkLilyTheme.current) Color(0xFFFCE4EE) else MaterialTheme.colorScheme.background

@Composable
internal fun heroTitleColor(fade: Color): Color =
    if (LocalPinkLilyTheme.current) PinkInk else if (fade.luminance() > 0.5f) Color.Black else Color.White

/** Scrim behind the status bar and floating buttons: soft white in Secret Pink, dark otherwise. */
@Composable
internal fun heroTopScrimColor(): Color = if (LocalPinkLilyTheme.current) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.35f)

/**
 * The header every playlist screen shares: a full-bleed square cover (single image or 2x2 collage) that
 * fades into the page with the title on top, then the share/menu, shuffle and play buttons.
 */
@Composable
internal fun PlaylistHeroHeader(
    title: String,
    subtitle: String,
    coverUrls: List<String>,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    menuIcon: Int = R.drawable.more_vert,
) {
    val covers =
        remember(coverUrls) {
            val urls = coverUrls.distinct().take(4)
            if (urls.size in 2..3) List(4) { urls[it % urls.size] } else urls
        }
    val heroBase = heroFadeColor()
    val heroText = heroTitleColor(heroBase)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(Modifier.matchParentSize().heroCoverFade()) {
            when (covers.size) {
                0 ->
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.queue_music), contentDescription = null, modifier = Modifier.size(96.dp))
                    }

                1 ->
                    AsyncImage(
                        model = covers[0],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                else ->
                    Column(Modifier.fillMaxSize()) {
                        repeat(2) { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                repeat(2) { col ->
                                    AsyncImage(
                                        model = covers.getOrNull(row * 2 + col),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxHeight().weight(1f),
                                    )
                                }
                            }
                        }
                    }
            }
            }
            if (!LocalPinkLilyTheme.current) {
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
            }
            // Keeps the status bar and the floating top buttons legible over bright covers
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + AppBarHeight)
                    .background(Brush.verticalGradient(listOf(heroTopScrimColor(), Color.Transparent))),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = heroText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = heroText.copy(alpha = 0.75f),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onMenu,
                shape = headerButtonShape(4),
                color = Color.Transparent,
                modifier = headerButtonSize(4).headerGlass(4),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(menuIcon), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onShuffle,
                    shape = headerButtonShape(2),
                    color = Color.Transparent,
                    modifier = headerButtonSize(2).headerGlass(2),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.shuffle),
                            contentDescription = stringResource(R.string.shuffle),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Surface(
                    onClick = onPlay,
                    color = if (LocalPinkLilyTheme.current) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                    shape = headerButtonShape(3),
                    modifier = headerButtonSize(3).headerGlass(3),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.play),
                            contentDescription = stringResource(R.string.play),
                            tint = if (LocalPinkLilyTheme.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }
    }
}
