package com.wavlin.music.spotify

import com.wavlin.innertube.YouTube
import com.wavlin.innertube.models.SongItem
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Finds the YouTube (Music) result that best matches a Spotify track.
 * Scoring (name / artists / duration, forbidden-word penalty, songs first then videos) is adapted
 * from spotDL (https://github.com/spotDL/spotify-downloader, MIT License).
 */
object SpotifyMatcher {
    private val FORBIDDEN_WORDS =
        listOf(
            "bassboosted", "remix", "remastered", "remaster", "reverb", "bassboost", "live",
            "acoustic", "8daudio", "concert", "acapella", "slowed", "instrumental", "cover",
        )
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Songs first; if nothing matches well, YouTube Music videos (covers tracks that have no "song" entry). */
    suspend fun match(track: SpotifyTrack): SongItem? {
        for (filter in listOf(YouTube.SearchFilter.FILTER_SONG, YouTube.SearchFilter.FILTER_VIDEO)) {
            val items =
                YouTube
                    .search(track.query, filter)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    .orEmpty()
            items
                .mapNotNull { item -> score(track, item)?.let { item to it } }
                .maxByOrNull { it.second }
                ?.let { return it.first }
        }
        return null
    }

    private fun score(track: SpotifyTrack, item: SongItem): Double? {
        val songName = normalize(track.title)
        val resultTitle = normalize(item.title)
        if (songName.isEmpty() || resultTitle.isEmpty()) return null

        val artists = track.artist.split(",").map { normalize(it) }.filter { it.isNotEmpty() }
        val haystack = (item.artists.map { normalize(it.name) } + resultTitle).joinToString(" ")

        // Name match
        var name = max(ratio(resultTitle, songName), ratio(resultTitle, normalize("${track.artist} ${track.title}")))
        if (containsWords(resultTitle, songName)) name = max(name, 85.0)
        val resultCompact = resultTitle.replace(" ", "")
        val songCompact = songName.replace(" ", "")
        name -= 15.0 * FORBIDDEN_WORDS.count { it in resultCompact && it !in songCompact }
        if (name <= 60) return null

        // Artist match: main artist must be present, others add the rest
        val artistScore =
            when {
                artists.isEmpty() -> 100.0
                else -> {
                    val mainFound = containsWords(haystack, artists.first())
                    val others = artists.drop(1)
                    if (others.isEmpty()) {
                        if (mainFound) 100.0 else 0.0
                    } else {
                        (if (mainFound) 70.0 else 0.0) + 30.0 * others.count { containsWords(haystack, it) } / others.size
                    }
                }
            }
        if (artistScore < 70) return null

        // Duration match
        val resultSeconds = item.duration
        val time =
            if (track.durationSeconds > 0 && resultSeconds != null) {
                exp(-0.1 * abs(track.durationSeconds - resultSeconds)) * 100
            } else {
                100.0
            }
        if (time < 25) return null

        var average = (artistScore + name) / 2
        if (time < 50 && average < 75) return null
        if (average <= 85) average = (average + time) / 2
        if (!item.isVideoSong) average += 1.0 // prefer the official audio version
        return average
    }

    private fun normalize(text: String): String =
        Normalizer
            .normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(NON_WORD, " ")
            .trim()

    private fun containsWords(haystack: String, needle: String): Boolean = needle.isNotEmpty() && " $haystack ".contains(" $needle ")

    // Similarity 0..100 from Levenshtein distance
    private fun ratio(a: String, b: String): Double {
        val longest = max(a.length, b.length)
        if (longest == 0) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return (1.0 - previous[b.length].toDouble() / longest) * 100
    }
}
