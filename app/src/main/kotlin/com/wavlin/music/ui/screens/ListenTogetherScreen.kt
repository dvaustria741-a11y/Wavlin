/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens

import com.wavlin.music.ui.component.pinkGlassPanel
import com.wavlin.music.ui.component.pinkTopBarColors
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import com.wavlin.music.ui.component.ScriptTitle
import com.wavlin.music.ui.component.lilyGlassAuto
import com.wavlin.music.ui.component.lilyNinePatch
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.wavlin.music.LocalListenTogetherManager
import com.wavlin.music.LocalPlayerAwareWindowInsets
import com.wavlin.music.R
import com.wavlin.music.constants.AppBarHeight
import com.wavlin.music.constants.ListenTogetherInTopBarKey
import com.wavlin.music.constants.ListenTogetherUsernameKey
import com.wavlin.music.listentogether.ConnectionState
import com.wavlin.music.listentogether.JoinRequestPayload
import com.wavlin.music.listentogether.ListenTogetherEvent
import com.wavlin.music.listentogether.RoomRole
import com.wavlin.music.listentogether.SuggestionReceivedPayload
import com.wavlin.music.listentogether.UserInfo
import com.wavlin.music.ui.component.DefaultDialog
import com.wavlin.music.ui.component.IconButton
import com.wavlin.music.ui.utils.backToMain
import com.wavlin.music.utils.rememberPreference
import kotlinx.coroutines.launch
import androidx.compose.material3.IconButton as MaterialIconButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListenTogetherScreen(
    navController: NavController,
    showTopBar: Boolean = false,
) {
    val context = LocalContext.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val windowInsets = LocalPlayerAwareWindowInsets.current
    val joiningRoomTemplate = stringResource(R.string.joining_room)

    if (listenTogetherManager == null) {
        NotConfiguredContent()
        return
    }

    val connectionState by listenTogetherManager.connectionState.collectAsStateWithLifecycle()
    val roomState by listenTogetherManager.roomState.collectAsStateWithLifecycle()
    val userId by listenTogetherManager.userId.collectAsStateWithLifecycle()
    val pendingJoinRequests by listenTogetherManager.pendingJoinRequests.collectAsStateWithLifecycle()
    val pendingSuggestions by listenTogetherManager.pendingSuggestions.collectAsStateWithLifecycle()

    val (listenTogetherInTopBar) = rememberPreference(ListenTogetherInTopBarKey, defaultValue = true)
    val shouldShowTopBar = showTopBar || listenTogetherInTopBar

    var savedUsername by rememberPreference(ListenTogetherUsernameKey, "")
    var roomCodeInput by rememberSaveable { mutableStateOf("") }
    var usernameInput by rememberSaveable { mutableStateOf(savedUsername) }

    var isCreatingRoom by rememberSaveable { mutableStateOf(false) }
    var isJoiningRoom by rememberSaveable { mutableStateOf(false) }
    var joinErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    var selectedUserForMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUsername by rememberSaveable { mutableStateOf<String?>(null) }

    val waitingForApprovalText = stringResource(R.string.waiting_for_approval)
    val invalidRoomCodeText = stringResource(R.string.invalid_room_code)
    val joinRequestDeniedText = stringResource(R.string.join_request_denied)

    LaunchedEffect(savedUsername) {
        if (usernameInput.isBlank() && savedUsername.isNotBlank()) {
            usernameInput = savedUsername
        }
    }

    LaunchedEffect(listenTogetherManager) {
        listenTogetherManager.events.collect { event ->
            when (event) {
                is ListenTogetherEvent.JoinRejected -> {
                    val reason = event.reason
                    joinErrorMessage =
                        when {
                            reason.isNullOrBlank() -> joinRequestDeniedText
                            reason.contains("invalid", ignoreCase = true) -> invalidRoomCodeText
                            else -> "$joinRequestDeniedText: $reason"
                        }
                    isJoiningRoom = false
                    isCreatingRoom = false
                }

                is ListenTogetherEvent.JoinApproved -> {
                    isJoiningRoom = false
                    joinErrorMessage = null
                }

                is ListenTogetherEvent.RoomCreated -> {
                    isCreatingRoom = false
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ListenTogetherRoom", event.roomCode)
                    clipboard.setPrimaryClip(clip)
                }

                else -> {}
            }
        }
    }

    val isInRoom = listenTogetherManager.isInRoom
    val isHost = roomState?.hostId == userId

    // User action menu dialog
    if (selectedUserForMenu != null && selectedUsername != null) {
        UserActionDialog(
            username = selectedUsername ?: "",
            onKick = {
                selectedUserForMenu?.let {
                    listenTogetherManager.kickUser(it, "Removed by host")
                }
                selectedUserForMenu = null
                selectedUsername = null
            },
            onPermanentKick = {
                selectedUserForMenu?.let { userId ->
                    selectedUsername?.let { username ->
                        listenTogetherManager.blockUser(username)
                        listenTogetherManager.kickUser(userId, R.string.user_blocked_by_host.toString())
                    }
                }
                selectedUserForMenu = null
                selectedUsername = null
            },
            onTransferOwnership = {
                selectedUserForMenu?.let {
                    listenTogetherManager.transferHost(it)
                }
                selectedUserForMenu = null
                selectedUsername = null
            },
            onDismiss = {
                selectedUserForMenu = null
                selectedUsername = null
            },
        )
    }

    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop = backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazyListState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (LocalPinkLilyTheme.current) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background)
                .imePadding(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = windowInsets.asPaddingValues().calculateTopPadding() + 16.dp,
                bottom = windowInsets.asPaddingValues().calculateBottomPadding() + 16.dp + AppBarHeight,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        item {
            HeaderSection(isInRoom = isInRoom)
        }

        // Connection status card
        item {
            ConnectionStatusCard(
                connectionState = connectionState,
                onConnect = { listenTogetherManager.connect() },
                onDisconnect = { listenTogetherManager.disconnect() },
                onReconnect = { listenTogetherManager.forceReconnect() },
            )
        }

        if (connectionState == ConnectionState.CONNECTED && !isInRoom) {
            item {
                Text(
                    text = stringResource(R.string.listen_together_background_disconnect_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (isInRoom) {
            // Room status card
            roomState?.let { room ->
                item {
                    RoomStatusCard(
                        roomCode = room.roomCode,
                        isHost = isHost,
                        context = context,
                    )
                }

                // Connected users
                val connectedUsers = room.users.filter { it.isConnected }
                val currentUserIdValue = userId ?: ""
                item {
                    ConnectedUsersSection(
                        users = connectedUsers,
                        isHost = isHost,
                        currentUserId = currentUserIdValue,
                        onUserClick = { clickedUserId, username ->
                            if (isHost && clickedUserId != currentUserIdValue) {
                                selectedUserForMenu = clickedUserId
                                selectedUsername = username
                            }
                        },
                    )
                }

                // Pending join requests (host only)
                if (isHost && pendingJoinRequests.isNotEmpty()) {
                    item {
                        PendingJoinRequestsSection(
                            requests = pendingJoinRequests,
                            onApprove = { listenTogetherManager.approveJoin(it) },
                            onReject = { listenTogetherManager.rejectJoin(it, "Rejected by host") },
                        )
                    }
                }

                // Pending suggestions (host only)
                if (isHost && pendingSuggestions.isNotEmpty()) {
                    item {
                        PendingSuggestionsSection(
                            suggestions = pendingSuggestions,
                            onApprove = { listenTogetherManager.approveSuggestion(it) },
                            onReject = { listenTogetherManager.rejectSuggestion(it, "Rejected by host") },
                        )
                    }
                }

                // Leave room button
                item {
                    Button(
                        onClick = { listenTogetherManager.leaveRoom() },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.logout),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.leave_room),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        } else {
            // Join/Create room section
            item {
                JoinCreateRoomSection(
                    usernameInput = usernameInput,
                    onUsernameChange = { usernameInput = it },
                    roomCodeInput = roomCodeInput,
                    onRoomCodeChange = { roomCodeInput = it },
                    savedUsername = savedUsername,
                    isJoiningRoom = isJoiningRoom,
                    joinErrorMessage = joinErrorMessage,
                    waitingForApprovalText = waitingForApprovalText,
                    bringIntoViewRequester = bringIntoViewRequester,
                    onCreateRoom = {
                        val username = usernameInput.takeIf { it.isNotBlank() } ?: savedUsername
                        val finalUsername = username.trim()
                        if (finalUsername.isNotBlank()) {
                            savedUsername = finalUsername
                            Toast.makeText(context, R.string.creating_room, Toast.LENGTH_SHORT).show()
                            isCreatingRoom = true
                            isJoiningRoom = false
                            joinErrorMessage = null
                            listenTogetherManager.connect()
                            listenTogetherManager.createRoom(finalUsername)
                        } else {
                            Toast.makeText(context, R.string.error_username_empty, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onJoinRoom = {
                        val username = usernameInput.takeIf { it.isNotBlank() } ?: savedUsername
                        val finalUsername = username.trim()
                        if (finalUsername.isNotBlank()) {
                            savedUsername = finalUsername
                            Toast
                                .makeText(
                                    context,
                                    String.format(joiningRoomTemplate, roomCodeInput),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            isJoiningRoom = true
                            isCreatingRoom = false
                            joinErrorMessage = null
                            listenTogetherManager.connect()
                            listenTogetherManager.joinRoom(roomCodeInput, finalUsername)
                        } else {
                            Toast.makeText(context, R.string.error_username_empty, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFieldFocused = {
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                    },
                )
            }
        }

        // Settings link
        item {
            SettingsLinkCard(
                onClick = { navController.navigate("settings/integrations/listen_together") },
            )
        }
    }

    if (shouldShowTopBar) {
        TopAppBar(
            modifier = Modifier.background(if (LocalPinkLilyTheme.current) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            title = {
                if (LocalPinkLilyTheme.current) {
                    ScriptTitle(text = stringResource(R.string.together), fontSize = 30.sp, showHeart = false, swooshWidth = 84.dp)
                } else {
                    Text(stringResource(R.string.together))
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

@Composable
private fun NotConfiguredContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.group),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.listen_together),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.listen_together_not_configured),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderSection(isInRoom: Boolean = false) {
    if (isInRoom) return

    val pink = LocalPinkLilyTheme.current
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ringTransition = rememberInfiniteTransition(label = "togetherRing")
        val ringPhase by ringTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
            label = "togetherRingPhase",
        )
        Box(
            modifier = Modifier.size(156.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (!pink) {
                repeat(2) { i ->
                    Box(
                        modifier =
                            Modifier
                                .size(100.dp)
                                .graphicsLayer {
                                    val q = (ringPhase + i * 0.5f) % 1f
                                    scaleX = 1f + q * 0.5f
                                    scaleY = 1f + q * 0.5f
                                    alpha = (1f - q) * 0.4f
                                }.border(2.dp, cs.primary, RoundedCornerShape(32.dp)),
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .let {
                            if (pink) {
                                it.lilyGlassAuto()
                            } else {
                                it.background(
                                    Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer)),
                                )
                            }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.group_outlined),
                    contentDescription = null,
                    tint = if (pink) com.wavlin.music.ui.theme.PinkInk else cs.onPrimaryContainer,
                    modifier = Modifier.size(52.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (pink) {
            ScriptTitle(text = stringResource(R.string.listen_together), showHeart = false)
        } else {
            Text(
                text = stringResource(R.string.listen_together),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                color = cs.onBackground,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.listen_together_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isOff = connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR
    val busy = connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING
    val dotColor =
        when (connectionState) {
            ConnectionState.CONNECTED -> cs.primary
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> cs.tertiary
            ConnectionState.ERROR -> cs.error
            ConnectionState.DISCONNECTED -> cs.outline
        }
    val pulse = rememberInfiniteTransition(label = "statusDot")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (busy || connectionState == ConnectionState.CONNECTED) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "statusDotAlpha",
    )
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                ),
        shape = RoundedCornerShape(28.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (connectionState) {
                        ConnectionState.CONNECTED -> cs.primaryContainer
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> cs.secondaryContainer
                        ConnectionState.ERROR -> cs.errorContainer
                        ConnectionState.DISCONNECTED -> cs.surfaceContainerHigh
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(22.dp)
                                .graphicsLayer { alpha = pulseAlpha * 0.35f }
                                .clip(CircleShape)
                                .background(dotColor),
                    )
                    Box(
                        modifier =
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text =
                        when (connectionState) {
                            ConnectionState.CONNECTED -> stringResource(R.string.listen_together_connected)
                            ConnectionState.CONNECTING -> stringResource(R.string.listen_together_connecting)
                            ConnectionState.RECONNECTING -> stringResource(R.string.listen_together_reconnecting)
                            ConnectionState.ERROR -> stringResource(R.string.listen_together_error)
                            ConnectionState.DISCONNECTED -> stringResource(R.string.listen_together_disconnected)
                        },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color =
                        when (connectionState) {
                            ConnectionState.CONNECTED -> cs.onPrimaryContainer
                            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> cs.onSecondaryContainer
                            ConnectionState.ERROR -> cs.onErrorContainer
                            ConnectionState.DISCONNECTED -> cs.onSurface
                        },
                    modifier = Modifier.weight(1f),
                )
                if (isOff) {
                    Button(
                        onClick = onConnect,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.link),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.connect), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (busy) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    color = cs.primary,
                )
            }

            if (!isOff) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    ) {
                        Text(stringResource(R.string.disconnect), fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = onReconnect,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("Reconnect", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomStatusCard(
    roomCode: String,
    isHost: Boolean,
    context: Context,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.room_code),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = roomCode,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    if (isHost) {
                        stringResource(R.string.listen_together_you_are_host)
                    } else {
                        stringResource(R.string.listen_together_you_are_guest)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (isHost) {
                Spacer(modifier = Modifier.height(16.dp))
                val inviteLink =
                    remember(roomCode) {
                        "https://wavlin.cc/listen?code=$roomCode"
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Listen Together Link", inviteLink)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.link),
                            contentDescription = stringResource(R.string.copy_link),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.copy_link))
                    }

                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Room Code", roomCode)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.content_copy),
                            contentDescription = stringResource(R.string.copy_code),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.copy_code))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedUsersSection(
    users: List<UserInfo>,
    isHost: Boolean,
    currentUserId: String,
    onUserClick: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = "${stringResource(R.string.connected_users)} (${users.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                users.forEach { user ->
                    UserAvatar(
                        user = user,
                        isCurrentUser = user.userId == currentUserId,
                        isClickable = isHost && user.userId != currentUserId,
                        onClick = { onUserClick(user.userId, user.username) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UserAvatar(
    user: UserInfo,
    isCurrentUser: Boolean,
    isClickable: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .width(72.dp)
                .clickable(enabled = isClickable, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color =
                    when {
                        user.isHost -> MaterialTheme.colorScheme.primary
                        isCurrentUser -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = user.username.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color =
                            when {
                                user.isHost -> MaterialTheme.colorScheme.onPrimary
                                isCurrentUser -> MaterialTheme.colorScheme.onSecondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }

            if (user.isHost || isCurrentUser) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(20.dp),
                    shape = CircleShape,
                    color = if (user.isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (user.isHost) R.drawable.crown else R.drawable.person,
                                ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = user.username,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isCurrentUser) FontWeight.Bold else FontWeight.Medium,
            color = if (user.isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        if (user.isHost) {
            Text(
                text = stringResource(R.string.host_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
        } else if (isCurrentUser) {
            Text(
                text = stringResource(R.string.you_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun PendingJoinRequestsSection(
    requests: List<JoinRequestPayload>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_join_requests),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            requests.forEach { request ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondary,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(
                                text = request.username.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = request.username,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    MaterialIconButton(onClick = { onApprove(request.userId) }) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = stringResource(R.string.approve),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    MaterialIconButton(onClick = { onReject(request.userId) }) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.reject),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingSuggestionsSection(
    suggestions: List<SuggestionReceivedPayload>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.pending_suggestions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            suggestions.forEach { suggestion ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = suggestion.trackInfo.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = suggestion.fromUsername,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MaterialIconButton(onClick = { onApprove(suggestion.suggestionId) }) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = stringResource(R.string.approve),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    MaterialIconButton(onClick = { onReject(suggestion.suggestionId) }) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.reject),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinCreateRoomSection(
    usernameInput: String,
    onUsernameChange: (String) -> Unit,
    roomCodeInput: String,
    onRoomCodeChange: (String) -> Unit,
    savedUsername: String,
    isJoiningRoom: Boolean,
    joinErrorMessage: String?,
    waitingForApprovalText: String,
    bringIntoViewRequester: BringIntoViewRequester,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onFieldFocused: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.primary,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = cs.surfaceContainerLow,
            unfocusedContainerColor = cs.surfaceContainerLow,
        )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Step 1: who you are
            Text(
                text = stringResource(R.string.username),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = cs.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
            OutlinedTextField(
                value = usernameInput,
                onValueChange = onUsernameChange,
                placeholder = { Text(stringResource(R.string.enter_username)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.person), null, tint = cs.primary)
                },
                trailingIcon = {
                    if (usernameInput.isNotBlank()) {
                        MaterialIconButton(onClick = { onUsernameChange("") }) {
                            Icon(painterResource(R.drawable.close), null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFieldFocused() },
            )

            Spacer(Modifier.height(10.dp))

            // Step 2: optional room code, big and spaced like a code
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.room_code),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Text(
                    text = "${roomCodeInput.length}/8",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            OutlinedTextField(
                value = roomCodeInput,
                onValueChange = { if (it.length <= 8) onRoomCodeChange(it.uppercase()) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.enter_room_code),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                },
                trailingIcon = {
                    if (roomCodeInput.isNotBlank()) {
                        MaterialIconButton(onClick = { onRoomCodeChange("") }) {
                            Icon(painterResource(R.drawable.close), null)
                        }
                    }
                },
                textStyle =
                    MaterialTheme.typography.titleLarge.copy(
                        textAlign = TextAlign.Center,
                        letterSpacing = 6.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = fieldColors,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bringIntoViewRequester)
                        .onFocusChanged { if (it.isFocused) onFieldFocused() },
            )

            // Waiting for approval indicator
            AnimatedVisibility(
                visible = isJoiningRoom,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = cs.primaryContainer,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = cs.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = waitingForApprovalText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onPrimaryContainer,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Error message
            AnimatedVisibility(
                visible = joinErrorMessage != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = cs.errorContainer,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.error),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = cs.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = joinErrorMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            val hasUsername = usernameInput.trim().isNotBlank() || savedUsername.isNotBlank()
            val hasRoomCode = roomCodeInput.length == 8

            // Create Room: username given, no full code
            AnimatedVisibility(visible = hasUsername && !hasRoomCode) {
                Button(
                    onClick = onCreateRoom,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp),
                    enabled = hasUsername,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.create_room), fontWeight = FontWeight.Bold)
                }
            }

            // Join Room: username and full code given
            AnimatedVisibility(visible = hasUsername && hasRoomCode) {
                Button(
                    onClick = onJoinRoom,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp),
                    enabled = hasUsername && hasRoomCode,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.tertiary),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.login),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.join_room), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SettingsLinkCard(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(cs.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.settings),
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.listen_together_settings_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_forward),
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun UserActionDialog(
    username: String,
    onKick: () -> Unit,
    onPermanentKick: () -> Unit,
    onTransferOwnership: () -> Unit,
    onDismiss: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.group),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.manage_user),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = username,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Kick button
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onKick),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.kick_user),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.kick_user_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Permanently kick button
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPermanentKick),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.permanently_kick_user),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.permanently_kick_user_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Transfer ownership button
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTransferOwnership),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.crown),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.transfer_ownership),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.transfer_ownership_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// Secret Pink glass versions of Card / Button / FilledTonalButton. Other themes get the stock Material ones.
@Composable
private fun Card(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = CardDefaults.shape,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (LocalPinkLilyTheme.current) {
        androidx.compose.material3.Card(
            modifier = modifier.pinkGlassPanel(RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            content = content,
        )
    } else {
        androidx.compose.material3.Card(modifier = modifier, shape = shape, colors = colors, content = content)
    }
}

@Composable
private fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.shape,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    if (LocalPinkLilyTheme.current) {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier.lilyGlassAuto(),
            enabled = enabled,
            shape = RoundedCornerShape(50),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = com.wavlin.music.ui.theme.PinkInk,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContentColor = com.wavlin.music.ui.theme.PinkInk.copy(alpha = 0.4f),
                ),
            elevation = null,
            content = content,
        )
    } else {
        androidx.compose.material3.Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, content = content)
    }
}

@Composable
private fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.filledTonalShape,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    if (LocalPinkLilyTheme.current) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    } else {
        androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, content = content)
    }
}
