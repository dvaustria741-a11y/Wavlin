/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.SongItem
import com.wavlin.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.wavlin.innertube.models.filterExplicit
import com.wavlin.innertube.models.filterVideoSongs
import com.wavlin.innertube.models.filterYoutubeShorts
import com.wavlin.innertube.pages.SearchSummary
import com.wavlin.innertube.pages.SearchSummaryPage
import com.wavlin.music.constants.HideExplicitKey
import com.wavlin.music.constants.HideVideoSongsKey
import com.wavlin.music.constants.HideYoutubeShortsKey
import com.wavlin.music.models.ItemsPage
import com.wavlin.music.utils.SearchRoutes
import com.wavlin.music.utils.dataStore
import com.wavlin.music.utils.get
import com.wavlin.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    private suspend fun loadSummaryPage() {
        if (summaryPage == null) {
                    YouTube
                        .searchSummary(query)
                        .onSuccess { page ->
                            val resolvedSummaries = page.summaries.map { summary ->
                                summary.copy(items = YouTube.resolveArtistIds(summary.items))
                            }
                            val resolvedPage = page.copy(summaries = resolvedSummaries).withOriginalSongOnTop(query)
                            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                            summaryPage =
                                resolvedPage.filterExplicit(hideExplicit)
                                  .filterVideoSongs(hideVideoSongs)
                                  .filterYoutubeShorts(hideYoutubeShorts)
                }.onFailure {
                    reportException(it)
                }
        }
    }

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    loadSummaryPage()
                } else if (filter == YouTube.SearchFilter.FILTER_EPISODE) {
                    // The FILTER_EPISODE API returns episodes in a format that differs from the
                    // summary search: playlistItemData is absent and the subtitle structure is
                    // different, making reliable isEpisode detection fail for many items.
                    // Reuse the "Episodes" section from the summary page instead — it is already
                    // parsed correctly by fromMusicResponsiveListItemRenderer and guaranteed to
                    // show the same results as the episodes section in the "All" filter.
                    if (viewStateMap[filter.value] == null) {
                        loadSummaryPage()
                        summaryPage?.let { page ->
                            val episodes = page.summaries
                                .firstOrNull { it.title == "Episodes" }
                                ?.items
                                .orEmpty()
                            viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                val resolvedItems = YouTube.resolveArtistIds(result.items)
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        resolvedItems
                                            .distinctBy { it.id }
                                            .filterExplicit(hideExplicit)
                                            .filterVideoSongs(hideVideoSongs)
                                            .filterYoutubeShorts(hideYoutubeShorts),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    fun loadMore() {
        val currentFilter = filter.value
        val filterValue = currentFilter?.value ?: return
        viewModelScope.launch {
            val viewState = viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val searchResult =
                YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
            val resolvedItems = YouTube.resolveArtistIds(searchResult.items)
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val newItems = resolvedItems
                .filterExplicit(hideExplicit)
                .filterVideoSongs(hideVideoSongs)
                .filterYoutubeShorts(hideYoutubeShorts)
            viewStateMap[filterValue] = ItemsPage(
                (viewState.items + newItems).distinctBy { it.id },
                searchResult.continuation
            )
        }
    }

    private companion object {
        val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        fun String.normalized() = lowercase().replace(NON_ALNUM, " ").trim()

        /**
         * A song is the original release when it is an official audio track (not a video upload)
         * whose title is exactly what was searched, optionally followed by one of its own artists.
         * Titles carrying extra text such as "(Male Cover Version)" never match.
         */
        fun SongItem.isOriginalFor(normalizedQuery: String): Boolean {
            if (isEpisode || musicVideoType != MUSIC_VIDEO_TYPE_ATV) return false
            val normalizedTitle = title.normalized()
            if (normalizedTitle.isEmpty() || !normalizedQuery.contains(normalizedTitle)) return false
            val rest = normalizedQuery.replace(normalizedTitle, " ").trim()
            return rest.isEmpty() || artists.any { artist ->
                val name = artist.name.normalized()
                name.isNotEmpty() && (rest.contains(name) || name.contains(rest))
            }
        }

        /**
         * YouTube's top card often leads with covers, remixes or fan videos. When that happens and
         * an official song matching the query exists in the results, move it to the top.
         */
        fun SearchSummaryPage.withOriginalSongOnTop(query: String): SearchSummaryPage {
            val top = summaries.firstOrNull() ?: return this
            val normalizedQuery = query.normalized()
            if ((top.items.firstOrNull() as? SongItem)?.isOriginalFor(normalizedQuery) == true) return this
            val original = summaries
                .flatMap { it.items }
                .filterIsInstance<SongItem>()
                .firstOrNull { it.isOriginalFor(normalizedQuery) }
                ?: return this
            val reorderedTop = SearchSummary(
                title = top.title,
                items = (listOf(original) + top.items).distinctBy { it.id },
            )
            return SearchSummaryPage(listOf(reorderedTop) + summaries.drop(1))
        }
    }
}
