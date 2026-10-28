package com.wavlin.music.ui.menu

import com.wavlin.music.ui.component.LocalInterfaceAppleMusic
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import coil3.compose.AsyncImage
import com.wavlin.music.R
import com.wavlin.music.audio.dolby.DolbyAtmosCapability
import com.wavlin.music.audio.dolby.DolbyAtmosManager
import com.wavlin.music.constants.PlayerBackgroundStyle
import com.wavlin.music.constants.PlayerBackgroundStyleKey
import com.wavlin.music.models.MediaMetadata
import com.wavlin.music.ui.component.LocalPinkLilyTheme
import com.wavlin.music.ui.component.VolumeSlider
import com.wavlin.music.ui.theme.PinkSwoosh
import com.wavlin.music.utils.rememberEnumPreference
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(28.dp)
private val AppleCardShape = RoundedCornerShape(18.dp)

/** Apple Music style: tighter cards, neutral translucent fill and trailing icons. */
@Composable
private fun appleMenu(): Boolean = LocalInterfaceAppleMusic.current && !LocalPinkLilyTheme.current

@Composable
private fun cardShape(): RoundedCornerShape = if (appleMenu()) AppleCardShape else CardShape

/** Card surface: tonal container normally, frosted rose glass in the Secret Pink theme. */
@Composable
private fun Modifier.menuCard(): Modifier =
    if (LocalPinkLilyTheme.current) {
        this
            .background(Color.White.copy(alpha = 0.42f), CardShape)
            .border(1.dp, PinkSwoosh.copy(alpha = 0.6f), CardShape)
    } else if (LocalInterfaceAppleMusic.current) {
        this.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    } else {
        this.background(MaterialTheme.colorScheme.surfaceContainer)
    }

/** SpatialFlow-style "Now Playing" card: artwork, title/artist and the volume control. */
@Composable
fun PlayerMenuNowPlayingCard(
    mediaMetadata: MediaMetadata,
    volumeProvider: () -> Float,
    onVolumeChange: (Float) -> Unit,
    castDeviceName: String?,
    animateProvider: () -> Boolean = { false },
) {
    // Read here so volume changes only recompose this card, not the whole menu.
    // Changes that don't come from dragging the slider (volume keys) glide instead of jumping.
    val volume by androidx.compose.animation.core.animateFloatAsState(
        targetValue = volumeProvider(),
        animationSpec =
            if (animateProvider()) {
                androidx.compose.animation.core.tween(
                    durationMillis = 200,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                )
            } else {
                androidx.compose.animation.core.snap()
            },
        label = "menuVolume",
    )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape())
                .menuCard()
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = mediaMetadata.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (castDeviceName != null) {
                            stringResource(R.string.casting_to, castDeviceName)
                        } else {
                            stringResource(R.string.now_playing)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaMetadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaMetadata.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.volume), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${(volume * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        VolumeSlider(
            value = volume,
            onValueChange = onVolumeChange,
            modifier = Modifier.fillMaxWidth(),
            accentColor = MaterialTheme.colorScheme.onSurface,
            compact = true,
        )
    }
}

class PlayerQuickAction(
    val iconRes: Int,
    val label: String,
    val circleColor: Color,
    val onClick: () -> Unit,
)

/** Row of quick tiles (Audio EQ, Add Playlist, Sleep Timer, Track Info). */
@Composable
fun PlayerMenuQuickActions(actions: List<PlayerQuickAction>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape())
                .menuCard()
                .padding(8.dp),
    ) {
        actions.forEach { action ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = action.onClick)
                        .padding(vertical = 10.dp, horizontal = 2.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (appleMenu()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else action.circleColor),
                ) {
                    Icon(
                        painter = painterResource(action.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun PlayerMenuRowCard(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape())
                .menuCard()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (appleMenu()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp))
        } else {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** White switch so the menu never picks up the dynamic accent colour. */
@Composable
private fun menuSwitchColors() =
    if (LocalPinkLilyTheme.current) {
        androidx.compose.material3.SwitchDefaults.colors()
    } else {
        val onSurface = MaterialTheme.colorScheme.onSurface
        androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.surface,
            checkedTrackColor = onSurface.copy(alpha = 0.92f),
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = onSurface.copy(alpha = 0.65f),
            uncheckedTrackColor = onSurface.copy(alpha = 0.14f),
            uncheckedBorderColor = Color.Transparent,
        )
    }

/** Player theme picker and Dolby Atmos switch. */
@Composable
fun PlayerMenuSettingsCard() {
    val (playerBackground, onPlayerBackgroundChange) =
        rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)
    val atmosEnabled by DolbyAtmosManager.isAtmosEnabledByUser.collectAsStateWithLifecycle()
    val atmosDiag by DolbyAtmosManager.liveDiagnostics.collectAsStateWithLifecycle()
    val atmosSupported = atmosDiag.capability != DolbyAtmosCapability.UNSUPPORTED

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape())
                .menuCard()
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.palette),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(text = stringResource(R.string.player_theme), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                PlayerBackgroundStyle.DEFAULT to R.string.default_style,
                PlayerBackgroundStyle.BLUR to R.string.player_background_blur,
                PlayerBackgroundStyle.GRADIENT to R.string.gradient,
            ).forEach { (style, label) ->
                val selected = playerBackground == style
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    selected && LocalPinkLilyTheme.current -> PinkSwoosh.copy(alpha = 0.45f)
                                    selected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
                                    LocalPinkLilyTheme.current -> Color.White.copy(alpha = 0.35f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                },
                            ).clickable { onPlayerBackgroundChange(style) },
                ) {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(text = stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(8.dp))
        SettingSwitchRow(
            iconRes = R.drawable.ic_dolby,
            title = stringResource(R.string.dolby_atmos),
            subtitle =
                when {
                    !atmosSupported -> stringResource(R.string.dolby_atmos_unsupported)
                    atmosEnabled -> stringResource(R.string.dolby_atmos_active)
                    else -> stringResource(R.string.dolby_atmos_off)
                },
            checked = atmosEnabled && atmosSupported,
            enabled = atmosSupported,
            onCheckedChange = { DolbyAtmosManager.setAtmosEnabled(it) },
        )
    }
}

