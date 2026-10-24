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
 * from spotDL (https://github.com/spotDL/spotify-downloader, MIT License), plus a strong preference
 * for official audio tracks over user uploads (edits, fan videos).
 */
object SpotifyMatcher {
    // Matched as whole words in the title (unless the Spotify title has them too)
    private val FORBIDDEN_WORDS =
        listOf(
            "remix", "remastered", "remaster", "reverb", "live", "acoustic", "concert", "acapella",
            "slowed", "instrumental", "cover", "edit", "mashup", "amv", "tiktok", "sped", "karaoke",
        )

    // Matched anywhere in the space-less title (they are often glued or spaced differently)
    private val FORBIDDEN_COMPACT =
        listOf("bassboosted", "bassboost", "8daudio", "spedup", "speedup", "nightcore", "fanmade")

    private const val CONFIDENT = 95.0
    private const val TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
    private const val TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
    private const val TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val BRACKETS = Regex("[\\(\\[][^\\)\\]]*[\\)\\]]")
    private val FEAT_TAIL = Regex("\\s+(feat|ft|featuring|with)\\.?\\s.*$", RegexOption.IGNORE_CASE)

    suspend fun match(track: SpotifyTrack): SongItem? {
        val scored = mutableListOf<Pair<SongItem, Double>>()

        suspend fun collect(query: String, filter: YouTube.SearchFilter) {
            YouTube
                .search(query, filter)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                .orEmpty()
                .forEach { item -> score(track, item)?.let { scored += item to it } }
        }

        fun best() = scored.maxByOrNull { it.second }

        // 1) Songs, "Artist - Title"
        collect(track.query, YouTube.SearchFilter.FILTER_SONG)
        // 2) Songs again with featured artists / brackets stripped (official titles often drop "(feat. X)")
        if ((best()?.second ?: 0.0) < CONFIDENT) {
            val simple = "${track.artist.substringBefore(",").trim()} ${stripExtras(track.title)}".trim()
            if (simple.isNotEmpty() && simple != track.query) collect(simple, YouTube.SearchFilter.FILTER_SONG)
        }
        // 3) Videos (tracks without a "song" entry)
        if ((best()?.second ?: 0.0) < CONFIDENT) collect(track.query, YouTube.SearchFilter.FILTER_VIDEO)

        return best()?.first
    }

    private fun score(track: SpotifyTrack, item: SongItem): Double? {
        val fullSong = normalize(track.title)
        val fullResult = normalize(item.title)
        val coreSong = normalize(stripExtras(track.title))
        val coreResult = normalize(stripExtras(item.title))
        if (fullSong.isEmpty() || fullResult.isEmpty()) return null

        val artists = track.artist.split(",").map { normalize(it) }.filter { it.isNotEmpty() }
        val haystack = (item.artists.map { normalize(it.name) } + fullResult).joinToString(" ")

        // Name match: compare both the full titles and the titles without "(feat. ...)" / brackets
        var name =
            maxOf(
                ratio(fullResult, fullSong),
                ratio(coreResult, coreSong),
                ratio(fullResult, normalize("${track.artist} ${track.title}")),
            )
        if (containsWords(fullResult, fullSong) || (coreSong.isNotEmpty() && containsWords(coreResult, coreSong))) {
            name = max(name, 85.0)
        }
        name -= 15.0 * forbiddenHits(fullSong, fullResult)
        if (name <= 60) return null

        // Artist match: main artist must be present, others add the rest
        val artistScore =
            if (artists.isEmpty()) {
                100.0
            } else {
                val mainFound = containsWords(haystack, artists.first())
                val others = artists.drop(1)
                if (others.isEmpty()) {
                    if (mainFound) 100.0 else 0.0
                } else {
                    (if (mainFound) 70.0 else 0.0) + 30.0 * others.count { containsWords(haystack, it) } / others.size
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

        // Prefer official audio tracks; push user uploads (edits, fan videos) down
        average +=
            when (item.musicVideoType) {
                TYPE_ATV -> 6.0
                TYPE_OMV -> 2.0
                TYPE_UGC -> -10.0
                else -> 0.0
            }
        return average
    }

    private fun forbiddenHits(song: String, result: String): Int {
        val songTokens = song.split(" ").toSet()
        val resultTokens = result.split(" ").toSet()
        val songCompact = song.replace(" ", "")
        val resultCompact = result.replace(" ", "")
        return FORBIDDEN_WORDS.count { it in resultTokens && it !in songTokens } +
            FORBIDDEN_COMPACT.count { it in resultCompact && it !in songCompact }
    }

    // "Raindance (feat. Tems)" -> "Raindance"; "Song [Official Audio]" -> "Song"
    private fun stripExtras(text: String): String =
        text.replace(BRACKETS, " ").replace(FEAT_TAIL, "").trim()

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
