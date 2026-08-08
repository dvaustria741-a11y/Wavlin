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
import com.wavlin.innertube.models.AlbumItem
import com.wavlin.innertube.models.filterExplicit
import com.wavlin.music.constants.HideExplicitKey
import com.wavlin.music.constants.PendingNewReleasesKey
import com.wavlin.music.db.MusicDatabase
import com.wavlin.music.utils.dataStore
import com.wavlin.music.utils.get
import com.wavlin.music.utils.reportException
import com.wavlin.music.utils.safeDataStoreEdit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * A Deezer-sourced release that hasn't been matched to anything on YouTube Music
 * yet. Persisted across sessions so it can keep being retried until it either
 * shows up on YouTube (and gets promoted into [NewReleaseViewModel.newReleaseAlbums])
 * or expires after [PENDING_EXPIRY_DAYS].
 */
@Serializable
data class PendingRelease(
    val artistName: String,
    val albumTitle: String,
    val coverUrl: String? = null,
    val firstSeenAtMillis: Long,
)

@HiltViewModel
class NewReleaseViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    private val _newReleaseAlbums = MutableStateFlow<List<AlbumItem>>(emptyList())
    val newReleaseAlbums = _newReleaseAlbums.asStateFlow()

    // Deezer releases still waiting on a YouTube Music upload. Exposed so the UI
    // can optionally surface a "coming soon" section; safe to ignore otherwise.
    private val _pendingReleases = MutableStateFlow<List<PendingRelease>>(emptyList())
    val pendingReleases = _pendingReleases.asStateFlow()

    private val _isCheckingDeezer = MutableStateFlow(false)
    val isCheckingDeezer = _isCheckingDeezer.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

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

            // Retry anything still pending from previous sessions before looking
            // for anything new.
            recheckPendingReleases()

            // Prefer checking followed artists; if none are bookmarked, fall back to
            // the most-played artists in the library.
            val artistNamesToCheck =
                favouriteArtistNames.ifEmpty { libraryArtistNames }.distinct()

            if (artistNamesToCheck.isNotEmpty()) {
                checkDeezerForMissingReleases(artistNamesToCheck)
            }
        }
    }

    private fun loadPending(): List<PendingRelease> =
        runCatching {
            context.dataStore
                .get(PendingNewReleasesKey, "")
                .takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString<List<PendingRelease>>(it) }
                ?: emptyList()
        }.getOrDefault(emptyList())

    private suspend fun savePending(pending: List<PendingRelease>) {
        context.safeDataStoreEdit {
            it[PendingNewReleasesKey] = json.encodeToString(pending)
        }
    }

    /**
     * Retries every persisted [PendingRelease]: drops anything older than
     * [PENDING_EXPIRY_DAYS], promotes anything now findable on YouTube Music into
     * [newReleaseAlbums], and leaves the rest pending for the next app launch.
     */
    private suspend fun recheckPendingReleases() {
        val pending = loadPending()
        if (pending.isEmpty()) return

        val now = System.currentTimeMillis()
        val expiryMillis = PENDING_EXPIRY_DAYS * 24 * 60 * 60 * 1000L
        val notExpired = pending.filter { now - it.firstSeenAtMillis < expiryMillis }

        val stillPending = mutableListOf<PendingRelease>()
        val promoted = mutableListOf<AlbumItem>()

        notExpired.forEach { release ->
            val matched = findOnYouTubeMusic(release.artistName, release.albumTitle)
            if (matched != null) {
                promoted += matched
            } else {
                stillPending += release
            }
        }

        if (promoted.isNotEmpty()) {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            _newReleaseAlbums.value =
                (_newReleaseAlbums.value + promoted)
                    .distinctBy { it.id }
                    .filterExplicit(hideExplicit)
        }

        _pendingReleases.value = stillPending
        if (stillPending.size != pending.size) {
            savePending(stillPending)
        }
    }

    /**
     * For each artist name, ask Deezer (free, no auth) whether they've dropped a
     * new album/single/EP recently. Deezer's catalog tends to reflect new releases
     * faster/more completely than YouTube Music's own "new releases" shelf. Any
     * release Deezer knows about that isn't already in [newReleaseAlbums] gets
     * searched for on YouTube Music so it can still be played through the app's
     * normal pipeline - Deezer is only ever used for metadata, never playback.
     * Anything not (yet) found on YouTube is persisted as pending and retried on
     * every future launch (see [recheckPendingReleases]) until it appears or expires.
     */
    private suspend fun checkDeezerForMissingReleases(artistNames: List<String>) {
        _isCheckingDeezer.value = true
        try {
            val existingKeys =
                _newReleaseAlbums.value
                    .map { it.normalizedKey() }
                    .toMutableSet()
            val pendingKeys =
                _pendingReleases.value
                    .map { normalizedKey(it.artistName, it.albumTitle) }
                    .toMutableSet()

            val found = mutableListOf<AlbumItem>()
            val newlyPending = mutableListOf<PendingRelease>()
            val now = System.currentTimeMillis()

            // Small chunks with a short delay between them to stay well within
            // Deezer's generous but rate-limited free tier.
            artistNames.chunked(5).forEach { chunk ->
                chunk.forEach { artistName ->
                    runCatching {
                        val releases = Deezer.getRecentReleases(artistName)
                        releases.forEach { release ->
                            val key = normalizedKey(artistName, release.title)
                            if (key in existingKeys || key in pendingKeys) return@forEach

                            val matched = findOnYouTubeMusic(artistName, release.title)
                            if (matched != null && matched.normalizedKey() !in existingKeys) {
                                existingKeys += matched.normalizedKey()
                                found += matched
                            } else {
                                pendingKeys += key
                                newlyPending +=
                                    PendingRelease(
                                        artistName = artistName,
                                        albumTitle = release.title,
                                        coverUrl = release.cover_medium,
                                        firstSeenAtMillis = now,
                                    )
                            }
                        }
                    }
                }
                delay(500)
            }

            if (found.isNotEmpty()) {
                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                _newReleaseAlbums.value =
                    (_newReleaseAlbums.value + found)
                        .distinctBy { it.id }
                        .filterExplicit(hideExplicit)
            }

            if (newlyPending.isNotEmpty()) {
                val merged = _pendingReleases.value + newlyPending
                _pendingReleases.value = merged
                savePending(merged)
            }
        } catch (e: Exception) {
            reportException(e)
        } finally {
            _isCheckingDeezer.value = false
        }
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

    companion object {
        private const val PENDING_EXPIRY_DAYS = 30
    }
}
