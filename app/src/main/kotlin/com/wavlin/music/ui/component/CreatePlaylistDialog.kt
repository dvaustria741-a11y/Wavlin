/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.wavlin.innertube.YouTube
import com.wavlin.music.LocalDatabase
import com.wavlin.music.R
import com.wavlin.music.constants.InnerTubeCookieKey
import com.wavlin.music.db.entities.PlaylistEntity
import com.wavlin.music.extensions.isSyncEnabled
import com.wavlin.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.logging.Logger

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    initialTextFieldValue: String? = null,
    allowSyncing: Boolean = true,
    onPlaylistCreated: ((String) -> Unit)? = null,
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    var syncedPlaylist by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(TextFieldValue(initialTextFieldValue ?: "")) }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isSignedIn = innerTubeCookie.isNotEmpty()

    val notLoggedInYoutubeStr = stringResource(R.string.not_logged_in_youtube)
    val syncDisabledStr = stringResource(R.string.sync_disabled)

    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
    }

    val submit = {
        val playlistName = name.text.trim()
        if (playlistName.isNotEmpty()) {
            onDismiss()
            coroutineScope.launch(Dispatchers.IO) {
                val browseId =
                    if (syncedPlaylist && isSignedIn) {
                        YouTube.createPlaylist(playlistName)
                    } else if (syncedPlaylist) {
                        Logger.getLogger("CreatePlaylistDialog").warning("Not signed in")
                        return@launch
                    } else {
                        null
                    }

                val playlistEntity =
                    PlaylistEntity(
                        name = playlistName,
                        browseId = browseId,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                    )

                database.query {
                    insert(playlistEntity)
                }

                withContext(Dispatchers.Main) {
                    onPlaylistCreated?.invoke(playlistEntity.id)
                }
            }
        }
    }

    GlassDialog(onDismiss = onDismiss, maxWidth = 340.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    painter = painterResource(R.drawable.queue_music),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.create_playlist),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(16.dp))

        TextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.playlist_name)) },
            shape = RoundedCornerShape(16.dp),
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )

        if (allowSyncing) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sync_playlist),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.allows_for_sync_witch_youtube),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = syncedPlaylist,
                        onCheckedChange = {
                            coroutineScope.launch {
                                val isYtmSyncEnabled = withContext(Dispatchers.IO) { context.isSyncEnabled() }
                                if (!isSignedIn && !syncedPlaylist) {
                                    Toast.makeText(context, notLoggedInYoutubeStr, Toast.LENGTH_SHORT).show()
                                } else if (!isYtmSyncEnabled) {
                                    Toast.makeText(context, syncDisabledStr, Toast.LENGTH_SHORT).show()
                                } else {
                                    syncedPlaylist = !syncedPlaylist
                                }
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(enabled = name.text.isNotBlank(), onClick = { submit() }) {
                Text(stringResource(R.string.glass_create))
            }
        }
    }
}
