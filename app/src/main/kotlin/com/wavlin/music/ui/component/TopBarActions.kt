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
 * Top bar actions: just the account, as a ringed avatar. Stats, History and Listen Together
 * live in the account drawer.
 */
@Composable
fun TopBarActions(
    onAccountClick: () -> Unit,
    accountImageUrl: String?,
    showUpdateBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(end = 12.dp),
    ) {
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
