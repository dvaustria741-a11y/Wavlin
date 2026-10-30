/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wavlin.music.R
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.wavlin.innertube.utils.parseCookieString
import com.wavlin.music.BuildConfig
import com.wavlin.music.constants.InnerTubeCookieKey
import com.wavlin.music.spotify.account.SpotifyAuthState
import com.wavlin.music.ui.screens.settings.AccountTokenEntry
import com.wavlin.music.utils.Updater
import com.wavlin.music.utils.rememberPreference
import com.wavlin.music.viewmodels.HomeViewModel
import com.wavlin.music.viewmodels.SpotifyViewModel

private val DrawerEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val DRAWER_WIDTH_FRACTION = 0.88f
private const val OPEN_DURATION_MS = 380
private const val CLOSE_DURATION_MS = 300
private const val SCRIM_MAX_ALPHA = 0.4f

/**
 * Push-style side drawer: the whole app shell slides left while the drawer, attached to its
 * right edge, slides in behind it. A sliver of the shell stays visible and tapping it closes the drawer.
 */
@Composable
fun AccountDrawerHost(
    open: Boolean,
    onDismiss: () -> Unit,
    drawer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(open) {
        progress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(if (open) OPEN_DURATION_MS else CLOSE_DURATION_MS, easing = DrawerEasing),
        )
    }
    BackHandler(enabled = open, onBack = onDismiss)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val drawerWidth = maxWidth * DRAWER_WIDTH_FRACTION
        val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }

        if (progress.value > 0f) {
            val dismissThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .graphicsLayer { translationX = drawerWidthPx * (1f - progress.value) }
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .pointerInput(onDismiss) {
                            var dragged = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = { if (dragged > dismissThresholdPx) onDismiss() },
                                onHorizontalDrag = { _, delta -> dragged += delta },
                            )
                        },
            ) {
                drawer()
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = -drawerWidthPx * progress.value },
        ) {
            content()
            if (progress.value > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = SCRIM_MAX_ALPHA * progress.value))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss,
                            ),
                )
            }
        }
    }
}

@Composable
fun AccountDrawerContent(
    navController: NavController,
    onClose: () -> Unit,
    latestVersionName: String,
) {
    val uriHandler = LocalUriHandler.current
    val (innerTubeCookie, _) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) { "SAPISID" in parseCookieString(innerTubeCookie) }
    val homeViewModel: HomeViewModel = hiltViewModel()
    val spotifyViewModel: SpotifyViewModel = hiltViewModel()
    val spotifyState by spotifyViewModel.authState.collectAsStateWithLifecycle()
    val accountName by homeViewModel.accountName.collectAsStateWithLifecycle()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsStateWithLifecycle()
    val spotifyConnected = spotifyState == SpotifyAuthState.CONNECTED
    val spotifyLabel = stringResource(R.string.spotify)
    val connected =
        buildList {
            if (isLoggedIn) add("YouTube Music")
            if (spotifyConnected) add(spotifyLabel)
        }.joinToString(" · ")

    val updateAvailable = BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.VERSION_NAME
    val downloadUrl =
        if (updateAvailable) {
            Updater.getCachedLatestRelease()?.let { Updater.getDownloadUrlForCurrentVariant(it) }
        } else {
            null
        }

    fun go(route: String) {
        onClose()
        navController.navigate(route)
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
    ) {
        DrawerHeader(
            isLoggedIn = isLoggedIn,
            name = if (isLoggedIn) accountName else stringResource(R.string.app_name),
            subtitle = connected.ifEmpty { null },
            imageUrl = accountImageUrl.takeIf { isLoggedIn },
            onClick = { go("accounts") },
        )

        DrawerGroup {
            DrawerItem(
                iconRes = R.drawable.login,
                title = stringResource(R.string.login),
                subtitle = spotifyLabel.takeIf { !isLoggedIn },
                onClick = { go("accounts") },
            )
            DrawerItem(R.drawable.stats, stringResource(R.string.stats), onClick = { go("stats") })
            DrawerItem(R.drawable.history, stringResource(R.string.history), onClick = { go("history") })
            DrawerItem(
                iconRes = R.drawable.group_outlined,
                title = stringResource(R.string.together),
                onClick = { go("listen_together_from_topbar") },
            )
        }

        DrawerGroup {
            DrawerItem(
                iconRes = R.drawable.integration,
                title = stringResource(R.string.integrations),
                onClick = { go("settings/integrations") },
            )
            DrawerItem(
                iconRes = R.drawable.settings,
                title = stringResource(R.string.settings),
                badge = updateAvailable,
                onClick = { go("settings") },
            )
            if (downloadUrl != null) {
                DrawerItem(
                    iconRes = R.drawable.update,
                    title = stringResource(R.string.new_version_available),
                    subtitle = latestVersionName,
                    badge = true,
                    onClick = { uriHandler.openUri(downloadUrl) },
                )
            }
        }

        AccountTokenEntry(modifier = Modifier.padding(horizontal = 8.dp))

        Spacer(Modifier.height(24.dp))
        Text(
            text = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun DrawerHeader(
    isLoggedIn: Boolean,
    name: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)),
                    ),
                ).clickable(onClick = onClick)
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(68.dp)
                    .border(2.dp, accent.copy(alpha = 0.7f), CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoggedIn && imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.person),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DrawerGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 6.dp),
        content = content,
    )
}

@Composable
private fun DrawerItem(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    badge: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(badge = { if (badge) Badge() }) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.navigate_next),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}
