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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.wavlin.music.LocalListenTogetherManager
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

private val TEMPO_PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val TEMPO_MIN = 0.25f
private const val TEMPO_MAX = 2f
private const val TEMPO_STEP = 0.05f
private const val PITCH_RANGE = 12

/** Lets the player's more menu ask the player to open the tempo and pitch card (same idea as the sleep timer). */
object TempoPitchOverlayRequest {
    var requested by mutableStateOf(false)
}

private fun snapTempo(value: Float): Float = round(value.coerceIn(TEMPO_MIN, TEMPO_MAX) * 100f) / 100f

/**
 * Tempo and pitch card, drawn in the main composition so it can be real glass.
 *
 * - Apple Music style: liquid glass that refracts [backdrop] (the player background).
 * - Material 3 style: frosted tonal glass.
 * - Secret Pink: rose tinted version of either.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BoxScope.TempoPitchOverlay(
    visible: Boolean,
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
                            tintAlpha = if (pink) 0.80f else 0.5f,
                            backdrop = backdrop,
                        )
                    } else {
                        Modifier
                            .legacyGlassPanel(shape = shape, style = if (pink) pinkLilyHazeStyle() else null, drawStroke = false)
                            .background(
                                if (pink) {
                                    PinkGlassTint.copy(alpha = 0.82f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
                                },
                                shape,
                            )
                    },
                ).then(
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
                ).then(
                    if (pink) {
                        Modifier.pinkGlassEdge(shape, strong = apple)
                    } else {
                        Modifier.border(1.dp, glassStrokeBrush(), shape)
                    },
                )
                // Swallow taps so they don't reach the dismiss scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            // Only composed while open, so the values are re-read from the player each time and nothing runs when closed.
            TempoPitchContent(onDismiss = onDismiss, apple = apple)
        }
    }
}

@Composable
private fun TempoPitchContent(
    onDismiss: () -> Unit,
    apple: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val cs = MaterialTheme.colorScheme
    val isInRoom = LocalListenTogetherManager.current?.isInRoom ?: false
    val initial = playerConnection.player.playbackParameters
    var tempo by remember { mutableFloatStateOf(initial.speed) }
    var semitones by remember { mutableIntStateOf(round(12 * log2(initial.pitch)).toInt()) }
    val apply = {
        playerConnection.player.playbackParameters = PlaybackParameters(tempo, 2f.pow(semitones / 12f))
    }
    val buttonShape = RoundedCornerShape(if (apple) 18.dp else 16.dp)

    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
                    painter = painterResource(R.drawable.discover_tune),
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.tempo_and_pitch),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (!isInRoom) {
            AdjusterSection(
                iconRes = R.drawable.speed,
                label = stringResource(R.string.tempo),
                readout = "x" + "%.2f".format(tempo),
                value = tempo,
                valueRange = TEMPO_MIN..TEMPO_MAX,
                steps = ((TEMPO_MAX - TEMPO_MIN) / TEMPO_STEP).roundToInt() - 1,
                onValueChange = {
                    tempo = snapTempo(it)
                    apply()
                },
                onStep = { dir ->
                    tempo = snapTempo(tempo + dir * TEMPO_STEP)
                    apply()
                },
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TEMPO_PRESETS.forEach { preset ->
                    PresetChip(
                        label = "x$preset",
                        selected = snapTempo(tempo) == preset,
                        onClick = {
                            tempo = preset
                            apply()
                        },
                    )
                }
            }
        }

        AdjusterSection(
            iconRes = R.drawable.tune,
            label = stringResource(R.string.pitch),
            readout = (if (semitones > 0) "+" else "") + semitones,
            value = semitones.toFloat(),
            valueRange = -PITCH_RANGE.toFloat()..PITCH_RANGE.toFloat(),
            steps = PITCH_RANGE * 2 - 1,
            onValueChange = {
                semitones = it.roundToInt()
                apply()
            },
            onStep = { dir ->
                semitones = (semitones + dir.toInt()).coerceIn(-PITCH_RANGE, PITCH_RANGE)
                apply()
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(
                label = stringResource(R.string.reset),
                filled = false,
                shape = buttonShape,
                modifier = Modifier.weight(1f),
                onClick = {
                    tempo = 1f
                    semitones = 0
                    apply()
                },
            )
            ActionButton(
                label = stringResource(android.R.string.ok),
                filled = true,
                shape = buttonShape,
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun AdjusterSection(
    iconRes: Int,
    label: String,
    readout: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onStep: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = readout,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(iconRes = R.drawable.remove, onClick = { onStep(-1f) })
            Slider(
                value = value.coerceIn(valueRange),
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            StepButton(iconRes = R.drawable.add, onClick = { onStep(1f) })
        }
    }
}

@Composable
private fun StepButton(
    iconRes: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) cs.primary else cs.onSurface.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
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

@Composable
private fun ActionButton(
    label: String,
    filled: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (filled) cs.primary else cs.onSurface.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) cs.onPrimary else cs.onSurface,
        )
    }
}
