/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.screens.spotify

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wavlin.music.R
import com.wavlin.music.constants.ListThumbnailSize
import com.wavlin.music.constants.ThumbnailCornerRadius
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.playback.PlayerConnection
import com.wavlin.music.spotify.SpotifyDirectDownloader
import com.wavlin.music.spotify.account.SpotifyPlayback
import com.wavlin.music.spotify.account.SpotifyWebTrack
import com.wavlin.music.spotify.account.artistNames
import com.wavlin.music.spotify.account.thumbnailUrl
import com.wavlin.music.spotify.account.toImportTrack
import com.wavlin.music.ui.component.ListItem

/**
 * Plays [tracks] through YouTube Music. A track YouTube doesn't have is fetched from Spotify and
 * saved on the device instead (see [SpotifyDirectDownloader]). Tells the person when nothing could be played.
 */
suspend fun playSpotifyTracks(
    context: Context,
    database: MusicDatabase,
    playerConnection: PlayerConnection,
    title: String,
    tracks: List<SpotifyWebTrack>,
    startIndex: Int = 0,
    shuffle: Boolean = false,
) {
    val played =
        SpotifyPlayback.play(playerConnection, title, tracks, startIndex, shuffle) { track ->
            SpotifyDirectDownloader.downloadAsLocalSong(context, database, track.toImportTrack())
        }
    if (!played) {
        Toast.makeText(context, context.getString(R.string.spotify_no_match), Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SpotifyTrackItem(
    track: SpotifyWebTrack,
    modifier: Modifier = Modifier,
) {
    ListItem(
        title = track.name,
        subtitle = {
            Text(
                text = track.artistNames,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        thumbnailContent = {
            val cover = track.thumbnailUrl()
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(ListThumbnailSize)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
            } else {
                Box(
                    Modifier
                        .size(ListThumbnailSize)
                        .clip(RoundedCornerShape(ThumbnailCornerRadius))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.music_note), contentDescription = null)
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
fun SpotifyConnectCard(
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = SpotifyGreen,
            modifier = Modifier.size(40.dp),
        )
        Text(stringResource(R.string.spotify_connect), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.spotify_integration_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onConnect) { Text(stringResource(R.string.spotify_connect)) }
    }
}

@Composable
fun SpotifyCenteredMessage(
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
