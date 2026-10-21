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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val PREVIEW_TRACK_COUNT = 4
private const val WHATSAPP_PACKAGE = "com.whatsapp"
private const val SHARED_TEXT_MAX_TRACKS = 100

private const val SHARED_MAX_IDS = 50
private const val CARD_IMAGE_WIDTH = 720
private const val SHARE_PAGE_URL = "https://wavlin-share.dvaustria741.workers.dev/"
private const val SHARE_LINKS_PREFS = "playlist_share_links"
private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * Builds a Wavlin link for the playlist that opens inside the app.
 *
 * - Playlists synced with YouTube Music link to their `list` id.
 * - Local-only playlists carry the name and the first [SHARED_MAX_IDS] track ids in the link. This
 *   long form is the fallback; [resolveShortShareLink] replaces it with a short one when possible.
 *
 * Both open the Wavlin share page, which hands the playlist over to the app.
 *
 * Returns null when there is nothing linkable (e.g. only imported local files).
 */
fun buildPlaylistShareLink(
    playlist: Playlist,
    songs: List<PlaylistSong>,
): String? {
    playlist.playlist.browseId?.let { return "$SHARE_PAGE_URL?list=${it.removePrefix("VL")}" }
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
    return "$SHARE_PAGE_URL?name=${Uri.encode(playlist.playlist.name)}&ids=${ids.joinToString(",")}"
}

/**
 * Short link for a local playlist: the name, track ids and card picture are stored on the share server
 * (no YouTube account needed) and the link only carries the short code the server returns. The code is
 * cached per playlist content so sharing the same playlist again does not store it twice.
 *
 * Fails (with the reason as the message) when the link cannot be created.
 */
private suspend fun resolveShortShareLink(
    context: Context,
    playlist: Playlist,
    songs: List<PlaylistSong>,
    cardJpeg: ByteArray?,
): Result<String> {
    val ids =
        songs
            .asSequence()
            .map { it.song.song }
            .filter { !it.isLocal && YOUTUBE_VIDEO_ID.matches(it.id) }
            .map { it.id }
            .distinct()
            .take(SHARED_MAX_IDS)
            .toList()
    if (ids.isEmpty()) return Result.failure(IllegalStateException("no online tracks"))

    val prefs = context.getSharedPreferences(SHARE_LINKS_PREFS, Context.MODE_PRIVATE)
    val cacheKey = "${playlist.id}:${(playlist.playlist.name + ids.joinToString(",")).hashCode()}"
    prefs.getString(cacheKey, null)?.let { return Result.success("${SHARE_PAGE_URL}p/$it") }

    return withContext(Dispatchers.IO) {
        runCatching {
            val body =
                JSONObject()
                    .put("name", playlist.playlist.name)
                    .put("ids", JSONArray(ids))
                    .apply { cardJpeg?.let { put("card", Base64.encodeToString(it, Base64.NO_WRAP)) } }
                    .toString()
            val connection = URL("${SHARE_PAGE_URL}p").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                check(connection.responseCode in 200..299) { "server ${connection.responseCode}" }
                val code = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("code")
                prefs.edit().putString(cacheKey, code).apply()
                "${SHARE_PAGE_URL}p/$code"
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.e("PlaylistShare", "Short link failed", it) }
    }
}

/** Renders the captured card to a small JPEG for the link preview picture. */
private suspend fun encodeCardJpeg(layer: GraphicsLayer): ByteArray? =
    runCatching {
        val source = layer.toImageBitmap().asAndroidBitmap()
        val scale = (CARD_IMAGE_WIDTH.toFloat() / source.width).coerceAtMost(1f)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            stream.toByteArray()
        }
    }.onFailure { Log.e("PlaylistShare", "Card capture failed", it) }.getOrNull()

/** Uploads the card so the share page can use it as the preview picture (the server keeps the first one per id). */
private suspend fun uploadShareCard(
    listId: String,
    jpeg: ByteArray,
) = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL("${SHARE_PAGE_URL}card/${Uri.encode(listId)}").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Content-Type", "image/jpeg")
            connection.outputStream.use { it.write(jpeg) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.e("PlaylistShare", "Card upload failed", it) }
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
    val coroutineScope = rememberCoroutineScope()
    val fallbackText = remember(playlist, songs) { buildPlaylistShareText(context, playlist, songs) }
    var isPreparing by remember { mutableStateOf(false) }
    val cardLayer = rememberGraphicsLayer()

    // Shares with the short link when it can be made; otherwise falls back to the long link.
    fun share(action: (String) -> Unit) {
        if (isPreparing) return
        coroutineScope.launch {
            isPreparing = true
            val cardJpeg = encodeCardJpeg(cardLayer)
            val syncedId = playlist.playlist.browseId?.removePrefix("VL")
            val text =
                if (syncedId != null) {
                    cardJpeg?.let { uploadShareCard(syncedId, it) }
                    fallbackText
                } else {
                    resolveShortShareLink(context, playlist, songs, cardJpeg).getOrElse {
                        val reason = it.message ?: it::class.java.simpleName
                        Toast.makeText(context, context.getString(R.string.short_link_failed, reason), Toast.LENGTH_LONG).show()
                        fallbackText
                    }
                }
            isPreparing = false
            action(text)
            onDismiss()
        }
    }

    PlaylistShareCard(
        playlist = playlist,
        songs = songs,
        ownerName = ownerName,
        modifier =
            Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .drawWithContent {
                    cardLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(cardLayer)
                },
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
                share { text ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(playlist.playlist.name, text))
                    Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                }
            },
        )
        ShareTarget(
            iconRes = R.drawable.share_chat,
            label = stringResource(R.string.share_via_whatsapp),
            containerColor = Color(0xFF25D366),
            contentColor = Color.White,
            onClick = { share { sendTo(context, it, WHATSAPP_PACKAGE) } },
        )
        ShareTarget(
            iconRes = R.drawable.share_chat,
            label = stringResource(R.string.share_via_sms),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = { share { openSms(context, it) } },
        )
        ShareTarget(
            iconRes = R.drawable.more_horiz,
            label = stringResource(R.string.share_more),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = { share { sendTo(context, it, targetPackage = null) } },
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
