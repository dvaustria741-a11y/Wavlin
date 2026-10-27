/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.wavlin.music.LocalNavController
import com.wavlin.music.ui.screens.settings.AccountSettings
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Apple Music style account card: a small liquid-glass popover anchored under the account button
 * (top-right edge), like a popup menu, instead of a big centered dialog.
 *
 * A [androidx.compose.ui.window.Dialog] lives in its own window, so it can never sample the layer backdrop that
 * the tab bar and mini player refract. This is drawn in the main composition instead, as a sibling of the
 * backdrop source, so the card gets the same liquid glass as them.
 *
 * Opening scales/fades out of the top-right corner; closing plays the reverse and only then calls [onDismiss].
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppleGlassAccountOverlay(
    onDismiss: () -> Unit,
    latestVersionName: String,
) {
    val navController = LocalNavController.current
    val shape = RoundedCornerShape(26.dp)
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val close: () -> Unit = { visibleState.targetState = false }
    BackHandler(onBack = close)

    // Leave composition only after the exit animation has finished.
    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle && !visibleState.currentState) onDismiss()
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .animateEnterExit(enter = fadeIn(tween(220)), exit = fadeOut(tween(180)))
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = close,
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 56.dp, end = 12.dp)
                    .animateEnterExit(
                        enter = fadeIn(tween(160)) +
                            scaleIn(
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                                initialScale = 0.72f,
                                transformOrigin = TransformOrigin(1f, 0f),
                            ),
                        exit = fadeOut(tween(130)) +
                            scaleOut(
                                animationSpec = tween(150),
                                targetScale = 0.86f,
                                transformOrigin = TransformOrigin(1f, 0f),
                            ),
                    )
                    .fillMaxWidth(0.86f)
                    .widthIn(max = 320.dp)
                    .clip(shape)
                    .appleGlass(shape, HazeMaterials.thin(), tintAlpha = 0.45f)
                    .border(1.dp, glassStrokeBrush(), shape)
                    // Swallow taps on the card so they don't fall through to the dismiss scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                // Rows inside the card must not try to glass themselves against the same backdrop.
                CompositionLocalProvider(
                    LocalGlassBackdrop provides null,
                    LocalPreferenceVerticalPadding provides 10.dp,
                ) {
                    AccountSettings(
                        navController = navController,
                        onClose = close,
                        latestVersionName = latestVersionName,
                        compact = true,
                    )
                }
            }
        }
    }
}

/**
 * Apple Music style player menu (the 3-dots sheet).
 *
 * Same idea as [AppleGlassAccountOverlay]: a ModalBottomSheet is its own window and cannot sample anything,
 * so this sheet is drawn in the main composition, above the expanded player, and refracts [backdrop]
 * (the player's blurred artwork background).
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppleGlassMenuSheet(
    state: MenuState,
    backdrop: LayerBackdrop,
) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    BackHandler(enabled = state.isVisible) { state.dismiss() }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { state.dismiss() },
                    ),
            )
        }
        AnimatedVisibility(
            visible = state.isVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(state.heightFraction)
                    .clip(shape)
                    .appleGlass(shape, HazeMaterials.thin(), tintAlpha = 0.4f, backdrop = backdrop)
                    .border(1.dp, glassStrokeBrush(), shape)
                    // Swallow taps so they don't reach the dismiss scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 12.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
                // Content must not try to glass itself against the screen backdrop.
                CompositionLocalProvider(LocalGlassBackdrop provides null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .navigationBarsPadding(),
                    ) {
                        state.content(this)
                    }
                }
            }
        }
    }
}
