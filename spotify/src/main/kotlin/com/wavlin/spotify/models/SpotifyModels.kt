package com.wavlin.spotify.models

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
)

@Serializable
data class ArtistSearchResponse(
    val artists: ArtistSearchResult? = null,
)

@Serializable
data class ArtistSearchResult(
    val items: List<SpotifyArtist> = emptyList(),
)

@Serializable
data class SpotifyArtist(
    val id: String,
    val name: String,
)

@Serializable
data class ArtistAlbumsResponse(
    val items: List<SpotifyAlbum> = emptyList(),
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val release_date: String? = null,
    // "day" | "month" | "year" - precision of release_date
    val release_date_precision: String? = null,
    // "album" | "single" | "compilation"
    val album_type: String? = null,
    val images: List<SpotifyImage> = emptyList(),
)

@Serializable
data class SpotifyImage(
    val url: String,
)
