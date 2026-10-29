/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.innertube.models.WatchEndpoint
import com.wavlin.music.BuildConfig
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.playback.PlayerConnection
import com.wavlin.music.playback.queues.YouTubeQueue
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.Material3SettingsGroup
import com.wavlin.music.ui.component.Material3SettingsItem
import com.wavlin.music.ui.utils.backToMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

private data class Contributor(
    val name: String,
    val roleRes: Int,
    val githubHandle: String,
    val avatarUrl: String = "https://github.com/$githubHandle.png",
    val avatarRes: Int? = null,
    val githubUrl: String = "https://github.com/$githubHandle",
    val sponsorUrl: String? = null,
    val polygon: RoundedPolygon? = null,
    val favoriteSongVideoId: String? = null
)

private data class CommunityLink(
    val labelRes: Int,
    val iconRes: Int,
    val url: String
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val leadDeveloper = Contributor(
    name = "Zenkai",
    roleRes = R.string.credits_lead_developer,
    githubHandle = "dvaustria741-a11y",
    avatarRes = R.drawable.dev_zenkai,
    polygon = MaterialShapes.Cookie9Sided,
    favoriteSongVideoId = "Lk3UlEyIW84"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val collaborators = listOf(
    Contributor(name = "Adriel O'Connel", roleRes = R.string.credits_collaborator, githubHandle = "adrielGGmotion", sponsorUrl = "https://github.com/sponsors/adrielGGmotion", polygon = MaterialShapes.Cookie4Sided, favoriteSongVideoId = "m2zUrruKjDQ"),
    Contributor(name = "Nyx", roleRes = R.string.credits_collaborator, githubHandle = "nyxiereal", sponsorUrl = "https://github.com/sponsors/nyxiereal", polygon = MaterialShapes.Cookie12Sided, favoriteSongVideoId = "zselaN6zPXw"),
)

private val communityLinks = listOf(
    CommunityLink(R.string.credits_telegram, R.drawable.telegram, "https://t.me/wavlinapp"),
    CommunityLink(R.string.credits_view_repo, R.drawable.github, "https://github.com/dvaustria741-a11y/Wavlin"),
    CommunityLink(R.string.credits_license_name, R.drawable.info, "https://github.com/dvaustria741-a11y/Wavlin/blob/main/LICENSE")
)

private fun handleEasterEggClick(
    clickCount: Int,
    favoriteSongVideoId: String?,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    playerConnection: PlayerConnection?,
    wannaPlayStr: String,
    yeahStr: String,
    onCountUpdate: (Int) -> Unit
) {
    if (favoriteSongVideoId != null) {
        val newCount = clickCount + 1
        onCountUpdate(newCount)
        if (newCount >= 3) {
            onCountUpdate(0)
            coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = wannaPlayStr,
                    actionLabel = yeahStr,
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    playerConnection?.playQueue(YouTubeQueue(WatchEndpoint(videoId = favoriteSongVideoId)))
                }
            }
        }
    }
}

@Composable
private fun ContributorAvatar(
    avatarUrl: String,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    avatarRes: Int? = null,
    contentDescription: String? = null,
    border: BorderStroke? = null,
    plain: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val fallback = painterResource(R.drawable.about_icon)
    if (plain) {
        // The artwork already has its own shape and transparent corners: draw it as is, no clip, background or ring.
        AsyncImage(
            model = avatarRes ?: avatarUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(sizeDp.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            placeholder = fallback,
            fallback = fallback,
            error = fallback,
        )
        return
    }
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.size(sizeDp.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 4.dp,
        border = border,
    ) {
        AsyncImage(
            model = avatarRes ?: avatarUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = fallback,
            fallback = fallback,
            error = fallback,
        )
    }
}

@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(380)) + slideInVertically(tween(380)) { it / 8 },
    ) { content() }
}

