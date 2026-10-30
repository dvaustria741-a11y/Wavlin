/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.SongItem
import com.wavlin.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

sealed interface SharedPlaylistState {
    data object Loading : SharedPlaylistState

    data class Loaded(
        val name: String,
        val songs: List<SongItem>,
    ) : SharedPlaylistState

    data object Failed : SharedPlaylistState
}

/**
 * Loads a playlist that someone shared (short code on the share server, or the older name + ids link)
 * for preview only. Nothing is written to the library until the user chooses to save it.
 */
@HiltViewModel
class SharedPlaylistViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val code: String = savedStateHandle["code"] ?: ""
        private val linkName: String = savedStateHandle["name"] ?: ""
        private val linkIds: String = savedStateHandle["ids"] ?: ""

        private val _state = MutableStateFlow<SharedPlaylistState>(SharedPlaylistState.Loading)
        val state: StateFlow<SharedPlaylistState> = _state.asStateFlow()

        init {
            load()
        }

        fun load() {
            _state.value = SharedPlaylistState.Loading
            viewModelScope.launch(Dispatchers.IO) {
                val (name, ids) = fetchSource()
                val videoIds = ids.filter { it.isNotBlank() }.distinct()
                if (videoIds.isEmpty()) {
                    _state.value = SharedPlaylistState.Failed
                    return@launch
                }
                // The queue endpoint only takes a limited number of ids per request, so fetch in batches, in order.
                val batches = videoIds.chunked(BATCH_SIZE).map { YouTube.queue(it) }
                val failure = batches.firstOrNull { it.isFailure }?.exceptionOrNull()
                if (failure != null) {
                    reportException(failure)
                    _state.value = SharedPlaylistState.Failed
                    return@launch
                }
                val songs = batches.flatMap { it.getOrThrow() }
                _state.value =
                    if (songs.isEmpty()) SharedPlaylistState.Failed else SharedPlaylistState.Loaded(name, songs)
            }
        }

        private fun fetchSource(): Pair<String, List<String>> {
            if (code.isEmpty()) return linkName to linkIds.split(",")
            if (!code.matches(CODE_REGEX)) return "" to emptyList()
            return runCatching {
                val connection = URL("https://$SHARE_PAGE_HOST/p/$code.json").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val idArray = json.getJSONArray("ids")
                    json.optString("name") to List(idArray.length()) { idArray.getString(it) }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { reportException(it) }
                .getOrDefault("" to emptyList())
        }

        private companion object {
            const val BATCH_SIZE = 50
            const val SHARE_PAGE_HOST = "wavlin-share.dvaustria741.workers.dev"
            val CODE_REGEX = Regex("[A-Za-z0-9]{4,32}")
        }
    }
