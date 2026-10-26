/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyTokenResponse(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("accessTokenExpirationTimestampMs") val expiresAt: Long
)

@Serializable
data class SpotifySearchResponse(
    val tracks: SpotifyTracksResult? = null
)

@Serializable
data class SpotifyTracksResult(
    val items: List<SpotifyTrack> = emptyList()
)

@Serializable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    @SerialName("duration_ms") val durationMs: Int = 0,
    val explicit: Boolean = false
) {
    val primaryArtist: String get() = artists.firstOrNull()?.name.orEmpty()
    val allArtists: String get() = artists.joinToString(", ") { it.name }
    val thumbnailUrl: String? get() = album?.images?.firstOrNull()?.url
}

@Serializable
data class SpotifyArtist(
    val id: String,
    val name: String
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val images: List<SpotifyImage> = emptyList()
)

@Serializable
data class SpotifyImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class SpotifyNewReleasesResponse(
    val albums: SpotifyAlbumsPage? = null
)

@Serializable
data class SpotifyAlbumsPage(
    val items: List<SpotifyAlbumItem> = emptyList()
)

@Serializable
data class SpotifyAlbumItem(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("album_type") val albumType: String? = null
) {
    val thumbnailUrl: String? get() = images.firstOrNull()?.url
    val primaryArtist: String get() = artists.firstOrNull()?.name.orEmpty()
}
