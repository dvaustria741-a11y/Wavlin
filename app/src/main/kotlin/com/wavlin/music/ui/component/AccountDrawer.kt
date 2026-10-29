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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.wavlin.music.ui.screens.settings.AccountSettings

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
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(vertical = 12.dp),
    ) {
        DrawerRow(R.drawable.stats, stringResource(R.string.stats)) {
            onClose()
            navController.navigate("stats")
        }
        DrawerRow(R.drawable.history, stringResource(R.string.history)) {
            onClose()
            navController.navigate("history")
        }
        // AccountSettings scrolls on its own, so it needs a bounded height.
        Box(modifier = Modifier.weight(1f)) {
            AccountSettings(
                navController = navController,
                onClose = onClose,
                latestVersionName = latestVersionName,
                compact = true,
            )
        }
    }
}

@Composable
private fun DrawerRow(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}
