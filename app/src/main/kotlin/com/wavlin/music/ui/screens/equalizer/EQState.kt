package com.wavlin.music.ui.screens.equalizer

import com.wavlin.music.eq.data.SavedEQProfile
import kotlin.math.round

/**
 * UI State for EQ Screen
 */
data class EQState(
    /** Imported / AutoEQ profiles. */
    val profiles: List<SavedEQProfile> = emptyList(),
    val activeProfileId: String? = null,
    val importStatus: String? = null,
    val error: String? = null,

    /** Gains (dB) of the custom graphic equalizer, one per [EqBandFrequencies] entry. */
    val gains: List<Float> = List(EqBandCount) { 0f },
    /** When true the preamp is derived from the response peak so boosts never clip. */
    val autoPreamp: Boolean = true,
    val manualPreampDb: Float = 0f,
    /** Key of the built-in preset (or id of the user preset) the current gains match, if any. */
    val selectedPresetKey: String? = "flat",
    /** Curves saved by the user from the custom equalizer. */
    val userPresets: List<SavedEQProfile> = emptyList(),
) {
    /** True while the custom graphic equalizer is the one being applied to playback. */
    val customActive: Boolean get() = activeProfileId == EQViewModel.CUSTOM_PROFILE_ID

    /** True while any equalizer (custom or imported) is being applied to playback. */
    val eqEnabled: Boolean get() = activeProfileId != null
}

/**
 * Preamp (dB) that goes with [gains]: the negated response peak when [autoPreamp] is on,
 * otherwise the manual value. Walks the response curve, so wrap calls in `remember`.
 */
fun effectivePreampDb(gains: List<Float>, autoPreamp: Boolean, manualPreampDb: Float): Double =
    if (autoPreamp) {
        val peak = responsePeakDb(gainsToBands(gains)).coerceAtLeast(0.0)
        // 0.0 - x avoids producing -0.0 for a flat curve
        0.0 - round(peak * 10.0) / 10.0
    } else {
        manualPreampDb.toDouble()
    }
