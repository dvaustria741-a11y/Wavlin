/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.wavlin.music.R
import com.wavlin.music.utils.makeTimeString
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SLEEP_PRESETS = listOf(10, 15, 30, 45, 60, 90, 120)
private const val SLEEP_MIN = 1
private const val SLEEP_MAX = 1440
private const val SLIDER_MIN = 5f
private const val SLIDER_MAX = 120f
private const val SLIDER_STEP = 5
private val ADD_TIME_OPTIONS = listOf(5, 10, 15, 30, 60)

/**
 * Lets other screens (the player's more menu, the queue bar) ask the player to open the sleep timer card.
 * The card itself lives in the player, so they just raise this flag and the player consumes it.
 */
object SleepTimerOverlayRequest {
    var requested by androidx.compose.runtime.mutableStateOf(false)
}

/** Big readout: minutes below an hour, whole hours ("2 h"), or hours:minutes ("1:30 h") above. */
private fun sleepReadout(
    minutes: Int,
    minUnit: String,
    hourUnit: String,
): Pair<String, String> =
    when {
        minutes < 60 -> minutes.toString() to minUnit
        minutes % 60 == 0 -> (minutes / 60).toString() to hourUnit
        else -> "%d:%02d".format(minutes / 60, minutes % 60) to hourUnit
    }

/** Short chip label: "45", "1 h", "1 h 30", "2 h". */
private fun sleepChipLabel(
    minutes: Int,
    hourUnit: String,
): String =
    when {
        minutes < 60 -> minutes.toString()
        minutes % 60 == 0 -> "${minutes / 60} $hourUnit"
        else -> "${minutes / 60} $hourUnit ${minutes % 60}"
    }

