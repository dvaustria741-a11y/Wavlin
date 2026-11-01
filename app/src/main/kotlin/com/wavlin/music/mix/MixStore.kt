/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.mix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for mixed playlists: which playlists are mixed, their transitions and the cached
 * track analysis. Kept in small JSON files so the Room schema stays untouched.
 */
object MixStore {
    private const val TAG = "MixStore"
    private const val STATE_FILE = "mix_state.json"
    private const val ANALYSIS_FILE = "mix_analysis.json"

    /** Pseudo playlist id used to audition an unsaved transition. */
    const val PREVIEW_PLAYLIST_ID = "__mix_preview__"

    private var stateFile: File? = null
    private var analysisFile: File? = null
    private val lock = Any()

    private val _mixedPlaylists = MutableStateFlow<Set<String>>(emptySet())
    val mixedPlaylists: StateFlow<Set<String>> = _mixedPlaylists.asStateFlow()

    private val _transitions = MutableStateFlow<Map<String, MixTransition>>(emptyMap())
    val transitions: StateFlow<Map<String, MixTransition>> = _transitions.asStateFlow()

    private val _analysis = MutableStateFlow<Map<String, MixTrackAnalysis>>(emptyMap())
    val analysis: StateFlow<Map<String, MixTrackAnalysis>> = _analysis.asStateFlow()

    @Volatile
    private var preview: MixTransition? = null

    @Volatile
    private var loaded = false

    /** True when at least one mixed playlist exists; used to keep audio offload off, as offload bypasses the mix DSP. */
    val hasMixedPlaylists: Boolean get() = _mixedPlaylists.value.isNotEmpty()

    fun init(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val dir = context.applicationContext.filesDir
            stateFile = File(dir, STATE_FILE)
            analysisFile = File(dir, ANALYSIS_FILE)
            loadState()
            loadAnalysis()
            loaded = true
        }
    }

    private fun key(
        playlistId: String,
        fromId: String,
        toId: String,
    ) = "$playlistId|$fromId|$toId"

    fun isMixed(playlistId: String?): Boolean = playlistId != null && playlistId in _mixedPlaylists.value

    fun setMixed(
        playlistId: String,
        mixed: Boolean,
    ) {
        synchronized(lock) {
            _mixedPlaylists.value = if (mixed) _mixedPlaylists.value + playlistId else _mixedPlaylists.value - playlistId
            saveState()
        }
    }

    fun transition(
        playlistId: String,
        fromId: String,
        toId: String,
    ): MixTransition? {
        if (playlistId == PREVIEW_PLAYLIST_ID) return preview?.takeIf { it.fromId == fromId && it.toId == toId }
        return _transitions.value[key(playlistId, fromId, toId)]
    }

    fun saveTransition(
        playlistId: String,
        transition: MixTransition,
    ) {
        synchronized(lock) {
            _transitions.value = _transitions.value + (key(playlistId, transition.fromId, transition.toId) to transition)
            saveState()
        }
    }

    fun removeTransition(
        playlistId: String,
        fromId: String,
        toId: String,
    ) {
        synchronized(lock) {
            _transitions.value = _transitions.value - key(playlistId, fromId, toId)
            saveState()
        }
    }

    fun setPreview(transition: MixTransition?) {
        preview = transition
    }

    fun analysisOf(songId: String): MixTrackAnalysis? = _analysis.value[songId]

    fun putAnalysis(result: MixTrackAnalysis) {
        synchronized(lock) {
            _analysis.value = _analysis.value + (result.songId to result)
            saveAnalysis()
        }
    }

    private fun loadState() {
        val f = stateFile ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val playlists = root.optJSONArray("playlists") ?: JSONArray()
            _mixedPlaylists.value = (0 until playlists.length()).map { playlists.getString(it) }.toSet()
            val items = root.optJSONArray("transitions") ?: JSONArray()
            val map = LinkedHashMap<String, MixTransition>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val t = MixTransition.fromJson(o)
                map[key(o.optString("playlist"), t.fromId, t.toId)] = t
            }
            _transitions.value = map
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read mix state")
        }
    }

    private fun saveState() {
        val f = stateFile ?: return
        try {
            val root = JSONObject()
            root.put("playlists", JSONArray(_mixedPlaylists.value.toList()))
            val items = JSONArray()
            _transitions.value.forEach { (k, t) ->
                items.put(t.toJson().put("playlist", k.substringBefore('|')))
            }
            root.put("transitions", items)
            writeAtomically(f, root.toString())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to write mix state")
        }
    }

    private fun loadAnalysis() {
        val f = analysisFile ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val map = LinkedHashMap<String, MixTrackAnalysis>()
            for (i in 0 until arr.length()) {
                val a = MixTrackAnalysis.fromJson(arr.getJSONObject(i))
                map[a.songId] = a
            }
            _analysis.value = map
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read mix analysis cache")
        }
    }

    private fun saveAnalysis() {
        val f = analysisFile ?: return
        try {
            val arr = JSONArray()
            _analysis.value.values.forEach { arr.put(it.toJson()) }
            writeAtomically(f, arr.toString())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to write mix analysis cache")
        }
    }

    private fun writeAtomically(
        target: File,
        text: String,
    ) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
