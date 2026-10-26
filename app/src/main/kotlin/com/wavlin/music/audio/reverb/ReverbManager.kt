/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.audio.reverb

import android.content.Context
import android.media.audiofx.PresetReverb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Singleton that manages the [PresetReverb] audio effect and persists all
 * user settings (enabled, preset, intensity) across song changes via
 * SharedPreferences.
 *
 * Preset index → label:
 *   0 = None, 1 = Small Room, 2 = Concert Hall,
 *   3 = Stadium, 4 = Plate, 5 = Spring
 */
object ReverbManager {

    private var ctx: Context? = null
    private val prefs get() = ctx?.getSharedPreferences("wavlin_reverb", Context.MODE_PRIVATE)

    private var effect: PresetReverb? = null

    // ── Public state flows ────────────────────────────────────────────────
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _presetIndex = MutableStateFlow(0)
    val presetIndex: StateFlow<Int> = _presetIndex.asStateFlow()

    private val _intensity = MutableStateFlow(1.0f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    // Android PresetReverb constants mapped by our 0-5 index
    private val androidPresets = shortArrayOf(
        PresetReverb.PRESET_NONE,
        PresetReverb.PRESET_SMALLROOM,
        PresetReverb.PRESET_LARGEHALL,    // Concert Hall
        PresetReverb.PRESET_LARGEROOM,    // Stadium (large open space)
        PresetReverb.PRESET_PLATE,
        PresetReverb.PRESET_MEDIUMROOM,   // Spring (closest available)
    )

    val presetNames = listOf(
        "None", "Small Room", "Concert Hall", "Stadium", "Plate", "Spring"
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────
    /** Call once from MusicService.onCreate alongside DolbyAtmosManager.init(). */
    fun init(context: Context) {
        ctx = context.applicationContext
        prefs?.also { p ->
            _isEnabled.value = p.getBoolean("enabled", false)
            _presetIndex.value = p.getInt("preset", 0)
            _intensity.value = p.getFloat("intensity", 1.0f)
        }
    }

    /** Call from MusicService.openAudioEffectSession — same site as DolbyAtmosManager.attachSession(). */
    fun attachSession(audioSessionId: Int) {
        releaseEffect()
        if (!_isEnabled.value || _presetIndex.value == 0) return
        try {
            effect = PresetReverb(0, audioSessionId).apply {
                preset = androidPresets[_presetIndex.value.coerceIn(0, androidPresets.size - 1)]
                enabled = true
            }
        } catch (e: Exception) {
            Timber.e(e, "ReverbManager: failed to attach to session $audioSessionId")
        }
    }

    /** Call from MusicService.onDestroy alongside DolbyAtmosManager.release(). */
    fun release() = releaseEffect()

    // ── User actions (called from AudioEffectsSheet) ──────────────────────
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (enabled && _presetIndex.value != 0) {
            effect?.enabled = true
        } else {
            effect?.enabled = false
        }
        prefs?.edit()?.putBoolean("enabled", enabled)?.apply()
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index
        if (_isEnabled.value && index != 0) {
            effect?.let {
                it.preset = androidPresets[index.coerceIn(0, androidPresets.size - 1)]
                it.enabled = true
            }
        } else {
            effect?.enabled = false
        }
        prefs?.edit()?.putInt("preset", index)?.apply()
    }

    /** Intensity is stored for the UI slider; PresetReverb has no direct gain API. */
    fun setIntensity(value: Float) {
        _intensity.value = value
        prefs?.edit()?.putFloat("intensity", value)?.apply()
    }

    private fun releaseEffect() {
        try { effect?.release() } catch (_: Exception) { }
        effect = null
    }
}
