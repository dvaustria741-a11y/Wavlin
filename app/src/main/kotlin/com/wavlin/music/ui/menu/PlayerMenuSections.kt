package com.wavlin.music.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import coil3.compose.AsyncImage
import com.wavlin.music.R
import com.wavlin.music.audio.dolby.DolbyAtmosCapability
import com.wavlin.music.audio.dolby.DolbyAtmosManager
import com.wavlin.music.constants.PlayerBackgroundStyle
import com.wavlin.music.constants.PlayerBackgroundStyleKey
import com.wavlin.music.constants.SwipeThumbnailKey
import com.wavlin.music.models.MediaMetadata
import com.wavlin.music.ui.component.VolumeSlider
import com.wavlin.music.utils.rememberEnumPreference
import com.wavlin.music.utils.rememberPreference
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(28.dp)

/** SpatialFlow-style "Now Playing" card: artwork, title/artist and the volume control. */
@Composable
fun PlayerMenuNowPlayingCard(
    mediaMetadata: MediaMetadata,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    castDeviceName: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = mediaMetadata.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (castDeviceName != null) {
                            stringResource(R.string.casting_to, castDeviceName)
                        } else {
                            stringResource(R.string.now_playing)
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaMetadata.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaMetadata.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.volume), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${(volume * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        VolumeSlider(
            value = volume,
            onValueChange = onVolumeChange,
            modifier = Modifier.fillMaxWidth(),
            accentColor = MaterialTheme.colorScheme.primary,
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
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(10.dp),
    ) {
        actions.forEach { action ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = action.onClick)
                        .padding(vertical = 14.dp, horizontal = 4.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(action.circleColor),
                ) {
                    Icon(
                        painter = painterResource(action.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
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
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(20.dp))
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Player theme picker, Dolby Atmos switch and artwork swipe switch. */
@Composable
fun PlayerMenuSettingsCard() {
    val (playerBackground, onPlayerBackgroundChange) =
        rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)
    val (swipeThumbnail, onSwipeThumbnailChange) = rememberPreference(SwipeThumbnailKey, true)
    val atmosEnabled by DolbyAtmosManager.isAtmosEnabledByUser.collectAsStateWithLifecycle()
    val atmosDiag by DolbyAtmosManager.liveDiagnostics.collectAsStateWithLifecycle()
    val atmosSupported = atmosDiag.capability != DolbyAtmosCapability.UNSUPPORTED

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.palette),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(text = stringResource(R.string.player_theme), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(14.dp))
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
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
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
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(12.dp))
        SettingSwitchRow(
            iconRes = R.drawable.swipe,
            title = stringResource(R.string.artwork_swipe_gestures),
            subtitle = stringResource(R.string.artwork_swipe_gestures_desc),
            checked = swipeThumbnail,
            enabled = true,
            onCheckedChange = onSwipeThumbnailChange,
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
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
