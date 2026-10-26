/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.audio.reverb

import android.content.Context
import androidx.annotation.StringRes
import com.wavlin.music.R
import com.wavlin.music.playback.audio.ReverbAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages reverb state and pushes settings to [ReverbAudioProcessor] in ExoPlayer's
 * audio pipeline.
 *
 * Previously used Android's [android.media.audiofx.EnvironmentalReverb] hardware effect,
 * which silently does nothing on most devices because the hardware DSP is not available or
 * doesn't intercept ExoPlayer's audio session. The audio-processor approach works on
 * all devices without any hardware dependency.
 *
 * Lifecycle:
 *  - [init]         : called once from MusicService.onCreate — loads persisted prefs.
 *  - [setProcessor] : called from MusicService.createExoPlayer — wires the processor.
 *                     attachSession / detachSession are kept as no-ops for compatibility.
 */
object ReverbManager {

    private const val TAG = "ReverbManager"

    /**
     * Internal preset parameters fed to [ReverbAudioProcessor].
     *
     * @param roomSize   0..1 — maps to comb-filter feedback (longer tail)
     * @param damping    0..1 — HF absorption in the feedback path
     * @param baseWet    base wet-mix level at 100 % intensity
     */
    private data class SoftPreset(val roomSize: Float, val damping: Float, val baseWet: Float)

    /** String resource IDs for the preset dropdown — order matches [softPresets]. */
    @StringRes
    val presetLabels: List<Int> = listOf(
        R.string.reverb_preset_none,
        R.string.reverb_preset_small_room,
        R.string.reverb_preset_concert_hall,
        R.string.reverb_preset_stadium,
        R.string.reverb_preset_plate,
        R.string.reverb_preset_spring,
    )

    /** null at index 0 (None) = effect bypassed. */
    private val softPresets: List<SoftPreset?> = listOf(
        null,                                       // 0 — None
        SoftPreset(0.30f, 0.55f, 0.25f),           // 1 — Small Room
        SoftPreset(0.50f, 0.35f, 0.28f),           // 2 — Concert Hall
        SoftPreset(0.65f, 0.22f, 0.32f),           // 3 — Stadium
        SoftPreset(0.78f, 0.12f, 0.35f),           // 4 — Plate
        SoftPreset(0.85f, 0.08f, 0.38f),           // 5 — Spring
    )

    // ── Shared preferences ────────────────────────────────────────────────────────
    private const val PREFS_NAME    = "wavlin_reverb"
    private const val KEY_ENABLED   = "enabled"
    private const val KEY_PRESET    = "preset"
    private const val KEY_INTENSITY = "intensity"

    private var appContext: Context? = null
    private val prefs get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── StateFlows observed by the UI ─────────────────────────────────────────────
    private val _isEnabled   = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _presetIndex = MutableStateFlow(0)
    val presetIndex: StateFlow<Int> = _presetIndex.asStateFlow()

    private val _intensity   = MutableStateFlow(1.0f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    // ── Audio processor reference ─────────────────────────────────────────────────
    private var audioProcessor: ReverbAudioProcessor? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    /** Load persisted settings.  Call once from MusicService.onCreate(). */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs?.let {
            _isEnabled.value   = it.getBoolean(KEY_ENABLED, false)
            _presetIndex.value = it.getInt(KEY_PRESET, 0).coerceIn(0, presetLabels.lastIndex)
            _intensity.value   = it.getFloat(KEY_INTENSITY, 1.0f).coerceIn(0f, 1f)
        }
    }

    /**
     * Register the [ReverbAudioProcessor] created in MusicService.createExoPlayer.
     * Called once per ExoPlayer instance.  Immediately pushes the current state so
     * reverb is active as soon as playback starts.
     */
    fun setProcessor(processor: ReverbAudioProcessor) {
        audioProcessor = processor
        pushToProcessor()
    }

    /**
     * No-op — kept so MusicService.openAudioEffectSession() compiles unchanged.
     * Software reverb doesn't need a hardware audio session.
     */
    fun attachSession(@Suppress("UNUSED_PARAMETER") sessionId: Int,
                      @Suppress("UNUSED_PARAMETER") player: androidx.media3.exoplayer.ExoPlayer) = Unit

    /**
     * No-op — kept so MusicService.closeAudioEffectSession() compiles unchanged.
     */
    fun detachSession(@Suppress("UNUSED_PARAMETER") player: androidx.media3.exoplayer.ExoPlayer? = null) = Unit

    // ── Settings mutations ────────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        pushToProcessor()
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index.coerceIn(0, presetLabels.lastIndex)
        prefs?.edit()?.putInt(KEY_PRESET, _presetIndex.value)?.apply()
        pushToProcessor()
    }

    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY_INTENSITY, _intensity.value)?.apply()
        pushToProcessor()
    }

    // ── Internal ──────────────────────────────────────────────────────────────────

    private fun pushToProcessor() {
        val proc = audioProcessor ?: return
        val p    = softPresets.getOrNull(_presetIndex.value)
        if (!_isEnabled.value || p == null) {
            proc.setReverbParams(enabled = false, roomSize = 0.5f, damping = 0.5f, wet = 0f)
        } else {
            proc.setReverbParams(
                enabled  = true,
                roomSize = p.roomSize,
                damping  = p.damping,
                wet      = p.baseWet * _intensity.value,
            )
        }
    }
}
