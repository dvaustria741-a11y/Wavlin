/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import timber.log.Timber
import com.wavlin.music.utils.reportException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.utils.parseCookieString
import com.wavlin.music.BuildConfig
import com.wavlin.music.R
import com.wavlin.music.constants.AccountChannelHandleKey
import com.wavlin.music.constants.AccountEmailKey
import com.wavlin.music.constants.AccountNameKey
import com.wavlin.music.constants.DataSyncIdKey
import com.wavlin.music.constants.InnerTubeCookieKey
import com.wavlin.music.constants.UseLoginForBrowse
import com.wavlin.music.constants.VisitorDataKey
import com.wavlin.music.constants.YtmSyncKey
import com.wavlin.music.ui.component.DefaultDialog
import com.wavlin.music.ui.component.InfoLabel
import com.wavlin.music.ui.component.Material3SettingsGroup
import com.wavlin.music.ui.component.Material3SettingsItem
import com.wavlin.music.ui.component.PreferenceEntry
import com.wavlin.music.ui.component.TextFieldDialog
import com.wavlin.music.utils.Updater
import com.wavlin.music.utils.rememberPreference
import com.wavlin.music.viewmodels.AccountSettingsViewModel
import com.wavlin.music.spotify.account.SpotifyAuthState
import com.wavlin.music.viewmodels.HomeViewModel
import com.wavlin.music.viewmodels.SpotifyViewModel

