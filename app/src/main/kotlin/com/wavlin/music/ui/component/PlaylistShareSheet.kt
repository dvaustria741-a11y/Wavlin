/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.ui.component

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wavlin.music.R
import com.wavlin.music.db.entities.Playlist
import com.wavlin.music.db.entities.PlaylistSong

private const val PREVIEW_TRACK_COUNT = 4
private const val WHATSAPP_PACKAGE = "com.whatsapp"
private const val SHARED_TEXT_MAX_TRACKS = 100

private const val SHARED_MAX_IDS = 50
private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * Builds a Wavlin link for the playlist that opens inside the app.
 *
 * - Playlists synced with YouTube Music use `wavlin.cc/playlist?list=<browseId>`.
 * - Local-only playlists have no public page, so the name and the first [SHARED_MAX_IDS] track ids
 *   are carried in `wavlin.cc/shared`, which Wavlin imports as a new playlist.
 *
 * Returns null when there is nothing linkable (e.g. only imported local files).
 */
fun buildPlaylistShareLink(
    playlist: Playlist,
    songs: List<PlaylistSong>,
): String? {
    playlist.playlist.browseId?.let { return "https://wavlin.cc/playlist?list=$it" }
    val ids =
        songs
            .asSequence()
            .map { it.song.song }
            .filter { !it.isLocal && YOUTUBE_VIDEO_ID.matches(it.id) }
            .map { it.id }
            .distinct()
            .take(SHARED_MAX_IDS)
            .toList()
    if (ids.isEmpty()) return null
    return "https://wavlin.cc/shared?name=${Uri.encode(playlist.playlist.name)}&ids=${ids.joinToString(",")}"
}

/** Text used for sharing: the link when we have one, otherwise a readable track list. */
fun buildPlaylistShareText(
    context: Context,
    playlist: Playlist,
    songs: List<PlaylistSong>,
): String {
    buildPlaylistShareLink(playlist, songs)?.let { return it }
    val header = context.getString(R.string.share_playlist_tracks_text, playlist.playlist.name)
    val tracks =
        songs.take(SHARED_TEXT_MAX_TRACKS).mapIndexed { index, item ->
            val artists = item.song.artists.joinToString(", ") { it.name }
            val title = item.song.song.title
            "${index + 1}. ${if (artists.isNotBlank()) "$artists - $title" else title}"
        }
    return (listOf(header) + tracks).joinToString("\n")
}

/**
 * Spotify-style share sheet: preview card of the playlist plus a row of quick-share targets.
 * Meant to be passed to `MenuState.show { ... }`.
 */
@Composable
fun ColumnScope.PlaylistShareSheet(
    playlist: Playlist,
    songs: List<PlaylistSong>,
    ownerName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shareText = remember(playlist, songs) { buildPlaylistShareText(context, playlist, songs) }

    PlaylistShareCard(
        playlist = playlist,
        songs = songs,
        ownerName = ownerName,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        ShareTarget(
            iconRes = R.drawable.link,
            label = stringResource(R.string.copy_link),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(playlist.playlist.name, shareText))
                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                onDismiss()
            },
        )
        ShareTarget(
            iconRes = R.drawable.share_chat,
            label = stringResource(R.string.share_via_whatsapp),
            containerColor = Color(0xFF25D366),
            contentColor = Color.White,
            onClick = {
                sendTo(context, shareText, WHATSAPP_PACKAGE)
                onDismiss()
            },
        )
        ShareTarget(
            iconRes = R.drawable.share_chat,
            label = stringResource(R.string.share_via_sms),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = {
                openSms(context, shareText)
                onDismiss()
            },
        )
        ShareTarget(
            iconRes = R.drawable.more_horiz,
            label = stringResource(R.string.share_more),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = {
                sendTo(context, shareText, targetPackage = null)
                onDismiss()
            },
        )
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PlaylistShareCard(
    playlist: Playlist,
    songs: List<PlaylistSong>,
    ownerName: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val covers =
        remember(playlist, songs) {
            songs.mapNotNull { it.song.song.thumbnailUrl }.distinct().take(4)
        }
    val preview = remember(songs) { songs.take(PREVIEW_TRACK_COUNT) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.35f), Color(0xFF121212)),
                    ),
                ).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverMosaic(
                customThumbnail = playlist.playlist.thumbnailUrl,
                covers = covers,
                modifier = Modifier.size(112.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = playlist.playlist.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.share_playlist_by, ownerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        preview.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                AsyncImage(
                    model = item.song.song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = item.song.song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.song.artists.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = "Wavlin",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
        )
    }
}

@Composable
private fun CoverMosaic(
    customThumbnail: String?,
    covers: List<String>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier.clip(shape).background(Color.Black.copy(alpha = 0.25f))) {
        when {
            !customThumbnail.isNullOrBlank() || covers.size < 4 -> {
                val single = customThumbnail?.takeIf { it.isNotBlank() } ?: covers.firstOrNull()
                if (single != null) {
                    AsyncImage(
                        model = single,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
            }
            else ->
                Column {
                    covers.chunked(2).forEach { rowCovers ->
                        Row(modifier = Modifier.weight(1f)) {
                            rowCovers.forEach { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).aspectRatio(1f),
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun ShareTarget(
    iconRes: Int,
    label: String,
    containerColor: Color,
    onClick: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(containerColor),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private fun sendTo(
    context: Context,
    text: String,
    targetPackage: String?,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            targetPackage?.let { setPackage(it) }
        }
    try {
        context.startActivity(if (targetPackage == null) Intent.createChooser(send, null) else send)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.share_app_not_found, Toast.LENGTH_SHORT).show()
    }
}

private fun openSms(
    context: Context,
    text: String,
) {
    val sms =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
            putExtra("sms_body", text)
        }
    try {
        context.startActivity(sms)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.share_app_not_found, Toast.LENGTH_SHORT).show()
    }
}
