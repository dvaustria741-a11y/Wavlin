package com.wavlin.innertube.pages

import com.wavlin.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
