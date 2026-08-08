/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavlin.deezer.Deezer
import com.wavlin.innertube.YouTube
import com.wavlin.music.BuildConfig
import com.wavlin.spotify.Spotify
import com.wavlin.innertube.models.AlbumItem
import com.wavlin.innertube.models.filterExplicit
import com.wavlin.music.constants.HideExplicitKey
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.utils.dataStore
import com.wavlin.music.utils.get
import com.wavlin.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewReleaseViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    private val _newReleaseAlbums = MutableStateFlow<List<AlbumItem>>(emptyList())
    val newReleaseAlbums = _newReleaseAlbums.asStateFlow()

    // Deezer-sourced releases that were found on YouTube Music but aren't part of
    // YT Music's own "new releases" feed. Surfaced separately so the UI can badge
    // them if desired, without changing the shape of newReleaseAlbums.
    private val _deezerFoundAlbums = MutableStateFlow<List<AlbumItem>>(emptyList())
    val deezerFoundAlbums = _deezerFoundAlbums.asStateFlow()

    private val _isCheckingDeezer = MutableStateFlow(false)
    val isCheckingDeezer = _isCheckingDeezer.asStateFlow()

    private val spotify by lazy {
        Spotify(
            clientId = BuildConfig.SPOTIFY_CLIENT_ID,
            clientSecret = BuildConfig.SPOTIFY_CLIENT_SECRET,
        )
    }

    init {
        viewModelScope.launch {
            var favouriteArtistNames: List<String> = emptyList()
            var libraryArtistNames: List<String> = emptyList()

            YouTube
                .newReleaseAlbums()
                .onSuccess { albums ->
                    val artists: MutableMap<Int, String> = mutableMapOf()
                    val favouriteArtists: MutableMap<Int, String> = mutableMapOf()
                    database.allArtistsByPlayTime().first().let { list ->
                        var favIndex = 0
                        for ((artistsIndex, artist) in list.withIndex()) {
                            artists[artistsIndex] = artist.id
                            if (artist.artist.bookmarkedAt != null) {
                                favouriteArtists[favIndex] = artist.id
                                favIndex++
                            }
                        }
                        favouriteArtistNames = list.filter { it.artist.bookmarkedAt != null }.map { it.artist.name }
                        // Cap at 25 most-played to keep the Deezer/YouTube lookups reasonable.
                        libraryArtistNames = list.take(25).map { it.artist.name }
                    }
                    _newReleaseAlbums.value =
                        albums
                            .sortedBy { album ->
                                val artistIds = album.artists.orEmpty().mapNotNull { it.id }
                                val firstArtistKey =
                                    artistIds.firstNotNullOfOrNull { artistId ->
                                        if (artistId in favouriteArtists.values) {
                                            favouriteArtists.entries.firstOrNull { it.value == artistId }?.key
                                        } else {
                                            artists.entries.firstOrNull { it.value == artistId }?.key
                                        }
                                    } ?: Int.MAX_VALUE
                                firstArtistKey
                            }.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                }.onFailure {
                    reportException(it)
                }

            // Prefer checking followed artists; if none are bookmarked, fall back to
            // the most-played artists in the library.
            val artistNamesToCheck =
                favouriteArtistNames.ifEmpty { libraryArtistNames }.distinct()

            if (artistNamesToCheck.isNotEmpty()) {
                checkForMissingReleases(artistNamesToCheck)
            }
        }
    }

    /**
     * For each artist name, ask an external catalog whether they've dropped a new
     * album/single/EP recently, then look it up on YouTube Music so it can still
     * be played through the app's normal pipeline. Spotify's official Web API is
     * tried first (better catalog coverage) when credentials are configured and
     * valid; if Spotify is unconfigured, unauthorized (e.g. no Premium on the
     * registering account), or simply doesn't know about the artist, this
     * silently falls back to Deezer's free/no-auth API per artist. Either way,
     * both sources are only ever used for metadata - never for playback.
     */
    private suspend fun checkForMissingReleases(artistNames: List<String>) {
        _isCheckingDeezer.value = true
        try {
            val existingKeys =
                _newReleaseAlbums.value
                    .map { it.normalizedKey() }
                    .toMutableSet()

            val found = mutableListOf<AlbumItem>()

            // Small chunks with a short delay between them to stay well within
            // both APIs' generous but rate-limited free tiers.
            artistNames.chunked(5).forEach { chunk ->
                chunk.forEach { artistName ->
                    runCatching {
                        val releaseTitles = fetchReleaseTitles(artistName)
                        releaseTitles.forEach { title ->
                            val key = normalizedKey(artistName, title)
                            if (key in existingKeys) return@forEach

                            val matched = findOnYouTubeMusic(artistName, title)
                            if (matched != null && matched.normalizedKey() !in existingKeys) {
                                existingKeys += matched.normalizedKey()
                                found += matched
                            }
                        }
                    }
                }
                kotlinx.coroutines.delay(500)
            }

            if (found.isNotEmpty()) {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                val merged =
                    (_newReleaseAlbums.value + found)
                        .distinctBy { it.id }
                        .filterExplicit(hideExplicit)
                _newReleaseAlbums.value = merged
                _deezerFoundAlbums.value = found
            }
        } catch (e: Exception) {
            reportException(e)
        } finally {
            _isCheckingDeezer.value = false
        }
    }

    /**
     * Returns recent release titles for [artistName]. Tries Spotify first (if
     * configured), falling back to Deezer if Spotify returns nothing - whether
     * that's because it's unconfigured, the credentials were rejected, or it
     * just doesn't have data for this artist. Both APIs fail closed (empty list)
     * on any error, so no explicit error handling is needed here.
     */
    private suspend fun fetchReleaseTitles(artistName: String): List<String> {
        if (spotify.isConfigured) {
            val spotifyReleases = spotify.getRecentReleases(artistName)
            if (spotifyReleases.isNotEmpty()) {
                return spotifyReleases.map { it.name }
            }
        }
        return Deezer.getRecentReleases(artistName).map { it.title }
    }

    private suspend fun findOnYouTubeMusic(
        artistName: String,
        albumTitle: String,
    ): AlbumItem? =
        runCatching {
            YouTube
                .search("$artistName $albumTitle", YouTube.SearchFilter.FILTER_ALBUM)
                .getOrNull()
                ?.items
                ?.filterIsInstance<AlbumItem>()
                ?.firstOrNull { candidate ->
                    titleSimilarity(candidate.title, albumTitle) > 0.6 &&
                        candidate.artists.orEmpty().any {
                            titleSimilarity(it.name, artistName) > 0.6
                        }
                }
        }.getOrNull()

    private fun AlbumItem.normalizedKey(): String =
        normalizedKey(artists.orEmpty().firstOrNull()?.name.orEmpty(), title)

    private fun normalizedKey(
        artist: String,
        title: String,
    ): String =
        "${normalize(artist)}|${normalize(title)}"

    private fun normalize(text: String): String =
        text
            .lowercase()
            .replace(Regex("""[^a-z0-9]"""), "")

    private fun titleSimilarity(
        a: String,
        b: String,
    ): Double {
        val s1 = normalize(a)
        val s2 = normalize(b)
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0
        if (s1.contains(s2) || s2.contains(s1)) return 0.85

        val distance = levenshtein(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] =
                    minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost,
                    )
            }
        }
        return dp[a.length][b.length]
    }
}