@Composable
private fun InfoChip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SocialCircle(iconRes: Int, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun LinkTile(
    iconRes: Int,
    title: String,
    tint: Color,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(
    navController: NavController,
) {
    val uriHandler = LocalUriHandler.current
    val playerConnection = LocalPlayerConnection.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val wannaPlayStr = stringResource(R.string.wanna_play_favorite_song)
    val yeahStr = stringResource(R.string.yeah)
    val cs = MaterialTheme.colorScheme

    val windowInsets = LocalPlayerAwareWindowInsets.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.windowInsetsPadding(windowInsets.only(WindowInsetsSides.Top)))
        Spacer(Modifier.height(16.dp))

        // App header: centered logo tile over a soft gradient wash
        Staggered(0) {
            ElevatedCard(
                shape = RoundedCornerShape(36.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(cs.primaryContainer.copy(alpha = 0.6f), Color.Transparent),
                            ),
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp, bottom = 26.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(108.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(cs.surface.copy(alpha = 0.75f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.wavlin_logo),
                                contentDescription = stringResource(R.string.wavlin),
                                modifier = Modifier.size(76.dp),
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        val wavlinName = stringResource(R.string.wavlin)
                            .lowercase(Locale.getDefault())
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        Text(
                            text = wavlinName,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = cs.onSurface,
                            letterSpacing = (-0.5).sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoChip(BuildConfig.VERSION_NAME, cs.primary.copy(alpha = 0.14f), cs.primary)
                            InfoChip(
                                BuildConfig.ARCHITECTURE.uppercase(),
                                cs.secondaryContainer.copy(alpha = 0.7f),
                                cs.onSecondaryContainer,
                            )
                            if (BuildConfig.DEBUG) {
                                InfoChip("DEBUG", cs.tertiaryContainer.copy(alpha = 0.7f), cs.onTertiaryContainer)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Lead developer: gradient banner with the avatar overlapping its bottom edge
        Staggered(1) {
            ElevatedCard(
                shape = RoundedCornerShape(36.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(cs.primary.copy(alpha = 0.55f), cs.tertiary.copy(alpha = 0.5f)),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 36.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        var leadClickCount by remember(leadDeveloper.name) { mutableIntStateOf(0) }
                        ContributorAvatar(
                            avatarUrl = leadDeveloper.avatarUrl,
                            avatarRes = leadDeveloper.avatarRes,
                            sizeDp = 128,
                            contentDescription = leadDeveloper.name,
                            plain = true,
                            onClick = {
                                handleEasterEggClick(
                                    clickCount = leadClickCount,
                                    favoriteSongVideoId = leadDeveloper.favoriteSongVideoId,
                                    coroutineScope = coroutineScope,
                                    snackbarHostState = snackbarHostState,
                                    playerConnection = playerConnection,
                                    wannaPlayStr = wannaPlayStr,
                                    yeahStr = yeahStr,
                                    onCountUpdate = { leadClickCount = it },
                                )
                            },
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = leadDeveloper.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = cs.onSurface,
                            letterSpacing = (-0.5).sp,
                        )
                        Text(
                            text = stringResource(R.string.credits_lead_developer),
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            SocialCircle(R.drawable.language) { uriHandler.openUri("https://wavlin.cc") }
                            SocialCircle(R.drawable.github) { uriHandler.openUri(leadDeveloper.githubUrl) }
                            SocialCircle(R.drawable.instagram) { uriHandler.openUri("https://www.instagram.com/mostafaalagamy") }
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { uriHandler.openUri("https://ko-fi.com/zenkaidev") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .height(54.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cs.primary,
                                contentColor = cs.onPrimary,
                            ),
                        ) {
                            Icon(painterResource(R.drawable.buymeacoffee), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.buy_mo_a_coffee), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // Community & info as link tiles instead of a settings list
        Staggered(2) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.community_and_info).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinkTile(
                        iconRes = R.drawable.telegram,
                        title = stringResource(R.string.credits_telegram),
                        tint = Color(0xFF2AABEE),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { uriHandler.openUri(communityLinks[0].url) },
                    )
                    LinkTile(
                        iconRes = R.drawable.github,
                        title = stringResource(R.string.credits_view_repo),
                        tint = Color(0xFF24292F),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { uriHandler.openUri(communityLinks[1].url) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                LinkTile(
                    iconRes = R.drawable.info,
                    title = stringResource(R.string.credits_license_name),
                    description = stringResource(R.string.credits_license_desc),
                    tint = Color(0xFF2EAD5B),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { uriHandler.openUri(communityLinks[2].url) },
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        Text(
            text = stringResource(R.string.stands_with_palestine),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onSurfaceVariant,
        )

        Spacer(Modifier.height(48.dp))
    }

    // The bar follows the theme animation instead of animating its own container color (see ThemeScreen).
    val pinkBar = LocalPinkLilyTheme.current
    TopAppBar(
        modifier = Modifier.background(if (pinkBar) Color.Transparent else MaterialTheme.colorScheme.surface),
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
    )

    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        )
    }
}
