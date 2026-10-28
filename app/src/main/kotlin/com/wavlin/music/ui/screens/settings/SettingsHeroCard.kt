/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wavlin.music.BuildConfig
import com.wavlin.music.R
import com.wavlin.music.ui.component.LocalInterfaceAppleMusic

/** App identity card at the top of Settings: logo, name and version, opens the About page. */
@Composable
fun SettingsHeroCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appleMusic = LocalInterfaceAppleMusic.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (appleMusic) 18.dp else 28.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
            .then(
                if (appleMusic) {
                    Modifier.border(BorderStroke(0.5.dp, colors.outlineVariant), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(colors.onPrimaryContainer.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
        Icon(
            painter = painterResource(R.drawable.navigate_next),
            contentDescription = null,
            tint = colors.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}
