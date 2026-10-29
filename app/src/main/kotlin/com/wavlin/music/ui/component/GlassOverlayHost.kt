/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Screens live inside the layer that the liquid glass samples, so glass drawn there can't sample it.
 * Floating buttons are therefore hosted here, in a sibling above that layer, where real glass works.
 */
@Stable
class GlassOverlayHostState {
    internal val entries = mutableStateMapOf<Any, @Composable BoxScope.() -> Unit>()
}

val LocalGlassOverlayHost = staticCompositionLocalOf<GlassOverlayHostState?> { null }

@Composable
fun GlassOverlayHost(state: GlassOverlayHostState) {
    Box(Modifier.fillMaxSize()) {
        state.entries.forEach { (token, content) ->
            key(token) { content() }
        }
    }
}

/**
 * Draws [content] in place, or in the app-level [GlassOverlayHost] when one is provided (Apple Music style).
 *
 * The content belongs to the screen that called it, so when that screen stops being the current destination
 * (a tab switch, opening another page) it eases down and fades out in step with the page transition instead
 * of lingering in place and vanishing when the old page is finally disposed.
 */
@Composable
fun BoxScope.GlassOverlay(content: @Composable BoxScope.() -> Unit) {
    val host = LocalGlassOverlayHost.current
    val leaving = rememberScreenLeaving()
    if (host == null) {
        Box(Modifier.matchParentSize().leavingScreenLayer(leaving)) { content() }
    } else {
        val token = remember { Any() }
        val latest = rememberUpdatedState(content)
        val leavingState = rememberUpdatedState(leaving)
        DisposableEffect(host, token) {
            host.entries[token] = {
                Box(Modifier.matchParentSize().leavingScreenLayer(leavingState.value)) { latest.value(this) }
            }
            onDispose { host.entries.remove(token) }
        }
    }
}

/** True once the screen hosting this composition was current (resumed) and has since been navigated away from. */
@Composable
private fun rememberScreenLeaving(): Boolean {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var wasResumed by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) wasResumed = true
    }
    return wasResumed && !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
}

private val LeaveEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun Modifier.leavingScreenLayer(leaving: Boolean): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (leaving) 1f else 0f,
        animationSpec = tween(if (leaving) 320 else 260, easing = LeaveEasing),
        label = "overlayLeave",
    )
    val slidePx = with(LocalDensity.current) { 140.dp.toPx() }
    return graphicsLayer {
        translationY = slidePx * progress
        alpha = 1f - progress
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}
