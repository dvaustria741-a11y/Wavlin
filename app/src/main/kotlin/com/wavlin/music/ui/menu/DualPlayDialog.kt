/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wavlin.music.LocalDatabase
import com.wavlin.music.LocalPlayerConnection
import com.wavlin.music.R
import com.wavlin.music.extensions.metadata
import com.wavlin.music.extensions.toMediaItem
import com.wavlin.music.ui.component.ListDialog
import com.wavlin.music.ui.component.SongListItem

/**
 * Entry point for Dual Play: while inactive, lets the user pick the song for the right (partner)
 * channel — the main player keeps playing whatever it already has queued on the left. While
 * active, shows the balance slider and independent controls for the partner side instead.
 */
@Composable
fun DualPlayDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return

    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isActive by playerConnection.isDualPlayActive.collectAsStateWithLifecycle()

    if (isActive) {
        DualPlayActiveContent(onDismiss = onDismiss)
    } else {
        val songs by database.songsByRowIdAsc().collectAsStateWithLifecycle(initialValue = emptyList())
        ListDialog(onDismiss = onDismiss) {
            item {
                Text(
                    text = stringResource(R.string.dual_play_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(songs, key = { it.id }) { song ->
                SongListItem(
                    song = song,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clickable(
                            onClick = {
                                val started = playerConnection.startDualPlay(song.toMediaItem())
                                if (started) {
                                    onDismiss()
                                } else {
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.dual_play_bluetooth_required),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun DualPlayActiveContent(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val balance by playerConnection.dualPlayBalance.collectAsStateWithLifecycle()
    val partnerIsPlaying by playerConnection.dualPlayPartnerIsPlaying.collectAsStateWithLifecycle()
    val partnerMediaItem by playerConnection.dualPlayPartnerMediaItem.collectAsStateWithLifecycle()

    ListDialog(onDismiss = onDismiss) {
        item {
            Text(
                text = partnerMediaItem?.metadata?.title ?: stringResource(R.string.dual_play),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.dual_play_balance),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Slider(
                value = balance,
                onValueChange = { playerConnection.setDualPlayBalance(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                IconButton(onClick = { playerConnection.dualPlayTogglePartnerPlayPause() }) {
                    Icon(
                        painter = painterResource(
                            if (partnerIsPlaying) R.drawable.pause else R.drawable.play,
                        ),
                        contentDescription = null,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { playerConnection.dualPlaySkipPartnerNext() }) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = null,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp).height(1.dp))
                TextButton(
                    onClick = {
                        playerConnection.stopDualPlay()
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.dual_play_stop))
                }
            }
        }
    }
}
