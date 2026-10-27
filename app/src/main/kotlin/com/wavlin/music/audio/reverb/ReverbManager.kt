/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.audio.reverb

import android.content.Context
import androidx.annotation.StringRes
import androidx.media3.exoplayer.ExoPlayer
import com.wavlin.music.R
import com.wavlin.music.playback.audio.ReverbAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central manager for Wavlin's reverb effect.
 *
 * The reverb is applied entirely in software via [ReverbAudioProcessor]
 * (Freeverb DSP), which lives in ExoPlayer's audio processor chain and
 * therefore works on every Android device regardless of hardware support.
 *
 * Preset names and approximate reverb character match SpatialFlow's reverb
 * presets exactly (None / Small Room / Concert Hall / Stadium / Plate / Spring).
 *
 * Lifecycle:
 *   1. [init]         — MusicService.onCreate()   → load persisted prefs
 *   2. [setProcessor] — createExoPlayer()          → wire up the DSP processor
 *   3. [setEnabled] / [setPreset] / [setIntensity] → called from the UI
 */
object ReverbManager {

    // ── Freeverb preset parameters ───────────────────────────────────────────
    /**
     * @param room     room size   [0.0, 1.0] — higher = longer tail
     * @param damping  HF damping  [0.0, 1.0] — higher = darker, less bright
     * @param wet      wet level   [0.0, 1.0] — amount of reverb in the mix
     */
    data class Preset(val room: Float, val damping: Float, val wet: Float)

    @StringRes
    val presetLabels: List<Int> = listOf(
        R.string.reverb_preset_none,
        R.string.reverb_preset_small_room,
        R.string.reverb_preset_concert_hall,
        R.string.reverb_preset_stadium,
        R.string.reverb_preset_plate,
        R.string.reverb_preset_spring,
    )

    /**
     * Freeverb parameters tuned to match SpatialFlow's EnvironmentalReverb
     * preset character as closely as possible in software.
     * Index 0 (None) = null → processor disabled.
     */
    private val presets: List<Preset?> = listOf(
        null,                                              // 0 — None
        Preset(room = 0.30f, damping = 0.70f, wet = 0.25f), // 1 — Small Room
        Preset(room = 0.65f, damping = 0.40f, wet = 0.40f), // 2 — Concert Hall
        Preset(room = 0.85f, damping = 0.25f, wet = 0.50f), // 3 — Stadium
        Preset(room = 0.60f, damping = 0.80f, wet = 0.35f), // 4 — Plate
        Preset(room = 0.50f, damping = 0.90f, wet = 0.30f), // 5 — Spring
    )

    // ── Shared preferences ───────────────────────────────────────────────────
    private const val PREFS_NAME    = "wavlin_reverb"
    private const val KEY_ENABLED   = "enabled"
    private const val KEY_PRESET    = "preset"
    private const val KEY_INTENSITY = "intensity"

    private var appContext: Context? = null
    private val prefs get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── StateFlows (observed by the UI) ──────────────────────────────────────
    private val _isEnabled   = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _presetIndex = MutableStateFlow(0)
    val presetIndex: StateFlow<Int> = _presetIndex.asStateFlow()

    private val _intensity   = MutableStateFlow(1.0f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    // ── DSP processor reference ───────────────────────────────────────────────
    private var processor: ReverbAudioProcessor? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Load persisted settings. Called once from MusicService.onCreate(). */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs?.let {
            _isEnabled.value   = it.getBoolean(KEY_ENABLED,   false)
            _presetIndex.value = it.getInt(KEY_PRESET,    0).coerceIn(0, presetLabels.lastIndex)
            _intensity.value   = it.getFloat(KEY_INTENSITY, 1.0f).coerceIn(0f, 1f)
        }
    }

    /**
     * Register the [ReverbAudioProcessor] that is part of the ExoPlayer
     * audio chain.  Called from MusicService.createExoPlayer() after the
     * processor is created and before the player is built.
     */
    fun setProcessor(proc: ReverbAudioProcessor) {
        processor = proc
        sync()
    }

    // Kept as no-ops so MusicService compiles unchanged after any prior edits.
    fun attachSession(sessionId: Int, player: ExoPlayer) = Unit
    fun detachSession(player: ExoPlayer? = null) = Unit

    // ─────────────────────────────────────────────────────────────────────────
    // Settings mutations (called from UI)
    // ─────────────────────────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        sync()
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index.coerceIn(0, presetLabels.lastIndex)
        prefs?.edit()?.putInt(KEY_PRESET, _presetIndex.value)?.apply()
        sync()
    }

    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY_INTENSITY, _intensity.value)?.apply()
        sync()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    /** Push current state to the DSP processor. */
    private fun sync() {
        val proc   = processor ?: return
        val preset = activePreset()
        if (preset != null) {
            proc.setEnabled(true)
            proc.setParameters(
                room     = preset.room,
                damping  = preset.damping,
                wetLevel = preset.wet * _intensity.value,
            )
        } else {
            proc.setEnabled(false)
        }
    }

    /** The active [Preset], or null when disabled or set to None. */
    fun activePreset(): Preset? =
        if (_isEnabled.value) presets[_presetIndex.value] else null
}
