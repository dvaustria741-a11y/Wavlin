/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wavlin.music.R

/**
 * Top bar actions: Stats and Listen Together share one tonal capsule, and the account sits beside it
 * as a ringed avatar, instead of three loose icon buttons.
 */
@Composable
fun TopBarActions(
    onStatsClick: () -> Unit,
    showListenTogether: Boolean,
    onListenTogetherClick: () -> Unit,
    onAccountClick: () -> Unit,
    accountImageUrl: String?,
    showUpdateBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val appleMusic = LocalInterfaceAppleMusic.current
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(end = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(if (appleMusic) colors.surfaceContainerHigh else colors.secondaryContainer)
                .then(
                    if (appleMusic) {
                        Modifier.border(BorderStroke(0.5.dp, colors.outlineVariant), CircleShape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            TopBarCapsuleButton(
                iconRes = R.drawable.stats,
                contentDescription = stringResource(R.string.stats),
                tint = if (appleMusic) colors.onSurface else colors.onSecondaryContainer,
                onClick = onStatsClick,
            )
            if (showListenTogether) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(colors.outlineVariant),
                )
                TopBarCapsuleButton(
                    iconRes = R.drawable.group_outlined,
                    contentDescription = stringResource(R.string.together),
                    tint = if (appleMusic) colors.onSurface else colors.onSecondaryContainer,
                    onClick = onListenTogetherClick,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        BadgedBox(badge = { if (showUpdateBadge) Badge() }) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .border(BorderStroke(2.dp, colors.primary), CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest)
                    .clickable(onClick = onAccountClick),
            ) {
                if (accountImageUrl != null) {
                    AsyncImage(
                        model = accountImageUrl,
                        contentDescription = stringResource(R.string.account),
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.account),
                        contentDescription = stringResource(R.string.account),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBarCapsuleButton(
    iconRes: Int,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 46.dp, height = 40.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
