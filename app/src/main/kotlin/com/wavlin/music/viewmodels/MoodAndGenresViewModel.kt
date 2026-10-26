/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.pages.MoodAndGenres
import com.wavlin.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class MoodAndGenresViewModel
@Inject
constructor() : ViewModel() {
    val moodAndGenres = MutableStateFlow<List<MoodAndGenres>?>(null)

    /** Cover art per mood, keyed by [artworkKey]; filled in as each mood's first item resolves. */
    val artwork = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        viewModelScope.launch {
            YouTube
                .moodAndGenres()
                .onSuccess {
                    moodAndGenres.value = it
                    resolveArtwork(it.flatMap { section -> section.items })
                }.onFailure {
                    reportException(it)
                }
        }
    }

    private fun resolveArtwork(items: List<MoodAndGenres.Item>) {
        val limiter = Semaphore(MAX_PARALLEL_LOOKUPS)
        items.forEach { item ->
            val key = artworkKey(item)
            val cached = artworkCache[key]
            if (cached != null) {
                artwork.update { it + (key to cached) }
                return@forEach
            }
            viewModelScope.launch {
                limiter.withPermit {
                    YouTube
                        .browse(item.endpoint.browseId, item.endpoint.params)
                        .onSuccess { page ->
                            val url =
                                page.items
                                    .asSequence()
                                    .flatMap { it.items.asSequence() }
                                    .mapNotNull { it.thumbnail?.takeIf(String::isNotBlank) }
                                    .firstOrNull()
                            if (url != null) {
                                artworkCache[key] = url
                                artwork.update { it + (key to url) }
                            }
                        }
                }
            }
        }
    }

    companion object {
        private const val MAX_PARALLEL_LOOKUPS = 4
        private val artworkCache = ConcurrentHashMap<String, String>()

        fun artworkKey(item: MoodAndGenres.Item) = "${item.endpoint.browseId}::${item.endpoint.params.orEmpty()}"
    }
}
