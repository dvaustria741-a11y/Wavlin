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
 * Reverb engine ported from SpatialFlow: an [EnvironmentalReverb] attached to the player's audio
 * session and fed through the player's aux send. The per-preset parameters are SpatialFlow's own.
 * Settings are persisted, so they survive song changes and are re-applied on every session change.
 */
object ReverbManager {

    /** SpatialFlow's EnvironmentalReverb parameters for one preset. */
    private class Params(
        val roomLevel: Int,
        val roomHfLevel: Int,
        val decayTime: Int,
        val decayHfRatio: Int,
        val reflectionsLevel: Int,
        val reflectionsDelay: Int,
        val reverbLevel: Int,
        val reverbDelay: Int,
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

    // SpatialFlow presets: 0 None, 1 Small Room, 2 Medium Room, 3 Large Room, 4 Medium Hall,
    // 5 Large Hall, 6 Plate.
    private val spatialFlowPresets = listOf(
        null,
        Params(-1500, -100, 4000, 1200, 0, 50, 500, 40),
        Params(-1500, 0, 6000, 1400, 200, 80, 700, 60),
        Params(-1500, 0, 8000, 1600, 400, 120, 900, 80),
        Params(-1500, 0, 12000, 1800, 600, 160, 1100, 100),
        Params(-1500, 0, 16000, 1900, 800, 220, 1300, 100),
        Params(-1500, 0, 20000, 2000, 1000, 300, 1600, 100),
    )

    // Wavlin preset index -> SpatialFlow preset index.
    private val engineIndex = intArrayOf(0, 1, 4, 5, 6, 2)

    private const val PREFS_NAME = "wavlin_reverb"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_INTENSITY = "intensity"

    private var appContext: Context? = null
    private var effect: EnvironmentalReverb? = null
    private var player: ExoPlayer? = null

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

    /** Called when the player's audio session opens or changes; re-applies the saved settings. */
    fun attachSession(audioSessionId: Int, exoPlayer: ExoPlayer) {
        releaseEffect()
        player = exoPlayer
        try {
            effect = EnvironmentalReverb(0, audioSessionId)
            Timber.tag(TAG).d("Attached reverb to session %d", audioSessionId)
            applyState()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create reverb for session %d", audioSessionId)
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

    /** Intensity is the strength of the player's aux send into the reverb. */
    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY_INTENSITY, _intensity.value)?.apply()
        applyState()
    }

    private fun applyState() {
        val reverb = effect ?: return
        val params = spatialFlowPresets[engineIndex[_presetIndex.value]]
        try {
            if (!_isEnabled.value || params == null) {
                reverb.enabled = false
                player?.setAuxEffectInfo(AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f))
                return
            }
            reverb.enabled = true
            reverb.roomLevel = params.roomLevel.toShort()
            reverb.roomHFLevel = params.roomHfLevel.toShort()
            reverb.decayTime = params.decayTime
            reverb.decayHFRatio = params.decayHfRatio.toShort()
            reverb.reflectionsLevel = params.reflectionsLevel.toShort()
            reverb.reflectionsDelay = params.reflectionsDelay
            reverb.reverbLevel = params.reverbLevel.toShort()
            reverb.reverbDelay = params.reverbDelay
            reverb.diffusion = 1000
            reverb.density = 1000
            player?.setAuxEffectInfo(AuxEffectInfo(reverb.id, _intensity.value))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to apply reverb state")
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

    private const val TAG = "ReverbManager"
}
