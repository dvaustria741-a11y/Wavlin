/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.audio.reverb

import android.content.Context
import androidx.annotation.StringRes
import com.wavlin.music.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the user's reverb settings (enabled, preset, intensity). They are persisted in
 * SharedPreferences, so they survive song changes and service restarts. The audio is
 * processed by `ReverbAudioProcessor`, which reads this state on every buffer.
 */
object ReverbManager {

    /** DSP parameters of one preset; delay lengths are scaled by [combScale]. */
    class Preset(
        val room: Float,
        val damping: Float,
        val wet: Float,
        val combScale: Float,
        val predelayMs: Int,
    )

    @StringRes
    val presetLabels = listOf(
        R.string.reverb_preset_none,
        R.string.reverb_preset_small_room,
        R.string.reverb_preset_concert_hall,
        R.string.reverb_preset_stadium,
        R.string.reverb_preset_plate,
        R.string.reverb_preset_spring,
    )

    // Index-aligned with presetLabels; null (None) means bypass.
    private val presets = listOf(
        null,
        Preset(room = 0.35f, damping = 0.50f, wet = 0.22f, combScale = 0.55f, predelayMs = 0),
        Preset(room = 0.80f, damping = 0.35f, wet = 0.30f, combScale = 1.00f, predelayMs = 20),
        Preset(room = 0.92f, damping = 0.25f, wet = 0.34f, combScale = 1.40f, predelayMs = 45),
        Preset(room = 0.82f, damping = 0.10f, wet = 0.32f, combScale = 0.85f, predelayMs = 8),
        Preset(room = 0.55f, damping = 0.15f, wet = 0.30f, combScale = 0.38f, predelayMs = 0),
    )

    private const val PREFS_NAME = "wavlin_reverb"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_INTENSITY = "intensity"

    private var appContext: Context? = null
    private val prefs get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _presetIndex = MutableStateFlow(0)
    val presetIndex: StateFlow<Int> = _presetIndex.asStateFlow()

    private val _intensity = MutableStateFlow(1.0f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs?.let {
            _isEnabled.value = it.getBoolean(KEY_ENABLED, false)
            _presetIndex.value = it.getInt(KEY_PRESET, 0).coerceIn(0, presetLabels.lastIndex)
            _intensity.value = it.getFloat(KEY_INTENSITY, 1.0f).coerceIn(0f, 1f)
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index.coerceIn(0, presetLabels.lastIndex)
        prefs?.edit()?.putInt(KEY_PRESET, _presetIndex.value)?.apply()
    }

    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY_INTENSITY, _intensity.value)?.apply()
    }

    /** The preset to render right now, or null when reverb is off or set to None. */
    fun activePreset(): Preset? = if (_isEnabled.value) presets[_presetIndex.value] else null
}
