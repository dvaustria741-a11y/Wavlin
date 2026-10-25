/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sign-in flow adapted from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.ui.screens.spotify

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.spotify.account.SpotifyAuth
import com.wavlin.music.spotify.account.SpotifyAuthState
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.component.pinkTopBarColors
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.viewmodels.SpotifyViewModel

val SpotifyGreen = Color(0xFF1DB954)

/** Spotify's own login page in a WebView. Once it holds the `sp_dc` cookie the account is connected. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySignInScreen(
    navController: NavController,
    viewModel: SpotifyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val accountName by viewModel.accountName.collectAsStateWithLifecycle()

    var submitted by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showManualSheet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    LaunchedEffect(authState, submitted) {
        if (submitted && authState == SpotifyAuthState.CONNECTED) {
            Toast.makeText(context, context.getString(R.string.spotify_welcome, accountName), Toast.LENGTH_SHORT).show()
            navController.navigateUp()
        }
    }

    val busy = authState == SpotifyAuthState.LOADING && submitted
    val failed = authState == SpotifyAuthState.ERROR && submitted

    Scaffold(
        topBar = {
            TopAppBar(
                colors = pinkTopBarColors(),
                title = {
                    Column {
                        Text(stringResource(R.string.spotify))
                        Text(
                            stringResource(R.string.spotify_sign_in_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showManualSheet = true }, onLongClick = {}) {
                        Icon(painterResource(R.drawable.key), contentDescription = stringResource(R.string.spotify_manual_title), tint = SpotifyGreen)
                    }
                    IconButton(onClick = {
                        viewModel.dismissError()
                        webView?.reload()
                    }, onLongClick = {}) {
                        Icon(painterResource(R.drawable.refresh), contentDescription = stringResource(R.string.spotify_reload_page))
                    }
                    IconButton(onClick = {
                        submitted = false
                        CookieManager.getInstance().removeAllCookies(null)
                        webView?.loadUrl(SpotifyAuth.LOGIN_URL)
                        Toast.makeText(context, context.getString(R.string.spotify_session_cleared), Toast.LENGTH_SHORT).show()
                    }, onLongClick = {}) {
                        Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.spotify_clear_session))
                    }
                },
            )
        },
    ) { scaffoldPadding ->
    Column(
        Modifier
            // Scaffold already includes status-bar height in scaffoldPadding;
            // windowInsetsPadding(LocalPlayerAwareWindowInsets) was doubling it.
            .padding(scaffoldPadding)
            .fillMaxSize(),
    ) {
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = SpotifyGreen)
        } else if (loadProgress in 1..99) {
            LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = SpotifyGreen)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        cookies.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // Match the desktop Chrome UA that SpotifyAuth uses for token calls;
                        // the mobile UA triggers Spotify's bot-detection and returns a blank page.
                        settings.userAgentString = SpotifyAuth.USER_AGENT

                        fun captureCookies(url: String?) {
                            if (submitted) return
                            cookies.flush()
                            val all =
                                listOfNotNull(
                                    "https://open.spotify.com",
                                    "https://accounts.spotify.com",
                                    "https://spotify.com",
                                    url,
                                ).joinToString("; ") { cookies.getCookie(it).orEmpty() }
                            val pairs = all.split(";").map { it.trim() }
                            val spDc = pairs.firstOrNull { it.startsWith("sp_dc=") }?.substringAfter("sp_dc=").orEmpty()
                            val spKey = pairs.firstOrNull { it.startsWith("sp_key=") }?.substringAfter("sp_key=").orEmpty()
                            if (spDc.isNotBlank()) {
                                submitted = true
                                viewModel.connectWithCookies(spDc, spKey)
                            }
                        }

                        webChromeClient =
                            object : WebChromeClient() {
                                override fun onProgressChanged(
                                    view: WebView?,
                                    newProgress: Int,
                                ) {
                                    loadProgress = newProgress
                                }

                                // Social logins (Google, Facebook, Apple) open in a popup; load them in this view instead
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?,
                                ): Boolean {
                                    val main = this@apply
                                    val popup =
                                        WebView(ctx).apply {
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                            webViewClient =
                                                object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        view: WebView?,
                                                        request: WebResourceRequest?,
                                                    ): Boolean {
                                                        request?.url?.toString()?.let { main.loadUrl(it) }
                                                        return true
                                                    }
                                                }
                                        }
                                    (resultMsg?.obj as? WebView.WebViewTransport)?.webView = popup
                                    resultMsg?.sendToTarget()
                                    return true
                                }
                            }

                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url?.toString() ?: return false
                                    return !(target.startsWith("http://") || target.startsWith("https://"))
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    captureCookies(url)
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean,
                                ) {
                                    captureCookies(url)
                                }
                            }

                        webView = this
                        loadUrl(SpotifyAuth.LOGIN_URL)
                    }
                },
            )

            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(color = SpotifyGreen)
                            Text(stringResource(R.string.spotify_connecting), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.spotify_connecting_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (failed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.spotify_connection_failed),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                errorMessage ?: stringResource(R.string.spotify_connection_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    viewModel.dismissError()
                                    submitted = false
                                }) { Text(stringResource(R.string.dismiss)) }
                                Button(onClick = {
                                    viewModel.dismissError()
                                    submitted = false
                                    webView?.reload()
                                }) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { showManualSheet = true },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.key), null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.spotify_manual_entry_hint),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.spotify_manual_entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyGreen,
                )
            }
        }

        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.lock), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.spotify_sign_in_privacy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    } // end Scaffold

    if (showManualSheet) {
        SpotifyManualTokenSheet(
            viewModel = viewModel,
            onDismiss = { showManualSheet = false },
            onConnected = {
                showManualSheet = false
                navController.navigateUp()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyManualTokenSheet(
    viewModel: SpotifyViewModel,
    onDismiss: () -> Unit,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var spKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errorMessage = error
    var verifying by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.spotify_manual_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.spotify_manual_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    error = null
                },
                label = { Text(stringResource(R.string.spotify_token_label)) },
                placeholder = { Text(stringResource(R.string.spotify_token_placeholder)) },
                isError = errorMessage != null,
                supportingText = if (errorMessage != null) ({ Text(errorMessage) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = spKey,
                onValueChange = { spKey = it },
                label = { Text(stringResource(R.string.spotify_sp_key_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.spotify_how_to_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.spotify_how_to_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = !verifying,
                    onClick = {
                        if (token.isBlank()) {
                            error = context.getString(R.string.spotify_token_required)
                            return@Button
                        }
                        verifying = true
                        viewModel.loginWithManualToken(token, spKey) { result ->
                            verifying = false
                            result
                                .onSuccess { name ->
                                    Toast.makeText(context, context.getString(R.string.spotify_welcome, name), Toast.LENGTH_SHORT).show()
                                    onConnected()
                                }.onFailure { error = it.message }
                        }
                    },
                ) {
                    Text(stringResource(if (verifying) R.string.spotify_verifying else R.string.connect))
                }
            }
        }
    }
}
