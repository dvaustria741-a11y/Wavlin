package com.wavlin.innertube.models.body

import com.wavlin.innertube.models.Context
import com.wavlin.innertube.models.Continuation
import kotlinx.serialization.Serializable

@Serializable
data class BrowseBody(
    val context: Context,
    val browseId: String?,
    val params: String?,
    val continuation: String?
)
