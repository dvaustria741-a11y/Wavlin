/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.music.spotify.account.SpotifyPlaylist
import com.wavlin.music.spotify.account.SpotifySession
import com.wavlin.music.spotify.account.SpotifyWebTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SpotifyViewModel
    @Inject
    constructor(
        private val session: SpotifySession,
    ) : ViewModel() {
        val authState = session.authState
        val playlists = session.playlists
        val likedTracks = session.likedTracks
        val accountName = session.accountName
        val accountAvatarUrl = session.accountAvatarUrl
        val errorMessage = session.errorMessage

        fun connectWithCookies(
            spDc: String,
            spKey: String,
        ) = session.connectWithCookies(spDc, spKey)

        fun loginWithManualToken(
            input: String,
            spKey: String,
            onResult: (Result<String>) -> Unit,
        ) = session.loginWithManualToken(input, spKey, onResult)

        fun reload() = session.reload()

        fun logout() = session.logout()

        fun dismissError() = session.dismissError()
    }

sealed interface SpotifyPlaylistState {
    data object Loading : SpotifyPlaylistState

    data class Loaded(
        val tracks: List<SpotifyWebTrack>,
        val isOfflineCopy: Boolean,
    ) : SpotifyPlaylistState

    data class Failed(
        val message: String,
    ) : SpotifyPlaylistState
}

@HiltViewModel
class SpotifyPlaylistViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val session: SpotifySession,
    ) : ViewModel() {
        private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

        val playlist: StateFlow<SpotifyPlaylist?> =
            session.playlists
                .map { playlists -> playlists.firstOrNull { it.id == playlistId } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, session.playlist(playlistId))

        private val _state = MutableStateFlow<SpotifyPlaylistState>(SpotifyPlaylistState.Loading)
        val state = _state.asStateFlow()

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                // Show the copy saved on the device straight away while the fresh one loads
                val cached = withContext(Dispatchers.IO) { session.cachedPlaylistTracks(playlistId) }
                if (cached != null && _state.value is SpotifyPlaylistState.Loading) {
                    _state.value = SpotifyPlaylistState.Loaded(cached, isOfflineCopy = false)
                }
                session
                    .playlistTracks(playlistId)
                    .onSuccess { _state.value = SpotifyPlaylistState.Loaded(it.tracks, it.isOfflineCopy) }
                    .onFailure { _state.value = SpotifyPlaylistState.Failed(session.friendlyMessage(it)) }
            }
        }
    }
