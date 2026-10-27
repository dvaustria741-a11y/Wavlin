/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.utils.parseCookieString
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.constants.InnerTubeCookieKey
import com.wavlin.music.constants.UseLoginForBrowse
import com.wavlin.music.constants.YtmSyncKey
import com.wavlin.music.spotify.account.SpotifyAuthState
import com.wavlin.music.ui.component.DefaultDialog
import com.wavlin.music.ui.component.LocalInterfaceAppleMusic
import com.wavlin.music.utils.rememberPreference
import com.wavlin.music.viewmodels.AccountSettingsViewModel
import com.wavlin.music.viewmodels.HomeViewModel
import com.wavlin.music.viewmodels.SpotifyViewModel
import kotlinx.coroutines.launch

private val SpotifyGreen = Color(0xFF1DB954)
private val YouTubeRed = Color(0xFFFF0000)

@Composable
fun AccountHubScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = hiltViewModel(),
    accountSettingsViewModel: AccountSettingsViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apple = LocalInterfaceAppleMusic.current

    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)
    val isLoggedIn = remember(innerTubeCookie) { "SAPISID" in parseCookieString(innerTubeCookie) }

    val accountName by homeViewModel.accountName.collectAsStateWithLifecycle()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsStateWithLifecycle()
    val spotifyState by spotifyViewModel.authState.collectAsStateWithLifecycle()
    val spotifyName by spotifyViewModel.accountName.collectAsStateWithLifecycle()
    val spotifyConnected = spotifyState == SpotifyAuthState.CONNECTED

    var showLogoutDialog by remember { mutableStateOf(false) }

    val displayName =
        when {
            isLoggedIn && accountName.isNotBlank() -> accountName
            spotifyConnected && spotifyName.isNotBlank() -> spotifyName
            else -> stringResource(R.string.account_guest)
        }
    val statusText =
        buildList {
            if (isLoggedIn) add(stringResource(R.string.youtube_music))
            if (spotifyConnected) add(stringResource(R.string.spotify))
        }.joinToString(" · ").ifEmpty { stringResource(R.string.account_not_logged_in) }

    val cardShape = RoundedCornerShape(if (apple) 18.dp else 24.dp)
    val cardColor =
        if (apple) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    if (showLogoutDialog) {
        DefaultDialog(
            onDismiss = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.logout_dialog_title)) },
            content = {
                Text(
                    text = stringResource(R.string.logout_dialog_message),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { accountSettingsViewModel.logoutAndClearLibraryData(context) }
                            onInnerTubeCookieChange("")
                            showLogoutDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.logout_clear)) }
                TextButton(
                    onClick = {
                        scope.launch {
                            accountSettingsViewModel.logoutKeepData(context, onInnerTubeCookieChange)
                            showLogoutDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.logout_keep)) }
            },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        IconButton(
            onClick = navController::navigateUp,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        ) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = stringResource(R.string.back))
        }

        Spacer(Modifier.height(16.dp))
        AccountAvatar(
            imageUrl = if (isLoggedIn) accountImageUrl else null,
            apple = apple,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.connected_accounts),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceCard(
                icon = painterResource(R.drawable.ic_youtube_music),
                iconTint = YouTubeRed,
                name = stringResource(R.string.youtube_music),
                status =
                    if (isLoggedIn) {
                        accountName.ifBlank { stringResource(R.string.spotify_connected) }
                    } else {
                        stringResource(R.string.spotify_not_connected)
                    },
                connected = isLoggedIn,
                actionLabel = stringResource(if (isLoggedIn) R.string.action_logout else R.string.sign_in),
                onAction = {
                    if (isLoggedIn) showLogoutDialog = true else navController.navigate("login")
                },
                onClick = { navController.navigate(if (isLoggedIn) "account" else "login") },
                shape = cardShape,
                color = cardColor,
            )
            ServiceCard(
                icon = painterResource(R.drawable.ic_spotify_logo),
                iconTint = SpotifyGreen,
                name = stringResource(R.string.spotify),
                status =
                    when {
                        spotifyConnected -> spotifyName.ifBlank { stringResource(R.string.spotify_connected) }
                        spotifyState == SpotifyAuthState.LOADING -> stringResource(R.string.spotify_connecting)
                        else -> stringResource(R.string.spotify_not_connected)
                    },
                connected = spotifyConnected,
                actionLabel = stringResource(if (spotifyConnected) R.string.action_logout else R.string.sign_in),
                onAction = {
                    if (spotifyConnected) spotifyViewModel.logout() else navController.navigate("spotify_signin")
                },
                onClick = {
                    navController.navigate(if (spotifyConnected) "settings/integrations/spotify" else "spotify_signin")
                },
                shape = cardShape,
                color = cardColor,
            )
        }

        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleCard(
                iconRes = R.drawable.cached,
                title = stringResource(R.string.more_content),
                checked = useLoginForBrowse,
                enabled = isLoggedIn,
                onCheckedChange = {
                    YouTube.useLoginForBrowse = it
                    onUseLoginForBrowseChange(it)
                },
                apple = apple,
                shape = cardShape,
                color = cardColor,
            )
            ToggleCard(
                iconRes = R.drawable.sync,
                title = stringResource(R.string.yt_sync),
                checked = ytmSync,
                enabled = isLoggedIn,
                onCheckedChange = onYtmSyncChange,
                apple = apple,
                shape = cardShape,
                color = cardColor,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AccountAvatar(
    imageUrl: String?,
    apple: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(176.dp), contentAlignment = Alignment.Center) {
        if (!apple) {
            // Soft scalloped halo behind the avatar (Material 3 expressive look).
            repeat(2) { index ->
                Box(
                    modifier =
                        Modifier
                            .size(150.dp)
                            .rotate(45f * index)
                            .clip(RoundedCornerShape(44.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(if (apple) 128.dp else 112.dp)
                    .clip(CircleShape)
                    .background(
                        if (apple) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
    }
}

@Composable
private fun ServiceCard(
    icon: Painter,
    iconTint: Color,
    name: String,
    status: String,
    connected: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onClick: () -> Unit,
    shape: RoundedCornerShape,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color)
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.16f)),
        ) {
            Icon(painter = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) SpotifyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (connected) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        } else {
            Button(
                onClick = onAction,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
            ) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ToggleCard(
    iconRes: Int,
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    apple: Boolean,
    shape: RoundedCornerShape,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color)
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors =
                if (apple) {
                    val onSurface = MaterialTheme.colorScheme.onSurface
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = onSurface.copy(alpha = 0.92f),
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = onSurface.copy(alpha = 0.65f),
                        uncheckedTrackColor = onSurface.copy(alpha = 0.14f),
                        uncheckedBorderColor = Color.Transparent,
                    )
                } else {
                    SwitchDefaults.colors()
                },
            thumbContent = {
                Icon(
                    painter = painterResource(if (checked) R.drawable.check else R.drawable.close),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    }
}
