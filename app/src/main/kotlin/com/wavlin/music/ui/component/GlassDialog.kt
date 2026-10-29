/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Glass for dialogs, kept cheap on purpose. A Dialog is its own window, so it can't sample the app's
 * backdrop layers (that needs a RenderEffect or AGSL shader running on every frame). Instead the blur
 * is done by the system compositor on Android 12+ (FLAG_BLUR_BEHIND), which costs the app nothing and is
 * skipped automatically when the device turns cross-window blur off. Without it the card simply gets a
 * more opaque surface, so older or low-end devices never pay for a blur.
 */
@Composable
fun rememberWindowBlurAvailable(): Boolean {
    val context = LocalContext.current
    return remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.isCrossWindowBlurEnabled == true
    }
}

@Composable
fun DialogWindowBlur(enabled: Boolean, radius: Int = 24) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setDimAmount(if (enabled) 0.18f else 0.45f)
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
        }
    }
}

@Composable
fun glassDialogColor(blurAvailable: Boolean): Color =
    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (blurAvailable) 0.76f else 0.96f)

@Composable
fun glassDialogShape(): Shape = RoundedCornerShape(if (LocalInterfaceAppleMusic.current) 30.dp else 24.dp)

/** A compact centered glass dialog. Secret Pink keeps its own pink panel. */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 360.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pinkLily = LocalPinkLilyTheme.current
    val blurAvailable = rememberWindowBlurAvailable()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogWindowBlur(enabled = !pinkLily && blurAvailable)
        Surface(
            modifier =
                Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .let { base -> if (pinkLily) base.pinkGlassPanel(pinkDialogShape()) else base },
            shape = if (pinkLily) pinkDialogShape() else glassDialogShape(),
            color = if (pinkLily) Color.Transparent else glassDialogColor(blurAvailable),
            border = if (pinkLily) null else BorderStroke(1.dp, glassStrokeBrush()),
        ) {
            Column(modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp), content = content)
        }
    }
}
