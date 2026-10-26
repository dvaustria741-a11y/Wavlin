/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.YouTube.SearchFilter
import com.wavlin.innertube.models.SongItem
import com.wavlin.innertube.models.YTItem
import com.wavlin.innertube.models.filterExplicit
import com.wavlin.music.R
import com.wavlin.music.constants.HideExplicitKey
import com.wavlin.music.utils.dataStore
import com.wavlin.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One horizontal shelf on a hub page. [ranked] shelves show a position number on each card. */
@Immutable
data class HubSection(
    val title: String,
    val items: List<YTItem>,
    val ranked: Boolean = false,
)

enum class HubKind {
    NEW_RELEASES,
    CHARTS,
    PODCASTS,
    ;

    val routeKey get() = name.lowercase()

    companion object {
        fun fromRouteKey(key: String?) = entries.firstOrNull { it.routeKey == key } ?: NEW_RELEASES
    }
}

@HiltViewModel
class CategoryHubViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _sections = MutableStateFlow<List<HubSection>?>(null)

    /** null while loading, empty when nothing could be loaded. */
    val sections = _sections.asStateFlow()

    private var loadedKind: HubKind? = null

    fun load(
        kind: HubKind,
        force: Boolean = false,
    ) {
        if (!force && loadedKind == kind) return
        loadedKind = kind
        _sections.value = null
        viewModelScope.launch {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val loaded =
                when (kind) {
                    HubKind.CHARTS -> loadCharts()
                    HubKind.PODCASTS -> loadPodcasts()
                    HubKind.NEW_RELEASES -> loadNewReleases()
                }
            _sections.value =
                loaded
                    .map { it.copy(items = it.items.filterExplicit(hideExplicit).distinctBy { item -> item.id }) }
                    .filter { it.items.isNotEmpty() }
        }
    }

    private suspend fun loadCharts(): List<HubSection> {
        val charts =
            YouTube
                .getChartsPage()
                .getOrNull()
                ?.sections
                .orEmpty()
                .map { HubSection(it.title, it.items, ranked = it.items.firstOrNull() is SongItem) }
        if (charts.any { it.items.isNotEmpty() }) return charts

        return coroutineScope {
            val songs = async { searchSection(context.getString(R.string.hub_trending_now), "top 50 songs", SearchFilter.FILTER_SONG, ranked = true) }
            val playlists = async { searchSection(context.getString(R.string.hub_chart_playlists), "top charts", SearchFilter.FILTER_FEATURED_PLAYLIST) }
            val albums = async { searchSection(context.getString(R.string.hub_albums_on_top), "top albums this year", SearchFilter.FILTER_ALBUM) }
            listOfNotNull(songs.await(), playlists.await(), albums.await())
        }
    }

    private suspend fun loadPodcasts(): List<HubSection> {
        val discovered =
            YouTube
                .podcastDiscover()
                .getOrNull()
                ?.sections
                .orEmpty()
                .map { HubSection(it.title, it.items) }
        if (discovered.any { it.items.isNotEmpty() }) return discovered

        return coroutineScope {
            val shows = async { searchSection(context.getString(R.string.hub_shows_to_follow), "popular podcasts", SearchFilter.FILTER_PODCAST) }
            val episodes = async { searchSection(context.getString(R.string.hub_fresh_episodes), "podcast episodes", SearchFilter.FILTER_EPISODE) }
            val playlists = async { searchSection(context.getString(R.string.hub_podcast_playlists), "podcasts", SearchFilter.FILTER_FEATURED_PLAYLIST) }
            listOfNotNull(shows.await(), episodes.await(), playlists.await())
        }
    }

    /** Albums are added by the screen from [NewReleaseViewModel], which also matches Deezer releases. */
    private suspend fun loadNewReleases(): List<HubSection> =
        coroutineScope {
            val singles = async { searchSection(context.getString(R.string.hub_fresh_singles), "new songs this week", SearchFilter.FILTER_SONG) }
            val playlists = async { searchSection(context.getString(R.string.hub_new_mixes), "new music friday", SearchFilter.FILTER_FEATURED_PLAYLIST) }
            listOfNotNull(singles.await(), playlists.await())
        }

    private suspend fun searchSection(
        title: String,
        query: String,
        filter: SearchFilter,
        ranked: Boolean = false,
    ): HubSection? =
        YouTube
            .search(query, filter)
            .getOrNull()
            ?.items
            ?.take(SEARCH_SECTION_LIMIT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { HubSection(title, it, ranked) }

    private companion object {
        const val SEARCH_SECTION_LIMIT = 20
    }
}
