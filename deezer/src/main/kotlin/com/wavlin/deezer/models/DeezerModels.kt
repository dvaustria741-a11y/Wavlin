package com.wavlin.deezer.models

import kotlinx.serialization.Serializable

@Serializable
data class ArtistSearchResponse(
    val data: List<DeezerArtist> = emptyList(),
)

@Serializable
data class DeezerArtist(
    val id: Long,
    val name: String,
    val picture_medium: String? = null,
    val nb_album: Int = 0,
)

@Serializable
data class AlbumsResponse(
    val data: List<DeezerAlbum> = emptyList(),
)

@Serializable
data class DeezerAlbum(
    val id: Long,
    val title: String,
    val release_date: String? = null,
    val cover_medium: String? = null,
    // "album", "single", "ep", "compile" ...
    val record_type: String? = null,
    val explicit_lyrics: Boolean = false,
)
