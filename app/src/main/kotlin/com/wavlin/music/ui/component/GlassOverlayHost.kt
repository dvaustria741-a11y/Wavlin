/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

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

/** Draws [content] in place, or in the app-level [GlassOverlayHost] when one is provided (Apple Music style). */
@Composable
fun BoxScope.GlassOverlay(content: @Composable BoxScope.() -> Unit) {
    val host = LocalGlassOverlayHost.current
    if (host == null) {
        content()
    } else {
        val token = remember { Any() }
        val latest = rememberUpdatedState(content)
        DisposableEffect(host, token) {
            host.entries[token] = { latest.value(this) }
            onDispose { host.entries.remove(token) }
        }
    }
}
