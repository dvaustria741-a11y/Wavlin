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
 * Manages Android's [EnvironmentalReverb] hardware audio effect.
 *
 * The effect is created as a session-bound INSERT effect tied to ExoPlayer's audio session.
 * Insert effects are applied automatically to all audio in that session — no [AuxEffectInfo]
 * needed.  Enabling the effect and setting its parameters is sufficient.
 *
 * Intensity is implemented by lerp-ing [EnvironmentalReverb.reverbLevel] and
 * [EnvironmentalReverb.reflectionsLevel] from −9000 mB (silent) to the preset's target value,
 * giving a true Subtle→Lush wet-level range without an auxiliary send path.
 *
 * Lifecycle:
 *  - [init]          : called once from MusicService.onCreate — loads persisted prefs.
 *  - [attachSession] : called from openAudioEffectSession() when ExoPlayer opens a session.
 *  - [detachSession] : called from closeAudioEffectSession() when the session closes.
 */
@Suppress("DEPRECATION")
object ReverbManager {

    private const val TAG = "ReverbManager"

    /**
     * EnvironmentalReverb parameters.
     * Units: roomLevel/roomHFLevel/reflectionsLevel/reverbLevel in mB,
     *        decayTime/reflectionsDelay/reverbDelay in ms,
     *        decayHFRatio/diffusion/density in ‰.
     */
    data class Preset(
        val roomLevel: Short,
        val roomHFLevel: Short,
        val decayTime: Int,
        val decayHFRatio: Short,
        val reflectionsLevel: Short,
        val reflectionsDelay: Int,
        val reverbLevel: Short,
        val reverbDelay: Int,
        val diffusion: Short,
        val density: Short,
    )

    /** String resource IDs for the preset dropdown — order matches [presets]. */
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
     * Index-aligned with [presetLabels].
     * null at index 0 (None) = effect is bypassed.
     *
     * Parameter values are taken verbatim from SpatialFlow's setReverbPreset():
     *   Small Room    → preset 1
     *   Concert Hall  → preset 2  (Medium Room in SpatialFlow code)
     *   Stadium       → preset 3  (Large Room)
     *   Plate         → preset 4  (Medium Hall)
     *   Spring        → preset 5  (Large Hall)
     */
    private val presets: List<Preset?> = listOf(
        // 0 — None (bypass)
        null,
        // 1 — Small Room
        Preset(
            roomLevel = -1500, roomHFLevel = -100, decayTime = 4000,  decayHFRatio = 1200,
            reflectionsLevel = 0,    reflectionsDelay = 50,  reverbLevel = 500,  reverbDelay = 40,
            diffusion = 1000, density = 1000,
        ),
        // 2 — Concert Hall  (SpatialFlow "Medium Room" params)
        Preset(
            roomLevel = -1500, roomHFLevel = 0,    decayTime = 6000,  decayHFRatio = 1400,
            reflectionsLevel = 200,  reflectionsDelay = 80,  reverbLevel = 700,  reverbDelay = 60,
            diffusion = 1000, density = 1000,
        ),
        // 3 — Stadium  (SpatialFlow "Large Room" params)
        Preset(
            roomLevel = -1500, roomHFLevel = 0,    decayTime = 8000,  decayHFRatio = 1600,
            reflectionsLevel = 400,  reflectionsDelay = 120, reverbLevel = 900,  reverbDelay = 80,
            diffusion = 1000, density = 1000,
        ),
        // 4 — Plate  (SpatialFlow "Medium Hall" params)
        Preset(
            roomLevel = -1500, roomHFLevel = 0,    decayTime = 12000, decayHFRatio = 1800,
            reflectionsLevel = 600,  reflectionsDelay = 160, reverbLevel = 1100, reverbDelay = 100,
            diffusion = 1000, density = 1000,
        ),
        // 5 — Spring  (SpatialFlow "Large Hall" params)
        Preset(
            roomLevel = -1500, roomHFLevel = 0,    decayTime = 16000, decayHFRatio = 1900,
            reflectionsLevel = 800,  reflectionsDelay = 220, reverbLevel = 1300, reverbDelay = 100,
            diffusion = 1000, density = 1000,
        ),
    )

