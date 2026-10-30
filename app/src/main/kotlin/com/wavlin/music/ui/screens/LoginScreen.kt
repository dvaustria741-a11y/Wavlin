/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import com.wavlin.music.ui.component.pinkTopBarColors
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.wavlin.innertube.utils.parseCookieString
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.wavlin.innertube.YouTube
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.constants.AccountChannelHandleKey
import com.wavlin.music.constants.AccountEmailKey
import com.wavlin.music.constants.AccountNameKey
import com.wavlin.music.constants.DataSyncIdKey
import com.wavlin.music.constants.InnerTubeCookieKey
import com.wavlin.music.constants.VisitorDataKey
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.utils.reportException
import com.wavlin.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCompletingLogin by remember { mutableStateOf(false) }

    var webView: WebView? = null
    var showManualSheet by remember { mutableStateOf(false) }
    var visitorDataFromWeb by remember { mutableStateOf("") }
    var dataSyncIdFromWeb by remember { mutableStateOf("") }

    // Saves ALL credentials atomically to DataStore, then restarts the app so every service reinitializes.
    suspend fun persistAndRestart(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        name: String,
        email: String,
        channelHandle: String,
    ) {
        val saved =
            withContext(Dispatchers.IO) {
                context.safeDataStoreEdit { settings ->
                    settings[InnerTubeCookieKey] = cookie
                    settings[VisitorDataKey] = visitorData
                    settings[DataSyncIdKey] = dataSyncId
                    settings[AccountNameKey] = name
                    settings[AccountEmailKey] = email
                    settings[AccountChannelHandleKey] = channelHandle
                }
            }

        if (!saved) {
            Timber.e("Login: Failed to persist account data")
            isCompletingLogin = false
            return
        }

        withContext(Dispatchers.Main) {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }

    fun loginWithCookie(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        onError: (String) -> Unit,
    ) {
        if (isCompletingLogin) return
        isCompletingLogin = true
        coroutineScope.launch {
            YouTube.cookie = cookie
            YouTube.visitorData = visitorData
            YouTube.dataSyncId = dataSyncId
            YouTube
                .accountInfo()
                .onSuccess { info ->
                    persistAndRestart(
                        cookie = cookie,
                        visitorData = visitorData,
                        dataSyncId = dataSyncId,
                        name = info.name,
                        email = info.email.orEmpty(),
                        channelHandle = info.channelHandle.orEmpty(),
                    )
                }.onFailure {
                    Timber.e(it, "Login: Manual cookie validation failed")
                    isCompletingLogin = false
                    onError(it.message ?: it.javaClass.simpleName)
                }
        }
    }

    fun completeLogin(onClose: () -> Unit) {
        if (isCompletingLogin) return

        isCompletingLogin = true
        coroutineScope.launch {
            val currentCookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
            if (currentCookie.isBlank()) {
                Timber.d("Login: No YouTube Music cookie found on close, leaving login screen")
                isCompletingLogin = false
                onClose()
                return@launch
            }

            // Save extracted values from the WebView before validating
            val savedVisitorData = visitorDataFromWeb
            val savedDataSyncId = dataSyncIdFromWeb

            // Initialize YouTube object with selected authentication data
            YouTube.cookie = currentCookie
            YouTube.dataSyncId = savedDataSyncId
            YouTube.visitorData = savedVisitorData

            Timber.d("Login: Manual close detected, validating selected account...")

            YouTube
                .accountInfo()
                .onSuccess { info ->
                    Timber.d("Login: Successfully logged in as ${info.name}, restarting app...")

                    // Clean up WebView
                    webView?.apply {
                        stopLoading()
                        clearHistory()
                        clearCache(true)
                        clearFormData()
                    }

                    persistAndRestart(
                        cookie = currentCookie,
                        visitorData = savedVisitorData,
                        dataSyncId = savedDataSyncId,
                        name = info.name,
                        email = info.email.orEmpty(),
                        channelHandle = info.channelHandle.orEmpty(),
                    )
                }.onFailure {
                    Timber.e(it, "Login: Authentication validation failed after manual close")
                    reportException(it)
                    isCompletingLogin = false
                    onClose()
                }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier =
                Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .padding(bottom = 64.dp)
                    .fillMaxSize(),
            factory = { webViewContext ->
                WebView(webViewContext).apply {
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(
                                view: WebView,
                                url: String?,
                            ) {
                                loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                                loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)")

                                // Auto-detect login completion: when the WebView lands on
                                // music.youtube.com with a valid cookie, complete the login.
                                if (url?.contains("music.youtube.com") == true &&
                                    !isCompletingLogin &&
                                    CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                                        .isNotBlank()
                                ) {
                                    Timber.d("Login: Detected authenticated session on music.youtube.com, completing login...")
                                    completeLogin(navController::navigateUp)
                                }
                            }
                        }
                    settings.apply {
                        javaScriptEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onRetrieveVisitorData(newVisitorData: String?) {
                                if (newVisitorData != null) {
                                    visitorDataFromWeb = newVisitorData
                                }
                            }

                            @JavascriptInterface
                            fun onRetrieveDataSyncId(newDataSyncId: String?) {
                                if (newDataSyncId != null) {
                                    dataSyncIdFromWeb = newDataSyncId.substringBefore("||")
                                }
                            }
                        },
                        "Android",
                    )
                    webView = this
                    loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
                }
            },
        )

        TopAppBar(
            colors = pinkTopBarColors(),
            title = { Text(stringResource(R.string.login)) },
            navigationIcon = {
                IconButton(
                    onClick = { completeLogin(navController::navigateUp) },
                    onLongClick = { completeLogin(navController::backToMain) },
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom))
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable { showManualSheet = true },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painterResource(R.drawable.key),
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(R.string.login_manual_entry_hint),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.login_manual_entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showManualSheet) {
        YouTubeManualLoginSheet(
            onDismiss = { showManualSheet = false },
            onSubmit = { cookie, visitorData, dataSyncId, onError ->
                loginWithCookie(cookie, visitorData, dataSyncId, onError)
            },
        )
    }

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            completeLogin(navController::navigateUp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YouTubeManualLoginSheet(
    onDismiss: () -> Unit,
    onSubmit: (cookie: String, visitorData: String, dataSyncId: String, onError: (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var cookie by remember { mutableStateOf("") }
    var visitorData by remember { mutableStateOf("") }
    var dataSyncId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val errorMessage = error

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
            Text(stringResource(R.string.login_manual_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.login_manual_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = cookie,
                onValueChange = {
                    cookie = it
                    error = null
                },
                label = { Text(stringResource(R.string.login_cookie_label)) },
                isError = errorMessage != null,
                supportingText = if (errorMessage != null) ({ Text(errorMessage) }) else null,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = visitorData,
                onValueChange = { visitorData = it },
                label = { Text(stringResource(R.string.login_visitor_data_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dataSyncId,
                onValueChange = { dataSyncId = it },
                label = { Text(stringResource(R.string.login_datasync_id_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.login_manual_how_to_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.login_manual_how_to_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = !verifying,
                    onClick = {
                        val trimmed = cookie.trim().removePrefix("cookie:").trim()
                        when {
                            trimmed.isBlank() -> error = context.getString(R.string.login_cookie_required)
                            "SAPISID" !in parseCookieString(trimmed) -> error = context.getString(R.string.login_cookie_invalid)
                            else -> {
                                verifying = true
                                onSubmit(trimmed, visitorData.trim(), dataSyncId.trim().substringBefore("||")) { message ->
                                    verifying = false
                                    error = message
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (verifying) R.string.spotify_verifying else R.string.connect))
                }
            }
        }
    }
}