/** Expanded stereo strength (0..3), shown right under the Dolby Atmos switch while Dolby is on. */
@Composable
fun PlayerMenuExpandedStereoCard() {
    val atmosEnabled by DolbyAtmosManager.isAtmosEnabledByUser.collectAsStateWithLifecycle()
    val atmosDiag by DolbyAtmosManager.liveDiagnostics.collectAsStateWithLifecycle()
    val level by DolbyAtmosManager.expandedStereoLevel.collectAsStateWithLifecycle()
    val onEarphones by DolbyAtmosManager.isHeadphoneRoute.collectAsStateWithLifecycle()
    val immersive by DolbyAtmosManager.immersiveMode.collectAsStateWithLifecycle()
    var dspStatus by remember { mutableStateOf("") }
    LaunchedEffect(atmosEnabled) {
        while (true) {
            dspStatus = DolbyAtmosManager.processorStatus()
            delay(700)
        }
    }
    if (!atmosEnabled || atmosDiag.capability == DolbyAtmosCapability.UNSUPPORTED) return

    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape())
                .menuCard()
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.dolby_expanded_stereo), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dolby_earphones_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(onSurface.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .alpha(if (onEarphones) 1f else 0.6f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(onSurface.copy(alpha = 0.12f))
                    .padding(4.dp),
        ) {
            (0..3).forEach { value ->
                val selected = level == value
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    selected && LocalPinkLilyTheme.current -> PinkSwoosh.copy(alpha = 0.45f)
                                    selected -> onSurface.copy(alpha = 0.24f)
                                    else -> Color.Transparent
                                },
                            ).clickable { DolbyAtmosManager.setExpandedStereoLevel(value) },
                ) {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(10.dp))
        SettingSwitchRow(
            iconRes = R.drawable.ic_dolby,
            title = stringResource(R.string.dolby_immersive_mode),
            subtitle = stringResource(R.string.dolby_immersive_mode_desc),
            checked = immersive,
            enabled = true,
            onCheckedChange = { DolbyAtmosManager.setImmersiveMode(it) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "DSP: $dspStatus",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = menuSwitchColors(),
        )
    }
}

/** Compact SpatialFlow-style card listing the remaining menu actions as divided rows. */
@Composable
fun PlayerMenuListCard(items: List<com.wavlin.music.ui.component.Material3MenuItemData>) {
    if (items.isEmpty()) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape())
                .menuCard(),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.onClick != null) { item.onClick?.invoke() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                val appleRows = appleMenu()
                if (!appleRows) {
                    item.icon?.let {
                        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { it() }
                        Spacer(Modifier.width(16.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    androidx.compose.material3.ProvideTextStyle(
                        MaterialTheme.typography.titleSmall.copy(fontWeight = if (appleRows) FontWeight.Normal else FontWeight.SemiBold),
                    ) {
                        item.title()
                    }
                    item.description?.let { desc ->
                        androidx.compose.material3.ProvideTextStyle(
                            MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        ) { desc() }
                    }
                }
                item.trailingContent?.let {
                    Spacer(Modifier.width(8.dp))
                    it()
                }
                if (appleRows) {
                    item.icon?.let {
                        Spacer(Modifier.width(16.dp))
                        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { it() }
                    }
                }
            }
        }
    }
}