    // ── Shared preferences ───────────────────────────────────────────────────────
    private const val PREFS_NAME    = "wavlin_reverb"
    private const val KEY_ENABLED   = "enabled"
    private const val KEY_PRESET    = "preset"
    private const val KEY_INTENSITY = "intensity"

    private var appContext: Context? = null
    private val prefs get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── StateFlows observed by the UI ────────────────────────────────────────────
    private val _isEnabled   = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _presetIndex = MutableStateFlow(0)
    val presetIndex: StateFlow<Int> = _presetIndex.asStateFlow()

    private val _intensity   = MutableStateFlow(1.0f)
    val intensity: StateFlow<Float> = _intensity.asStateFlow()

    // ── Hardware effect — one instance per ExoPlayer audio session ───────────────
    private var reverbEffect: EnvironmentalReverb? = null
    private var attachedPlayer: ExoPlayer? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────────

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
     * Create [EnvironmentalReverb] tied to the new ExoPlayer audio session.
     * This is a session-bound INSERT effect — it processes audio automatically once enabled.
     * Called from MusicService.openAudioEffectSession().
     */
    fun attachSession(sessionId: Int, player: ExoPlayer) {
        releaseEffect()
        // Clear any stale AuxEffectInfo — insert effects don't use the aux path.
        player.setAuxEffectInfo(AuxEffectInfo(0, 0f))
        try {
            reverbEffect = EnvironmentalReverb(0, sessionId).also { applyCurrentStateToEffect(it) }
            attachedPlayer = player
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create EnvironmentalReverb for session $sessionId")
            reverbEffect = null
        }
    }

    /**
     * Disconnect the reverb from the player and release the hardware effect.
     * Called from MusicService.closeAudioEffectSession().
     */
    fun detachSession(player: ExoPlayer? = null) {
        (player ?: attachedPlayer)?.setAuxEffectInfo(AuxEffectInfo(0, 0f))
        releaseEffect()
    }

    private fun releaseEffect() {
        try { reverbEffect?.release() } catch (_: Exception) {}
        reverbEffect   = null
        attachedPlayer = null
    }

    // ── Settings mutations ───────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        reverbEffect?.let { applyCurrentStateToEffect(it) }
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index.coerceIn(0, presetLabels.lastIndex)
        prefs?.edit()?.putInt(KEY_PRESET, _presetIndex.value)?.apply()
        reverbEffect?.let { applyCurrentStateToEffect(it) }
    }

    fun setIntensity(value: Float) {
        _intensity.value = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY_INTENSITY, _intensity.value)?.apply()
        reverbEffect?.let { applyCurrentStateToEffect(it) }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    /**
     * Push current enabled/preset/intensity state onto the [EnvironmentalReverb] instance.
     *
     * [reverbLevel] and [reflectionsLevel] are lerp'd from −9000 mB (silent) to the preset's
     * target, so the Subtle (0 %) → Lush (100 %) slider scales the wet reverb output directly.
     */
    private fun applyCurrentStateToEffect(effect: EnvironmentalReverb) {
        try {
            val p = activePreset()
            // Disable (bypass) when toggled off or preset is None.
            effect.enabled = _isEnabled.value && p != null
            if (p == null) {
                effect.decayTime   = 100
                effect.reverbLevel = -9000
                return
            }
            val t = _intensity.value
            effect.roomLevel        = p.roomLevel
            effect.roomHFLevel      = p.roomHFLevel
            effect.decayTime        = p.decayTime
            effect.decayHFRatio     = p.decayHFRatio
            effect.diffusion        = p.diffusion
            effect.density          = p.density
            effect.reflectionsDelay = p.reflectionsDelay
            effect.reverbDelay      = p.reverbDelay
            // Lerp wet levels by intensity: 0 % = silent (−90 dB), 100 % = preset target
            effect.reflectionsLevel = ((-9000) + (p.reflectionsLevel.toInt() - (-9000)) * t)
                .toInt().coerceIn(-9000, 1000).toShort()
            effect.reverbLevel      = ((-9000) + (p.reverbLevel.toInt() - (-9000)) * t)
                .toInt().coerceIn(-9000, 2000).toShort()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to apply preset to EnvironmentalReverb")
        }
    }

    /** The preset currently active, or null when disabled / set to None. */
    fun activePreset(): Preset? = if (_isEnabled.value) presets[_presetIndex.value] else null
}
