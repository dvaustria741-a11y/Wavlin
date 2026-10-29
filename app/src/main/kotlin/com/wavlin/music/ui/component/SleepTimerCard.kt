/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.wavlin.music.R
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

private val SLEEP_PRESETS = listOf(10, 15, 30, 45, 60, 90, 120)
private const val SLEEP_MIN = 1
private const val SLEEP_MAX = 1440

/**
 * Sleep timer card, drawn in the main composition (not in a dialog window) so it can be real glass.
 *
 * - Apple Music style: liquid glass that refracts [backdrop] (the player background).
 * - Material 3 style: frosted tonal glass (translucent container, glass edge), no lens.
 * - Secret Pink: rose tinted version of either.
 *
 * Layout: header with a big minute readout, preset chips plus a Custom chip (type any minutes),
 * a quick slider, two rounded option tiles (set as default / end of song) and Cancel / Start.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BoxScope.SleepTimerOverlay(
    visible: Boolean,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    isAtDefault: Boolean,
    onSetDefault: () -> Unit,
    onEndOfSong: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: LayerBackdrop?,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    val apple = LocalInterfaceAppleMusic.current
    val pink = LocalPinkLilyTheme.current
    val shape = RoundedCornerShape(if (apple) 30.dp else 28.dp)

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (apple) 0.30f else 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(tween(160)) +
            slideInVertically(spring(dampingRatio = 0.82f, stiffness = 380f)) { it / 3 } +
            scaleIn(
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                initialScale = 0.92f,
                transformOrigin = TransformOrigin(0.5f, 1f),
            ),
        exit = fadeOut(tween(140)) +
            slideOutVertically(tween(180)) { it / 4 } +
            scaleOut(tween(180), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)),
    ) {
        Box(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(shape)
                .then(
                    if (apple) {
                        Modifier.appleGlass(
                            shape,
                            if (pink) pinkLilyHazeStyle() else HazeMaterials.thin(),
                            tintAlpha = if (pink) 0.55f else 0.5f,
                            backdrop = backdrop,
                        )
                    } else {
                        Modifier
                            .legacyGlassPanel(shape = shape, style = if (pink) pinkLilyHazeStyle() else null, drawStroke = false)
                            .background(
                                if (pink) PinkGlassTint.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                                shape,
                            )
                    },
                )
                .then(
                    if (pink) Modifier.pinkGlassEdge(shape, strong = apple)
                    else Modifier.border(1.dp, glassStrokeBrush(), shape),
                )
                // Swallow taps so they don't reach the dismiss scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            SleepTimerCardContent(
                minutes = minutes,
                onMinutesChange = onMinutesChange,
                isAtDefault = isAtDefault,
                onSetDefault = onSetDefault,
                onEndOfSong = onEndOfSong,
                onStart = onStart,
                onDismiss = onDismiss,
                apple = apple,
            )
        }
    }
}

@Composable
private fun SleepTimerCardContent(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    isAtDefault: Boolean,
    onSetDefault: () -> Unit,
    onEndOfSong: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    apple: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val customValid = customText.toIntOrNull()?.let { it in SLEEP_MIN..SLEEP_MAX } == true
    val tileShape = RoundedCornerShape(if (apple) 18.dp else 16.dp)

    LaunchedEffect(customOpen) {
        if (customOpen) {
            customText = minutes.toString()
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(cs.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.bedtime),
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.sleep_timer),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = minutes.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = cs.primary,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.sleep_timer_unit_min),
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SLEEP_PRESETS.forEach { preset ->
                SleepChip(
                    label = preset.toString(),
                    selected = !customOpen && minutes == preset,
                    onClick = {
                        customOpen = false
                        onMinutesChange(preset)
                    },
                )
            }
            SleepChip(
                label = stringResource(R.string.sleep_timer_pick_custom),
                selected = customOpen || minutes !in SLEEP_PRESETS,
                onClick = { customOpen = true },
            )
        }

        if (customOpen) {
            OutlinedTextField(
                value = customText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(4)
                    customText = digits
                    digits.toIntOrNull()?.takeIf { it in SLEEP_MIN..SLEEP_MAX }?.let(onMinutesChange)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                shape = tileShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.sleep_timer_custom_label)) },
                suffix = { Text(stringResource(R.string.sleep_timer_unit_min)) },
                isError = customText.isNotEmpty() && !customValid,
                supportingText = {
                    Text(stringResource(R.string.sleep_timer_custom_range, SLEEP_MIN, SLEEP_MAX))
                },
            )
        } else {
            Slider(
                value = minutes.coerceIn(5, 120).toFloat(),
                onValueChange = { onMinutesChange(it.roundToInt()) },
                valueRange = 5f..120f,
                steps = (120 - 5) / 5 - 1,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val defaultColors =
                if (isAtDefault) {
                    ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = cs.onSurface.copy(alpha = 0.10f),
                        contentColor = cs.onSurface,
                    )
                }
            Button(
                onClick = onSetDefault,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                shape = tileShape,
                colors = defaultColors,
            ) {
                Text(
                    text = stringResource(R.string.set_as_default),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Button(
                onClick = onEndOfSong,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                shape = tileShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.onSurface.copy(alpha = 0.10f),
                    contentColor = cs.onSurface,
                ),
            ) {
                Text(
                    text = stringResource(R.string.end_of_song),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            Button(
                onClick = onStart,
                enabled = !customOpen || customValid,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(stringResource(R.string.sleep_timer_start))
            }
        }
    }
}

@Composable
private fun SleepChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) cs.primary else cs.onSurface.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onPrimary else cs.onSurface,
            maxLines = 1,
        )
    }
}