private fun addTimeLabel(
    minutes: Int,
    minUnit: String,
    hourUnit: String,
): String = if (minutes >= 60 && minutes % 60 == 0) "+${minutes / 60} $hourUnit" else "+$minutes $minUnit"

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
    // When a timer is already running the card shows what is left instead of the picker.
    running: Boolean = false,
    endOfSong: Boolean = false,
    canAddTime: Boolean = false,
    timeLeftMs: Long = 0L,
    onStop: () -> Unit = {},
    onAddMinutes: (Int) -> Unit = {},
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    val apple = LocalInterfaceAppleMusic.current
    val pink = LocalPinkLilyTheme.current
    val shape = RoundedCornerShape(if (apple) 30.dp else 28.dp)

    // Lifted here so the card itself can react to the keyboard: while typing a custom value it glides
    // from the bottom to the middle of the space left above the keyboard, and back again afterwards.
    var customOpen by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) customOpen = false }
    val verticalBias by animateFloatAsState(
        targetValue = if (customOpen) 0f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 260f),
        label = "sleepTimerCardBias",
    )

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

    Box(modifier = Modifier.matchParentSize().imePadding()) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(BiasAlignment(0f, verticalBias)),
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
                            tintAlpha = if (pink) 0.80f else 0.5f,
                            backdrop = backdrop,
                        )
                    } else {
                        Modifier
                            .legacyGlassPanel(shape = shape, style = if (pink) pinkLilyHazeStyle() else null, drawStroke = false)
                            .background(
                                if (pink) PinkGlassTint.copy(alpha = 0.82f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                                shape,
                            )
                    },
                )
                // Secret Pink: a soft milky sheen on top so the card reads light and airy instead of mauve.
                .then(
                    if (pink) {
                        Modifier.background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.16f)),
                            ),
                            shape,
                        )
                    } else {
                        Modifier
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
            if (running) {
                SleepTimerRunningContent(
                    endOfSong = endOfSong,
                    canAddTime = canAddTime,
                    timeLeftMs = timeLeftMs,
                    onStop = onStop,
                    onAddMinutes = onAddMinutes,
                    onDismiss = onDismiss,
                    apple = apple,
                )
            } else {
                SleepTimerCardContent(
                    minutes = minutes,
                    onMinutesChange = onMinutesChange,
                    isAtDefault = isAtDefault,
                    onSetDefault = onSetDefault,
                    onEndOfSong = onEndOfSong,
                    onStart = onStart,
                    onDismiss = onDismiss,
                    apple = apple,
                    customOpen = customOpen,
                    onCustomOpenChange = { customOpen = it },
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    customOpen: Boolean,
    onCustomOpenChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val minUnit = stringResource(R.string.sleep_timer_unit_min)
    val hourUnit = stringResource(R.string.sleep_timer_unit_h)
    var customText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val customValid = customText.toIntOrNull()?.let { it in SLEEP_MIN..SLEEP_MAX } == true
    val tileShape = RoundedCornerShape(if (apple) 18.dp else 16.dp)

    LaunchedEffect(customOpen) {
        if (customOpen) {
            customText = minutes.toString()
            // Let the field fade in first, then raise the keyboard so the card can start gliding to the middle.
            delay(120)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // The slider thumb glides to its new spot when a preset chip is tapped instead of jumping.
    val scope = rememberCoroutineScope()
    val sliderTarget = minutes.coerceIn(SLIDER_MIN.toInt(), SLIDER_MAX.toInt()).toFloat()
    val sliderAnim = remember { Animatable(sliderTarget) }
    var dragging by remember { mutableStateOf(false) }
    val glideSpec = spring<Float>(dampingRatio = 0.82f, stiffness = 170f)
    LaunchedEffect(sliderTarget) {
        if (!dragging) sliderAnim.animateTo(sliderTarget, glideSpec)
    }
    val sliderColors = SliderDefaults.colors()

    Column(
        modifier = Modifier
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 380f))
            .padding(horizontal = 20.dp, vertical = 20.dp),
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
            val (readoutValue, readoutUnit) = sleepReadout(minutes, minUnit, hourUnit)
            Text(
                text = readoutValue,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = cs.primary,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = readoutUnit,
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
                    label = sleepChipLabel(preset, hourUnit),
                    selected = !customOpen && minutes == preset,
                    onClick = {
                        onCustomOpenChange(false)
                        onMinutesChange(preset)
                    },
                )
            }
            SleepChip(
                label = stringResource(R.string.sleep_timer_pick_custom),
                selected = customOpen || minutes !in SLEEP_PRESETS,
                onClick = { onCustomOpenChange(true) },
            )
        }

        AnimatedContent(
            targetState = customOpen,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "sleepTimerInput",
        ) { custom ->
            if (custom) {
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
                    value = sliderAnim.value,
                    onValueChange = { v ->
                        dragging = true
                        scope.launch { sliderAnim.snapTo(v) }
                        onMinutesChange((v / SLIDER_STEP).roundToInt() * SLIDER_STEP)
                    },
                    onValueChangeFinished = {
                        dragging = false
                        // Settle onto the nearest 5 min mark with the same glide.
                        scope.launch { sliderAnim.animateTo(sliderTarget, glideSpec) }
                    },
                    valueRange = SLIDER_MIN..SLIDER_MAX,
                    steps = 0,
                    colors = sliderColors,
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state,
                            colors = sliderColors,
                            drawStopIndicator = null,
                            modifier = Modifier.drawWithContent {
                                drawContent()
                                // 5 min tick dots drawn by hand so the thumb can glide freely between them.
                                val count = ((SLIDER_MAX - SLIDER_MIN) / SLIDER_STEP).toInt()
                                val frac = (sliderAnim.value - SLIDER_MIN) / (SLIDER_MAX - SLIDER_MIN)
                                val pad = 14.dp.toPx()
                                val usable = size.width - pad * 2
                                for (i in 0..count) {
                                    val f = i / count.toFloat()
                                    drawCircle(
                                        color = if (f <= frac) sliderColors.activeTickColor else sliderColors.inactiveTickColor,
                                        radius = 2.dp.toPx(),
                                        center = Offset(pad + usable * f, size.height / 2f),
                                    )
                                }
                            },
                        )
                    },
                )
            }
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
private fun SleepTimerRunningContent(
    endOfSong: Boolean,
    canAddTime: Boolean,
    timeLeftMs: Long,
    onStop: () -> Unit,
    onAddMinutes: (Int) -> Unit,
    onDismiss: () -> Unit,
    apple: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val minUnit = stringResource(R.string.sleep_timer_unit_min)
    val hourUnit = stringResource(R.string.sleep_timer_unit_h)
    Column(
        modifier = Modifier
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 380f))
            .padding(horizontal = 20.dp, vertical = 20.dp),
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
            if (endOfSong) {
                Text(
                    text = stringResource(R.string.end_of_song),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                )
            } else {
                Text(
                    text = makeTimeString(timeLeftMs.coerceAtLeast(0L)),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                    maxLines = 1,
                )
            }
        }

        if (canAddTime && !endOfSong) {
            Text(
                text = stringResource(R.string.sleep_timer_add_time),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ADD_TIME_OPTIONS.forEach { option ->
                    SleepChip(
                        label = addTimeLabel(option, minUnit, hourUnit),
                        selected = false,
                        onClick = { onAddMinutes(option) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
            Button(
                onClick = onStop,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
            ) {
                Text(stringResource(R.string.sleep_timer_stop))
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
