/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Data models adapted from SpatialFlow (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.spotify.account

import androidx.compose.runtime.Immutable
import com.wavlin.music.spotify.SpotifyTrack
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyInternalToken(
    val accessToken: String,
    val accessTokenExpirationTimestampMs: Long,
    val isAnonymous: Boolean = false,
    val clientId: String = "",
)

@Immutable
@Serializable
data class SpotifyUser(
    val id: String = "",
    val displayName: String? = null,
    val images: List<SpotifyImage> = emptyList(),
)

@Immutable
@Serializable
data class SpotifyImage(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
)

@Immutable
@Serializable
data class SpotifySimpleArtist(
    val id: String? = null,
    val name: String = "",
    val uri: String? = null,
)

@Immutable
@Serializable
data class SpotifySimpleAlbum(
    val id: String = "",
    val name: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val uri: String? = null,
)

/** A track as returned by the signed-in Spotify library. Not to be confused with the keyless [SpotifyTrack]. */
@Immutable
@Serializable
data class SpotifyWebTrack(
    val id: String = "",
    val name: String = "",
    val artists: List<SpotifySimpleArtist> = emptyList(),
    val album: SpotifySimpleAlbum? = null,
    val durationMs: Int = 0,
    val explicit: Boolean = false,
    val uri: String? = null,
)

@Immutable
@Serializable
data class SpotifyPlaylistOwner(
    val id: String = "",
    val displayName: String? = null,
    val uri: String? = null,
)

@Immutable
@Serializable
data class SpotifyPlaylist(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val owner: SpotifyPlaylistOwner? = null,
    val totalTracks: Int? = null,
    val uri: String? = null,
)

@Immutable
data class SpotifyPaging<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0,
)

/** The highest resolution cover of a playlist. */
fun SpotifyPlaylist.thumbnailUrl(): String? = images.maxByOrNull { it.width ?: 0 }?.url ?: images.firstOrNull()?.url

/** The highest resolution album cover of a track. */
fun SpotifyWebTrack.thumbnailUrl(): String? =
    album?.images?.let { covers -> covers.maxByOrNull { it.width ?: 0 }?.url ?: covers.firstOrNull()?.url }

val SpotifyWebTrack.artistNames: String
    get() = artists.joinToString(", ") { it.name }

/** Converts to the track type the matcher and importer already understand. */
fun SpotifyWebTrack.toImportTrack(): SpotifyTrack {
    val artist = artistNames
    return SpotifyTrack(
        title = name,
        artist = artist,
        query = if (artist.isEmpty()) name else "$artist - $name",
        durationSeconds = durationMs / 1000,
        url = if (id.isNotEmpty()) "https://open.spotify.com/track/$id" else null,
        coverUrl = thumbnailUrl(),
    )
}
