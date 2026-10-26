/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.audio.reverb

import android.content.Context
import android.media.audiofx.EnvironmentalReverb
import androidx.annotation.StringRes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.exoplayer.ExoPlayer
import com.wavlin.music.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Singleton owning the reverb engine (ported from SpatialFlow). Settings are kept in
 * SharedPreferences and re-applied whenever the audio session changes, so they survive
 * song changes and service restarts. Intensity drives the player's aux send level.
 */
object ReverbManager {

    private class Params(
        val roomLevel: Int,
        val roomHfLevel: Int,
        val decayTime: Int,
        val decayHfRatio: Int,
        val reflectionsLevel: Int,
        val reflectionsDelay: Int,
        val reverbLevel: Int,
        val reverbDelay: Int,
        val diffusion: Int,
        val density: Int,
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

    // Index-aligned with presetLabels; index 0 (None) is handled by disabling the effect.
    private val presetParams = listOf(
        null,
        Params(-1500, -100, 4000, 1200, 0, 50, 500, 40, 1000, 1000),
        Params(-1500, 0, 12000, 1800, 600, 160, 1100, 100, 1000, 1000),
        Params(-1500, 0, 16000, 1900, 800, 220, 1300, 100, 1000, 1000),
        Params(-1500, 0, 20000, 2000, 1000, 300, 1600, 100, 1000, 1000),
        Params(-1500, -600, 2500, 600, 300, 30, 900, 30, 600, 500),
    )

    private const val PREFS_NAME = "wavlin_reverb"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_INTENSITY = "intensity"

    private var appContext: Context? = null
    private var effect: EnvironmentalReverb? = null
    private var player: ExoPlayer? = null

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _presetIndex = MutableStateFlow(0)
    val presetIndex: StateFlow<Int> = _presetIndex.asStateFlow()

    private val _intensity = MutableStateFlow(1.0f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    private val prefs get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs?.let {
            _isEnabled.value = it.getBoolean(KEY_ENABLED, false)
            _presetIndex.value = it.getInt(KEY_PRESET, 0).coerceIn(0, presetLabels.lastIndex)
            _intensity.value = it.getFloat(KEY_INTENSITY, 1.0f).coerceIn(0f, 1f)
        }
    }

    /** Called whenever the player's audio session opens or changes; re-applies the saved settings. */
    fun attachSession(audioSessionId: Int, exoPlayer: ExoPlayer) {
        releaseEffect()
        player = exoPlayer
        try {
            effect = EnvironmentalReverb(0, audioSessionId)
            applyState()
        } catch (e: Exception) {
            Timber.e(e, "ReverbManager: failed to attach to session $audioSessionId")
            effect = null
        }
    }

    fun release() {
        releaseEffect()
        player = null
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        applyState()
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index.coerceIn(0, presetLabels.lastIndex)
        prefs?.edit()?.putInt(KEY_PRESET, _presetIndex.value)?.apply()
        applyState()
    }

    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY_INTENSITY, _intensity.value)?.apply()
        applyState()
    }

    private fun applyState() {
        val reverb = effect ?: return
        val params = presetParams[_presetIndex.value]
        try {
            if (!_isEnabled.value || params == null) {
                reverb.enabled = false
                player?.setAuxEffectInfo(AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f))
                return
            }
            reverb.roomLevel = params.roomLevel.toShort()
            reverb.roomHFLevel = params.roomHfLevel.toShort()
            reverb.decayTime = params.decayTime
            reverb.decayHFRatio = params.decayHfRatio.toShort()
            reverb.reflectionsLevel = params.reflectionsLevel.toShort()
            reverb.reflectionsDelay = params.reflectionsDelay
            reverb.reverbLevel = params.reverbLevel.toShort()
            reverb.reverbDelay = params.reverbDelay
            reverb.diffusion = params.diffusion.toShort()
            reverb.density = params.density.toShort()
            reverb.enabled = true
            player?.setAuxEffectInfo(AuxEffectInfo(reverb.id, _intensity.value))
        } catch (e: Exception) {
            Timber.e(e, "ReverbManager: failed to apply reverb state")
        }
    }

    private fun releaseEffect() {
        try {
            player?.setAuxEffectInfo(AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f))
        } catch (_: Exception) {
        }
        try {
            effect?.release()
        } catch (_: Exception) {
        }
        effect = null
    }
}
