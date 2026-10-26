/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.wavlin.music.R
import com.wavlin.music.ui.theme.PinkInk
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)
    var expandFully by mutableStateOf(false)
    var heightFraction by mutableFloatStateOf(1f)
    var skipPartial by mutableStateOf(false)

    fun show(content: @Composable ColumnScope.() -> Unit) = show(expandFully = false, content = content)

    /** Opens a sheet that covers only [heightFraction] of the screen height, leaving the screen behind it visible. */
    fun show(
        heightFraction: Float,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        show(expandFully = false, content = content)
        this.heightFraction = heightFraction
        // Open straight at full sheet height instead of the half-height stop.
        this.skipPartial = true
    }

    /** [expandFully] opens tall content (e.g. the share card) fully instead of half-height. */
    fun show(
        expandFully: Boolean,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        this.expandFully = expandFully
        this.heightFraction = 1f
        this.skipPartial = false
        isVisible = true
        this.content = content
    }

    fun dismiss() {
        isVisible = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedBottomSheet(
    isVisible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    expandFully: Boolean = false,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    var lastContent by remember { mutableStateOf(content) }

    LaunchedEffect(content) {
        if (isVisible) {
            lastContent = content
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            sheetState.show()
        } else {
            sheetState.hide()
        }
    }

    if (!sheetState.isVisible && !isVisible) {
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        properties = properties,
        content = lastContent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
) {
    val focusManager = LocalFocusManager.current
    // Tall content (the share card) skips the half-height stop and opens fully; the state is keyed on it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = state.expandFully || state.skipPartial)
    val pinkLily = LocalPinkLilyTheme.current
    // The share sheet is only as tall as its card + buttons: the sheet itself stays full height (so its size never
    // depends on late-measured content) and the content sits at the bottom of it.
    val wrapContent = state.expandFully
    val sheetShape = RoundedCornerShape(topStart = if (pinkLily) 30.dp else 28.dp, topEnd = if (pinkLily) 30.dp else 28.dp)

    AnimatedBottomSheet(
        isVisible = state.isVisible,
        expandFully = state.expandFully,
        onDismissRequest = {
            focusManager.clearFocus()
            state.isVisible = false
        },
        sheetState = sheetState,
        shape = sheetShape,
        // Secret Pink: the frosted lily sheet art is the container (drawn ~92% opaque below, so the screen
        // beneath only faintly glows through) and a rose-tinted scrim replaces the default grey.
        containerColor = if (pinkLily || wrapContent) Color.Transparent else background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = if (pinkLily) PinkInk.copy(alpha = 0.32f) else BottomSheetDefaults.ScrimColor,
        dragHandle = {
            // Secret Pink draws its own handle inside the sheet art (below).
            if (!pinkLily && !wrapContent) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }
        },
        contentWindowInsets = if (wrapContent) {
            { WindowInsets(0, 0, 0, 0) }
        } else {
            { BottomSheetDefaults.modalWindowInsets }
        },
        modifier = if (pinkLily || wrapContent) {
            modifier.fillMaxHeight(state.heightFraction)
        } else {
            modifier
                .fillMaxHeight(state.heightFraction)
                .border(1.dp, glassStrokeBrush(), sheetShape)
        }
    ) {
        if (wrapContent) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) { detectTapGestures { state.isVisible = false } },
            )
        }
        if (pinkLily) {
            // The sheet art is drawn here, inside the sheet's own (offset) coordinates, so its top edge
            // sits exactly where the sheet starts instead of at the top of the screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wrapContent) Modifier else Modifier.weight(1f))
                    .lilyNinePatch(res = R.drawable.lily_sheet, srcCap = 260, dstCap = 76.dp, alpha = 0.92f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PinkInk.copy(alpha = 0.35f)),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        state.content(this)
                    }
                    if (wrapContent) Spacer(Modifier.navigationBarsPadding())
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (wrapContent) {
                            Modifier
                                .clip(sheetShape)
                                .background(background)
                                .border(1.dp, glassStrokeBrush(), sheetShape)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (wrapContent) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    state.content(this)
                }
                if (wrapContent) Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}
