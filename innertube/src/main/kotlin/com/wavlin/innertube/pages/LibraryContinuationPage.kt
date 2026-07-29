package com.wavlin.innertube.pages

import com.wavlin.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
