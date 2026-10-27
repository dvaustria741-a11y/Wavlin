/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Token ───────────────────────────────────────────────────────────────────

@Serializable
data class SpotifyTokenResponse(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("accessTokenExpirationTimestampMs") val expiresAt: Long,
)

// ─── External URLs ────────────────────────────────────────────────────────────

@Serializable
data class SpotifyExternalUrls(
    val spotify: String? = null,
)

// ─── Track ───────────────────────────────────────────────────────────────────

@Serializable
data class SpotifyTrack(
    val id: String,
    @SerialName("name") val title: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    @SerialName("duration_ms") val durationMs: Int = 0,
    val explicit: Boolean = false,
    @SerialName("external_urls") val externalUrls: SpotifyExternalUrls? = null,
) {
    /** Comma-separated artist string — used for matching, display, and search. */
    val artist: String get() = artists.joinToString(", ") { it.name }

    /** Album/track artwork URL. */
    val coverUrl: String? get() = album?.images?.firstOrNull()?.url

    /** Direct Spotify link for this track, e.g. for SpotifyUnmatchedDialog. */
    val url: String? get() = externalUrls?.spotify

    /** Duration in whole seconds — used by SpotifyMatcher scoring. */
    val durationSeconds: Int get() = durationMs / 1000

    /** Canonical YouTube search query for this track. */
    val query: String get() = "$title $artist".trim()

    /** Return a copy with the given images injected into the album (for album-page tracks). */
    fun withAlbumImages(images: List<SpotifyImage>): SpotifyTrack =
        copy(album = (album ?: SpotifyAlbum()).copy(images = images))
}

@Serializable
data class SpotifyArtist(
    val id: String = "",
    val name: String,
)

@Serializable
data class SpotifyAlbum(
    val id: String = "",
    val name: String = "",
    val images: List<SpotifyImage> = emptyList(),
)

@Serializable
data class SpotifyImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

// ─── Result (single track or playlist / album) ────────────────────────────────

sealed interface SpotifyResult {
    /** A single Spotify track link was pasted. */
    data class Track(val track: SpotifyTrack) : SpotifyResult

    /** A Spotify playlist or album link was pasted. */
    data class Tracks(val title: String, val tracks: List<SpotifyTrack>) : SpotifyResult
}

// ─── Search API response ──────────────────────────────────────────────────────

@Serializable
data class SpotifySearchResponse(
    val tracks: SpotifyTracksPage? = null,
)

@Serializable
data class SpotifyTracksPage(
    val items: List<SpotifyTrack> = emptyList(),
    val next: String? = null,
)

// ─── New-releases (home feed) ─────────────────────────────────────────────────

@Serializable
data class SpotifyNewReleasesResponse(
    val albums: SpotifyAlbumsPage? = null,
)

@Serializable
data class SpotifyAlbumsPage(
    val items: List<SpotifyAlbumItem> = emptyList(),
)

@Serializable
data class SpotifyAlbumItem(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("album_type") val albumType: String? = null,
) {
    val thumbnailUrl: String? get() = images.firstOrNull()?.url
    val primaryArtist: String get() = artists.firstOrNull()?.name.orEmpty()
}

// ─── Playlist resolution ──────────────────────────────────────────────────────

@Serializable
data class SpotifyPlaylistResponse(
    val name: String,
    val tracks: SpotifyPlaylistTracksPage? = null,
)

@Serializable
data class SpotifyPlaylistTracksPage(
    val items: List<SpotifyPlaylistTrackItem> = emptyList(),
    val next: String? = null,
)

@Serializable
data class SpotifyPlaylistTrackItem(
    val track: SpotifyTrack? = null,
)

// ─── Album resolution ─────────────────────────────────────────────────────────

@Serializable
data class SpotifyAlbumDetails(
    val id: String,
    val name: String,
    val images: List<SpotifyImage> = emptyList(),
    val tracks: SpotifyAlbumTracksPage? = null,
)

@Serializable
data class SpotifyAlbumTracksPage(
    val items: List<SpotifyTrack> = emptyList(),
    val next: String? = null,
)