@Composable
fun AccountSettings(
    navController: NavController,
    onClose: () -> Unit,
    latestVersionName: String,
    /** Small popover layout (profile header, no title bar / close button). */
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val (accountNamePref, onAccountNameChange) = rememberPreference(AccountNameKey, "")
    val (accountEmail, onAccountEmailChange) = rememberPreference(AccountEmailKey, "")
    val (accountChannelHandle, onAccountChannelHandleChange) = rememberPreference(AccountChannelHandleKey, "")
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val (visitorData, onVisitorDataChange) = rememberPreference(VisitorDataKey, "")
    val (dataSyncId, onDataSyncIdChange) = rememberPreference(DataSyncIdKey, "")

    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

    val homeViewModel: HomeViewModel = hiltViewModel()
    val accountSettingsViewModel: AccountSettingsViewModel = hiltViewModel()
    val spotifyViewModel: SpotifyViewModel = hiltViewModel()
    val spotifyState by spotifyViewModel.authState.collectAsStateWithLifecycle()
    val spotifyName by spotifyViewModel.accountName.collectAsStateWithLifecycle()
    val spotifyConnected = spotifyState == SpotifyAuthState.CONNECTED
    val accountName by homeViewModel.accountName.collectAsStateWithLifecycle()
    val accountImageUrl by homeViewModel.accountImageUrl.collectAsStateWithLifecycle()

    var showToken by remember { mutableStateOf(false) }
    var showTokenEditor by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLoginPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .background(Color.Transparent)
            .padding(if (compact) 12.dp else 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (compact) {
            val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoggedIn && accountImageUrl != null) {
                        AsyncImage(
                            model = accountImageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.person),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isLoggedIn && accountName.isNotBlank()) accountName else stringResource(R.string.account_guest),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            when {
                                hour < 12 -> R.string.account_good_morning
                                hour < 18 -> R.string.account_good_afternoon
                                else -> R.string.account_good_evening
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.close), contentDescription = null)
            }
        }

        Spacer(Modifier.height(12.dp))
        }

        // Logout confirmation dialog
        if (showLogoutDialog) {
            DefaultDialog(
                onDismiss = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.logout_dialog_title)) },
                content = {
                    Text(
                        text = stringResource(R.string.logout_dialog_message),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                },
                buttons = {
                    TextButton(
                        onClick = {
                            Timber.d("[LOGOUT_CLEAR] User chose to clear data")
                            scope.launch {
                                try {
                                    Timber.d("[LOGOUT_CLEAR] Starting clear and logout process")
                                    // Forget account first (stops all sync), then clear data.
                                    // This prevents background syncs from re-adding songs.
                                    accountSettingsViewModel.logoutAndClearLibraryData(context)
                                    Timber.d("[LOGOUT_CLEAR] Library data cleared and account forgotten")
                                } catch (e: Exception) {
                                    Timber.e(e, "[LOGOUT_CLEAR] Error clearing library data, proceeding with logout")
                                    reportException(e)
                                }
                                onInnerTubeCookieChange("")
                                Timber.d("[LOGOUT_CLEAR] Logout complete")
                                showLogoutDialog = false
                                onClose()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.logout_clear))
                    }
                    TextButton(
                        onClick = {
                            Timber.d("[LOGOUT_KEEP] User chose to keep data")
                            scope.launch {
                                Timber.d("[LOGOUT_KEEP] Starting logout process (keeping data)")
                                accountSettingsViewModel.logoutKeepData(context, onInnerTubeCookieChange)
                                Timber.d("[LOGOUT_KEEP] Logout complete")
                                showLogoutDialog = false
                                onClose()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.logout_keep))
                    }
                }
            )
        }

        if (showLoginPicker) {
            DefaultDialog(
                onDismiss = { showLoginPicker = false },
                title = { Text(stringResource(R.string.login_choose_service)) },
                content = {
                    LoginServiceRow(
                        icon = painterResource(R.drawable.ic_youtube_music),
                        tint = Color(0xFFFF0000),
                        name = "YouTube Music",
                        connected = isLoggedIn,
                        status =
                            if (isLoggedIn) {
                                listOf(stringResource(R.string.spotify_connected), accountName).filter { it.isNotBlank() }.joinToString(" · ")
                            } else {
                                stringResource(R.string.spotify_not_connected)
                            },
                        actionLabel = if (isLoggedIn) stringResource(R.string.action_logout) else null,
                        onAction = {
                            showLoginPicker = false
                            showLogoutDialog = true
                        },
                        onClick = {
                            showLoginPicker = false
                            onClose()
                            navController.navigate(if (isLoggedIn) "account" else "login")
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    LoginServiceRow(
                        icon = painterResource(R.drawable.ic_spotify_logo),
                        tint = Color(0xFF1DB954),
                        name = stringResource(R.string.spotify),
                        connected = spotifyConnected,
                        status =
                            when {
                                spotifyConnected ->
                                    listOf(stringResource(R.string.spotify_connected), spotifyName).filter { it.isNotBlank() }.joinToString(" · ")
                                spotifyState == SpotifyAuthState.LOADING -> stringResource(R.string.spotify_connecting)
                                else -> stringResource(R.string.spotify_not_connected)
                            },
                        actionLabel = if (spotifyConnected) stringResource(R.string.action_logout) else null,
                        onAction = { spotifyViewModel.logout() },
                        onClick = {
                            showLoginPicker = false
                            onClose()
                            navController.navigate(if (spotifyConnected) "settings/integrations/spotify" else "spotify_signin")
                        }
                    )
                },
                buttons = {
                    TextButton(onClick = { showLoginPicker = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }

        if (showTokenEditor) {
            val text = """
                ***INNERTUBE COOKIE*** =$innerTubeCookie
                ***VISITOR DATA*** =$visitorData
                ***DATASYNC ID*** =$dataSyncId
                ***ACCOUNT NAME*** =$accountNamePref
                ***ACCOUNT EMAIL*** =$accountEmail
                ***ACCOUNT CHANNEL HANDLE*** =$accountChannelHandle
            """.trimIndent()

            TextFieldDialog(
                initialTextFieldValue = TextFieldValue(text),
                onDone = { data ->
                    var cookie = ""
                    var visitorDataValue = ""
                    var dataSyncIdValue = ""
                    var accountNameValue = ""
                    var accountEmailValue = ""
                    var accountChannelHandleValue = ""

                    data.split("\n").forEach {
                        when {
                            it.startsWith("***INNERTUBE COOKIE*** =") -> cookie = it.substringAfter("=")
                            it.startsWith("***VISITOR DATA*** =") -> visitorDataValue = it.substringAfter("=")
                            it.startsWith("***DATASYNC ID*** =") -> dataSyncIdValue = it.substringAfter("=")
                            it.startsWith("***ACCOUNT NAME*** =") -> accountNameValue = it.substringAfter("=")
                            it.startsWith("***ACCOUNT EMAIL*** =") -> accountEmailValue = it.substringAfter("=")
                            it.startsWith("***ACCOUNT CHANNEL HANDLE*** =") -> accountChannelHandleValue = it.substringAfter("=")
                        }
                    }
                    // Write all credentials atomically to DataStore and wait for completion
                    // before restarting, preventing the race condition where the process
                    // would be killed before async DataStore coroutines finished writing.
                    accountSettingsViewModel.saveTokenAndRestart(
                        context = context,
                        cookie = cookie,
                        visitorData = visitorDataValue,
                        dataSyncId = dataSyncIdValue,
                        accountName = accountNameValue,
                        accountEmail = accountEmailValue,
                        accountChannelHandle = accountChannelHandleValue,
                    )
                },
                onDismiss = { showTokenEditor = false },
                singleLine = false,
                maxLines = 20,
                isInputValid = { fullText ->
                    // Extract the cookie value from the formatted template line,
                    // then validate it separately — avoids the bug where parseCookieString
                    // received the entire multi-line template and failed to find "SAPISID"
                    // as a key because the "***INNERTUBE COOKIE*** =" prefix shadowed it.
                    val cookieLine = fullText.lines()
                        .find { it.startsWith("***INNERTUBE COOKIE*** =") }
                    val cookieValue = cookieLine?.substringAfter("***INNERTUBE COOKIE*** =")?.trim() ?: ""
                    cookieValue.isNotEmpty() && "SAPISID" in parseCookieString(cookieValue)
                },
                extraContent = {
                    Spacer(Modifier.height(8.dp))
                    InfoLabel(text = stringResource(R.string.token_adv_login_description))
                }
            )
        }

        // Plain rows like Settings/Integrations below: no card behind them.
        val loginDescription = buildList {
            if (isLoggedIn) add("YouTube Music")
            if (spotifyConnected) add(stringResource(R.string.spotify))
        }.joinToString(" · ")

        PreferenceEntry(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoggedIn && accountImageUrl != null) {
                        AsyncImage(
                            model = accountImageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                        )

                        Spacer(Modifier.width(12.dp))
                    }

                    Text(text = if (isLoggedIn) accountName else stringResource(R.string.login))
                }
            },
            description = loginDescription.ifEmpty { null },
            icon = if (!isLoggedIn) {
                { Icon(painterResource(R.drawable.login), null) }
            } else null,
            onClick = { showLoginPicker = true },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        if (isLoggedIn) {
            PreferenceEntry(
                title = {
                    Text(
                        if (showToken) stringResource(R.string.token_shown) else stringResource(R.string.token_hidden)
                    )
                },
                icon = { Icon(painterResource(R.drawable.token), null) },
                onClick = {
                    if (!showToken) showToken = true else showTokenEditor = true
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
        }

        PreferenceEntry(
            title = { Text(stringResource(R.string.more_content)) },
            icon = { Icon(painterResource(R.drawable.cached), null) },
            trailingContent = {
                Switch(
                    enabled = isLoggedIn,
                    checked = useLoginForBrowse,
                    onCheckedChange = {
                        YouTube.useLoginForBrowse = it
                        onUseLoginForBrowseChange(it)
                    },
                    thumbContent = {
                        Icon(
                            painter = painterResource(
                                id = if (useLoginForBrowse) R.drawable.check else R.drawable.close
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        PreferenceEntry(
            title = { Text(stringResource(R.string.yt_sync)) },
            icon = { Icon(painterResource(R.drawable.cached), null) },
            trailingContent = {
                Switch(
                    enabled = isLoggedIn,
                    checked = ytmSync,
                    onCheckedChange = onYtmSyncChange,
                    thumbContent = {
                        Icon(
                            painter = painterResource(
                                id = if (ytmSync) R.drawable.check else R.drawable.close
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Transparent)
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.integrations)) },
                icon = { Icon(painterResource(R.drawable.integration), null) },
                onClick = {
                    onClose()
                    navController.navigate("settings/integrations")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            )

            Spacer(Modifier.height(4.dp))

            PreferenceEntry(
                title = { Text(stringResource(R.string.settings)) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.VERSION_NAME) {
                                Badge()
                            }
                        }
                    ) {
                        Icon(painterResource(R.drawable.settings), contentDescription = null)
                    }
                },
                onClick = {
                    onClose()
                    navController.navigate("settings")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            )

            Spacer(Modifier.height(4.dp))

            if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.VERSION_NAME) {
                val releaseInfo = Updater.getCachedLatestRelease()
                val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }
                
                if (downloadUrl != null) {
                    PreferenceEntry(
                        title = {
                            Text(text = stringResource(R.string.new_version_available))
                        },
                        description = latestVersionName,
                        icon = {
                            BadgedBox(badge = { Badge() }) {
                                Icon(painterResource(R.drawable.update), null)
                            }
                        },
                        onClick = {
                            uriHandler.openUri(downloadUrl)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginServiceRow(
    icon: androidx.compose.ui.graphics.painter.Painter,
    tint: Color,
    name: String,
    status: String,
    connected: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
