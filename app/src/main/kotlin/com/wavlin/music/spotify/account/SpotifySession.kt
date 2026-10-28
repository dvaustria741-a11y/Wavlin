/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Session handling adapted from SpatialFlow's SpotifyViewModel (https://github.com/MythicalSHUB/SpatialFlow),
 * Copyright (c) 2026 Shubham Karande, permissively licensed (MIT per its README, Apache-2.0 per its LICENSE file).
 */

package com.wavlin.music.spotify.account

import android.content.Context
import com.wavlin.music.spotify.SpotifyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class SpotifyAuthState {
    NOT_CONNECTED,
    LOADING,
    CONNECTED,
    ERROR,
}

/** Tracks of a playlist, flagged when they come from the copy saved on the device instead of Spotify. */
data class SpotifyTracksResult(
    val tracks: List<SpotifyWebTrack>,
    val isOfflineCopy: Boolean,
)

/**
 * App-wide Spotify account state: sign-in, token renewal and the cached library.
 * Lives as a singleton so the Library tab, the playlist screen and the settings share one session.
 */
@Singleton
class SpotifySession
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = SpotifyStore(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val tokenMutex = Mutex()
        private var loadJob: Job? = null

        @Volatile
        private var lastAuthError: Throwable? = null

        private val _authState = MutableStateFlow(SpotifyAuthState.LOADING)
        val authState: StateFlow<SpotifyAuthState> = _authState.asStateFlow()

        private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
        val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists.asStateFlow()

        private val _likedTracks = MutableStateFlow<List<SpotifyWebTrack>>(emptyList())
        val likedTracks: StateFlow<List<SpotifyWebTrack>> = _likedTracks.asStateFlow()

        private val _accountName = MutableStateFlow("")
        val accountName: StateFlow<String> = _accountName.asStateFlow()

        private val _accountAvatarUrl = MutableStateFlow<String?>(null)
        val accountAvatarUrl: StateFlow<String?> = _accountAvatarUrl.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        init {
            SpotifyClient.setTokenRefresher { refreshTokenSilently() }
            SpotifyProvider.tokenProvider = { validAccessToken() }
            scope.launch {
                if (store.isConnected) {
                    restoreSession()
                } else {
                    _authState.value = SpotifyAuthState.NOT_CONNECTED
                }
            }
        }

        // ── Sign-in ──────────────────────────────────────────────────────────────

        /** Called with the cookies the sign-in WebView captured. */
        fun connectWithCookies(
            spDc: String,
            spKey: String,
        ) {
            _authState.value = SpotifyAuthState.LOADING
            _errorMessage.value = null
            launchLoad {
                store.spDc = spDc
                store.spKey = spKey
                store.accessToken = ""
                authenticateAndLoad()
            }
        }

        /** Signs in with a pasted sp_dc value or Cookie header. [onResult] runs on the main thread. */
        fun loginWithManualToken(
            input: String,
            manualSpKey: String = "",
            onResult: (Result<String>) -> Unit = {},
        ) {
            val (spDc, spKey) = SpotifyAuth.parseCookieInput(input, manualSpKey)
            if (spDc.isBlank()) {
                val error = IllegalArgumentException("The sp_dc token could not be found. Please check your input.")
                _errorMessage.value = error.message
                onResult(Result.failure(error))
                return
            }

            _authState.value = SpotifyAuthState.LOADING
            _errorMessage.value = null
            launchLoad {
                SpotifyAuth
                    .fetchAccessToken(spDc, spKey)
                    .onSuccess { token ->
                        store.spDc = spDc
                        store.spKey = spKey
                        saveToken(token)
                        loadLibrary(silent = false)
                        val name = _accountName.value.ifEmpty { "Spotify" }
                        withContext(Dispatchers.Main) { onResult(Result.success(name)) }
                    }.onFailure { error ->
                        Timber.e(error, "Spotify manual sign-in failed")
                        val message = friendlyMessage(error)
                        _authState.value = SpotifyAuthState.ERROR
                        _errorMessage.value = message
                        withContext(Dispatchers.Main) { onResult(Result.failure(Exception(message, error))) }
                    }
            }
        }

        /** Re-fetches the library. */
        fun reload() {
            if (_authState.value == SpotifyAuthState.LOADING) return
            if (_authState.value == SpotifyAuthState.ERROR) _authState.value = SpotifyAuthState.LOADING
            _errorMessage.value = null
            launchLoad {
                if (!store.isConnected) {
                    _authState.value = SpotifyAuthState.NOT_CONNECTED
                    return@launchLoad
                }
                refreshLibrary(silent = _playlists.value.isNotEmpty())
            }
        }

        /** Forgets the account and everything cached for it. */
        fun logout() {
            loadJob?.cancel()
            SpotifyClient.clearToken()
            _authState.value = SpotifyAuthState.NOT_CONNECTED
            _playlists.value = emptyList()
            _likedTracks.value = emptyList()
            _accountName.value = ""
            _accountAvatarUrl.value = null
            _errorMessage.value = null
            scope.launch { store.clearAll() }
        }

        fun dismissError() {
            _errorMessage.value = null
            if (_authState.value == SpotifyAuthState.ERROR) {
                _authState.value = if (_playlists.value.isNotEmpty()) SpotifyAuthState.CONNECTED else SpotifyAuthState.NOT_CONNECTED
            }
        }

        // ── Library ──────────────────────────────────────────────────────────────

        /**
         * All tracks of a playlist. Falls back to the copy saved on this device when Spotify can't be
         * reached, so a playlist that was opened before stays readable offline.
         */
        suspend fun playlistTracks(playlistId: String): Result<SpotifyTracksResult> =
            withContext(Dispatchers.IO) {
                val fresh =
                    if (ensureToken()) {
                        runCatching { fetchAllPlaylistTracks(playlistId) }
                    } else {
                        Result.failure(lastAuthError ?: IllegalStateException("Not signed in to Spotify"))
                    }
                fresh.getOrNull()?.let { tracks ->
                    store.writePlaylistTracks(playlistId, tracks)
                    return@withContext Result.success(SpotifyTracksResult(tracks, isOfflineCopy = false))
                }
                val cached = store.readPlaylistTracks(playlistId)
                if (cached != null) {
                    Result.success(SpotifyTracksResult(cached, isOfflineCopy = true))
                } else {
                    Result.failure(fresh.exceptionOrNull() ?: IllegalStateException())
                }
            }

        fun cachedPlaylistTracks(playlistId: String): List<SpotifyWebTrack>? = store.readPlaylistTracks(playlistId)

        fun playlist(playlistId: String): SpotifyPlaylist? = _playlists.value.firstOrNull { it.id == playlistId }

        private suspend fun fetchAllPlaylistTracks(playlistId: String): List<SpotifyWebTrack> {
            val tracks = ArrayList<SpotifyWebTrack>()
            var offset = 0
            while (offset < MAX_PLAYLIST_TRACKS) {
                val page = SpotifyClient.playlistTracks(playlistId, limit = PLAYLIST_PAGE_SIZE, offset = offset).getOrThrow()
                tracks += page.items
                offset += PLAYLIST_PAGE_SIZE
                if (offset >= page.total || page.items.isEmpty()) break
            }
            return tracks
        }

        // ── Internals ────────────────────────────────────────────────────────────

        private fun launchLoad(block: suspend () -> Unit) {
            loadJob?.cancel()
            loadJob =
                scope.launch {
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Unhandled Spotify error")
                        _errorMessage.value = friendlyMessage(e)
                        _authState.value = SpotifyAuthState.ERROR
                    }
                }
        }

        private suspend fun restoreSession() {
            _accountName.value = store.accountName
            _accountAvatarUrl.value = store.accountAvatarUrl
            val cachedPlaylists = store.readPlaylists()
            if (cachedPlaylists != null) {
                // Show what was saved right away; the refresh below runs quietly behind it
                _playlists.value = cachedPlaylists
                _likedTracks.value = store.readLikedTracks().orEmpty()
                _authState.value = SpotifyAuthState.CONNECTED
            } else {
                _authState.value = SpotifyAuthState.LOADING
            }
            launchLoad { refreshLibrary(silent = cachedPlaylists != null) }
        }

        private suspend fun authenticateAndLoad() {
            val spDc = store.spDc
            SpotifyAuth
                .fetchAccessToken(spDc, store.spKey)
                .onSuccess { token ->
                    saveToken(token)
                    loadLibrary(silent = false)
                }.onFailure { error ->
                    Timber.e(error, "Spotify sign-in failed")
                    _errorMessage.value = friendlyMessage(error)
                    _authState.value = SpotifyAuthState.ERROR
                }
        }

        private suspend fun refreshLibrary(silent: Boolean) {
            if (!ensureToken()) {
                if (!silent) {
                    _errorMessage.value = friendlyMessage(lastAuthError ?: IllegalStateException())
                    _authState.value = SpotifyAuthState.ERROR
                }
                return
            }
            loadLibrary(silent)
        }

        private fun saveToken(token: SpotifyInternalToken) {
            SpotifyClient.setToken(token.accessToken)
            store.accessToken = token.accessToken
            store.tokenExpiresAtMs = token.accessTokenExpirationTimestampMs
        }

        /** A web-player access token that is still valid (renewed from the saved cookie when needed), or null when signed out. */
        suspend fun validAccessToken(): String? =
            withContext(Dispatchers.IO) {
                if (ensureToken()) store.accessToken.takeIf { it.isNotBlank() } else null
            }

        /** Makes sure [SpotifyClient] holds a token that is still valid, renewing it from the saved cookie if needed. */
        private suspend fun ensureToken(): Boolean {
            if (!store.isTokenExpiringSoon()) {
                SpotifyClient.setToken(store.accessToken)
                return true
            }
            return refreshTokenSilently()
        }

        private suspend fun refreshTokenSilently(): Boolean =
            tokenMutex.withLock {
                val spDc = store.spDc
                if (spDc.isBlank()) return@withLock false
                SpotifyAuth
                    .fetchAccessToken(spDc, store.spKey)
                    .onSuccess {
                        saveToken(it)
                        lastAuthError = null
                    }.onFailure { lastAuthError = it }
                    .isSuccess
            }

        /**
         * Loads profile, playlists and liked songs. With [silent] the screen keeps showing the saved
         * library and a failure (for example no internet) leaves it untouched instead of showing an error.
         */
        private suspend fun loadLibrary(silent: Boolean) {
            try {
                coroutineScope {
                    val profile = async { SpotifyClient.me() }
                    val playlistsLoad = async { loadAllPlaylists() }
                    val liked = async { SpotifyClient.likedSongs(limit = LIKED_PAGE_SIZE, offset = 0) }

                    profile.await().onSuccess { user ->
                        val name = user.displayName ?: user.id
                        val avatar = user.images.maxByOrNull { it.width ?: 0 }?.url ?: user.images.firstOrNull()?.url
                        _accountName.value = name
                        _accountAvatarUrl.value = avatar
                        store.accountName = name
                        store.accountAvatarUrl = avatar
                    }

                    val (loadedPlaylists, playlistError) = playlistsLoad.await()
                    if (loadedPlaylists.isEmpty() && playlistError != null) {
                        if (silent) return@coroutineScope
                        _errorMessage.value = friendlyMessage(playlistError)
                        _authState.value = SpotifyAuthState.ERROR
                        return@coroutineScope
                    }
                    _playlists.value = loadedPlaylists
                    store.writePlaylists(loadedPlaylists)

                    val firstLikedPage = liked.await()
                    firstLikedPage.onSuccess { page ->
                        _likedTracks.value = page.items
                        _authState.value = SpotifyAuthState.CONNECTED
                        loadRemainingLikedTracks(page)
                    }
                    firstLikedPage.onFailure { Timber.w(it, "Failed to load Spotify liked songs") }
                    _authState.value = SpotifyAuthState.CONNECTED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Spotify library load failed")
                if (!silent) {
                    _errorMessage.value = friendlyMessage(e)
                    _authState.value = SpotifyAuthState.ERROR
                }
            }
        }

        private suspend fun loadAllPlaylists(): Pair<List<SpotifyPlaylist>, Throwable?> {
            val all = ArrayList<SpotifyPlaylist>()
            var offset = 0
            var error: Throwable? = null
            while (true) {
                val page = SpotifyClient.myPlaylists(limit = PLAYLIST_LIST_PAGE_SIZE, offset = offset)
                page.onFailure { error = it }
                val loaded = page.getOrNull() ?: break
                all += loaded.items
                offset += loaded.items.size
                if (loaded.items.isEmpty() || offset >= loaded.total) break
            }
            return all to error
        }

        private suspend fun loadRemainingLikedTracks(firstPage: SpotifyPaging<SpotifyWebTrack>) {
            val tracks = ArrayList(firstPage.items)
            var offset = firstPage.items.size
            while (offset < firstPage.total && offset < MAX_LIKED_TRACKS && firstPage.items.isNotEmpty()) {
                val page = SpotifyClient.likedSongs(limit = LIKED_PAGE_SIZE, offset = offset).getOrNull() ?: break
                if (page.items.isEmpty()) break
                tracks += page.items
                offset += page.items.size
                _likedTracks.value = tracks.toList()
            }
            store.writeLikedTracks(tracks)
        }

        fun friendlyMessage(error: Throwable): String {
            val raw = error.message.orEmpty()
            return when {
                raw.contains("anonymous", ignoreCase = true) -> "Your Spotify session expired. Please sign in again."
                raw.contains("totp", ignoreCase = true) -> "Spotify rejected the sign-in request. Wavlin may need an update."
                error is java.io.IOException -> "Couldn't reach Spotify. Check your internet connection."
                raw.isBlank() -> "Spotify could not be connected."
                raw.length > 200 -> raw.take(200) + "…"
                else -> raw
            }
        }

        private companion object {
            const val PLAYLIST_LIST_PAGE_SIZE = 50
            const val PLAYLIST_PAGE_SIZE = 100
            const val LIKED_PAGE_SIZE = 50
            const val MAX_PLAYLIST_TRACKS = 5000
            const val MAX_LIKED_TRACKS = 5000
        }
    }
