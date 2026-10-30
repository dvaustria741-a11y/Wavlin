/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.spotify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wavlin.music.R
import com.wavlin.music.db.entities.Playlist
import com.wavlin.music.ui.component.ListDialog
import com.wavlin.music.ui.component.PlaylistListItem
import com.wavlin.music.viewmodels.PlaylistsViewModel

/**
 * Lets the user keep a Spotify playlist: save it as a new playlist (with the Spotify cover) in their
 * Playlists, or add its songs to a playlist they already have.
 */
@Composable
fun SpotifySaveDialog(
    onDismiss: () -> Unit,
    onSaveNew: () -> Unit,
    onAddToExisting: (Playlist) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val editable = playlists.filter { it.playlist.isEditable }

    ListDialog(onDismiss = onDismiss) {
        item {
            FilledTonalButton(
                onClick = onSaveNew,
                shape = RoundedCornerShape(50),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.library_add),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
                Text(
                    text = stringResource(R.string.spotify_save_as_new),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.spotify_add_to_existing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        if (editable.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.spotify_no_editable_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        items(editable, key = { it.id }) { playlist ->
            PlaylistListItem(
                playlist = playlist,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onAddToExisting(playlist) },
            )
        }
    }
}
